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
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
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
    /** 阈值合法区间：低于 0.30 会把整个库都算成候选，高于 0.95 一对都出不来 */
    public static final double MIN_THRESHOLD = 0.30;
    public static final double MAX_THRESHOLD = 0.95;
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
    private final DocService docService;
    private final ProjectService projectService;
    private final ReviewCandidateService candidateService;

    private final Map<String, Cached> cache = new ConcurrentHashMap<>();

    private record Cached(ScopeIndex index, long builtAt) {}

    /** 一个作用域的索引：全局库或某个项目 */
    public record ScopeIndex(Long projectId,
                             String label,
                             List<SimilarityScorer.Sketch> sketches,
                             Map<String, Integer> lineDf,
                             int skippedShort,
                             int skippedSuperseded,
                             LocalDateTime builtAt) {

        public String buildTime() {
            return builtAt == null ? "-" : builtAt.format(HHMMSS);
        }

        /**
         * 把「还没进索引的文档」（刚写入的那篇）加工成可比较的快照。
         *
         * <p>必须用作用域已有的行 df 回填去模板行，否则它会以
         * {@code lineModeReliable=false} 参与比较 → 永远回退 2-gram 口径 →
         * **同一对文档在 L0 与 L2 下得到两个分数**。这正是线上第一次实测暴露的 bug
         * （#648 明明是标准多行文档，回执却写「回退 2-gram 口径」）。
         */
        public SimilarityScorer.Sketch prepare(SimilarityScorer.Sketch incoming) {
            Map<String, Integer> df = lineDf == null ? Map.of() : lineDf;
            java.util.Set<String> distinct = SimilarityScorer.distinctLines(incoming.lines(), df);
            return incoming.withDistinctLines(distinct,
                    SimilarityScorer.isLineModeReliable(incoming.lines()));
        }
    }

    /** 一条候选（L0 与 L2 共用） */
    public record Suggestion(Long docId, String title, double score, String reasons,
                             SimilarityScorer.Kind kind, int sharedLines, double coverage,
                             String advice) {}

    /** L0 命中的一条 + 它在队列里的实际状态（回执必须自证值，不能只说「已处理」） */
    public record L0Hit(Long docId, String title, double score, String reasons, String queueState,
                        SimilarityScorer.Kind kind, int sharedLines, double coverage, String advice) {}

    public record L0Result(boolean ok, String note, int compared, List<L0Hit> hits, int enqueued) {}

    /**
     * 扫描出来的一行结果。
     *
     * @param motherId 「并回母档再清子页」时的母档 ID（其余动作为 null）
     * @param kidId    同上，被并进去的子页 ID
     * @param kidLines 同上，子页独有行数 —— <b>这个数字只在「并之前」成立</b>，
     *                 同一母档的每一对并完都会变（2026-10-26 阁主指出的串行依赖）
     */
    public record Row(Long docA, String titleA, Long docB, String titleB,
                      double score, String reason, String queueState,
                      SimilarityScorer.Kind kind, int sharedLines, double coverage, String advice,
                      Long motherId, Long kidId, int kidLines) {

        /** 只描述「像什么」、不涉及合并方向时的构造（母档/子页留空） */
        public Row(Long docA, String titleA, Long docB, String titleB,
                   double score, String reason, String queueState,
                   SimilarityScorer.Kind kind, int sharedLines, double coverage, String advice) {
            this(docA, titleA, docB, titleB, score, reason, queueState, kind, sharedLines, coverage, advice,
                    null, null, 0);
        }

        /** 分层排序键：先按「像的是什么」，同层再比分数 —— 单靠分数排会把演进链排到真重复前面 */
        static java.util.Comparator<Row> order() {
            return java.util.Comparator
                    .comparingInt((Row r) -> r.kind() == null ? 9 : r.kind().ordinal())
                    .thenComparing(java.util.Comparator.comparingDouble(Row::score).reversed());
        }
    }

    /**
     * 扫描结果。
     *
     * <p>⚠️ <b>2026-10-26 修</b>：{@code tierStats} 统计的是**限流前全部超阈值对**，
     * 与 {@code rows}（限流后展示的那批）不是同一个集合。
     *
     * <p>为什么必须分开（阁主实测发现）：原来分层统计直接对 {@code rows} 数个数，
     * 于是同一阈值下 limit 一改结论就变 ——
     * <pre>
     *   limit=12 → 字面重复 4 ｜ 演进链 8            （进度层 36 对整层消失）
     *   limit=50 → 字面重复 4 ｜ 演进链 10 ｜ 进度 36
     * </pre>
     * 下一个人用默认 limit 一跑，会以为进度降级失效了。
     * <b>统计必须反映全库事实，限流只该影响「这次显示几条」。</b>
     *
     * @param tierStats 全量超阈值对的分层计数（key = Kind），与 rows 无关
     */
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
                             int maxPerDoc,
                             int limit,
                             int filledPerDoc,
                             int enqueued,
                             int alreadyPending,
                             int alreadyResolved,
                             List<Row> rows,
                             java.util.Map<SimilarityScorer.Kind, Integer> tierStats,
                             java.util.Map<SimilarityScorer.Kind, TierDetail> tierDetail,
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
            // 必须走 index.prepare 而不是裸 Sketch.of：否则新文档拿不到「去模板行」，
            // 会以回退的 2-gram 口径参与比较，和 L2 的分数不一致（实测踩过）
            SimilarityScorer.Sketch self = index.prepare(SimilarityScorer.Sketch.of(doc.getId(), doc.getTitle(),
                    doc.getTags(), doc.getAliases(), doc.getCategory(), doc.getProjectId(),
                    doc.getTemplateType(), doc.getContent()));

            List<Suggestion> hits = new ArrayList<>();
            for (SimilarityScorer.Sketch other : index.sketches()) {
                if (other.id().equals(doc.getId())) continue;
                SimilarityScorer.Score s = SimilarityScorer.score(self, other);
                if (s.total() < CREATE_THRESHOLD) continue;
                hits.add(new Suggestion(other.id(), other.title(), s.total(), s.reason(),
                        s.kind(), s.sharedLines(), s.lineCoverage(), s.diagnosis()));
            }
            // 与 L2 同一套排序：先分层再比分数。写入查重如果只按分数，
            // 「刚写完一条待办」会命中一堆同话题的旧记录，真重复反而被挤出 TOP3
            hits.sort(Comparator
                    .comparingInt((Suggestion s) -> s.kind() == null ? 9 : s.kind().ordinal())
                    .thenComparing(Comparator.comparingDouble(Suggestion::score).reversed()));
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
                out.add(new L0Hit(h.docId(), h.title(), h.score(), h.reasons(), state,
                        h.kind(), h.sharedLines(), h.coverage(), h.advice()));
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
        return scan(projectId, threshold, limit, maxPerDoc, null, dryRun, operator);
    }

    /**
     * @param progressSample ③层（进度/日志）最多展示几条，{@code null} = 默认 5，{@code 0} = 不限。
     *                       2026-10-26 02:51 越哥定：可判性最低的层不该吃掉 60% 名额。
     */
    public ScanResult scan(Long projectId, Double threshold, Integer limit, Integer maxPerDoc,
                           Integer progressSample, boolean dryRun, String operator) {
        // ⚠️ 2026-10-25 阁主验收教训：原来这里是 `if (th < 0.30) th = 0.30` 静默兜底。
        // 上游把 0.5 截成整数 0 之后，静默兜底把它变成 0.30 —— 用户看到「阈值: 0.30」，
        // 只会得出「阈值参数根本没生效」的结论，**永远查不到是链路丢了小数**。
        // 静默修正等于把缺陷藏起来。现在非法值一律明拒，并在错误里写明收到多少。
        double th = (threshold == null) ? DEFAULT_THRESHOLD : threshold;
        if (!isThresholdValid(th)) {
            return new ScanResult(false, thresholdRejectMessage(threshold, th), DEFAULT_THRESHOLD, dryRun,
                    0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, List.of(), java.util.Map.of(), java.util.Map.of(), List.of());
        }
        int maxRows = (limit == null || limit < 1) ? 20 : Math.min(limit, 50);
        int perDoc = (maxPerDoc == null || maxPerDoc < 1) ? 2 : Math.min(maxPerDoc, 10);

        List<ScopeIndex> scopes = new ArrayList<>();
        List<String> notes = new ArrayList<>();
        try {
            if (projectId != null && projectId > 0) {
                Project p = projectService.getProject(projectId);
                if (p == null) {
                    return new ScanResult(false, "未找到项目 " + projectId + "，或你没有访问权限", th, dryRun,
                            0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, List.of(), java.util.Map.of(), java.util.Map.of(), List.of());
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
                    th, dryRun, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, List.of(), java.util.Map.of(), java.util.Map.of(), List.of());
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
                    //
                    // ⚠️ 2026-10-26 10:4x 阁主指出「同族多对 merge 的串行依赖」（唯一会让人按错数据动手的）：
                    //    一个母档同时命中 4 个子页时，4 对都给 merge，但每并完一对母档行数就变，
                    //    后一对的「独有 N 行 / 覆盖 X%」立刻过期，回执却把 4 对并列呈现。
                    //    → 把「母档 / 子页 / 子页独有行」一并带出去，渲染层才能按母档分组给出执行顺序。
                    long[] sides = mergeSides(s, a.id(), b.id());
                    above.add(new Row(a.id(), a.title(), b.id(), b.title(), s.total(), s.reason(), null,
                            s.kind(), s.sharedLines(), s.lineCoverage(), s.diagnosis(),
                            sides == null ? null : sides[0],
                            sides == null ? null : sides[1],
                            sides == null ? 0 : (int) sides[2]));
                }
            }
        }
        // 先分层再比分数：正文有重合（疑似真重复）的排前面，只有标题标签像的排后面。
        // 实测教训：单按分数排，0 行重合的演进链 0.62 会压住 33 行重合的真重复 0.51。
        above.sort(Row.order());

        // 全量分层统计：**必须基于限流前的 above**，不能基于最终 rows。
        // 阁主实测发现：原来按 rows 数个数，limit=12 时进度层 36 对整层消失，
        // 同一阈值下改个 limit 结论就变，下个人会以为降级失效。
        Map<SimilarityScorer.Kind, Integer> tierStats = new java.util.LinkedHashMap<>();
        for (Row r : above) {
            SimilarityScorer.Kind k = r.kind() == null ? SimilarityScorer.Kind.UNKNOWN : r.kind();
            tierStats.merge(k, 1, Integer::sum);
        }

        // ⚠️ 索引是缓存（3 分钟），期间被删掉的文档仍会出现在 pairs 里。
        // 线上实测踩到：8 条结果里 4 条是已删文档，占满名额把真候选挤到后面 ——
        // 入队时的存在性校验兜住了「不写脏数据」，但名额已经浪费了。
        // 所以先按库里真实存在性过滤，再进限流与截断。
        int staleDropped = 0;
        Set<Long> allIds = new LinkedHashSet<>();
        for (Row r : above) { allIds.add(r.docA()); allIds.add(r.docB()); }
        if (!allIds.isEmpty()) {
            Set<Long> alive = new LinkedHashSet<>();
            for (Doc d : docService.listByIds(allIds)) alive.add(d.getId());
            if (alive.size() < allIds.size()) {
                List<Row> filtered = new ArrayList<>();
                for (Row r : above) {
                    if (alive.contains(r.docA()) && alive.contains(r.docB())) {
                        filtered.add(r);
                    } else {
                        staleDropped++;
                    }
                }
                above = filtered;
            }
        }

        // per-doc 限流 + 分层优先截断：逻辑抽到静态 select()，纯函数、可单测。
        // ⚠️ 2026-10-26 02:1x 越哥实测：原来这段内联在 scan 里，①层 6 对只显示 4 条
        //    （#63 命中 4 个子页，被「每篇最多 2 条」砍掉两条），而低层把空出的名额填满 ——
        //    观感与「按分数截断」一样，读的人会以为分层没生效。抽出来才测得动。
        int sampleCap = (progressSample == null) ? DEFAULT_PROGRESS_SAMPLE : Math.max(0, progressSample);
        Selection sel = select(above, maxRows, perDoc, sampleCap);
        List<Row> selected = sel.rows();
        int dropped = 0;
        for (TierDetail d : sel.detail().values()) dropped += d.droppedPerDoc() + d.droppedByLimit();

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
            rowsOut.add(new Row(r.docA(), r.titleA(), r.docB(), r.titleB(), r.score(), r.reason(), state,
                    r.kind(), r.sharedLines(), r.coverage(), r.advice(),
                    r.motherId(), r.kidId(), r.kidLines()));
        }

        notes.add("只比较同一作用域内的文档（全局库 ↔ 全局库、项目内 ↔ 项目内）：跨项目实测全是假阳性"
                + "（6 个项目的 AGENTS.md 章节标题标签全同，两两 0.87，但它们各自属于不同项目）");
        notes.add("已判 distinct 的对不会重新入队；已标记取代（superseded_by 有值）的文档整条跳过");
        if (staleDropped > 0) {
            notes.add("本次丢弃 " + staleDropped + " 对「索引里有、库里已删」的过期候选（索引缓存 3 分钟内被删的文档会这样）");
        }
        if (skippedShort > 0) {
            notes.add("跳过正文短于 " + SimilarityScorer.MIN_CONTENT_LEN + " 字的文档 " + skippedShort + " 篇（信息量不足，判不准）");
        }
        String scopeLabel = (projectId == null) ? "全部作用域（" + scopes.size() + " 个）"
                : (projectId == 0 ? "仅全局库" : scopes.get(0).label());
        // ⚠️ 2026-10-26 08:4x 阁主两次实测：这里原来无条件拼「（limit=N；差额见分层明细）」，
        //    于是「展示 22 条 / limit=22」时也在喊「差额见分层明细」—— 没有差额，却让人去找差额。
        //    规则：**只有真的没用满 limit 才提差额，并且直接把差额数字写出来**（不要让人翻明细）。
        String capNote = "";
        if (selected.size() < above.size()) {
            // ⚠️ 2026-10-26 10:5x 阁主第 4 条：这里原来写「差额 N 条是各层规则主动省略」——
            //    把「可能的因」当成「已经发生的因」。实际差额常常全部来自③层抽样上限，
            //    per-doc 一次都没挤掉，范围行却并列报「per-doc 限流」。
            //    现在范围行**只报差额数字**，归因留给层头（那里是按真实计数拼的），不在这里猜。
            capNote = (maxRows > selected.size())
                    ? "（limit=" + maxRows + "；差额 " + (maxRows - selected.size())
                      + " 条，**每条的真实归因见下面的层头**，不在这里猜）"
                    : "（limit=" + maxRows + "，已用满；其余超阈值的对见分层）";
        }
        String summary = scopeLabel + "：" + docs + " 篇、比较 " + pairs + " 对、≥"
                + SimilarityScorer.Score.fmt(th) + " 共 " + above.size() + " 对"
                + (selected.size() < above.size() ? "，本次展示 " + selected.size() + " 对" + capNote : "");
        return new ScanResult(true, summary, th, dryRun, docs, skippedShort, skippedSuperseded, pairs,
                above.size(), dropped, perDoc, maxRows, sel.filledPerDoc(), enqueued, pending, resolved,
                rowsOut, tierStats, sel.detail(), notes);
    }

    /**
     * 某一层的「统计 / 展示 / 被挤掉」三件套 —— 让回执能自证「这层为什么少了」。
     *
     * <p><b>2026-10-26 02:1x 越哥实测发现</b>：①层统计 6 对，清单里只出现 4 条；
     * ②层 15 对只展示 10 条；而 ④层（进度类）却有 36 条、最低 0.47 的都进来了。
     * 他据此判断「limit 按总分取前 50，分层只决定显示顺序」。
     * 实测根因不是按分数截断，而是 **per-doc 限流**（`maxPerDoc`，默认 2）：
     * #63 在①层命中 4 个子页（65/66/67/68），第 3、4 条被「每篇最多 2 条」直接丢掉，
     * 丢掉的 2 个名额被低层填满 —— **观感与「按分数截断」完全一样，但病在别处**。
     *
     * <p>所以两件事都要做：①①层（最该先判、对数最少）不再受 per-doc 限制；
     * ②每层把「统计 X 对 / 展示 Y 条 / 被 per-doc 挤掉 Z 对（列出是哪几对）/ 被 limit 截断 W 对」
     * 写进回执 —— 缺了就让读的人看见缺了，而不是从「统计 6、清单 4」里自己猜。
     */
    public record TierDetail(int above, int shown, int droppedPerDoc, int droppedByLimit,
                             int droppedBySample, int droppedRedundant,
                             java.util.List<String> droppedSamples,
                             /** 被略去的同族交叉对（形如「#65↔#66」）—— 一行字备查，不占名额 */
                             java.util.List<String> omittedSamples) {

        public TierDetail(int above, int shown, int droppedPerDoc, int droppedByLimit,
                          int droppedBySample, int droppedRedundant,
                          java.util.List<String> droppedSamples) {
            this(above, shown, droppedPerDoc, droppedByLimit, droppedBySample, droppedRedundant,
                    droppedSamples, java.util.List.of());
        }

        /** 这一层有没有「没展示出来」的对 */
        public boolean hasDrop() {
            return droppedPerDoc > 0 || droppedByLimit > 0 || droppedBySample > 0 || droppedRedundant > 0;
        }
    }

    /**
     * 进度/日志层默认只展示几条样本（2026-10-26 02:51 越哥定）。
     *
     * <p>现场：清单 50 条里③层（进度/日志、「一般不用判」）占 30 条 = 60%，
     * <b>可判性最低的那层挤掉了别的</b>。这类记录的「像」是工作流决定的，本来就不用判，
     * 摆一堆只会把①②层的名额吃掉。
     */
    public static final int DEFAULT_PROGRESS_SAMPLE = 5;

    /** 限流结果：选中的行 + 每层的自证数据（纯函数，便于单测；不碰 DB） */
    public record Selection(java.util.List<Row> rows,
                            java.util.Map<SimilarityScorer.Kind, TierDetail> detail,
                            int filledPerDoc) {}

    /**
     * 把「全部超阈值对」限流成「这次展示哪些」。
     *
     * <p><b>铁律：分层优先于 limit。</b>入参必须是**已按层+分数排好序**的行（{@link Row#order()}），
     * 于是截断只会发生在最后一层，不会出现「高层被低层挤掉」。
     *
     * <p><b>①层（DUPLICATE）不受 per-doc 限制</b>，理由：
     * <ul>
     *   <li>①层是全库里最该人判的一批，对数本来就少（跨 7 个作用域才 6~7 对）；</li>
     *   <li>同一篇母文档命中多个子页（#63 ↔ 65/66/67/68）**正是需要一起判的信号**，
     *       砍掉后两条等于让判的人看不到「这是个母子文档族」；</li>
     *   <li>per-doc 的本意是「防止一批同格式提示词把队列占满」，那是②层及以下才有的量级。</li>
     * </ul>
     *
     * <p>①层也不计入 per-doc 计数：它已经展示了，低层再命中同一篇不额外占名额。
     */
    static Selection select(java.util.List<Row> sortedAbove, int maxRows, int perDoc) {
        return select(sortedAbove, maxRows, perDoc, DEFAULT_PROGRESS_SAMPLE);
    }

    static Selection select(java.util.List<Row> sortedAbove, int maxRows, int perDoc, int progressSample) {
        java.util.Map<SimilarityScorer.Kind, int[]> c = new java.util.LinkedHashMap<>();
        java.util.Map<SimilarityScorer.Kind, java.util.List<String>> samples = new java.util.LinkedHashMap<>();
        java.util.List<Row> selected = new java.util.ArrayList<>();
        java.util.List<Row> perDocDropped = new java.util.ArrayList<>();
        java.util.Map<Long, Integer> perDocCount = new java.util.LinkedHashMap<>();

        for (Row r : sortedAbove) {
            SimilarityScorer.Kind k = r.kind() == null ? SimilarityScorer.Kind.UNKNOWN : r.kind();
            c.computeIfAbsent(k, x -> new int[6])[0]++;   // [0]=above [1]=shown [2]=perDoc [3]=limit [4]=sample [5]=redundant
        }

        // ⚠️ 2026-10-26 02:51 越哥实测：上一版把 per-doc 豁免扩散到了②层，结果 6 个名额被一个家族占满 ——
        //    ②层展示的 14 条里有 6 条是 #63 家族的完全图 C(4,2)（#65↔#66/#65↔#67/#65↔#68/
        //    #66↔#67/#66↔#68/#67↔#68），结论全是 distinct。
        //    ①层豁免的理由是「一篇母档命中多个子页该一起判」，②层这批**不是一起判**，
        //    而是同一家族内部两两比较，而且 #63 与这四个子页在①层已逐对给过结论 —— 摆在②层纯占位。
        //    → **per-doc 只在①层豁免，②层恢复。**
        // ⚠️ 2026-10-26 03:15 越哥定：**③层（none）不参与抢名额** —— 让它排到最后，名额有余才给。
        //
        // 他给的判据：`progressSample=0` 时③层 33 条占 66% 名额，而②层 4 对被 per-doc 挤掉；
        // 「none 意味着不用判，占名额等于浪费；抢位会造成新的顺序复杂性和更多状态」。
        // 所以不做「补位优先抢③层名额」那种互相插队的规则，而是**三段式**：
        //   ① 可判层（①② + UNKNOWN）按原顺序占位 → ② per-doc 挤掉的补回 → ③ 最后才轮到进度类
        // 这样顺序规则只有一条：**能判的永远先于不用判的**。
        java.util.List<Row> progressRows = new java.util.ArrayList<>();

        // ⚠️ 2026-10-26 08:5x 阁主实测两件事，同源一个病：
        //   ① **跨层冲突**：①层 4 对 merge 各自说「清掉这一份」（#65/#66/#67/#68 各被点名清一次），
        //      而②层 #65↔#66/#65↔#67/#65↔#68/#66↔#67/#66↔#68/#67↔#68 六条全给 distinct
        //      「谁也别并、谁也别清」——**同一个 #66，一边要清它、一边说谁也别清它**。
        //   ② **补位把上一轮的修法抵消了**：上一版把 per-doc 豁免收回①层，本意是让②层不再砍家族交叉对，
        //      结果补位机制又按原顺序把它们补回来 —— ②层 15 条里 6 条（40%）是这些对，结论全 distinct、纯占位。
        //   → 一并解决：**①层家族内部的交叉对不再展示**。判据是「两侧都出现在①层的某一对里」——
        //     这几份的关系在①层已逐对判过，②层这一对没有新信息，留着只会与①层的「清掉这一份」打架。
        //   ⚠️ 只在**同族**（连通到同一篇母档）时跳过；家族成员与家族外的对（如 #67↔#68 之外的普通对）照常展示。
        //
        // ⚠️ 2026-10-26 10:4x 阁主指出（潜在，当时无实例）：判定键原来是「①层已判文档全集」，
        //    而不是「同一母档家族」。当时①层刚好有三个互不相干的家族（#63 族、#159↔#160、#59↔#71），
        //    于是「两侧都在集合里」碰巧等于「同族」。**将来 #159 若与 #63 在②层成对，会被误略** ——
        //    这两条从没在①层判过关系，略去等于凭空删掉一条有信息量的候选。
        //    修法：按母档分区（对①层每一对做并查集，familyId = 连通分量的代表），
        //    只有 docA 与 docB 落在**同一个分量**里才略去。
        java.util.Map<Long, Long> famParent = new java.util.LinkedHashMap<>();
        for (Row r : sortedAbove) {
            if (r.kind() == SimilarityScorer.Kind.DUPLICATE) {
                familyUnion(famParent, r.docA(), r.docB());
            }
        }
        java.util.Map<SimilarityScorer.Kind, java.util.List<String>> omitted =
                new java.util.LinkedHashMap<>();

        for (Row r : sortedAbove) {
            SimilarityScorer.Kind k = r.kind() == null ? SimilarityScorer.Kind.UNKNOWN : r.kind();
            if (k == SimilarityScorer.Kind.PROGRESS) {
                progressRows.add(r);   // 攒着，等可判层都坐下了再说
                continue;
            }
            // 同族交叉对：①层已逐对判过，这里跳过（补位也不许把它捞回来）
            if (k != SimilarityScorer.Kind.DUPLICATE && sameFamily(famParent, r)) {
                c.get(k)[5]++;
                noteOmitted(omitted, k, r);
                continue;
            }
            int[] cnt = c.get(k);
            if (selected.size() >= maxRows) {
                cnt[3]++;
                continue;
            }
            if (k != SimilarityScorer.Kind.DUPLICATE) {
                int ca = perDocCount.getOrDefault(r.docA(), 0);
                int cb = perDocCount.getOrDefault(r.docB(), 0);
                if (ca >= perDoc || cb >= perDoc) {
                    perDocDropped.add(r);   // 先攒着：名额有余要补回来，见下面的补位
                    continue;
                }
                perDocCount.put(r.docA(), ca + 1);
                perDocCount.put(r.docB(), cb + 1);
            }
            selected.add(r);
            cnt[1]++;
        }

        // 第二段：可判层被 per-doc 挤掉的，名额有余就补回
        // ⚠️ 2026-10-26 03:01 越哥实测：③层抽样截到 5 条后，**空出来的名额没人用** ——
        //    传 limit=50 实际只展示 22 条，而②层还有 4 对是被 per-doc 挤掉的。
        //    per-doc 的本意是「防一批同格式提示词刷屏」，**不是硬不变量**：
        //    名额闲着的时候，宁可多给几条候选，也不留空位。
        int filled = 0;
        for (Row r : perDocDropped) {
            if (selected.size() >= maxRows) break;
            // 双保险：同族交叉对即使进了候选池也不许补回（它的关系在①层已判过）
            if (sameFamily(famParent, r)) {
                c.get(r.kind() == null ? SimilarityScorer.Kind.UNKNOWN : r.kind())[5]++;
                noteOmitted(omitted, r.kind() == null ? SimilarityScorer.Kind.UNKNOWN : r.kind(), r);
                continue;
            }
            selected.add(r);
            filled++;
        }

        // 第三段：最后才给③层（进度/日志）—— 受 progressSample 与剩余名额双重约束
        for (Row r : progressRows) {
            int[] cnt = c.get(SimilarityScorer.Kind.PROGRESS);
            if (selected.size() >= maxRows) {
                cnt[3]++;
                continue;
            }
            if (progressSample > 0 && cnt[1] >= progressSample) {
                cnt[4]++;
                continue;
            }
            int ca = perDocCount.getOrDefault(r.docA(), 0);
            int cb = perDocCount.getOrDefault(r.docB(), 0);
            if (ca >= perDoc || cb >= perDoc) {
                cnt[2]++;
                samples.computeIfAbsent(SimilarityScorer.Kind.PROGRESS, x -> new java.util.ArrayList<>())
                        .add("#" + r.docA() + "↔#" + r.docB());
                continue;
            }
            perDocCount.put(r.docA(), ca + 1);
            perDocCount.put(r.docB(), cb + 1);
            selected.add(r);
            cnt[1]++;
        }
        selected.sort(Row.order());   // 展示顺序仍是①②③，非「先到先得」

        // 最终计数（必须基于补位后的 selected，否则「展示 N 条」与实际清单对不上）
        java.util.Map<SimilarityScorer.Kind, Integer> shownNow = new java.util.LinkedHashMap<>();
        for (Row r : selected) {
            SimilarityScorer.Kind k = r.kind() == null ? SimilarityScorer.Kind.UNKNOWN : r.kind();
            shownNow.merge(k, 1, Integer::sum);
        }
        java.util.Map<SimilarityScorer.Kind, Integer> stillDropped = new java.util.LinkedHashMap<>();
        int consumed = 0;
        for (Row r : perDocDropped) {
            if (consumed < filled) { consumed++; continue; }
            SimilarityScorer.Kind k = r.kind() == null ? SimilarityScorer.Kind.UNKNOWN : r.kind();
            stillDropped.merge(k, 1, Integer::sum);
            samples.computeIfAbsent(k, x -> new java.util.ArrayList<>())
                    .add("#" + r.docA() + "↔#" + r.docB());
        }

        java.util.Map<SimilarityScorer.Kind, TierDetail> detail = new java.util.LinkedHashMap<>();
        for (var e : c.entrySet()) {
            int[] v = e.getValue();
            detail.put(e.getKey(), new TierDetail(v[0], shownNow.getOrDefault(e.getKey(), 0),
                    stillDropped.getOrDefault(e.getKey(), 0), v[3], v[4], v[5],
                    samples.getOrDefault(e.getKey(), java.util.List.of()),
                    omitted.getOrDefault(e.getKey(), java.util.List.of())));
        }
        return new Selection(java.util.List.copyOf(selected), detail, filled);
    }

    /**
     * 一对候选如果是「并回母档再清子页」，返回 {@code {母档ID, 子页ID, 子页独有行}}；其余动作返回 null。
     *
     * <p>母档 = 行数多的一侧。两侧一样多时按 ID 小的当母档 —— 只是给一个确定的顺序，
     * <b>不代表判断</b>（判不了就不该给 merge，这是 decide() 的事）。
     *
     * <p>抽成纯函数是为了能单测「母档/子页」不会取反：取反的后果是把母档清掉、把子页留下。
     */
    static long[] mergeSides(SimilarityScorer.Score s, Long idA, Long idB) {
        if (s == null || idA == null || idB == null) return null;
        SimilarityScorer.Action act = SimilarityScorer.decide(s.kind(), s.sharedLines(),
                s.linesA(), s.linesB(), s.titleA(), s.titleB());
        if (act != SimilarityScorer.Action.MERGE_THEN_CLEAR) return null;
        boolean aMother = s.linesA() > s.linesB() || (s.linesA() == s.linesB() && idA < idB);
        return aMother ? new long[]{idA, idB, s.uniqueB()} : new long[]{idB, idA, s.uniqueA()};
    }

    /** 记一条「被略去的同族交叉对」，回执里要能点到具体 ID（只点 ID，不展开内容） */
    private static void noteOmitted(java.util.Map<SimilarityScorer.Kind, java.util.List<String>> acc,
                                    SimilarityScorer.Kind k, Row r) {
        acc.computeIfAbsent(k, x -> new java.util.ArrayList<>()).add("#" + r.docA() + "↔#" + r.docB());
    }

    /** 并查集：把①层判过的一对并到同一族（族代表固定取分量里最小的文档 ID，便于回执对账） */
    static void familyUnion(java.util.Map<Long, Long> parent, Long a, Long b) {
        if (a == null || b == null) return;
        Long ra = familyRoot(parent, a), rb = familyRoot(parent, b);
        if (ra == null) { parent.put(a, a); ra = a; }
        if (rb == null) { parent.put(b, b); rb = b; }
        if (ra.equals(rb)) return;
        if (ra < rb) parent.put(rb, ra); else parent.put(ra, rb);
    }

    /** 找族代表；文档没在①层判过 → null（不是任何家族的成员） */
    static Long familyRoot(java.util.Map<Long, Long> parent, Long x) {
        if (x == null || !parent.containsKey(x)) return null;
        Long cur = x;
        while (!cur.equals(parent.get(cur))) cur = parent.get(cur);
        parent.put(x, cur);   // 路径压缩
        return cur;
    }

    /**
     * 这一对的两侧是否在**同一族**里。
     *
     * <p>「都在①层的某对里」不等于「同族」：①层可能同时有 #63 族和 #159↔#160，
     * 拿全集当键会把跨族对（#65↔#159）也略去 —— 而这两份从未被判过关系。
     */
    static boolean sameFamily(java.util.Map<Long, Long> parent, Row r) {
        Long ra = familyRoot(parent, r.docA());
        Long rb = familyRoot(parent, r.docB());
        return ra != null && ra.equals(rb);
    }

    /** 阈值是否落在允许区间（NaN 一律无效） */
    public static boolean isThresholdValid(double th) {
        return th >= MIN_THRESHOLD && th <= MAX_THRESHOLD;
    }

    /**
     * 阈值非法时的拒绝说明。
     *
     * <p>重点是**区分「你传错了」和「链路把数丢了」**：
     * 收到恰好 0 而用户说传的是 0.5，那是小数被截成整数，必须直接点出来，
     * 否则用户只会以为「阈值参数不可调」，白白在 ExpHub 侧找 bug。
     */
    static String thresholdRejectMessage(Double raw, double parsed) {
        StringBuilder sb = new StringBuilder();
        sb.append("阈值 ").append(parsed).append(" 不在允许区间 [")
          .append(String.format(java.util.Locale.ROOT, "%.2f", MIN_THRESHOLD)).append(", ")
          .append(String.format(java.util.Locale.ROOT, "%.2f", MAX_THRESHOLD))
          .append("] 内，**已拒绝执行，没有比较任何文档、没有入队任何东西**。\n");
        sb.append("  ExpHub 实际收到的 threshold = ").append(parsed);
        if (parsed == 0d) {
            sb.append("\n  ⚠️ 收到的是 0。如果你传的是 0.5 / 0.55 这类小数，说明**调用链路上小数被截成了整数**"
                    + "（MCP 客户端常犯：`asInt()` / `intValue()` / `(int)`）。"
                    + "这是 ExpHub 之外的问题，ExpHub 收不到原始小数。");
        } else if (parsed < MIN_THRESHOLD) {
            sb.append("\n  低于下限会把整个库都算成候选（实测 0.30 时全库 5270 对），没有参考价值。");
        } else {
            sb.append("\n  高于上限几乎一对都出不来。");
        }
        sb.append("\n  不传 threshold 则用默认 ").append(DEFAULT_THRESHOLD).append("。");
        return sb.toString();
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
        // ⚠️ 这里**故意不调 qw.select(...) 白名单**，查全部列。
        //
        // 2026-10-26 00:18 踩的坑：原来是
        //     qw.select("id","title","tags","aliases","category","project_id","status","superseded_by","content")
        // 后来打分需要 template_type（判断进度类降级），但白名单没加 → getTemplateType() 恒为 null
        // → 新功能在线上完全不生效，而编译、测试、回执全都不报错。
        //
        // 这是**同一天第三次**「白名单漏字段」：
        //   ① 忆阁 ToolRegistry.updateStatus 白名单漏 updatedName
        //   ② 忆阁 createExperience 的注释里早就写着「不能只挑已知字段，否则静默丢参数」
        //   ③ 这里
        // 三次都是同一形态：**白名单在加字段时不会提醒你**。
        // Doc 表列不多、content 本来就要查，全查更安全 —— 拿这点 IO 换「永不再漏」。
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
                    d.getCategory(), d.getProjectId(), d.getTemplateType(), content));
        }
        List<SimilarityScorer.Sketch> withDistinct = SimilarityScorer.applyDistinctLines(sketches);
        Map<String, Integer> df = SimilarityScorer.lineDf(sketches);
        String label = projectId == null ? "全局库" : labelOf(projectId);
        log.debug("SimilarityService.buildIndex: scope={} docs={} usable={}", label, docs.size(), withDistinct.size());
        return new ScopeIndex(projectId, label, withDistinct, df, skippedShort, skippedSuperseded, LocalDateTime.now());
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
