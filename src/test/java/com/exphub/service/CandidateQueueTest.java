package com.exphub.service;

import com.exphub.entity.Doc;
import com.exphub.entity.ReviewCandidate;
import com.exphub.mapper.DocMapper;
import com.exphub.mapper.ReviewCandidateMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * D1 施工包 3：整理候选队列（2026-10-25）。
 *
 * <p>三条断言都对应真实会踩的坑：
 * 1) 入队必须规范化顺序 —— 否则 (5,9) 与 (9,5) 会变成两条候选，唯一键形同虚设；
 * 2) 判过「不同」的对不能再入队 —— 否则每周都问同一个问题，人很快就无视整个队列；
 * 3) 判重复/取代都不得删数据 —— 队列是「待判断」，不是「自动清理」。
 */
@ExtendWith(MockitoExtension.class)
class CandidateQueueTest {

    @Mock
    private ReviewCandidateMapper candidateMapper;

    @Mock
    private DocMapper docMapper;

    @Mock
    private DocService docService;

    @InjectMocks
    private ReviewCandidateService service;

    private Doc doc(long id, Long supersededBy) {
        Doc d = new Doc();
        d.setId(id);
        d.setTitle("文档" + id);
        d.setVersion(2);
        d.setCreatedAt(LocalDateTime.of(2026, 10, 20, 10, 0));
        d.setSupersededBy(supersededBy);
        return d;
    }

    @Test
    void enqueueNormalizesPairOrder() {
        when(docMapper.selectById(5L)).thenReturn(doc(5L, null));
        when(docMapper.selectById(9L)).thenReturn(doc(9L, null));
        when(candidateMapper.selectOne(any())).thenReturn(null);

        // 故意倒着传：大的在前
        var r = service.enqueue(9L, 5L, new BigDecimal("0.81"), "标签交集:3", "MANUAL");

        assertTrue(r.created());
        assertEquals(5L, r.candidate().getDocA(), "docA 应被规范化为较小 ID");
        assertEquals(9L, r.candidate().getDocB());
        assertEquals(ReviewCandidate.PENDING, r.candidate().getStatus());
    }

    @Test
    void enqueueRejectsSelfPair() {
        var r = service.enqueue(5L, 5L, null, null, null);

        assertFalse(r.created());
        assertTrue(r.message().contains("自己"));
        verify(candidateMapper, never()).insert(any(ReviewCandidate.class));
    }

    @Test
    void enqueueRejectsMissingDoc() {
        when(docMapper.selectById(5L)).thenReturn(doc(5L, null));
        when(docMapper.selectById(404L)).thenReturn(null);

        var r = service.enqueue(5L, 404L, null, null, null);

        assertFalse(r.created());
        assertTrue(r.message().contains("404"));
    }

    @Test
    void enqueueDoesNotRequeueDistinctPair() {
        ReviewCandidate exist = new ReviewCandidate();
        exist.setId(11L);
        exist.setDocA(5L);
        exist.setDocB(9L);
        exist.setStatus(ReviewCandidate.DISTINCT);
        when(docMapper.selectById(anyLong())).thenReturn(doc(5L, null));
        when(candidateMapper.selectOne(any())).thenReturn(exist);

        var r = service.enqueue(5L, 9L, new BigDecimal("0.9"), "又算到了", "L2_WEEKLY");

        assertFalse(r.created());
        assertTrue(r.message().contains("已处理过"));
        // 判过的对不刷新、不重建，避免每周重复打扰
        verify(candidateMapper, never()).insert(any(ReviewCandidate.class));
        verify(candidateMapper, never()).updateById(any(ReviewCandidate.class));
    }

    @Test
    void enqueueRefreshesPendingPair() {
        ReviewCandidate exist = new ReviewCandidate();
        exist.setId(12L);
        exist.setDocA(5L);
        exist.setDocB(9L);
        exist.setStatus(ReviewCandidate.PENDING);
        when(docMapper.selectById(anyLong())).thenReturn(doc(5L, null));
        when(candidateMapper.selectOne(any())).thenReturn(exist);

        var r = service.enqueue(5L, 9L, new BigDecimal("0.95"), "更精确的算法算出更高分", "L2_WEEKLY");

        assertFalse(r.created());
        assertEquals(new BigDecimal("0.95"), r.candidate().getScore());
        verify(candidateMapper).updateById(any(ReviewCandidate.class));
    }

    @Test
    void enqueueRefreshDoesNotDowngradeReasons() {
        ReviewCandidate exist = new ReviewCandidate();
        exist.setId(13L);
        exist.setDocA(5L);
        exist.setDocB(9L);
        exist.setStatus(ReviewCandidate.PENDING);
        exist.setSource("MANUAL");
        exist.setReasons("标签交集:3；正文高度重合");
        when(docMapper.selectById(anyLong())).thenReturn(doc(5L, null));
        when(candidateMapper.selectOne(any())).thenReturn(exist);

        // 第二次传来的是随手写的理由、且没传得分
        var r = service.enqueue(5L, 9L, null, "又算到了", "L2_WEEKLY");

        assertEquals("标签交集:3；正文高度重合", r.candidate().getReasons(),
                "理由先到先得，不能被后来的弱理由覆盖");
        assertEquals("MANUAL", r.candidate().getSource(), "来源记录最初是谁发现的，不该被刷新覆盖");
    }

    @Test
    void enqueueFillsReasonsWhenEmpty() {
        ReviewCandidate exist = new ReviewCandidate();
        exist.setId(15L);
        exist.setDocA(5L);
        exist.setDocB(9L);
        exist.setStatus(ReviewCandidate.PENDING);
        exist.setReasons(null);
        when(docMapper.selectById(anyLong())).thenReturn(doc(5L, null));
        when(candidateMapper.selectOne(any())).thenReturn(exist);

        var r = service.enqueue(5L, 9L, new BigDecimal("0.8"), "首次补写理由", "L2_WEEKLY");

        assertEquals("首次补写理由", r.candidate().getReasons(), "原为空时应补写");
    }

    @Test
    void enqueueRefreshKeepsReasonsWhenBlank() {
        ReviewCandidate exist = new ReviewCandidate();
        exist.setId(14L);
        exist.setDocA(5L);
        exist.setDocB(9L);
        exist.setStatus(ReviewCandidate.PENDING);
        exist.setReasons("原有理由");
        when(docMapper.selectById(anyLong())).thenReturn(doc(5L, null));
        when(candidateMapper.selectOne(any())).thenReturn(exist);

        var r = service.enqueue(5L, 9L, new BigDecimal("0.7"), "   ", "L2_WEEKLY");

        assertEquals("原有理由", r.candidate().getReasons());
        assertEquals(new BigDecimal("0.7"), r.candidate().getScore(), "得分有值就该刷新");
    }

    @Test
    void resolveDuplicateFlagsBothAndDeletesNothing() {
        ReviewCandidate c = new ReviewCandidate();
        c.setId(7L);
        c.setDocA(1L);
        c.setDocB(2L);
        c.setStatus(ReviewCandidate.PENDING);
        when(candidateMapper.selectById(7L)).thenReturn(c);
        when(docMapper.selectById(1L)).thenReturn(doc(1L, null));
        when(docMapper.selectById(2L)).thenReturn(doc(2L, null));

        var r = service.resolve(7L, "duplicate", "同一条命令，说法不同", "烛龙");

        assertTrue(r.ok());
        assertEquals(ReviewCandidate.DUPLICATE, c.getStatus());
        // 两条都打标记，但绝不合并、绝不删除
        verify(docService).setFlags(org.mockito.ArgumentMatchers.eq(1L), any(), anyString(), anyString());
        verify(docService).setFlags(org.mockito.ArgumentMatchers.eq(2L), any(), anyString(), anyString());
        verify(docMapper, never()).deleteById(anyLong());
    }

    @Test
    void resolveSupersededPointsOlderToNewer() {
        ReviewCandidate c = new ReviewCandidate();
        c.setId(8L);
        c.setDocA(1L);
        c.setDocB(2L);
        c.setStatus(ReviewCandidate.PENDING);
        when(candidateMapper.selectById(8L)).thenReturn(c);
        when(docMapper.selectById(1L)).thenReturn(doc(1L, null));
        Doc newer = doc(2L, null);
        newer.setCreatedAt(LocalDateTime.of(2026, 10, 24, 10, 0));  // 更新的那条
        when(docMapper.selectById(2L)).thenReturn(newer);
        when(docService.markSuperseded(1L, 2L, "烛龙"))
                .thenReturn(new DocService.SupersedeResult(true, "ok", doc(1L, 2L)));

        var r = service.resolve(8L, "superseded", null, "烛龙");

        assertTrue(r.ok());
        assertEquals(ReviewCandidate.SUPERSEDED, c.getStatus());
        // 旧的（1，创建更早）指向新的（2）
        verify(docService).markSuperseded(1L, 2L, "烛龙");
        verify(docMapper, never()).deleteById(anyLong());
    }

    @Test
    void resolveRejectsUnknownAction() {
        ReviewCandidate c = new ReviewCandidate();
        c.setId(9L);
        c.setStatus(ReviewCandidate.PENDING);
        when(candidateMapper.selectById(9L)).thenReturn(c);

        var r = service.resolve(9L, "merge", null, "烛龙");

        assertFalse(r.ok());
        assertTrue(r.message().contains("无法识别"));
    }

    @Test
    void resolveRejectsAlreadyHandled() {
        ReviewCandidate c = new ReviewCandidate();
        c.setId(10L);
        c.setStatus(ReviewCandidate.DISTINCT);
        when(candidateMapper.selectById(10L)).thenReturn(c);

        var r = service.resolve(10L, "duplicate", null, "烛龙");

        assertFalse(r.ok());
        assertTrue(r.message().contains("已处理过"));
    }
}
