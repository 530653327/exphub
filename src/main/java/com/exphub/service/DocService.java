package com.exphub.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
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

    private List<String> parseStatusList(String includeStatus) {
        if (includeStatus == null || includeStatus.trim().isEmpty()) return null;
        List<String> list = new ArrayList<>();
        for (String s : includeStatus.split(",")) {
            if (!s.trim().isEmpty()) list.add(s.trim());
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
        Doc old = docMapper.selectById(id);
        if (old == null) {
            return null;
        }
        // save version snapshot
        DocVersion v = new DocVersion();
        v.setDocId(id);
        v.setVersion(old.getVersion());
        v.setContent(old.getContent());
        v.setAliases(old.getAliases());
        v.setSummary(old.getSummary());
        AiAssistant assistant = ApiKeyInterceptor.getCurrentAssistant();
        if (assistant != null) {
            v.setUpdatedBy(assistant.getAssistantId());
            v.setUpdatedName(assistant.getAssistantName());
        } else {
            ServletRequestAttributes attrs = (ServletRequestAttributes) RequestContextHolder.getRequestAttributes();
            if (attrs != null) {
                HttpSession session = attrs.getRequest().getSession(false);
                User user = session != null ? (User) session.getAttribute("user") : null;
                if (user != null) {
                    v.setUpdatedBy("user_" + user.getId());
                    v.setUpdatedName(user.getDisplayName() != null ? user.getDisplayName() : user.getUsername());
                } else {
                    v.setUpdatedBy("system");
                    v.setUpdatedName("系统");
                }
            } else {
                v.setUpdatedBy("system");
                v.setUpdatedName("系统");
            }
        }
        versionMapper.insert(v);

        // update doc
        newDoc.setId(id);
        newDoc.setVersion(old.getVersion() + 1);
        newDoc.setAuthorId(old.getAuthorId());
        newDoc.setAuthorName(old.getAuthorName());
        newDoc.setCallCount(old.getCallCount());
        newDoc.setSuccessCount(old.getSuccessCount());
        newDoc.setFailCount(old.getFailCount());
        newDoc.setRating(old.getRating());
        newDoc.setRatingCount(old.getRatingCount());
        docMapper.updateById(newDoc);
        Doc updated = docMapper.selectById(id);
        
        // 记录更新日志
        callLogService.logUpdate(updated);
        
        return updated;
    }

    @Transactional
    public void delete(Long id) {
        Doc doc = docMapper.selectById(id);
        String title = doc != null ? doc.getTitle() : "未知";
        docMapper.deleteById(id);
        
        // 记录删除日志
        callLogService.logDelete(id, title);
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

    /**
     * 按模板类型查询（用于 check_my_todos 等场景）
     */
    public List<Doc> listByTemplateType(String templateType) {
        QueryWrapper<Doc> wrapper = new QueryWrapper<>();
        // API Key 级别隔离
        AiAssistant assistant = ApiKeyInterceptor.getCurrentAssistant();
        if (assistant != null) {
            wrapper.and(w -> w.eq("visibility", "PUBLIC").or().eq("api_key", assistant.getApiKey()));
        }
        wrapper.isNull("project_id"); // 待办等列表只列全局经验，项目内容不泄漏到全局
        wrapper.eq("template_type", templateType)
               .eq("status", "ACTIVE")
               .orderByDesc("updated_at");
        return docMapper.selectList(wrapper);
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
