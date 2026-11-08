package com.exphub.service;

import com.exphub.entity.Doc;
import com.exphub.mapper.DocMapper;
import com.exphub.mapper.DocVersionMapper;
import com.exphub.mapper.ProjectMapper;
import com.exphub.mapper.ReviewCandidateMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

/**
 * search_experience(explain=true) 的契约（2026-11-08 阁主 #134 第 1 件）。
 *
 * <p><b>为什么做这件事</b>：验收脚本靠 Python 复刻 Java 打分，实测逐位一致只有
 * 10/33（「积分兑换提现异常」）和 23/33（「代码怎么发布到线上服务器 部署」）。
 * 复刻是另一套实现，永远可能漂；服务端自己吐分项后，验收不再依赖「两个实现对齐」。
 *
 * <p>这里锁的是三件最容易退化的东西：
 * <ol>
 *   <li>各维度得分必须能和总分对上（不是事后另算一份）；</li>
 *   <li>阈值预过滤的两个计数必须真的来自这一次（未命中 / 命中但不够门槛）；</li>
 *   <li>同分时谁获胜必须能看见（#360/#1278 真的同为 0.5615，复刻就是栽在这）；</li>
 *   <li>不传 explain 时零变化 —— 老调用方回执不能因为这次改动长出一段。</li>
 * </ol>
 */
@ExtendWith(MockitoExtension.class)
class SearchExplainTest {

    @Mock private DocMapper docMapper;
    @Mock private DocVersionMapper versionMapper;
    @Mock private ProjectMapper projectMapper;
    @Mock private ReviewCandidateMapper reviewCandidateMapper;
    @Mock private CallLogService callLogService;

    @InjectMocks private DocService docService;

    private static Doc doc(long id, String title, String content, int calls, LocalDateTime updatedAt) {
        Doc d = new Doc();
        d.setId(id);
        d.setTitle(title);
        d.setContent(content);
        d.setCallCount(calls);
        d.setUpdatedAt(updatedAt);
        d.setStatus("ACTIVE");
        return d;
    }

    private void stubCandidates(List<Doc> candidates) {
        // 第一次 count = 严格 AND 命中数（0 → 不进严格档）；第二次 = 重排档的 DB 匹配数
        lenient().when(docMapper.countByFulltext(any(), any(), any(), any(), any(), anyBoolean(), any(), anyBoolean()))
                .thenReturn(0L);
        lenient().when(docMapper.searchByFulltext(any(), any(), any(), any(), any(), any(),
                        anyLong(), anyLong(), anyBoolean(), any(), anyBoolean()))
                .thenReturn(candidates);
    }

    private DocService.SearchMeta searchWithExplain(String query) {
        DocService.SearchMeta meta = new DocService.SearchMeta();
        meta.explain = true;
        docService.search(query, null, null, null, 1, 20, meta, true);
        return meta;
    }

    @Test
    void totalIsSumOfTheSameComponentsItReports() {
        LocalDateTime now = LocalDateTime.now();
        Doc hit = doc(95, "代码怎么发布到线上服务器 部署手册", "部署脚本与上线检查单", 12, now.minusDays(1));
        stubCandidates(List.of(hit));

        DocService.SearchMeta meta = searchWithExplain("部署 上线 服务器");

        DocService.SearchExplain ex = meta.explainResult;
        assertNotNull(ex, "explain=true 必须回传打分快照");
        assertEquals(1, ex.docs.size());
        DocService.DocExplain d = ex.docs.get(0);

        double expected = 0.55 * d.coverage + 0.25 * d.field + 0.0 * d.recency + 0.08 * d.popularity;
        assertEquals(expected, d.total, 1e-9,
                "总分必须等于回执里那几项按同一权重算出来的值（两套算法必漂）");
        assertTrue(d.total > 0);
        assertEquals(1, d.rank, "唯一条结果位次是 1");
        assertEquals(1, d.candidateRank, "候选位次来自 DB 召回池的原始顺序");
        assertEquals(3, d.conceptTotal, "三个概念都要报出来");
    }

    @Test
    void thresholdDropCountsAreReal() {
        LocalDateTime now = LocalDateTime.now();
        stubCandidates(List.of(
                // 命中"部署"+"服务器"=2 个 → 过门槛（3 概念需要 ≥2）
                doc(1, "部署到服务器", "部署脚本", 0, now),
                // 只命中"部署"=1 个 → 被门槛滤掉
                doc(2, "部署回执", "今天部署完成", 0, now),
                // 一个概念都不命中 → 未命中计数
                doc(3, "天气记录", "今天是晴天，气温二十度", 0, now)));

        DocService.SearchMeta meta = searchWithExplain("部署 上线 服务器");

        DocService.SearchExplain ex = meta.explainResult;
        assertEquals(3, ex.concepts.size(), "概念数按实际切词报");
        assertEquals(2, ex.requiredMatches, "3 个概念时门槛 = 40% 向上取整且不少于 2");
        assertEquals(3, ex.candidateCount, "参与比较的候选数 = 召回池条数");
        assertEquals(1, ex.droppedNoConcept, "零命中的要单独计数，不能混进'不足门槛'");
        assertEquals(1, ex.droppedByThreshold, "命中 1/3 不够门槛，必须算进预过滤");
        assertEquals(1, ex.scoredCount, "只有 1 条进入排序");
        assertEquals(1, ex.docs.get(0).id);
        assertTrue(ex.docs.get(0).matched >= 2);
    }

    @Test
    void tieIsBrokenByUpdatedAtAndTheWinnerIsVisible() {
        // 同一份内容、同一 call_count → 各维度完全相同，总分精确相等（真实库 #360/#1278 就是这样）
        String title = "部署手册";
        String content = "部署脚本与上线检查单";
        LocalDateTime newer = LocalDateTime.now().minusDays(1);
        LocalDateTime older = LocalDateTime.now().minusDays(30);
        stubCandidates(List.of(
                doc(360, title, content, 7, older),
                doc(1278, title, content, 7, newer)));

        DocService.SearchMeta meta = searchWithExplain("部署");

        DocService.SearchExplain ex = meta.explainResult;
        assertEquals(2, ex.scoredCount);
        assertEquals(1, ex.tieGroupCount, "两条总分完全相等 = 一组同分");
        DocService.DocExplain first = ex.docs.get(0);
        DocService.DocExplain second = ex.docs.get(1);
        assertEquals(1278L, first.id, "同分时按 updated_at 决胜：更新的在前");
        assertEquals(first.total, second.total, 0.0, "两条必须真的同分，否则这条测试没验到东西");
        assertTrue(first.tieWon, "赢家要能看见自己赢了");
        assertFalse(second.tieWon);
        assertEquals(List.of(360L), first.tiePeers);
        assertEquals(List.of(1278L), second.tiePeers);
    }

    /**
     * 重排档 0 候选 → 紧接 LIKE 兜底。档位必须如实写 LIKE：
     * 写 RERANK 会出现「档位说重排、结果来自 LIKE」的自相矛盾（本轮线上实测「部」踩到）。
     */
    @Test
    void emptyRelaxedPoolReportsLikeTierWithRetainedStats() {
        stubCandidates(List.of());

        DocService.SearchMeta meta = searchWithExplain("部署");

        DocService.SearchExplain ex = meta.explainResult;
        assertNotNull(ex);
        assertEquals(DocService.SearchTier.LIKE, ex.tier, "重排空之后走的是 LIKE，档位不许写 RERANK");
        assertEquals(0, ex.candidateCount, "候选 0 条是事实，要留着重排阶段的统计");
        assertEquals(1, ex.concepts.size(), "概念/门槛等统计不能被 LIKE 覆盖掉");
        assertTrue(ex.docs.isEmpty());
    }

    @Test
    void explainOffKeepsTheOldContract() {        stubCandidates(List.of(doc(1, "部署手册", "部署脚本", 0, LocalDateTime.now())));
        DocService.SearchMeta meta = new DocService.SearchMeta();

        docService.search("部署", null, null, null, 1, 20, meta, true);

        assertFalse(meta.explain, "默认不开");
        assertNull(meta.explainResult, "不开 explain 就不能多算一份分项（老调用方回执零变化）");
        assertTrue(meta.tier == DocService.SearchTier.RERANK);
    }

    @Test
    void formulaNoteSingleSource() {
        String note = DocService.formulaNote();
        assertTrue(note.contains("0.55"), note);
        assertTrue(note.contains("0.25"), note);
        assertTrue(note.contains("0.08"), note);
        assertTrue(note.contains("CONTENT_ONLY_FACTOR"), note);
        assertTrue(note.contains("CANDIDATE_LIMIT"), note);
        // 常量必须和打分代码同源：这里不重新写一遍数字，只断言回执里能看到它们
        assertTrue(DocService.thresholdNote(1, 2).contains("≥1"));
        assertTrue(DocService.thresholdNote(2, 3).contains("≥2"));
    }
}
