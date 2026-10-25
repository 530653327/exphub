package com.exphub.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.exphub.entity.AiAssistant;
import com.exphub.entity.Doc;
import com.exphub.entity.DocVersion;
import com.exphub.entity.Project;
import com.exphub.mapper.DocMapper;
import com.exphub.mapper.DocVersionMapper;
import com.exphub.mapper.ProjectMapper;
import com.exphub.entity.User;
import com.exphub.interceptor.ApiKeyInterceptor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import jakarta.servlet.http.HttpSession;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Service
public class DocService {

    private static final Logger log = LoggerFactory.getLogger(DocService.class);

    @Autowired
    private DocMapper docMapper;

    @Autowired
    private DocVersionMapper versionMapper;

    @Autowired
    private ProjectMapper projectMapper;

    @Autowired
    private com.exphub.mapper.ReviewCandidateMapper reviewCandidateMapper;

    @Autowired
    private CallLogService callLogService;

    @Transactional
    public Doc create(Doc doc) {
        AiAssistant assistant = ApiKeyInterceptor.getCurrentAssistant();
        log.info("DocService.create: assistant from ThreadLocal={}, title={}, tags={}, aliases={}", 
            assistant != null ? assistant.getAssistantId() : "NULL",
            doc.getTitle(), doc.getTags(), doc.getAliases());
        
        if (assistant != null) {
            doc.setAuthorId(assistant.getAssistantId());
            // 优先使用 AI 助手自报的名称，否则用 API Key 对应的名称
            if (doc.getAuthorName() == null || doc.getAuthorName().isEmpty()) {
                doc.setAuthorName(assistant.getAssistantName());
            }
            doc.setApiKey(assistant.getApiKey());  // API Key 级别的经验隔离
            log.info("DocService.create: setting author={}/{}, apiKey={}...", 
                assistant.getAssistantId(), assistant.getAssistantName(), maskKey(assistant.getApiKey()));
        } else {
            // 后台页面操作，使用默认管理员
            log.warn("DocService.create: CURRENT_ASSISTANT is NULL, falling back to default");
            ServletRequestAttributes attrs = (ServletRequestAttributes) RequestContextHolder.getRequestAttributes();
            if (attrs != null) {
                HttpSession session = attrs.getRequest().getSession(false);
                User user = session != null ? (User) session.getAttribute("user") : null;
                if (user != null) {
                    doc.setAuthorId("user_" + user.getId());
                    doc.setAuthorName(user.getDisplayName() != null ? user.getDisplayName() : user.getUsername());
                } else {
                    doc.setAuthorId("system");
                    doc.setAuthorName("系统");
                }
            } else {
                doc.setAuthorId("system");
                doc.setAuthorName("系统");
            }
        }
        doc.setVersion(1);
        doc.setCallCount(0);
        doc.setSuccessCount(0);
        doc.setFailCount(0);
        doc.setRating(new BigDecimal("5.00"));
        doc.setRatingCount(0);
        doc.setStatus("ACTIVE");
        docMapper.insert(doc);
        
        // 记录操作日志
        callLogService.logCreate(doc);
        
        return doc;
    }

    public Page<Doc> search(String keyword, int page, int size) {
        return search(keyword, null, null, page, size);
    }

    public Page<Doc> search(String keyword, String templateType, int page, int size) {
        return search(keyword, templateType, null, page, size);
    }

    public Page<Doc> search(String keyword, String templateType, String includeStatus, int page, int size) {
        return search(keyword, templateType, includeStatus, null, page, size);
    }

    /**
     * 搜索经验
     * @param projectId 项目ID。null=只搜全局经验（自动排除项目专属经验）；有值=只搜该项目内经验
     */
    public Page<Doc> search(String keyword, String templateType, String includeStatus, Long projectId, int page, int size) {
        AiAssistant assistant = ApiKeyInterceptor.getCurrentAssistant();
        String apiKey = (assistant != null) ? assistant.getApiKey() : null;

        // 项目域鉴权：项目必须归属当前 Key（后台无 Key 上下文时放行）。
        // 校验通过后，项目本身即为隔离边界，不再对项目内文档重复做 api_key 过滤
        // （项目文档常由后台/系统创建，api_key 为空，重复过滤会把所有者自己也挡掉）。
        if (projectId != null && !canAccessProject(projectId, apiKey)) {
            log.warn("DocService.search: 无权访问项目 {}, key={}", projectId, maskKey(apiKey));
            return emptyPage(page, size);
        }

        String tt = (templateType != null && !templateType.trim().isEmpty()) ? templateType.trim() : null;
        List<String> statusList = parseStatusList(includeStatus);

        // 无实质内容的查询词（null、空白、纯标点、纯停用词）直接返回空，不再走全库兜底。
        // 旧逻辑：空词 → searchWithLike → concepts 为空 → 不加关键词条件全库分页返回，
        // 线上实测空 query 曾一次返回 198 条无关经验，误导 AI 判断「库里什么都有」。
        SearchTerms terms = buildSearchTerms(keyword);
        if (terms.concepts.isEmpty()) {
            log.info("search rejected: 查询词无实质内容 kw='{}' projectId={}", keyword, projectId);
            return emptyPage(page, size);
        }

        String kw = keyword.trim();
        String cleanKeyword = toCleanKeyword(kw);

        // ===== 第 1 级：严格 AND（精确优先，但只在“整词覆盖”时直接采用）=====
        // 若严格命中只是靠 2-gram 泛词凑出来的（如「数据 结构」凑中「数据模型 表结构」），
        // 直接返回会绕过重排、把真正精确的文档挤掉，此时下沉到第 2 级。
        String strictQuery = toFulltextQuery(kw);
        long strictTotal = docMapper.countByFulltext(strictQuery, apiKey, projectId, tt, statusList);
        if (strictTotal > 0) {
            if (page > 1) {
                log.info("search tier=strict kw='{}' projectId={} hits={}", kw, projectId, strictTotal);
                return buildFulltextResult(cleanKeyword, strictQuery, cleanKeyword, apiKey, projectId, tt, statusList, page, size, strictTotal);
            }
            List<Doc> strictDocs = docMapper.searchByFulltext(strictQuery, cleanKeyword, apiKey, projectId, tt, statusList, 0, size);
            // 严格命中能填满一页、且存在整词覆盖时，直接采用（精确查询走快路径）；
            // 若只命中零星几条（常见于“某文档恰好引用了整句 query”），继续走重排补全，
            // 避免用户只看到 1 条结果。
            if (hasExactCoverage(strictDocs, terms) && strictTotal >= size) {
                log.info("search tier=strict kw='{}' projectId={} hits={}", kw, projectId, strictTotal);
                return buildResultFromRecords(kw, strictDocs, strictTotal, page, size);
            }
            log.info("search tier=strict-weak kw='{}' projectId={} hits={} → 放宽重排", kw, projectId, strictTotal);
        }

        // ===== 第 2 级：OR 召回 + 覆盖率重排（真正的放宽：不再要求所有词同时命中）=====
        if (!terms.expanded.isEmpty()) {
            String orQuery = terms.toOrQuery();
            List<Doc> candidates = docMapper.searchByFulltext(orQuery, cleanKeyword, apiKey, projectId, tt, statusList, 0, CANDIDATE_LIMIT);
            List<Doc> ranked = rankAndFilter(candidates, terms, true);
            if (!ranked.isEmpty()) {
                log.info("search tier=relaxed kw='{}' projectId={} candidates={} accepted={}",
                        kw, projectId, candidates.size(), ranked.size());
                return pagedResult(kw, ranked, page, size);
            }
            log.info("search tier=relaxed-empty kw='{}' projectId={} candidates={} concepts={}",
                    kw, projectId, candidates.size(), terms.concepts.size());
        }

        // ===== 第 3 级：LIKE 兜底（单字/标点等全文索引覆盖不到的场景）=====
        return searchWithLike(keyword, templateType, includeStatus, projectId, page, size);
    }

    /**
     * 相近经验：不做覆盖率门槛，按同一套评分取 TopN。
     * 用于“0 结果”时给调用方兜底参考，避免 AI 误判“没有经验”而重复创建。
     */
    public List<Doc> findSimilar(String keyword, String templateType, String includeStatus, Long projectId, int limit) {
        AiAssistant assistant = ApiKeyInterceptor.getCurrentAssistant();
        String apiKey = (assistant != null) ? assistant.getApiKey() : null;
        if (projectId != null && !canAccessProject(projectId, apiKey)) {
            return new ArrayList<>();
        }
        String tt = (templateType != null && !templateType.trim().isEmpty()) ? templateType.trim() : null;
        List<String> statusList = parseStatusList(includeStatus);
        SearchTerms terms = buildSearchTerms(keyword);
        if (terms.concepts.isEmpty() || terms.expanded.isEmpty()) {
            return new ArrayList<>();
        }
        List<Doc> candidates = docMapper.searchByFulltext(terms.toOrQuery(), toCleanKeyword(keyword == null ? "" : keyword),
                apiKey, projectId, tt, statusList, 0, CANDIDATE_LIMIT);
        List<Doc> ranked = rankAndFilter(candidates, terms, false);
        if (ranked.size() > limit) {
            return new ArrayList<>(ranked.subList(0, Math.max(0, limit)));
        }
        return ranked;
    }

    /**
     * 构建 FULLTEXT 搜索结果
     */
    private Page<Doc> buildFulltextResult(String originalKeyword, String ftQuery, String sortKeyword,
                                          String apiKey, Long projectId, String tt, List<String> statusList,
                                          int page, int size, long total) {
        long offset = (long) (page - 1) * size;
        List<Doc> records = docMapper.searchByFulltext(ftQuery, sortKeyword, apiKey, projectId, tt, statusList, offset, (long) size);

        return buildResultFromRecords(originalKeyword, records, total, page, size);
    }

    private Page<Doc> buildResultFromRecords(String originalKeyword, List<Doc> records, long total, int page, int size) {
        Page<Doc> result = new Page<>(page, size);
        result.setRecords(records);
        result.setTotal(total);

        // 注意：列表曝光不再计入 call_count。
        // 旧逻辑“每展示一条就 +1”会让越常被搜到的文档越靠前（马太效应），
        // 并把真实使用量（打开详情）淹没在曝光里；调用日志已由 logSearch 记录。
        callLogService.logSearch(originalKeyword, (int) total, records);
        return result;
    }

    /**
     * 严格命中里是否真的存在“覆盖全部概念（整词，而非 2-gram 拼凑）”的文档。
     * 有则说明严格 AND 的结果可信，直接返回；没有则下沉到重排，避免泛词命中挤掉精确文档。
     */
    private boolean hasExactCoverage(List<Doc> docs, SearchTerms terms) {
        if (docs == null || docs.isEmpty() || terms.concepts.isEmpty()) return false;
        for (Doc doc : docs) {
            boolean all = true;
            for (String concept : terms.concepts) {
                if (!matchesAnyField(doc, concept)) {
                    all = false;
                    break;
                }
            }
            if (all) return true;
        }
        return false;
    }

    private Page<Doc> pagedResult(String originalKeyword, List<Doc> ranked, int page, int size) {
        Page<Doc> result = new Page<>(page, size);
        result.setTotal(ranked.size());
        int from = Math.max(0, (page - 1) * size);
        if (from >= ranked.size()) {
            result.setRecords(new ArrayList<>());
        } else {
            result.setRecords(new ArrayList<>(ranked.subList(from, Math.min(ranked.size(), from + size))));
        }
        callLogService.logSearch(originalKeyword, ranked.size(), result.getRecords());
        return result;
    }
    
    /**
     * LIKE 搜索兜底（FULLTEXT 无结果或空关键词时使用）
     */
    private Page<Doc> searchWithLike(String keyword, String templateType, String includeStatus, Long projectId, int page, int size) {
        AiAssistant assistant = ApiKeyInterceptor.getCurrentAssistant();
        String apiKey = (assistant != null) ? assistant.getApiKey() : null;
        String tt = (templateType != null && !templateType.trim().isEmpty()) ? templateType.trim() : null;
        List<String> statusList = parseStatusList(includeStatus);

        QueryWrapper<Doc> wrapper = new QueryWrapper<>();
        // 与全文检索保持一致：项目域已鉴权，不再叠加文档级 Key 过滤
        if (apiKey != null && projectId == null) {
            wrapper.and(w -> w.eq("visibility", "PUBLIC").or().eq("api_key", apiKey));
        }
        if (projectId != null) {
            wrapper.eq("project_id", projectId);
        } else {
            wrapper.isNull("project_id");
        }
        if (tt != null) {
            wrapper.eq("template_type", tt);
        }
        if (statusList != null && !statusList.isEmpty()) {
            wrapper.in("status", statusList);
        }

        SearchTerms terms = buildSearchTerms(keyword);
        if (terms.concepts.isEmpty()) {
            // 查询词无实质内容（空白/纯标点/纯停用词）：返回空，不做全库兜底。
            // 与 search() 主入口口径一致，防止第 3 级 LIKE 兜底把空 query 放大成全库列表
            return emptyPage(page, size);
        }

        // LIKE 兜底同样放宽为“任一关键词命中”，再按覆盖率排序
        List<String> tokens = terms.concepts;
        wrapper.and(outer -> {
            for (int i = 0; i < tokens.size(); i++) {
                String t = tokens.get(i);
                if (i > 0) outer.or();
                outer.like("title", t).or().like("content", t).or().like("aliases", t)
                     .or().like("summary", t).or().like("tags", t);
            }
        });
        wrapper.last("LIMIT " + CANDIDATE_LIMIT);
        List<Doc> candidates = docMapper.selectList(wrapper);
        List<Doc> ranked = rankAndFilter(candidates, terms, false);
        return pagedResult(keyword, ranked, page, size);
    }
    
    /**
     * 将用户搜索关键词转换为 FULLTEXT BOOLEAN MODE 查询字符串
     * - 单关键词：直接使用（ngram 自动分词）
     * - 多关键词：+前缀实现 AND 逻辑，每个词必须在结果中出现
     */
    private String toFulltextQuery(String keyword) {
        // 移除 BOOLEAN MODE 特殊字符（+ -> < ( ) ~ * " @），替换为空格分词
        String cleaned = keyword.replaceAll("[+\\-><\\(\\)~\\*\"@]", " ");
        String[] tokens = cleaned.split("[\\s,，、]+");

        List<String> parts = new ArrayList<>();
        for (String token : tokens) {
            String t = token.trim();
            if (t.isEmpty()) continue;
            parts.add(t);
        }

        if (parts.isEmpty()) {
            // 全是特殊字符，返回原始内容去掉符号
            return keyword.replaceAll("[^a-zA-Z0-9\\u4e00-\\u9fa5]", " ");
        }

        if (parts.size() == 1) {
            return parts.get(0);
        }

        // 多关键词：+前缀 = AND 逻辑
        StringBuilder sb = new StringBuilder();
        for (String part : parts) {
            if (sb.length() > 0) sb.append(" ");
            sb.append("+").append(part);
        }
        return sb.toString();
    }

    /**
     * 生成纯净关键词（不含 BOOLEAN MODE 操作符），用于 NATURAL LANGUAGE MODE 排序
     */
    private String toCleanKeyword(String keyword) {
        return keyword.replaceAll("[+\\-><\\(\\)~\\*\"@]", " ")
                .replaceAll("[\\s,，、]+", " ")
                .trim();
    }

    // ==================== 检索：召回与排序 ====================

    /** 候选池上限：先宽松召回，再在内存里按覆盖率/字段权重重排 */
    private static final int CANDIDATE_LIMIT = 200;
    /** 查询词上限，避免长句导致召回/评分退化 */
    private static final int MAX_QUERY_TOKENS = 10;

    private static final Set<String> STOPWORDS = Set.of(
            "的", "了", "和", "与", "及", "或", "怎么", "如何", "什么", "为什么", "哪些",
            "是否", "可以", "需要", "一下", "我们", "你们", "他们", "这个", "那个",
            "请问", "帮我", "看看", "相关", "关于", "现在", "进行");

    /**
     * 查询词是否含有实质内容（非空、非纯空白/标点、非纯停用词）。
     * 与 search() 内部判定口径一致；供 MCP 工具层在搜索前快速拒绝无效查询，
     * 避免空 query 走「全库兜底」返回一堆无关经验、误导 AI 判断。
     */
    public static boolean hasMeaningfulQuery(String keyword) {
        return !buildSearchTerms(keyword).concepts.isEmpty();
    }

    /** 一次检索的两种视图：concepts 用于评分/LIKE，expanded 用于全文召回 */
    private static class SearchTerms {
        /** 原始概念（用户输入的关键词，去停用词后） */
        final List<String> concepts = new ArrayList<>();
        /** 每个概念的可匹配变体：原词 + 中文 2-gram + 英文片段（用于覆盖率/字段命中判断） */
        final List<List<String>> variants = new ArrayList<>();
        /** 全文召回用的扁平词表 */
        final List<String> expanded = new ArrayList<>();

        String toOrQuery() {
            if (expanded.isEmpty()) return "";
            return "+(" + String.join(" ", expanded) + ")";
        }
    }

    private static class ScoredDoc {
        final Doc doc;
        final double score;

        ScoredDoc(Doc doc, double score) {
            this.doc = doc;
            this.score = score;
        }
    }

    /**
     * 构建检索词：
     * - concepts：去掉停用词后的原始概念，用于覆盖率和字段命中判断
     * - expanded：全文召回用。中文按 2-gram 展开（ngram_token_size=2），
     *   英文/数字按非字母数字切分后取长度>=2 的片段；单字中文不进全文索引，只保留在 concepts 里
     */
    private static SearchTerms buildSearchTerms(String keyword) {
        SearchTerms terms = new SearchTerms();
        if (keyword == null) return terms;

        String cleaned = keyword.replaceAll("[+\\-><\\(\\)~\\*\"@]", " ").trim();
        for (String raw : cleaned.split("[\\s,，、;；]+")) {
            String token = raw.trim();
            if (token.isEmpty()) continue;
            // 纯标点/符号（如「。。。」「___」「---」）不含任何文字，无实质内容：
            // 放行后会进 FULLTEXT，被 MySQL ngram 当 token 匹配大量含标点的文档，返回一堆无关结果
            if (!containsTextChar(token)) continue;
            if (STOPWORDS.contains(token.toLowerCase())) continue;
            if (!terms.concepts.contains(token)) {
                terms.concepts.add(token);
            }
            if (terms.concepts.size() >= MAX_QUERY_TOKENS) break;
        }

        for (String concept : terms.concepts) {
            List<String> variants = new ArrayList<>();
            variants.add(concept);
            addExpandedTerms(variants, concept);
            terms.variants.add(variants);
            for (String v : variants) {
                if (!terms.expanded.contains(v)) terms.expanded.add(v);
            }
            if (terms.expanded.size() >= 32) break;
        }
        return terms;
    }

    /** token 是否含有至少一个文字字符（中文/英文字母/数字），纯标点符号视为无实质内容 */
    private static boolean containsTextChar(String token) {
        for (int i = 0; i < token.length(); i++) {
            char c = token.charAt(i);
            if ((c >= '\u4e00' && c <= '\u9fa5') || (c >= 'A' && c <= 'Z')
                    || (c >= 'a' && c <= 'z') || (c >= '0' && c <= '9')) {
                return true;
            }
        }
        return false;
    }

    private static void addExpandedTerms(List<String> out, String token) {        String chinese = extractChinese(token);
        String nonChinese = token.replaceAll("[\\u4e00-\\u9fa5]", " ").trim();

        if (!nonChinese.isEmpty()) {
            for (String part : nonChinese.split("[^A-Za-z0-9]+")) {
                if (part.length() >= 2 && !out.contains(part)) {
                    out.add(part);
                }
            }
        }
        if (chinese.length() == 2) {
            if (!out.contains(chinese)) out.add(chinese);
        } else if (chinese.length() > 2) {
            for (int i = 0; i <= chinese.length() - 2; i++) {
                String gram = chinese.substring(i, i + 2);
                if (!out.contains(gram)) out.add(gram);
            }
        }
    }

    /**
     * 按覆盖率 + 字段权重 + 时效 + 热度排序。
     * @param applyThreshold true=只保留达到最低覆盖率的结果（正常搜索）；
     *                       false=全部保留，用于“相近经验”兜底
     */
    private List<Doc> rankAndFilter(List<Doc> candidates, SearchTerms terms, boolean applyThreshold) {
        List<Doc> empty = new ArrayList<>();
        if (candidates == null || candidates.isEmpty() || terms.concepts.isEmpty()) {
            return empty;
        }
        List<ScoredDoc> scored = new ArrayList<>();
        for (Doc doc : candidates) {
            double conceptScore = 0;
            int matched = 0;
            for (List<String> variants : terms.variants) {
                double strength = conceptStrength(doc, variants);
                if (strength > 0) {
                    matched++;
                    conceptScore += strength;
                }
            }
            if (matched == 0) continue;
            if (applyThreshold && !passesThreshold(matched, terms.variants.size())) continue;
            scored.add(new ScoredDoc(doc, score(doc, terms.variants, conceptScore)));
        }
        scored.sort((a, b) -> Double.compare(b.score, a.score));
        List<Doc> ranked = new ArrayList<>(scored.size());
        for (ScoredDoc s : scored) ranked.add(s.doc);
        return ranked;
    }

    private boolean passesThreshold(int matched, int total) {
        if (matched <= 0) return false;
        return matched >= requiredMatches(total);
    }

    /**
     * 放宽召回的最低命中概念数：
     * 单/双概念至少命中 1 个（双词 query 不再退化成严格 AND）；
     * 3 个以上至少 2 个，且不低于 40%（避免长 query 只命中一两个泛词就进来）。
     */
    private int requiredMatches(int total) {
        if (total <= 2) return 1;
        return Math.min(total, Math.max(2, (int) Math.ceil(total * 0.4)));
    }

    private double score(Doc doc, List<List<String>> variants, double conceptScore) {
        double coverage = conceptScore / Math.max(1, variants.size());

        double fieldSum = 0;
        for (List<String> conceptVariants : variants) {
            fieldSum += fieldWeight(doc, conceptVariants);
        }
        double fieldScore = Math.min(1.0, fieldSum / Math.max(1, variants.size()) / 3.0);

        double recency = recencyScore(doc.getUpdatedAt());
        int calls = doc.getCallCount() == null ? 0 : doc.getCallCount();
        double popularity = Math.min(1.0, Math.log1p(calls) / Math.log1p(50));

        return 0.55 * coverage + 0.25 * fieldScore + 0.12 * recency + 0.08 * popularity;
    }

    /** 一个概念的最佳字段权重：概念内任一 2-gram 命中该字段即算命中 */
    private double fieldWeight(Doc doc, List<String> variants) {
        double best = 0;
        for (String v : variants) {
            best = Math.max(best, fieldWeight(doc, v));
            if (best >= 3.0) break;
        }
        return best;
    }

    private double fieldWeight(Doc doc, String term) {
        if (containsIgnoreCase(doc.getTitle(), term)) return 3.0;
        if (containsIgnoreCase(doc.getAliases(), term)) return 2.0;
        if (containsIgnoreCase(doc.getSummary(), term)) return 2.0;
        if (containsIgnoreCase(doc.getTags(), term)) return 2.0;
        if (containsIgnoreCase(doc.getContent(), term)) return 1.0;
        return 0;
    }

    /**
     * 概念命中强度：整词命中记 1.0，仅 2-gram/英文片段命中记 0.5。
     * 这样「数据模型」这类精确命中，不会被只命中「数据」的泛匹配在覆盖率上压平。
     */
    private double conceptStrength(Doc doc, List<String> variants) {
        if (variants == null || variants.isEmpty()) return 0;
        if (matchesAnyField(doc, variants.get(0))) return 1.0;
        for (int i = 1; i < variants.size(); i++) {
            if (matchesAnyField(doc, variants.get(i))) return 0.5;
        }
        return 0;
    }

    private boolean matchesAnyField(Doc doc, String term) {
        return containsIgnoreCase(doc.getTitle(), term)
                || containsIgnoreCase(doc.getAliases(), term)
                || containsIgnoreCase(doc.getSummary(), term)
                || containsIgnoreCase(doc.getTags(), term)
                || containsIgnoreCase(doc.getContent(), term);
    }

    private boolean containsIgnoreCase(String text, String needle) {
        if (text == null || needle == null || needle.isEmpty()) return false;
        return text.toLowerCase().contains(needle.toLowerCase());
    }

    private double recencyScore(LocalDateTime updatedAt) {
        if (updatedAt == null) return 0;
        long days = Duration.between(updatedAt, LocalDateTime.now()).toDays();
        if (days < 0) days = 0;
        return Math.exp(-days / 90.0);
    }

    /**
     * 项目域访问校验：项目必须归属当前 Key。
     * 后台（无 Key 上下文）放行；项目 api_key 为空时不允许助手访问（避免孤儿项目被任意 Key 读取）。
     */
    private boolean canAccessProject(Long projectId, String apiKey) {
        Project project = projectMapper.selectById(projectId);
        if (project == null) return false;
        if (apiKey == null) return true;
        return apiKey.equals(project.getApiKey());
    }

    /** 包级静态便于单测：M10 的关键安全性在于「ALL 也不许带出待删项」 */
    static List<String> parseStatusList(String includeStatus) {
        if (includeStatus == null || includeStatus.trim().isEmpty()) return null;
        List<String> list = new ArrayList<>();
        for (String s : includeStatus.split(",")) {
            String v = s.trim();
            if (v.isEmpty()) continue;
            // M10：ALL 展开为「除待删外的全部状态」。
            // 待删项是「等用户确认要删的东西」，让 AI 在检索里看到它只会误导判断；
            // 真想看待删队列请显式传 PENDING_DELETE（后台用，MCP 侧不鼓励）。
            if ("ALL".equalsIgnoreCase(v)) {
                list.add("ACTIVE");
                list.add("COMPLETED");
                list.add("BROKEN");
                list.add("DEPRECATED");
                continue;
            }
            list.add(v);
        }
        return list.isEmpty() ? null : list;
    }

    private Page<Doc> emptyPage(int page, int size) {
        Page<Doc> p = new Page<>(page, size);
        p.setRecords(new ArrayList<>());
        p.setTotal(0);
        return p;
    }

    /**
     * 提取字符串中的中文字符
     */
    private static String extractChinese(String str) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < str.length(); i++) {
            char c = str.charAt(i);
            if (c >= '\u4e00' && c <= '\u9fa5') {
                sb.append(c);
            }
        }
        return sb.toString();
    }

    public Page<Doc> listByCategory(String category, int page, int size) {
        Page<Doc> p = new Page<>(page, size);
        QueryWrapper<Doc> wrapper = new QueryWrapper<>();
        if (category != null && !category.trim().isEmpty()) {
            wrapper.eq("category", category.trim());
        }
        wrapper.orderByDesc("updated_at");
        return docMapper.selectPage(p, wrapper);
    }

    public Page<Doc> listByAuthor(String authorId, int page, int size) {
        Page<Doc> p = new Page<>(page, size);
        QueryWrapper<Doc> wrapper = new QueryWrapper<>();
        wrapper.eq("author_id", authorId);
        wrapper.orderByDesc("updated_at");
        return docMapper.selectPage(p, wrapper);
    }

    public Doc getById(Long id) {
        Doc doc = docMapper.selectById(id);
        // API Key 级别隔离：MCP/API 请求可查看同 Key 下的经验 + 管理员创建的经验（api_key 为空）
        if (doc != null) {
            AiAssistant assistant = ApiKeyInterceptor.getCurrentAssistant();
            if (assistant != null) {
                boolean allowed;
                if (doc.getProjectId() != null) {
                    // 项目文档以项目归属为隔离边界（与搜索口径一致）。
                    // 项目 api_key 为空时对任何 Key 都不可见，避免“孤儿项目”泄权。
                    allowed = canAccessProject(doc.getProjectId(), assistant.getApiKey());
                } else {
                    // 可见性：PUBLIC 对所有 Key 可见；PRIVATE 仅同 API Key 可见
                    boolean isPublic = "PUBLIC".equalsIgnoreCase(doc.getVisibility());
                    boolean sameKey = doc.getApiKey() != null && assistant.getApiKey().equals(doc.getApiKey());
                    allowed = isPublic || sameKey;
                }
                if (!allowed) {
                    return null; // 不可见
                }
                // MCP/API 调用查看详情时，增加引用计数
                incrementCallCount(id);
            }
        }
        return doc;
    }

    @Transactional
    public Doc update(Long id, Doc newDoc) {
        return update(id, newDoc, java.util.Set.of());
    }

    /**
     * 更新文档（支持显式清空字段）。
     *
     * <p>A2：{@code newDoc} 里为 null 的字段一律「不改」，这是向后兼容的老约定
     * （调用方传空串也走不到这里，ExpHubTools 层已过滤）。
     *
     * <p>为什么要单独有 clearFields：MCP 侧「字段没传」和「显式传 null」经反序列化后
     * 在 Java 里都是 null，二者无法区分。所以「清空」不能靠 null 表达 —— 否则任何一个
     * 客户端漏传字段就会把对应内容清空。改成调用方显式声明要清哪些字段。
     *
     * @param clearFields 需要置空的字段名（tags / aliases / summary / relatedIds / category）。
     *                    title 与 content 不在白名单内：清空标题或正文没有合理语义。
     */
    @Transactional
    public Doc update(Long id, Doc newDoc, java.util.Set<String> clearFields) {
        return update(id, newDoc, clearFields, null);
    }

    /**
     * 更新文档，可指定「谁改的」（写进版本快照的 updated_name）。
     *
     * <p>为什么要这个参数：快照署名原来只能靠当前 API Key 绑定的助手名兜底，
     * 而**一个 Key 常被多个助手共用**（开发/对话/不同项目），于是所有改动都记在同一个人头上，
     * 审计链等于没有。越哥 2026-10-25 定：**谁修改的就记上谁的名字**。
     *
     * <p>与 {@code newDoc.authorName} 的区别（这两件事必须分清）：
     * <ul>
     *   <li>{@code newDoc.authorName} = **改文档作者**（作品归属，动了就等于改写著作权）</li>
     *   <li>{@code authorOverride} = **本条版本的操作人署名**（审计痕迹，每次改动各记各的）</li>
     * </ul>
     *
     * @param authorOverride 操作人署名；null/空 = 走 Key 绑定的助手名兜底
     */
    @Transactional
    public Doc update(Long id, Doc newDoc, java.util.Set<String> clearFields, String authorOverride) {
        Doc old = docMapper.selectById(id);
        if (old == null) {
            return null;
        }
        // A3：只有正文真的变了才存全文快照。
        // content 为 null（= 不传 = 不改）或与原值完全相同，都算「元数据变更」，
        // 这类快照只记一条事件（change_type=META、content=NULL），不再重复存 4KB 正文。
        String incomingContent = newDoc.getContent();
        boolean contentChanged = incomingContent != null && !incomingContent.equals(old.getContent());
        saveVersionSnapshot(old, contentChanged, authorOverride);

        newDoc.setId(id);
        newDoc.setVersion(old.getVersion() + 1);
        newDoc.setAuthorId(old.getAuthorId());
        // 只有调用方显式传了作者才改；没传就回填旧值（updateById 对 null 是「跳过」，
        // 但这里必须显式回填，否则后面 clearFields 的 UpdateWrapper 与 updateById 语义不一致）
        // 🔴 原实现无条件 setAuthorName(old.getAuthorName())，
        //    导致 update_experience 的 authorName 参数**完全无效**（死参数，2026-10-25 实测确认）
        if (newDoc.getAuthorName() == null || newDoc.getAuthorName().trim().isEmpty()) {
            newDoc.setAuthorName(old.getAuthorName());
        }
        newDoc.setCallCount(old.getCallCount());
        newDoc.setSuccessCount(old.getSuccessCount());
        newDoc.setFailCount(old.getFailCount());
        newDoc.setRating(old.getRating());
        newDoc.setRatingCount(old.getRatingCount());
        docMapper.updateById(newDoc);

        // 清空必须单独用 UpdateWrapper：updateById 对 null 字段是「跳过」而不是「置 null」，
        // 直接塞 null 会静默失败（和 E7 里 project_id 归零踩的是同一个坑）
        if (clearFields != null && !clearFields.isEmpty()) {
            com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper<Doc> uw =
                    new com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper<>();
            uw.eq("id", id);
            boolean any = false;
            for (String f : clearFields) {
                if (f == null) continue;
                switch (f.trim()) {
                    case "tags" -> { uw.set("tags", ""); any = true; }
                    case "aliases" -> { uw.set("aliases", ""); any = true; }
                    case "summary" -> { uw.set("summary", ""); any = true; }
                    case "relatedIds", "related_ids" -> { uw.set("related_ids", ""); any = true; }
                    case "category" -> { uw.set("category", "未分类"); any = true; }
                    default -> log.warn("update: clearFields 忽略不可清空字段 '{}'（id={}）", f, id);
                }
            }
            if (any) {
                docMapper.update(null, uw);
            }
        }

        Doc updated = docMapper.selectById(id);
        callLogService.logUpdate(updated);
        return updated;
    }

    /**
     * 写版本快照。从 update / append / patch 三条写路径复用，避免三份拷贝走样。
     *
     * @param contentChanged 本次是否动了正文（A3）。
     *        true  → 存旧版全文，change_type=CONTENT
     *        false → 只记事件（change_type=META），content 存 NULL，不重复存全文
     * @param authorOverride 非空时用它作为 updated_name。
     *        为什么要这个：一个 API Key 只能绑一个 assistant_name，而同一个 Key 往往被多个
     *        助手共用（开发/对话/不同项目）。署名必须由调用方自报，否则谁的活都记在同一个人头上。
     */
    private void saveVersionSnapshot(Doc old, boolean contentChanged, String authorOverride) {
        DocVersion v = new DocVersion();
        v.setDocId(old.getId());
        v.setVersion(old.getVersion());
        if (contentChanged) {
            v.setContent(old.getContent());
            v.setChangeType("CONTENT");
        } else {
            // 正文没变就不存副本。NULL 是「本次没动正文」的显式标记，不是「数据丢了」；
            // 读取端遇到 NULL 会明确告诉调用方「该版仅元数据变更，正文请参考更早版本」。
            v.setContent(null);
            v.setChangeType("META");
        }
        v.setAliases(old.getAliases());
        v.setSummary(old.getSummary());
        AiAssistant assistant = ApiKeyInterceptor.getCurrentAssistant();
        if (assistant != null) {
            v.setUpdatedBy(assistant.getAssistantId());
            v.setUpdatedName(assistant.getAssistantName());
        } else {
            ServletRequestAttributes attrs = (ServletRequestAttributes) RequestContextHolder.getRequestAttributes();
            User user = null;
            if (attrs != null) {
                HttpSession session = attrs.getRequest().getSession(false);
                user = session != null ? (User) session.getAttribute("user") : null;
            }
            if (user != null) {
                v.setUpdatedBy("user_" + user.getId());
                v.setUpdatedName(user.getDisplayName() != null ? user.getDisplayName() : user.getUsername());
            } else {
                v.setUpdatedBy("system");
                v.setUpdatedName("系统");
            }
        }
        if (authorOverride != null && !authorOverride.trim().isEmpty()) {
            v.setUpdatedName(authorOverride.trim());
        }
        versionMapper.insert(v);
    }

    // ==================== A1：追加 / 局部替换（2026-10-25） ====================
    //
    // 为什么要有这两个工具：原来的「补充一段进展」只能 update 整篇 content。
    // 而 AI 拿到的正文是被截断的（get_experience_detail 有长度上限），
    // 整篇写回就等于把没读到的部分删掉 —— 这既是浪费，也是数据损坏。
    // 这两个方法都在服务端直接读改写全文，客户端永远不需要持有全文。

    /**
     * 在正文末尾追加一段（服务端拼接，不受客户端截断影响）。
     * 只在尾部追加、不会缩短原文，因此不触发截断保护。
     */
    @Transactional
    public Doc appendContent(Long id, String addition) {
        return appendContent(id, addition, null);
    }

    /** @param authorName 可选署名，记入版本快照的 updated_name（多助手共用一个 Key 时必须传） */
    @Transactional
    public Doc appendContent(Long id, String addition, String authorName) {
        Doc old = docMapper.selectById(id);
        if (old == null) {
            return null;
        }
        if (addition == null || addition.trim().isEmpty()) {
            return old;   // 空追加不写库：不递增版本、不产生快照
        }
        saveVersionSnapshot(old, true, authorName);
        String merged = mergeAppend(old.getContent(), addition);

        Doc patch = new Doc();
        patch.setId(id);
        patch.setContent(merged);
        patch.setVersion(old.getVersion() + 1);
        docMapper.updateById(patch);

        Doc updated = docMapper.selectById(id);
        callLogService.logUpdate(updated);
        return updated;
    }

    /**
     * 局部替换正文（服务端执行，find 的唯一性由调用方先校验）。
     */
    @Transactional
    public Doc patchContent(Long id, String find, String replacement) {
        return patchContent(id, find, replacement, null);
    }

    /** @param authorName 可选署名，记入版本快照的 updated_name */
    @Transactional
    public Doc patchContent(Long id, String find, String replacement, String authorName) {
        Doc old = docMapper.selectById(id);
        if (old == null) {
            return null;
        }
        saveVersionSnapshot(old, true, authorName);
        String base = old.getContent() == null ? "" : old.getContent();
        String merged = base.replace(find, replacement == null ? "" : replacement);

        Doc patch = new Doc();
        patch.setId(id);
        patch.setContent(merged);
        patch.setVersion(old.getVersion() + 1);
        docMapper.updateById(patch);

        Doc updated = docMapper.selectById(id);
        callLogService.logUpdate(updated);
        return updated;
    }

    /**
     * A1：把追加片段接到原文末尾。
     *
     * <p>这个方法是「追加不会丢原文」的核心保证，所以单独抽出来可测：
     * 无论原文多长、有无尾部空白，返回值都必须**完整包含原文**（只允许削掉原文末尾的空白字符）。
     */
    static String mergeAppend(String base, String addition) {
        String b = base == null ? "" : base;
        String a = addition == null ? "" : addition.trim();
        // 追加内容为空 = 什么都不做。否则会平白多出两个空行，还骗来一次版本递增
        if (a.isEmpty()) return b;
        // 原文只有空白时按空处理，避免拼出「开头两行空白 + 追加内容」的正文
        if (b.trim().isEmpty()) return a;
        // 去掉尾空白再接，避免出现四五个连续空行；注意只动尾部空白，正文一个字不删
        return b.replaceAll("\\s+$", "") + "\n\n" + a;
    }

    /**
     * 统计 find 在 text 中出现的次数（包级静态便于单测）。
     * patch 的前置校验靠它：0 次说明锚点不存在，多次说明会误改，两种都必须拒绝。
     */
    public static int countOccurrences(String text, String find) {
        if (text == null || find == null || find.isEmpty()) return 0;
        int count = 0, idx = 0;
        while ((idx = text.indexOf(find, idx)) >= 0) {
            count++;
            idx += find.length();
        }
        return count;
    }

    /**
     * 只调整文档归属，不动内容、不 bump 版本号。
     *
     * ⚠️ 必须走 UpdateWrapper：MyBatis-Plus 默认忽略 null 字段，
     * 用 updateById + setProjectId(null) 无法把列置空（会静默失败）。
     * 这也是「移回全局」唯一的实现方式。
     *
     * @param projectId 目标项目ID；传 null 表示移回全局
     */
    @Transactional
    public Doc updateProjectId(Long id, Long projectId) {
        Doc old = docMapper.selectById(id);
        if (old == null) {
            return null;
        }
        docMapper.update(null, new UpdateWrapper<Doc>()
                .eq("id", id)
                .set("project_id", projectId));
        log.info("DocService.updateProjectId: docId={}, {} -> {}", id, old.getProjectId(), projectId);
        return docMapper.selectById(id);
    }

    /** 删除经验，并级联清理版本快照（同一事务，避免出现「主表没了、全文还在」） */
    @Transactional
    public void delete(Long id) {        Doc doc = docMapper.selectById(id);
        String title = doc != null ? doc.getTitle() : "未知";
        docMapper.deleteById(id);

        // D1：一并清掉这条相关的「待判断」候选。
        // 不清的话队列里会永久挂着指向已删文档的候选（界面显示「该条已不存在」），
        // 既判不了也删不掉，人只能一直跳过 —— 队列一旦掺垃圾就没人看了。
        // 只清 PENDING：已判成 DUPLICATE / SUPERSEDED 的记录是判断历史，保留。
        if (reviewCandidateMapper != null) {
            try {
                reviewCandidateMapper.delete(
                        new QueryWrapper<com.exphub.entity.ReviewCandidate>()
                                .eq("status", com.exphub.entity.ReviewCandidate.PENDING)
                                .and(w -> w.eq("doc_a", id).or().eq("doc_b", id)));
            } catch (Exception e) {
                // 清理候选属附带动作，不能让它把删除主流程带崩（副作用不得拖垮主业务）
                log.warn("delete: 清理待判断候选失败 doc={} err={}", id, e.getMessage());
            }
        }

        // 版本表必须跟着删。历史快照存的是正文全文，只删主表的话：
        // 1) 界面任何地方都看不到这些快照（get_experience_history 按 doc_id 查，doc 已不存在）；
        // 2) 它们仍在库里占空间，且是**完整正文**——旧版里写过的密码/Token 会一直留着，
        //    形成「以为删干净了，其实全文还在」的错觉。
        // M10 上线后「后台点删除」是正规路径（AI 标记 → 用户确认 → 用户执行），一定会走到这里，
        // 所以这条级联清理必须在删除入口就能保证，不能靠事后手工 SQL。
        versionMapper.delete(new QueryWrapper<DocVersion>().eq("doc_id", id));

        // 记录删除日志
        callLogService.logDelete(id, title);
    }

    // ==================== set_flags：机器可读标记（2026-10-25，#631 A1） ====================
    //
    // 为什么必须有这个专用工具：给一条经验打「待复核 / 疑似重复」标记，唯一的替代做法是
    // update_experience(tags=...)。而 update 在判断任何字段之前就先 saveVersionSnapshot(old)，
    // 无条件存一份正文全文副本 —— 打一个标记就等于多一份 4KB 快照。
    // D1 的分层整理（L1 每日增量 / L2 每周相似度）核心动作就是打标记，
    // 不修这条，整理方案还没开工就在制造版本垃圾。
    //
    // 因此本方法：不递增 version、不写 doc_versions、不动 updated_at。

    /** 标记白名单，顺序即回显顺序 */
    public static final List<String> FLAG_WHITELIST = List.of(
            "needs_review", "duplicate", "superseded", "stale", "low_quality");

    /** 人话 → 规范值。允许用中文写标记，避免因为拼不对白名单而被静默丢弃 */
    private static final Map<String, String> FLAG_ALIASES = Map.ofEntries(
            Map.entry("待复核", "needs_review"), Map.entry("复核", "needs_review"),
            Map.entry("待确认", "needs_review"), Map.entry("review", "needs_review"),
            Map.entry("重复", "duplicate"), Map.entry("疑似重复", "duplicate"),
            Map.entry("冗余", "duplicate"), Map.entry("dup", "duplicate"),
            Map.entry("已取代", "superseded"), Map.entry("取代", "superseded"),
            Map.entry("过期", "stale"), Map.entry("失效", "stale"),
            Map.entry("待更新", "stale"), Map.entry("旧", "stale"),
            Map.entry("低质", "low_quality"), Map.entry("质量差", "low_quality"),
            Map.entry("存疑", "low_quality"));

    /** 标记的中文展示名，用于回显（给人看的地方） */
    public static String flagLabel(String flag) {
        if (flag == null) return "";
        return switch (flag) {
            case "needs_review" -> "待复核";
            case "duplicate" -> "疑似重复";
            case "superseded" -> "已被取代";
            case "stale" -> "可能失效";
            case "low_quality" -> "质量存疑";
            default -> flag;
        };
    }

    /** 规范值（白名单内）→ 中文标签串，用于列表回显 */
    public static String flagLabelText(String flags) {
        List<String> parsed = parseFlags(flags);
        if (parsed.isEmpty()) return "";
        List<String> labels = new ArrayList<>();
        for (String f : parsed) labels.add(flagLabel(f));
        return String.join("、", labels);
    }

    /**
     * 解析 flags 参数为标准值列表（保序、去重、忽略非法值）。
     * 包级静态便于单测：白名单过滤错了会导致「标记了但系统不认」，必须可测。
     */
    public static List<String> parseFlags(String raw) {
        List<String> out = new ArrayList<>();
        if (raw == null || raw.trim().isEmpty()) return out;
        for (String token : raw.split("[,，;；\\s]+")) {
            String t = token.trim().toLowerCase();
            if (t.isEmpty()) continue;
            String canonical = FLAG_WHITELIST.contains(t) ? t : FLAG_ALIASES.get(t);
            if (canonical != null && !out.contains(canonical)) out.add(canonical);
        }
        return out;
    }

    /** 被丢弃的 token（不属于白名单也不在别名表里），用于在工具返回里明确告知调用方 */
    public static List<String> rejectedFlagTokens(String raw) {
        List<String> out = new ArrayList<>();
        if (raw == null || raw.trim().isEmpty()) return out;
        for (String token : raw.split("[,，;；\\s]+")) {
            String t = token.trim().toLowerCase();
            if (t.isEmpty()) continue;
            if (!FLAG_WHITELIST.contains(t) && !FLAG_ALIASES.containsKey(t)) out.add(token.trim());
        }
        return out;
    }

    /**
     * 打标记 / 改标记 / 清标记。flags 为空 = 清空标记。
     *
     * @param markedBy 打标记的助手名（同一 API Key 可能被多个助手共用，由调用方自报）
     */
    @Transactional
    public Doc setFlags(Long id, List<String> flags, String note, String markedBy) {
        Doc old = docMapper.selectById(id);
        if (old == null) {
            return null;
        }
        // 刻意不调 saveVersionSnapshot：这就是本方法存在的全部理由
        UpdateWrapper<Doc> uw = new UpdateWrapper<>();
        uw.eq("id", id);
        boolean cleared = flags == null || flags.isEmpty();
        uw.set("flags", cleared ? null : String.join(",", flags));
        uw.set("flag_note", note == null || note.trim().isEmpty() ? null : note.trim());
        uw.set("flag_marked_by", cleared ? null : markedBy);
        uw.set("flag_marked_at", cleared ? null : LocalDateTime.now());
        // 也不动 updated_at：打标记属元信息，若刷新会把老文档顶到
        // list_recent_memories（按更新时间倒序）的最前面，干扰「最近记忆」的语义
        docMapper.update(null, uw);
        return docMapper.selectById(id);
    }

    // ==================== D1 施工包 1：取代链（2026-10-25） ====================
    //
    // 只做「谁取代谁」这一个指针，不合并正文、不删除旧条。
    // 理由：合并错了不可逆，而「标记 + 保留原文」错了随时能撤。

    /** markSuperseded 的结果：ok=false 时 message 直接给调用方看，不抛异常 */
    public record SupersedeResult(boolean ok, String message, Doc doc) {}

    /**
     * 标记「id 被 byId 取代」。
     *
     * <p>只写 superseded_by 一列：<b>不动 version、不写版本快照、不动 updated_at</b>。
     * 关系变更属元信息，跟 set_flags 是同一类操作 —— 整理动作不该产生版本垃圾。
     *
     * @param id   被取代的旧条
     * @param byId 取代它的新条
     * @param by   操作人（助手代号），写日志用
     */
    @Transactional
    public SupersedeResult markSuperseded(Long id, Long byId, String by) {
        Doc old = docMapper.selectById(id);
        if (old == null) {
            return new SupersedeResult(false, "未找到 ID 为 " + id + " 的经验", null);
        }
        Doc rep = docMapper.selectById(byId);
        if (rep == null) {
            return new SupersedeResult(false, "未找到 ID 为 " + byId + " 的经验（取代者必须真实存在）", null);
        }
        if (id.equals(byId)) {
            return new SupersedeResult(false, "不能自己取代自己", null);
        }
        if (old.getSupersededBy() != null && old.getSupersededBy().equals(byId)) {
            return new SupersedeResult(true, "该取代关系已存在，未做改动", old);
        }
        // 防环：若从「取代者」顺着它自己的取代链能走回本条，再加这条边就成环
        if (chainReaches(byId, id, pid -> {
            Doc d = docMapper.selectById(pid);
            return d == null ? null : d.getSupersededBy();
        }, MAX_SUPERSEDE_HOPS)) {
            return new SupersedeResult(false,
                    "#" + byId + " 已经（直接或间接）被 #" + id + " 取代，再标一次会形成环。"
                            + "请先 clear_superseded 解掉旧关系再标。", null);
        }

        UpdateWrapper<Doc> uw = new UpdateWrapper<>();
        uw.eq("id", id).set("superseded_by", byId);
        docMapper.update(null, uw);
        callLogService.logUpdate(old);
        log.info("markSuperseded: doc={} supersededBy={} by={}", id, byId, by);
        return new SupersedeResult(true, "已标记 #" + id + " 被 #" + byId + " 取代", docMapper.selectById(id));
    }

    /** 撤销取代关系。只清指针，不动正文与状态。 */
    @Transactional
    public Doc clearSuperseded(Long id) {
        Doc doc = docMapper.selectById(id);
        if (doc == null) {
            return null;
        }
        UpdateWrapper<Doc> uw = new UpdateWrapper<>();
        uw.eq("id", id).set("superseded_by", null);
        docMapper.update(null, uw);
        log.info("clearSuperseded: doc={}", id);
        return docMapper.selectById(id);
    }

    /** 反查：本条取代了哪些（用于详情页展示「本条取代了 #A、#B」） */
    public List<Doc> listSuperseding(Long id) {
        if (id == null) return List.of();
        return docMapper.selectList(new QueryWrapper<Doc>().eq("superseded_by", id));
    }

    /** 批量取（候选队列展示用，避免逐条查导致 N+1） */
    public List<Doc> listByIds(java.util.Collection<Long> ids) {
        if (ids == null || ids.isEmpty()) return List.of();
        return docMapper.selectBatchIds(ids);
    }

    /** 取代链最大深度：超过这个长度说明数据已经不对劲，不再往下走 */
    public static final int MAX_SUPERSEDE_HOPS = 20;

    /**
     * 从 from 顺着取代链走，看能不能走到 target。用于防环（包级可见便于单测）。
     *
     * @param lookup 取「某条被谁取代」，走到尽头返回 null
     */
    public static boolean chainReaches(Long from, Long target,
                                       java.util.function.Function<Long, Long> lookup,
                                       int maxHops) {
        if (from == null || target == null || lookup == null) return false;
        Long cur = from;
        for (int i = 0; i < maxHops && cur != null; i++) {
            if (cur.equals(target)) return true;
            cur = lookup.apply(cur);
        }
        return false;
    }

    public void updateCallResult(Long docId, boolean success) {
        if (docId == null) return;
        Doc doc = docMapper.selectById(docId);
        if (doc == null) return;
        doc.setCallCount(doc.getCallCount() + 1);
        if (success) {
            doc.setSuccessCount(doc.getSuccessCount() + 1);
        } else {
            doc.setFailCount(doc.getFailCount() + 1);
        }
        docMapper.updateById(doc);
    }

    /**
     * AI 反馈经验是否有帮助（核心价值指标）
     * success/fail 计数完全由 AI 助手主动反馈决定，而非代码逻辑判断
     */
    @Transactional
    public Doc reportResult(Long docId, boolean helpful, String feedback) {
        if (docId == null) return null;
        Doc doc = docMapper.selectById(docId);
        if (doc == null) return null;
        if (helpful) {
            doc.setSuccessCount(doc.getSuccessCount() + 1);
        } else {
            doc.setFailCount(doc.getFailCount() + 1);
        }
        docMapper.updateById(doc);
        callLogService.logFeedback(doc, helpful, feedback);
        return doc;
    }

    /**
     * 双向关联两篇经验（互相引用）
     * 在 docs 的 related_ids 字段中互相添加对方的 ID
     */
    @Transactional
    public void linkExperiences(Long docId1, Long docId2) {
        if (docId1 == null || docId2 == null) return;
        // 给 doc1 加上 doc2
        addRelatedId(docId1, docId2);
        // 给 doc2 加上 doc1
        addRelatedId(docId2, docId1);
    }

    private void addRelatedId(Long targetId, Long relatedId) {
        Doc target = docMapper.selectById(targetId);
        if (target == null) return;
        String current = target.getRelatedIds();
        if (current == null || current.isEmpty()) {
            target.setRelatedIds(String.valueOf(relatedId));
        } else if (!current.contains(String.valueOf(relatedId))) {
            target.setRelatedIds(current + "," + relatedId);
        }
        docMapper.updateById(target);
    }

    /**
     * 简单增加调用计数（不区分成功失败）
     */
    private void incrementCallCount(Long docId) {
        if (docId == null) return;
        Doc doc = docMapper.selectById(docId);
        if (doc == null) return;
        doc.setCallCount(doc.getCallCount() + 1);
        docMapper.updateById(doc);
    }

    @Transactional
    public void updateRating(Long docId, Integer rating) {
        if (docId == null || rating == null) return;
        Doc doc = docMapper.selectById(docId);
        if (doc == null) return;
        int count = doc.getRatingCount();
        BigDecimal avg = doc.getRating();
        BigDecimal newAvg = avg.multiply(new BigDecimal(count))
                .add(new BigDecimal(rating))
                .divide(new BigDecimal(count + 1), 2, BigDecimal.ROUND_HALF_UP);
        doc.setRating(newAvg);
        doc.setRatingCount(count + 1);
        docMapper.updateById(doc);
    }

    public List<DocVersion> getVersions(Long docId) {
        QueryWrapper<DocVersion> wrapper = new QueryWrapper<>();
        wrapper.eq("doc_id", docId).orderByDesc("version");
        return versionMapper.selectList(wrapper);
    }

    public long countAll() {
        return docMapper.selectCount(null);
    }

    public long countByAuthor(String authorId) {
        return docMapper.selectCount(new QueryWrapper<Doc>().eq("author_id", authorId));
    }

    public List<Doc> getHotDocs(int limit) {
        QueryWrapper<Doc> wrapper = new QueryWrapper<>();
        wrapper.eq("status", "ACTIVE").orderByDesc("call_count").last("LIMIT " + limit);
        return docMapper.selectList(wrapper);
    }

    public List<String> getCategories() {
        return docMapper.getCategories();
    }

    public long countProblemDocs() {
        return docMapper.selectCount(new QueryWrapper<Doc>().eq("status", "BROKEN"));
    }

    /**
     * 更新经验状态（ACTIVE / COMPLETED / BROKEN / DEPRECATED）
     */
    @Transactional
    public Doc updateStatus(Long id, String status) {
        // PENDING_DELETE 只能由 markForDeletion 设置：它必须带原因，且语义是「不可逆操作的前置标记」，
        // 不能被通用的状态变更接口顺手写上（否则等于给模型留了一条绕过原因说明的后门）。
        if (PENDING_DELETE.equalsIgnoreCase(status)) {
            throw new IllegalArgumentException("PENDING_DELETE 不能通过状态接口设置，请使用 mark_for_deletion 并说明原因");
        }
        Doc doc = docMapper.selectById(id);
        if (doc == null) {
            return null;
        }
        doc.setStatus(status);
        docMapper.updateById(doc);
        
        // 记录状态变更日志
        callLogService.logUpdate(doc);
        
        return doc;
    }

    // ==================== M10 标记待删（2026-10-25） ====================
    //
    // 设计红线：模型没有删除能力。它能做的只有「标记」，真正的删除必须由用户在后台点击执行。
    // 实现上借用 status 字段（值 PENDING_DELETE）：
    //   - 所有检索默认只查 ACTIVE（search / check_my_todos / listRecentAccessible），待删项自动不可见
    //   - 后台 /docs?status=PENDING_DELETE 就是待删队列，用户在这里恢复或彻底删除
    // 这样不碰任何检索 SQL，避免为了一个状态改动全库查询条件。

    /** 待删状态值：AI 标记后等待用户确认，不等于已删除 */
    public static final String PENDING_DELETE = "PENDING_DELETE";

    /**
     * 标记经验为待删。
     *
     * @param id     经验 ID
     * @param reason 删除原因（必填，用户据它判断是否真删）
     * @param by     标记人（助手名，记录进 delete_marked_by）
     * @return 标记后的文档；文档不存在返回 null
     */
    @Transactional
    public Doc markForDeletion(Long id, String reason, String by) {
        Doc doc = docMapper.selectById(id);
        if (doc == null) {
            return null;
        }
        doc.setStatus(PENDING_DELETE);
        doc.setDeleteReason(truncateForColumn(reason, 500));
        doc.setDeleteMarkedBy(truncateForColumn(by, 100));
        doc.setDeleteMarkedAt(java.time.LocalDateTime.now());
        docMapper.updateById(doc);
        callLogService.logUpdate(doc);
        log.info("markForDeletion: doc={} by={} reason={}", id, by, reason);
        return doc;
    }

    /**
     * 取消待删标记，恢复为 ACTIVE。
     * 用户判断「这条不该删」时在后台点恢复；AI 误标后也可用 cancel_deletion 自行撤回。
     */
    @Transactional
    public Doc cancelDeletion(Long id) {
        Doc doc = docMapper.selectById(id);
        if (doc == null) {
            return null;
        }
        if (!PENDING_DELETE.equals(doc.getStatus())) {
            return doc;
        }
        doc.setStatus("ACTIVE");
        doc.setDeleteReason(null);
        doc.setDeleteMarkedBy(null);
        doc.setDeleteMarkedAt(null);
        docMapper.updateById(doc);
        callLogService.logUpdate(doc);
        log.info("cancelDeletion: doc={}", id);
        return doc;
    }

    /** 待删队列（后台用）：按标记时间倒序，仅供参考与批量清理，不参与任何 MCP 检索 */
    public Page<Doc> listPendingDelete(int page, int size) {
        QueryWrapper<Doc> wrapper = new QueryWrapper<>();
        wrapper.eq("status", PENDING_DELETE).orderByDesc("delete_marked_at");
        return docMapper.selectPage(new Page<>(page, size), wrapper);
    }

    /** 列宽保护：delete_reason 是 varchar(500)，超长在 STRICT 模式下会直接抛错 */
    private static String truncateForColumn(String s, int max) {
        if (s == null) return null;
        String t = s.trim();
        return t.length() > max ? t.substring(0, max) : t;
    }

    /**
     * 按模板类型查询（用于 check_my_todos 等场景）
     */
    public List<Doc> listByTemplateType(String templateType) {
        return listByTemplateType(templateType, null, null);
    }

    /**
     * 按模板类型列经验，支持项目与状态过滤（A7）。
     *
     * <p>为什么不直接把默认值改成「全部」：原行为是「只列全局、只列 ACTIVE」，
     * 项目内容不外泄到全局、已完成的待办不刷屏。默认值必须保持，否则调用方拿到的是
     * 一屏已完成任务，反而看不清还有什么没做。
     *
     * @param projectId null 或 0 = 全局（默认行为）；&gt;0 = 指定项目
     * @param status    null = ACTIVE；传 ALL 表示不限状态
     */
    public List<Doc> listByTemplateType(String templateType, Long projectId, String status) {
        QueryWrapper<Doc> wrapper = new QueryWrapper<>();
        // API Key 级别隔离
        AiAssistant assistant = ApiKeyInterceptor.getCurrentAssistant();
        if (assistant != null) {
            wrapper.and(w -> w.eq("visibility", "PUBLIC").or().eq("api_key", assistant.getApiKey()));
        }
        if (projectId == null || projectId == 0) {
            wrapper.isNull("project_id"); // 待办等列表只列全局经验，项目内容不泄漏到全局
        } else {
            wrapper.eq("project_id", projectId);
        }
        wrapper.eq("template_type", templateType);
        if (status != null && !status.trim().isEmpty() && !"ALL".equalsIgnoreCase(status.trim())) {
            wrapper.eq("status", status.trim().toUpperCase());
        }
        wrapper.orderByDesc("updated_at");
        return docMapper.selectList(wrapper);
    }

    /**
     * 版本表占用统计（只读，供后台展示「快照在膨胀」这件事）。
     * 不改任何数据 —— 清理必须由人决定（删历史正文是不可逆操作）。
     */
    public java.util.Map<String, Object> versionStats() {
        java.util.Map<String, Object> out = new java.util.LinkedHashMap<>();
        QueryWrapper<DocVersion> qw = new QueryWrapper<>();
        qw.select("COUNT(*) AS cnt",
                  "IFNULL(SUM(CHAR_LENGTH(content)),0) AS bytes",
                  "SUM(CASE WHEN change_type = 'META' THEN 1 ELSE 0 END) AS metaCnt");
        try {
            java.util.Map<String, Object> row = versionMapper.selectMaps(qw).get(0);
            long cnt = row.get("cnt") == null ? 0 : ((Number) row.get("cnt")).longValue();
            long bytes = row.get("bytes") == null ? 0 : ((Number) row.get("bytes")).longValue();
            long metaCnt = row.get("metaCnt") == null ? 0 : ((Number) row.get("metaCnt")).longValue();
            out.put("snapshots", cnt);
            out.put("kb", bytes / 1024);
            out.put("metaCount", metaCnt);
            out.put("avgChars", cnt == 0 ? 0 : (bytes / cnt));
        } catch (Exception e) {
            log.warn("versionStats failed: {}", e.getMessage());
        }
        return out;
    }

    // ==================== 记忆列表（游标分页） ====================

    /**
     * 记忆列表分页结果。
     * @param docs       当前页文档（最多 pageSize 条）
     * @param nextCursor 下一页游标；没有下一页时为 "-"
     */
    public record RecentPage(List<Doc> docs, String nextCursor) {}

    /** 游标格式：<updated_at>|<id>，如 2026-10-22T19:05:12|437 */
    private static final DateTimeFormatter CURSOR_TIME =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss");

    /**
     * 按更新时间倒序枚举当前 Key 的记忆：全局经验（api_key 归属当前 Key）+ 自有活跃项目内的经验。
     *
     * <p>使用 keyset 分页（updated_at + id），不用 offset：记忆持续新增时 offset 会重复/漏条。
     * 只返回 ACTIVE 文档，避免已废弃/已损坏内容出现在记忆流中。
     * 禁止在 MCP 工具里另写 SQL —— 复用本方法的可见性口径。
     *
     * @param pageSize 每页条数（调用方已校验；此处再兜底 1~50）
     * @param cursor   上一页返回的游标，首页传 null；非法格式按首页处理
     */
    public RecentPage listRecentAccessible(int pageSize, String cursor) {
        return listRecentAccessible(pageSize, cursor, null);
    }

    /**
     * 同上，多一个时间下界（D1 施工包 2：L1「每日增量」需要「只看今天新增/改动的」）。
     *
     * <p>为什么必须有它：原来只能靠游标一页页翻来判断「今天有什么」，条目一多就不可靠
     * —— 翻到第 5 页时前面可能已经被新写入挤走了。
     *
     * @param since 只看 updated_at &gt;= since 的；null=不限
     */
    public RecentPage listRecentAccessible(int pageSize, String cursor, LocalDateTime since) {
        AiAssistant assistant = ApiKeyInterceptor.getCurrentAssistant();
        String apiKey = (assistant != null) ? assistant.getApiKey() : null;

        // MCP 工具必须带 Key 上下文；缺失时不返回任何内容，避免越权枚举
        if (apiKey == null) {
            log.warn("listRecentAccessible: CURRENT_ASSISTANT is NULL, 拒绝返回记忆列表");
            return new RecentPage(List.of(), "-");
        }

        int size = (pageSize < 1) ? 10 : Math.min(pageSize, 50);

        // 自有活跃项目：项目本身即隔离边界
        List<Long> projectIds = new ArrayList<>();
        for (Project p : projectMapper.selectList(new QueryWrapper<Project>()
                .eq("api_key", apiKey).eq("status", "ACTIVE"))) {
            projectIds.add(p.getId());
        }

        QueryWrapper<Doc> wrapper = new QueryWrapper<>();
        wrapper.eq("status", "ACTIVE");
        wrapper.and(scope -> {
            // 全局经验：只取归属当前 Key 的（“自己的全局”）
            scope.and(global -> global.isNull("project_id").eq("api_key", apiKey));
            if (!projectIds.isEmpty()) {
                scope.or().in("project_id", projectIds);
            }
        });

        if (since != null) {
            wrapper.ge("updated_at", since);
        }

        Cursor c = parseCursor(cursor);
        if (c != null) {
            wrapper.and(k -> k.lt("updated_at", c.updatedAt())
                    .or(o -> o.eq("updated_at", c.updatedAt()).lt("id", c.id())));
        }

        wrapper.orderByDesc("updated_at").orderByDesc("id");
        // 多取 1 条用于精确判断是否还有下一页
        wrapper.last("LIMIT " + (size + 1));

        List<Doc> rows = docMapper.selectList(wrapper);
        boolean hasMore = rows.size() > size;
        List<Doc> page = hasMore ? new ArrayList<>(rows.subList(0, size)) : rows;
        String next = (hasMore && !page.isEmpty()) ? formatCursor(page.get(page.size() - 1)) : "-";
        return new RecentPage(page, next);
    }

    private String formatCursor(Doc doc) {
        LocalDateTime t = doc.getUpdatedAt() != null ? doc.getUpdatedAt() : doc.getCreatedAt();
        return CURSOR_TIME.format(t) + "|" + doc.getId();
    }

    /**
     * 解析游标；任何非法格式（含 null/空/“-”）都返回 null，由调用方按首页处理。
     */
    private Cursor parseCursor(String cursor) {
        if (cursor == null) return null;
        String s = cursor.trim();
        if (s.isEmpty() || "-".equals(s)) return null;
        int sep = s.lastIndexOf('|');
        if (sep <= 0 || sep == s.length() - 1) return null;
        try {
            LocalDateTime time = LocalDateTime.parse(s.substring(0, sep), DateTimeFormatter.ISO_LOCAL_DATE_TIME);
            long id = Long.parseLong(s.substring(sep + 1).trim());
            if (id <= 0) return null;
            return new Cursor(time, id);
        } catch (Exception e) {
            return null;
        }
    }

    private record Cursor(LocalDateTime updatedAt, long id) {}

    private String maskKey(String key) {
        if (key == null || key.length() < 12) return "****";
        return key.substring(0, 8) + "..." + key.substring(key.length() - 4);
    }
}
