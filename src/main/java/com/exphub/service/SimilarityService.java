package com.exphub.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.exphub.entity.Doc;
import com.exphub.entity.Project;
import com.exphub.entity.ReviewCandidate;
import com.exphub.interceptor.ApiKeyInterceptor;
import com.exphub.mapper.DocMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * D1 施工包 4：相似度候选生成（L0 写入查重 + L2 全库两两打分）。
 *
 * <p>与包 3 的分工：包 3 只解决「候选对存哪、谁判过」；本类解决「候选对从哪来」。
 * 之前 {@code add_review_candidate} 只能人工发现，整理永远推进不下去。
 *
 * <p><b>三条硬约束（踩过才写下来的）</b>
 * <ol>
 *   <li><b>只在同一作用域内比较</b>。跨作用域实测全是假阳性：6 个项目的 AGENTS.md 章节
 *       （「AGENTS.md 索引」「目录结构」「数据模型」）标题相同、标签相同，两两 0.87，
 *       但它们是各项目自己的章节，一条也不该合并。作用域 = 全局库（project_id IS NULL）
 *       或单个项目。</li>
 *   <li><b>查重是旁路，不能拖垮主业务</b>。写入查重任何异常都吞掉并降级成一句提示，
 *       绝不让「创建经验」因为查重失败而失败。</li>
 *   <li><b>只入队，不判定</b>。算出来的分只用于排序，判重/判取代仍然由人用
 *       {@code resolve_candidate} 做。</li>
 * </ol>
 *
 * <p>索引缓存 3 分钟：全库 563 篇正文约 2.2MB，每次写入都全量重算会拖慢创建。
 * 缓存过期后第一次访问重建（约百毫秒级）。回执里会写「索引构建时间」，
 * 免得把过期索引当成实时数据（静默不等于正确）。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SimilarityService {

    /** L2 全库扫描的默认阈值：实测 0.50 时全库 23 对，人工十分钟能过一遍 */
    public static final double DEFAULT_THRESHOLD = 0.50;
    /** L0 写入查重更严：新写入就弹提示，宁缺毋滥 */
    public static final double CREATE_THRESHOLD = 0.55;
    /** 单次写入最多提示几条 */
    public static final int CREATE_TOP_N = 3;
    /** 索引缓存时长 */
    private static final long INDEX_TTL_MS = 180_000L;
    /** 单作用域最多索引多少篇（防极端库把内存吃满） */
    private static final int MAX_INDEX_DOCS = 5000;

    private static final DateTimeFormatter HHMMSS = DateTimeFormatter.ofPattern("HH:mm:ss");

    private final DocMapper docMapper;
    private final ProjectService projectService;
    private final ReviewCandidateService candidateService;

    private final Map<String, Cached> cache = new ConcurrentHashMap<>();

    private record Cached(ScopeIndex index, long builtAt) {}

    /** 一个作用域的索引：全局库或某个项目 */
    public record ScopeIndex(Long projectId,
                             String label,
                             List<SimilarityScorer.Sketch> sketches,
                             int skippedShort,
                             int skippedSuperseded,
                             LocalDateTime builtAt) {

        public String buildTime() {
            return builtAt == null ? "-" : builtAt.format(HHMMSS);
        }
    }

    /** 一条候选（L0 与 L2 共用） */
    public record Suggestion(Long docId, String title, double score, String reasons) {}

    /** L0 命中的一条 + 它在队列里的实际状态（回执必须自证值，不能只说「已处理」） */
    public record L0Hit(Long docId, String title, double score, String reasons, String queueState) {}

    public record L0Result(boolean ok, String note, int compared, List<L0Hit> hits, int enqueued) {}

    /** 扫描出来的一行结果 */
    public record Row(Long docA, String titleA, Long docB, String titleB,
                      double score, String reason, String queueState) {}

    public record ScanResult(boolean ok,
                             String summary,
                             double threshold,
                             boolean dryRun,
                             int docsCompared,
                             int skippedShort,
                             int skippedSuperseded,
                             long pairsCompared,
                             int aboveThreshold,
                             int droppedByCap,
                             int enqueued,
                             int alreadyPending,
                             int alreadyResolved,
                             List<Row> rows,
                             List<String> notes) {}

    // ==================== L0：写入查重 ====================

    /**
     * 新写入一篇文档后，找出作用域内与它最像的几条，入队并把结论带回回执。
     *
     * <p>不抛异常：查重失败只降级成「本次查重不可用」，创建本身必须成功。
     */
    public L0Result checkOnCreate(Doc doc) {
        if (doc == null || doc.getId() == null) {
            return new L0Result(false, "没有文档信息，跳过查重", 0, List.of(), 0);
        }
        try {
            ScopeIndex index = indexOf(doc.getProjectId());
            SimilarityScorer.Sketch self = SimilarityScorer.Sketch.of(doc.getId(), doc.getTitle(),
                    doc.getTags(), doc.getAliases(), doc.getCategory(), doc.getProjectId(), doc.getContent());

            List<Suggestion> hits = new ArrayList<>();
            for (SimilarityScorer.Sketch other : index.sketches()) {
                if (other.id().equals(doc.getId())) continue;
                SimilarityScorer.Score s = SimilarityScorer.score(self, other);
                if (s.total() < CREATE_THRESHOLD) continue;
                hits.add(new Suggestion(other.id(), other.title(), s.total(), s.reason()));
            }
            hits.sort(Comparator.comparingDouble(Suggestion::score).reversed());
            if (hits.size() > CREATE_TOP_N) hits = new ArrayList<>(hits.subList(0, CREATE_TOP_N));

            List<L0Hit> out = new ArrayList<>();
            int enqueued = 0;
            for (Suggestion h : hits) {
                String state;
                try {
                    ReviewCandidateService.EnqueueResult r = candidateService.enqueue(doc.getId(), h.docId(),
                            BigDecimal.valueOf(h.score()).setScale(4, RoundingMode.HALF_UP),
                            "写入查重（L0）：" + h.reasons(), "L0_CREATE");
                    if (r.created()) {
                        enqueued++;
                        state = "已入队 #" + (r.candidate() == null ? "?" : r.candidate().getId());
                    } else if (r.candidate() != null && ReviewCandidate.PENDING.equals(r.candidate().getStatus())) {
                        state = "已在队列 #" + r.candidate().getId() + "（待判断，已刷新得分）";
                    } else if (r.candidate() != null) {
                        state = "已判过（" + r.candidate().getStatus() + "），不重复入队";
                    } else {
                        state = "未入队：" + r.message();
                    }
                } catch (Exception e) {
                    log.warn("SimilarityService.checkOnCreate: 入队失败 {} - {}", doc.getId(), h.docId(), e);
                    state = "入队失败：" + e.getClass().getSimpleName();
                }
                out.add(new L0Hit(h.docId(), h.title(), h.score(), h.reasons(), state));
            }
            String note = out.isEmpty()
                    ? "作用域「" + index.label() + "」内没有相似度 ≥ "
                      + SimilarityScorer.Score.fmt(CREATE_THRESHOLD) + " 的经验（已比 " + index.sketches().size() + " 篇）"
                    : "作用域「" + index.label() + "」内发现 " + out.size() + " 条相似经验，新入队 " + enqueued + " 对";
            return new L0Result(true, note, Math.max(0, index.sketches().size() - 1), out, enqueued);
        } catch (Exception e) {
            log.error("SimilarityService.checkOnCreate: FAILED docId={}", doc.getId(), e);
            return new L0Result(false, "本次写入查重不可用（" + e.getClass().getSimpleName() + "），不影响创建结果",
                    0, List.of(), 0);
        }
    }

    // ==================== L2：全库两两打分 ====================

    /**
     * 全库（或指定作用域）两两打分，超过阈值的入队。
     *
     * @param projectId null = 全局库 + 我名下所有项目；0 = 仅全局库；&gt;0 = 指定项目
     * @param threshold 阈值，null 用 {@link #DEFAULT_THRESHOLD}
     * @param limit     最多返回/入队多少条
     * @param maxPerDoc 一篇文档最多出现在几条候选里（防止一个模板族把队列占满）
     * @param dryRun    true = 只算不入队
     */
    public ScanResult scan(Long projectId, Double threshold, Integer limit, Integer maxPerDoc,
                           boolean dryRun, String operator) {
        double th = (threshold == null) ? DEFAULT_THRESHOLD : threshold;
        if (th < 0.30) th = 0.30;
        if (th > 0.95) th = 0.95;
        int maxRows = (limit == null || limit < 1) ? 20 : Math.min(limit, 50);
        int perDoc = (maxPerDoc == null || maxPerDoc < 1) ? 2 : Math.min(maxPerDoc, 10);

        List<ScopeIndex> scopes = new ArrayList<>();
        List<String> notes = new ArrayList<>();
        try {
            if (projectId != null && projectId > 0) {
                Project p = projectService.getProject(projectId);
                if (p == null) {
                    return new ScanResult(false, "未找到项目 " + projectId + "，或你没有访问权限", th, dryRun,
                            0, 0, 0, 0, 0, 0, 0, 0, 0, List.of(), List.of());
                }
                scopes.add(indexOf(projectId));
            } else if (projectId != null && projectId == 0) {
                scopes.add(indexOf(null));
            } else {
                scopes.add(indexOf(null));
                for (Project p : projectService.listProjects()) {
                    scopes.add(indexOf(p.getId()));
                }
            }
        } catch (Exception e) {
            log.error("SimilarityService.scan: 建索引失败", e);
            return new ScanResult(false, "建立索引失败：" + e.getClass().getSimpleName() + " - " + e.getMessage(),
                    th, dryRun, 0, 0, 0, 0, 0, 0, 0, 0, 0, List.of(), List.of());
        }

        int docs = 0, skippedShort = 0, skippedSuperseded = 0;
        long pairs = 0;
        List<Row> above = new ArrayList<>();
        for (ScopeIndex idx : scopes) {
            docs += idx.sketches().size();
            skippedShort += idx.skippedShort();
            skippedSuperseded += idx.skippedSuperseded();
            List<SimilarityScorer.Sketch> list = idx.sketches();
            for (int i = 0; i < list.size(); i++) {
                for (int j = i + 1; j < list.size(); j++) {
                    pairs++;
                    SimilarityScorer.Score s = SimilarityScorer.score(list.get(i), list.get(j));
                    if (s.total() < th) continue;
                    SimilarityScorer.Sketch a = list.get(i);
                    SimilarityScorer.Sketch b = list.get(j);
                    // 同一作用域内 docA<docB 只是入队口径，这里按分数排序前的展示顺序不重要
                    above.add(new Row(a.id(), a.title(), b.id(), b.title(), s.total(), s.reason(), null));
                }
            }
        }
        above.sort(Comparator.comparingDouble(Row::score).reversed());

        // per-doc 限流：一条文档最多出现在 N 条候选里，取分更高的
        Map<Long, Integer> perDocCount = new LinkedHashMap<>();
        List<Row> selected = new ArrayList<>();
        int dropped = 0;
        for (Row r : above) {
            if (selected.size() >= maxRows) { dropped++; continue; }
            int ca = perDocCount.getOrDefault(r.docA(), 0);
            int cb = perDocCount.getOrDefault(r.docB(), 0);
            if (ca >= perDoc || cb >= perDoc) { dropped++; continue; }
            perDocCount.put(r.docA(), ca + 1);
            perDocCount.put(r.docB(), cb + 1);
            selected.add(r);
        }

        int enqueued = 0, pending = 0, resolved = 0;
        List<Row> rowsOut = new ArrayList<>();
        for (Row r : selected) {
            String state;
            if (dryRun) {
                state = "未入队（dryRun）";
            } else {
                try {
                    ReviewCandidateService.EnqueueResult er = candidateService.enqueue(r.docA(), r.docB(),
                            BigDecimal.valueOf(r.score()).setScale(4, RoundingMode.HALF_UP),
                            "全库扫描（L2）：" + r.reason(), "L2_SCAN");
                    if (er.created()) {
                        enqueued++;
                        state = "已入队 #" + (er.candidate() == null ? "?" : er.candidate().getId());
                    } else if (er.candidate() != null && ReviewCandidate.PENDING.equals(er.candidate().getStatus())) {
                        pending++;
                        state = "已在队列 #" + er.candidate().getId() + "（待判断，已刷新得分）";
                    } else if (er.candidate() != null) {
                        resolved++;
                        state = "已判过（" + er.candidate().getStatus() + "），不重复入队";
                    } else {
                        state = "未入队：" + er.message();
                    }
                } catch (Exception e) {
                    log.warn("SimilarityService.scan: 入队失败 {} - {}", r.docA(), r.docB(), e);
                    state = "入队失败：" + e.getClass().getSimpleName();
                }
            }
            rowsOut.add(new Row(r.docA(), r.titleA(), r.docB(), r.titleB(), r.score(), r.reason(), state));
        }

        notes.add("只比较同一作用域内的文档（全局库 ↔ 全局库、项目内 ↔ 项目内）：跨项目实测全是假阳性"
                + "（6 个项目的 AGENTS.md 章节标题标签全同，两两 0.87，但它们各自属于不同项目）");
        notes.add("已判 distinct 的对不会重新入队；已标记取代（superseded_by 有值）的文档整条跳过");
        if (skippedShort > 0) {
            notes.add("跳过正文短于 " + SimilarityScorer.MIN_CONTENT_LEN + " 字的文档 " + skippedShort + " 篇（信息量不足，判不准）");
        }
        String scopeLabel = (projectId == null) ? "全部作用域（" + scopes.size() + " 个）"
                : (projectId == 0 ? "仅全局库" : scopes.get(0).label());
        String summary = scopeLabel + "：" + docs + " 篇、比较 " + pairs + " 对、≥"
                + SimilarityScorer.Score.fmt(th) + " 共 " + above.size() + " 对"
                + (dropped > 0 ? "，限流后取前 " + selected.size() + " 对" : "");
        return new ScanResult(true, summary, th, dryRun, docs, skippedShort, skippedSuperseded, pairs,
                above.size(), dropped, enqueued, pending, resolved, rowsOut, notes);
    }

    /** 作用域索引（带 3 分钟缓存） */
    public ScopeIndex indexOf(Long projectId) {
        String key = cacheKey(projectId);
        Cached c = cache.get(key);
        long now = System.currentTimeMillis();
        if (c != null && now - c.builtAt() < INDEX_TTL_MS) {
            return c.index();
        }
        ScopeIndex idx = buildIndex(projectId);
        cache.put(key, new Cached(idx, now));
        return idx;
    }

    /** 写入/删除后主动作废缓存（不依赖它也能靠 TTL 兜底） */
    public void invalidate(Long projectId) {
        cache.remove(cacheKey(projectId));
    }

    private String cacheKey(Long projectId) {
        String k = callerKey();
        return (k == null ? "-" : k) + "|" + (projectId == null ? 0 : projectId);
    }

    private String callerKey() {
        try {
            var a = ApiKeyInterceptor.getCurrentAssistant();
            return a == null ? null : a.getApiKey();
        } catch (Exception e) {
            return null;
        }
    }

    private ScopeIndex buildIndex(Long projectId) {
        String apiKey = callerKey();
        QueryWrapper<Doc> qw = new QueryWrapper<>();
        qw.select("id", "title", "tags", "aliases", "category", "project_id", "status",
                "superseded_by", "content");
        if (projectId == null) {
            qw.isNull("project_id");
            if (apiKey != null) {
                qw.and(w -> w.eq("visibility", "PUBLIC").or().eq("api_key", apiKey));
            }
        } else {
            qw.eq("project_id", projectId);
        }
        qw.ne("status", DocService.PENDING_DELETE);
        qw.orderByAsc("id");
        qw.last("LIMIT " + MAX_INDEX_DOCS);

        List<Doc> docs = docMapper.selectList(qw);
        List<SimilarityScorer.Sketch> sketches = new ArrayList<>();
        int skippedShort = 0, skippedSuperseded = 0;
        for (Doc d : docs) {
            if (d.getSupersededBy() != null) { skippedSuperseded++; continue; }
            String content = d.getContent();
            if (content == null || content.length() < SimilarityScorer.MIN_CONTENT_LEN) { skippedShort++; continue; }
            sketches.add(SimilarityScorer.Sketch.of(d.getId(), d.getTitle(), d.getTags(), d.getAliases(),
                    d.getCategory(), d.getProjectId(), content));
        }
        List<SimilarityScorer.Sketch> withDistinct = SimilarityScorer.applyDistinctLines(sketches);
        String label = projectId == null ? "全局库" : labelOf(projectId);
        log.debug("SimilarityService.buildIndex: scope={} docs={} usable={}", label, docs.size(), withDistinct.size());
        return new ScopeIndex(projectId, label, withDistinct, skippedShort, skippedSuperseded, LocalDateTime.now());
    }

    private String labelOf(Long projectId) {
        try {
            Project p = projectService.getProject(projectId);
            return p == null ? ("项目#" + projectId) : ("项目「" + p.getName() + "」#" + projectId);
        } catch (Exception e) {
            return "项目#" + projectId;
        }
    }

    /** 干跑统计（后台/自测用）：不改数据，只回分数分布 */
    public Map<String, Object> distribution(Long projectId) {        Map<String, Object> out = new LinkedHashMap<>();
        ScopeIndex idx = indexOf(projectId);
        double[] cuts = {0.45, 0.50, 0.55, 0.60, 0.70};
        int[] counts = new int[cuts.length];
        long pairs = 0;
        List<SimilarityScorer.Sketch> list = idx.sketches();
        for (int i = 0; i < list.size(); i++) {
            for (int j = i + 1; j < list.size(); j++) {
                pairs++;
                double s = SimilarityScorer.score(list.get(i), list.get(j)).total();
                for (int k = 0; k < cuts.length; k++) if (s >= cuts[k]) counts[k]++;
            }
        }
        out.put("scope", idx.label());
        out.put("docs", list.size());
        out.put("pairs", pairs);
        for (int k = 0; k < cuts.length; k++) out.put(">=" + cuts[k], counts[k]);
        return out;
    }
}
