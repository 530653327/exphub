package com.exphub.service;

import com.exphub.entity.Doc;
import com.exphub.entity.ReviewCandidate;
import com.exphub.mapper.DocMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

/**
 * 2026-11-11 阁主派单（T261111085841-439）两项入队门槛的守卫。
 *
 * <p><b>A. 正文 0 行重合不入队</b>（#2101 依据：368 对里 353 对是 0 行；L0 模拟 118 条入队里 112 条是 0 行）
 * <p><b>B. 已判过的对不占名额</b>（实测展示 50 对里 48 对已判过 → 整理永远推进不下去）
 *
 * <p>四条断言对应派单验收里点名的四个场景：
 * <ol>
 *   <li>0 行重合跳过；</li>
 *   <li><b>回退 2-gram 的对不被误伤</b>（0 行里 81 条属此类，判 {@code sharedLines == 0} 就会错杀）；</li>
 *   <li>{@code minSharedLines=0} 时行为与旧版一致（回退开关）；</li>
 *   <li>已判过的不进展示但计数正确（差额必须能对上账）。</li>
 * </ol>
 */
@ExtendWith(MockitoExtension.class)
class SharedLineGateTest {

    @Mock
    private DocMapper docMapper;

    @Mock
    private DocService docService;

    @Mock
    private ProjectService projectService;

    @Mock
    private ReviewCandidateService candidateService;

    @InjectMocks
    private SimilarityService service;

    /** 三行、行行不重合：按行口径 sharedLines=0，但标题标签全同 → 分数照样超阈值 */
    private static final String BODY_A = String.join("\n",
            "部署脚本先停止旧服务再打包，端口固定 8088",
            "反向代理统一走 nginx，证书放在 /etc/ssl 下",
            "回滚第一步先停服务，再切回上一个 tag");

    private static final String BODY_B = String.join("\n",
            "检索排序只按覆盖度加权，热度只做同分决胜",
            "候选池上限两百条，超出部分按自然分词分数截断",
            "验收必须带查询原文与调用次数，否则不算通过");

    private Doc doc(long id, String body) {
        Doc d = new Doc();
        d.setId(id);
        d.setTitle("ExpHub 部署流程");
        d.setTags("exphub,部署,运维");
        d.setAliases("");
        d.setCategory("部署");
        d.setContent(body);
        d.setStatus("ACTIVE");
        return d;
    }

    private void stubIndex(List<Doc> store) {
        org.mockito.Mockito.lenient().when(projectService.listProjects()).thenReturn(List.of());
        org.mockito.Mockito.lenient().when(docMapper.selectList(any())).thenReturn(new ArrayList<>(store));
        org.mockito.Mockito.lenient().when(docService.listByIds(any())).thenAnswer(inv -> {
            java.util.Collection<Long> ids = inv.getArgument(0);
            List<Doc> out = new ArrayList<>();
            for (Doc d : store) if (ids.contains(d.getId())) out.add(d);
            return out;
        });
    }

    private void stubEnqueue(long candidateId) {
        ReviewCandidate fake = new ReviewCandidate();
        fake.setId(candidateId);
        fake.setStatus(ReviewCandidate.PENDING);
        when(candidateService.enqueue(anyLong(), anyLong(), any(), anyString(), anyString()))
                .thenReturn(new ReviewCandidateService.EnqueueResult(true, fake, "已入队"));
    }

    // ---------- ① 判据本身：三种形态 ----------

    @Test
    @DisplayName("① 按行口径 0 行重合 → 拦；1 行 → 放")
    void gateBlocksZeroSharedLinesInLineMode() {
        assertFalse(SimilarityService.passesSharedLineGate(true, 0, 1),
                "按行比较 0 行完全重合 = 没有内容证据，不该进候选");
        assertTrue(SimilarityService.passesSharedLineGate(true, 1, 1), "1 行就该放过（门槛是「至少 1 行」）");
        assertTrue(SimilarityService.passesSharedLineGate(true, 2, 1));
        assertFalse(SimilarityService.passesSharedLineGate(true, 1, 2), "门槛调到 2 时 1 行不该放");
    }

    @Test
    @DisplayName("② 回退 2-gram 的对不被误伤：sharedLines 恒为 0，但它有正文重合")
    void gateMustNotPunishTwoGramFallback() {
        assertTrue(SimilarityService.passesSharedLineGate(false, 0, 1),
                "行数太少回退 2-gram 时 sharedLines 恒为 0 —— 拦它就是误伤（#2101：353 条 0 行里 81 条属此类）");
        assertTrue(SimilarityService.passesSharedLineGate(false, 0, 5),
                "门槛调多高都不该管这类对：它们根本没走行口径");
    }

    @Test
    @DisplayName("③ minSharedLines=0 = 关闭这道门（回退旧行为）")
    void zeroMeansGateOff() {
        assertTrue(SimilarityService.passesSharedLineGate(true, 0, 0), "传 0 时 0 行重合也照旧放行");
        assertTrue(SimilarityService.passesSharedLineGate(false, 0, 0));
        assertTrue(SimilarityService.passesSharedLineGate(true, 0, -5),
                "负值在 service 入口已被拒；纯函数这里也不许反向生效（不能因为负数把一切都拦掉）");
    }

    // ---------- ① + ③ 端到端：L2 扫描 ----------

    @Test
    @DisplayName("① L2：0 行重合的对不进清单、不入队，但「超阈值」仍如实统计")
    void scanSkipsZeroSharedLinePairByDefault() {
        stubIndex(List.of(doc(1L, BODY_A), doc(2L, BODY_B)));

        SimilarityService.ScanResult r = service.scan(null, 0.5, 20, 2, null, true, "测试");

        assertTrue(r.ok());
        assertEquals(1, r.aboveThreshold(), "超阈值仍是 1 对 —— 阈值口径不能因为门槛变小");
        assertEquals(1, r.gatedBySharedLines(), "被门槛拦下 1 对，要能单独报出来");
        assertEquals(0, r.rows().size(), "0 行重合的对不该进候选清单");
        assertTrue(r.summary().contains("行重合不足 1 行"),
                "范围行必须说明这 1 对为什么不在候选里：" + r.summary());
    }

    @Test
    @DisplayName("③ minSharedLines=0 时与旧版一致：同一对重新出现")
    void zeroRestoresOldBehaviorEndToEnd() {
        stubIndex(List.of(doc(1L, BODY_A), doc(2L, BODY_B)));

        SimilarityService.ScanResult r = service.scan(null, 0.5, 20, 2, null, true, "测试", false, 0);

        assertTrue(r.ok());
        assertEquals(1, r.rows().size(), "关闭门槛后，这对照旧进清单（旧行为）");
        assertEquals(0, r.gatedBySharedLines(), "门槛关了就不该有「拦下」计数");
        assertEquals(0, r.rows().get(0).sharedLines(), "前置条件：这对确实是 0 行重合");
    }

    @Test
    @DisplayName("② 端到端：回退 2-gram 的对即使 0 行重合也照常进清单")
    void scanKeepsTwoGramFallbackPairs() {
        // 两行正文 → 行数 < MIN_LINES_FOR_LINE_MODE → 走 2-gram 口径；两边内容完全相同 → 分数 1.0
        String shortBody = String.join("\n",
                "部署脚本先停止旧服务再打包，端口固定 8088",
                "反向代理统一走 nginx，证书放在 /etc/ssl 下");
        stubIndex(List.of(doc(1L, shortBody), doc(2L, shortBody)));

        SimilarityService.ScanResult r = service.scan(null, 0.5, 20, 2, null, true, "测试");

        assertTrue(r.ok());
        assertEquals(1, r.rows().size(),
                "回退 2-gram 的对不能被门槛误伤（#2101 实测 81 条属此类）");
        assertEquals(0, r.gatedBySharedLines());
        assertEquals(0, r.rows().get(0).sharedLines(), "前置条件：这类对的 sharedLines 恒为 0");
        assertFalse(r.rows().get(0).lineMode(), "前置条件：它确实走的是 2-gram 口径");
    }

    @Test
    @DisplayName("非法门槛（负数）明拒并回显收到的值，不静默改成默认")
    void negativeGateIsRejectedExplicitly() {
        SimilarityService.ScanResult r = service.scan(null, 0.5, 20, 2, null, true, "测试", false, -1);

        assertFalse(r.ok(), "非法参数必须拒绝执行");
        assertTrue(r.summary().contains("-1"), "要回显实际收到的值：" + r.summary());
        assertTrue(r.summary().contains("没有比较任何文档"), "要说清什么都没做：" + r.summary());
    }

    // ---------- ① L0：写入查重 ----------

    @Test
    @DisplayName("① L0：0 行重合不再提示、不再入队；回执写明拦了几条")
    void checkOnCreateSkipsZeroSharedLineHit() {
        stubIndex(List.of(doc(1L, BODY_A)));

        SimilarityService.L0Result r = service.checkOnCreate(doc(2L, BODY_B));

        assertTrue(r.ok());
        assertEquals(0, r.hits().size(), "正文 0 行重合不该再弹「发现相似经验」");
        assertEquals(0, r.enqueued(), "更不该入队");
        assertTrue(r.note().contains("未提示"), "拦了几条必须写进回执：" + r.note());

        SimilarityService.L0Result old = service.checkOnCreate(doc(2L, BODY_B), 0);
        assertEquals(1, old.hits().size(), "传 0 = 关闭门槛，恢复旧行为");
    }

    @Test
    @DisplayName("② L0：回退 2-gram 的命中不受门槛影响")
    void checkOnCreateKeepsTwoGramHit() {
        String shortBody = String.join("\n",
                "部署脚本先停止旧服务再打包，端口固定 8088",
                "反向代理统一走 nginx，证书放在 /etc/ssl 下");
        stubIndex(List.of(doc(1L, shortBody)));
        stubEnqueue(901L);

        SimilarityService.L0Result r = service.checkOnCreate(doc(2L, shortBody));

        assertEquals(1, r.hits().size(), "2-gram 口径的对有正文重合，不该被行门槛拦");
        assertTrue(r.hits().get(0).reasons().contains("回退 2-gram"), r.hits().get(0).reasons());
    }

    // ---------- ④ 已判过的对 ----------

    @Test
    @DisplayName("④ 已判过的对不进清单（①②层同样），但计数进 droppedJudged，不凭空消失")
    void judgedPairsAreNotShownButStillCounted() {
        SimilarityService.Row dup = new SimilarityService.Row(1L, "A", 2L, "B", 0.9, "r", null,
                SimilarityScorer.Kind.DUPLICATE, 5, 0.6, "advice");
        SimilarityService.Row ser = new SimilarityService.Row(3L, "C", 4L, "D", 0.7, "r", null,
                SimilarityScorer.Kind.SERIES, 2, 0.3, "advice");
        Set<String> judged = Set.of(
                ReviewCandidateService.pairKey(1L, 2L),
                ReviewCandidateService.pairKey(3L, 4L));

        SimilarityService.Selection sel = SimilarityService.select(List.of(dup, ser), 20, 2, 5, judged);

        assertTrue(sel.rows().isEmpty(), "判过的对一律不进清单（省下名额给未判过的）");
        assertEquals(1, sel.detail().get(SimilarityScorer.Kind.DUPLICATE).droppedJudged(),
                "①层判过的也要记账，否则「超阈值 − 展示」对不上");
        assertEquals(1, sel.detail().get(SimilarityScorer.Kind.SERIES).droppedJudged(),
                "②层同理");
        assertEquals(1, sel.detail().get(SimilarityScorer.Kind.DUPLICATE).above(),
                "分层统计仍反映全库事实：判过不等于不存在");
        assertEquals(0, sel.detail().get(SimilarityScorer.Kind.DUPLICATE).shown());
    }

    @Test
    @DisplayName("④ 未判过的对不受影响：同一批里判过的被剔除，剩下照常展示")
    void unjudgedPairsStillShown() {
        SimilarityService.Row judgedRow = new SimilarityService.Row(1L, "A", 2L, "B", 0.9, "r", null,
                SimilarityScorer.Kind.DUPLICATE, 5, 0.6, "advice");
        SimilarityService.Row fresh = new SimilarityService.Row(5L, "E", 6L, "F", 0.8, "r", null,
                SimilarityScorer.Kind.DUPLICATE, 4, 0.5, "advice");

        SimilarityService.Selection sel = SimilarityService.select(List.of(judgedRow, fresh), 20, 2, 5,
                Set.of(ReviewCandidateService.pairKey(1L, 2L)));

        assertEquals(1, sel.rows().size());
        assertEquals(5L, sel.rows().get(0).docA(), "留下的必须是没判过的那对");
        assertEquals(1, sel.detail().get(SimilarityScorer.Kind.DUPLICATE).droppedJudged());
    }

    @Test
    @DisplayName("门槛默认值必须是 1（派单硬要求），不是 0 也不是 2")
    void defaultThresholdIsOne() {
        assertEquals(1, SimilarityService.DEFAULT_MIN_SHARED_LINES,
                "默认 1：设 2 只多挡 5 条且全在③层（#2101 实测），抬高只会漏掉真候选");
        SimilarityService.Row zeroShared = new SimilarityService.Row(1L, "A", 2L, "B", 0.9, "r", null,
                SimilarityScorer.Kind.SERIES, 0, 0.0, "advice");
        assertFalse(SimilarityService.shouldEnqueue(zeroShared, false, 1),
                "shouldEnqueue 也必须有同一道门（第二条路径不能只改一处）");
        assertTrue(SimilarityService.shouldEnqueue(zeroShared, false, 0),
                "传 0 = 关闭门槛，旧行为");
    }

    @Test
    @DisplayName("L0 的默认门槛可配置：字段存在且默认 1（改配置即可回退，不必改代码）")
    void l0DefaultGateIsConfigurable() throws Exception {
        java.lang.reflect.Field f = SimilarityService.class.getDeclaredField("defaultMinSharedLines");
        f.setAccessible(true);
        assertEquals(1, f.get(service), "没有 Spring 上下文时应保持默认值 1");
        assertTrue(f.isAnnotationPresent(org.springframework.beans.factory.annotation.Value.class),
                "要有 exphub.similarity.min-shared-lines 开关 —— 这是「不回退代码也能关掉」的那条路");
    }
}
