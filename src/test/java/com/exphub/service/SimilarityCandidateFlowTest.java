package com.exphub.service;

import com.exphub.entity.Doc;
import com.exphub.entity.ReviewCandidate;
import com.exphub.mapper.DocMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * D1 施工包 4：相似度候选生成的编排层（2026-10-25）。
 *
 * <p>三条断言对应三件真的会出事的事：
 * <ol>
 *   <li><b>查重是旁路</b>：索引建不起来时，只能降级成一句提示，不能让「创建经验」跟着失败
 *       —— 红线：副作用不得拖垮主业务。</li>
 *   <li><b>只入队、不判定</b>：命中只调用 enqueue，不碰正文、不改状态。</li>
 *   <li><b>限流</b>：一个模板族（一批同格式文档）不能把队列占满，per-doc 必须生效。</li>
 * </ol>
 */
@ExtendWith(MockitoExtension.class)
class SimilarityCandidateFlowTest {

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

    private static final String BODY = String.join("\n",
            "# 部署流程",
            "执行 bash deploy.sh，脚本会先 git reset 再打包并重启服务。",
            "重启后轮询 /health 九十秒，失败则退出并保留日志。",
            "回滚方式是 git reset 到上一个 tag 再跑一遍脚本。",
            "日志在 /usr/local/exphub/app.log，用 journalctl 看不到。");

    private Doc doc(long id, String title, String tags, String body, Long projectId) {
        Doc d = new Doc();
        d.setId(id);
        d.setTitle(title);
        d.setTags(tags);
        d.setAliases("");
        d.setCategory("部署");
        d.setProjectId(projectId);
        d.setContent(body);
        d.setStatus("ACTIVE");
        return d;
    }

    /** 存活性校验走的批量取：按传入的 id 过滤，模拟「库里真实存在哪些」 */
    private void stubAlive(List<Doc> all) {
        when(docService.listByIds(any())).thenAnswer(inv -> {
            java.util.Collection<Long> ids = inv.getArgument(0);
            List<Doc> out = new ArrayList<>();
            for (Doc d : all) if (ids.contains(d.getId())) out.add(d);
            return out;
        });
    }

    @Test
    void indexFailureDegradesInsteadOfThrowing() {
        when(docMapper.selectList(any())).thenThrow(new RuntimeException("db down"));

        SimilarityService.L0Result r = service.checkOnCreate(
                doc(100L, "部署流程", "exphub,部署", BODY, null));

        assertFalse(r.ok(), "索引建不起来时必须明确说自己不可用");
        assertTrue(r.note().contains("不影响创建结果"), "必须说清主流程没受影响：" + r.note());
        assertTrue(r.hits().isEmpty());
    }

    @Test
    void similarDocIsEnqueuedAsL0Candidate() {
        when(docMapper.selectList(any())).thenReturn(new ArrayList<>(List.of(
                doc(1L, "ExpHub 部署流程", "exphub,部署,systemd", BODY, null))));
        ReviewCandidate fake = new ReviewCandidate();
        fake.setId(900L);
        fake.setStatus(ReviewCandidate.PENDING);
        when(candidateService.enqueue(anyLong(), anyLong(), any(), anyString(), eq("L0_CREATE")))
                .thenReturn(new ReviewCandidateService.EnqueueResult(true, fake, "已入队 #900"));

        SimilarityService.L0Result r = service.checkOnCreate(
                doc(2L, "ExpHub 部署流程", "exphub,部署,systemd", BODY, null));

        assertTrue(r.ok());
        assertEquals(1, r.hits().size(), "同标题同标签同正文必须命中");
        assertEquals(1, r.enqueued());
        assertTrue(r.hits().get(0).queueState().contains("已入队"), r.hits().get(0).queueState());
        // 入队参数是「新文档, 库里那条」；顺序由 enqueue 内部规范化，这里只核对是谁与谁
        verify(candidateService).enqueue(eq(2L), eq(1L), any(), anyString(), eq("L0_CREATE"));
    }

    @Test
    void alreadyPendingHitSaysSoInsteadOfClaimingNewlyQueued() {
        when(docMapper.selectList(any())).thenReturn(new ArrayList<>(List.of(
                doc(1L, "ExpHub 部署流程", "exphub,部署", BODY, null))));
        ReviewCandidate exist = new ReviewCandidate();
        exist.setId(918L);
        exist.setStatus(ReviewCandidate.PENDING);
        when(candidateService.enqueue(anyLong(), anyLong(), any(), anyString(), eq("L0_CREATE")))
                .thenReturn(new ReviewCandidateService.EnqueueResult(false, exist, "该候选对已在队列中"));

        SimilarityService.L0Result r = service.checkOnCreate(
                doc(2L, "ExpHub 部署流程", "exphub,部署", BODY, null));

        assertEquals(0, r.enqueued(), "没有新建就不能说新建了");
        assertTrue(r.hits().get(0).queueState().contains("已在队列"), r.hits().get(0).queueState());
    }

    @Test
    void tierOneIsNotCappedByPerDoc() {
        // 三篇几乎完全一样的文档 → 三对都超阈值（①层）。
        //
        // ⚠️ 2026-10-26 02:1x 改判：原来断言「maxPerDoc=1 → 只留 1 对」，
        // 但那正是越哥实测发现的问题 —— ①层一篇命中多个子页时（#63↔65/66/67/68）
        // 被砍到只剩 2 条，最该人判的一批少一半，低层却把空出的名额填满。
        // 现在①层豁免 per-doc（②层及以下的限流见 SelectionLimitTest）。
        List<Doc> store = new ArrayList<>(List.of(
                doc(1L, "ExpHub 部署流程", "exphub,部署", BODY, null),
                doc(2L, "ExpHub 部署流程说明", "exphub,部署", BODY, null),
                doc(3L, "ExpHub 部署流程说明 v2", "exphub,部署", BODY, null)));
        when(projectService.listProjects()).thenReturn(List.of());
        when(docMapper.selectList(any())).thenReturn(store);
        stubAlive(store);
        ReviewCandidate fake = new ReviewCandidate();
        fake.setId(901L);
        fake.setStatus(ReviewCandidate.PENDING);
        when(candidateService.enqueue(anyLong(), anyLong(), any(), anyString(), eq("L2_SCAN")))
                .thenReturn(new ReviewCandidateService.EnqueueResult(true, fake, "已入队"));

        SimilarityService.ScanResult r = service.scan(null, 0.5, 20, 1, false, "测试");

        assertTrue(r.ok());
        assertEquals(3, r.aboveThreshold(), "阈值之上的原始对数应如实统计");
        assertEquals(3, r.rows().size(), "①层不受 per-doc 限制，三对全部展示");
        assertEquals(3, r.enqueued(), "展示 3 对就入队 3 对（dryRun=false）");
        verify(candidateService, atLeastOnce()).enqueue(anyLong(), anyLong(), any(), anyString(), anyString());
    }

    @Test
    void dryRunWritesNothing() {
        List<Doc> store = new ArrayList<>(List.of(
                doc(1L, "ExpHub 部署流程", "exphub,部署", BODY, null),
                doc(2L, "ExpHub 部署流程", "exphub,部署", BODY, null)));
        when(projectService.listProjects()).thenReturn(List.of());
        when(docMapper.selectList(any())).thenReturn(store);
        stubAlive(store);

        SimilarityService.ScanResult r = service.scan(null, 0.5, 20, 2, true, "测试");

        assertTrue(r.ok());
        assertEquals(1, r.aboveThreshold());
        assertEquals(0, r.enqueued(), "dryRun 不得入队");
        assertTrue(r.rows().get(0).queueState().contains("dryRun"), r.rows().get(0).queueState());
        verify(candidateService, org.mockito.Mockito.never())
                .enqueue(anyLong(), anyLong(), any(), anyString(), anyString());
    }

    @Test
    void supersededAndTinyDocsAreSkipped() {
        Doc superseded = doc(3L, "ExpHub 部署流程（旧）", "exphub,部署", BODY, null);
        superseded.setSupersededBy(1L);
        Doc tiny = doc(4L, "一句话", "exphub", "太短了", null);
        List<Doc> store = new ArrayList<>(List.of(
                doc(1L, "ExpHub 部署流程", "exphub,部署", BODY, null),
                doc(2L, "ExpHub 部署流程", "exphub,部署", BODY, null),
                superseded, tiny));
        when(projectService.listProjects()).thenReturn(List.of());
        when(docMapper.selectList(any())).thenReturn(store);
        stubAlive(store);

        SimilarityService.ScanResult r = service.scan(null, 0.5, 20, 2, true, "测试");

        assertEquals(1, r.skippedSuperseded(), "已标取代的整条跳过：关系已经清楚了，不用再问一遍");
        assertEquals(1, r.skippedShort(), "正文太短的判不准，跳过并如实计数");
        assertEquals(2, r.docsCompared());
    }

    @Test
    void unknownProjectIsRejectedExplicitly() {
        when(projectService.getProject(anyLong())).thenReturn(null);

        SimilarityService.ScanResult r = service.scan(99L, 0.5, 20, 2, false, "测试");

        assertFalse(r.ok(), "没有权限/项目不存在时必须报错，不能静默返回空");
        assertTrue(r.summary().contains("99"));
    }

    /**
     * 线上实测暴露的 bug（2026-10-25 首次上线）：新写入的文档用裸 {@code Sketch.of} 构造，
     * 没走「去模板行」这一步 → {@code lineModeReliable=false} → L0 永远回退 2-gram 口径，
     * 于是**同一对文档在 L0 与 L2 下得到两个分数**。
     *
     * <p>断言：一对多行文档在 L0 与 L2 下的分数必须一致，且都不许写「回退 2-gram」。
     */
    @Test
    void l0AndL2MustUseTheSameContentMetric() {
        String longBodyA = String.join("\n",
                "# 部署流程",
                "执行 bash deploy.sh，脚本会先 git reset 再打包并重启服务。",
                "重启后轮询 /health 九十秒，失败则退出并保留日志。",
                "回滚方式是 git reset 到上一个 tag 再跑一遍脚本。",
                "日志在 /usr/local/exphub/app.log，用 journalctl 看不到。");
        String longBodyB = String.join("\n",
                "# 部署流程",
                "执行 bash deploy.sh，脚本会先 git reset 再打包并重启服务。",
                "重启后轮询 /health 九十秒，失败则退出并保留日志。",
                "回滚方式是 git reset 到上一个 tag 再跑一遍脚本。",
                "日志在 /usr/local/exphub/app.log，用 journalctl 看不到。");

        // 库里已有 #1；#2 是「刚写入」的那篇，两边都能取到它，才谈得上比口径
        List<Doc> store = new ArrayList<>(List.of(
                doc(1L, "ExpHub 部署流程", "exphub,部署", longBodyA, null),
                doc(2L, "ExpHub 部署流程", "exphub,部署", longBodyB, null)));
        when(docMapper.selectList(any())).thenReturn(store);
        stubAlive(store);
        ReviewCandidate fake = new ReviewCandidate();
        fake.setId(903L);
        fake.setStatus(ReviewCandidate.PENDING);
        when(candidateService.enqueue(anyLong(), anyLong(), any(), anyString(), eq("L0_CREATE")))
                .thenReturn(new ReviewCandidateService.EnqueueResult(true, fake, "ok"));

        SimilarityService.L0Result l0 = service.checkOnCreate(
                doc(2L, "ExpHub 部署流程", "exphub,部署", longBodyB, null));

        // L2 侧：同一对文档走全库打分
        SimilarityService.ScanResult l2 = service.scan(0L, 0.5, 20, 2, true, "测试");

        assertEquals(1, l0.hits().size());
        assertEquals(1, l2.rows().size());
        assertEquals(l2.rows().get(0).score(), l0.hits().get(0).score(), 1e-9,
                "同一对文档在 L0 与 L2 下必须是同一个分数，否则调用方不知道该信谁");
        assertFalse(l0.hits().get(0).reasons().contains("回退 2-gram"),
                "多行文档不该回退 2-gram 口径：" + l0.hits().get(0).reasons());
        assertTrue(l0.hits().get(0).reasons().contains("模板行已剔除"), l0.hits().get(0).reasons());
    }

    @Test
    void suggestionScoreIsPersistedWithFourDecimals() {        when(docMapper.selectList(any())).thenReturn(new ArrayList<>(List.of(
                doc(1L, "ExpHub 部署流程", "exphub,部署", BODY, null))));
        ReviewCandidate fake = new ReviewCandidate();
        fake.setId(902L);
        fake.setStatus(ReviewCandidate.PENDING);
        when(candidateService.enqueue(anyLong(), anyLong(), any(), anyString(), eq("L0_CREATE")))
                .thenReturn(new ReviewCandidateService.EnqueueResult(true, fake, "ok"));

        service.checkOnCreate(doc(2L, "ExpHub 部署流程", "exphub,部署", BODY, null));

        ArgumentCaptor<java.math.BigDecimal> score = ArgumentCaptor.forClass(java.math.BigDecimal.class);
        verify(candidateService).enqueue(anyLong(), anyLong(), score.capture(), anyString(), eq("L0_CREATE"));
        assertTrue(score.getValue().compareTo(java.math.BigDecimal.ZERO) > 0, "入队必须带分，不能是 0");
        assertTrue(score.getValue().scale() <= 4, "小数位不超过 4，与候选表口径一致");
    }
}
