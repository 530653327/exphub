package com.exphub.service;

import com.exphub.entity.ReviewCandidate;
import com.exphub.mapper.CandidateResolutionLogMapper;
import com.exphub.mapper.DocMapper;
import com.exphub.mapper.ReviewCandidateMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * #1220 第⑥条：SKIPPED 必须真的可复活。
 *
 * <p>背景是这个工具**自己说过假话**：{@code resolve(action=skip)} 的回执一直写
 * 「下次相似度再算到它仍会提出来」，而 {@code enqueue} 把 SKIPPED 和 DISTINCT 一样当封口状态 ——
 * 承诺了不会发生的事。判错一次就再也见不到，理性的做法自然是不判，队列只会越积越长。
 */
@ExtendWith(MockitoExtension.class)
class CandidateSkipReviveTest {

    @Mock
    private ReviewCandidateMapper candidateMapper;
    @Mock
    private DocMapper docMapper;
    @Mock
    private DocService docService;
    @Mock
    private CandidateResolutionLogMapper resolutionLogMapper;

    @InjectMocks
    private ReviewCandidateService service;

    private ReviewCandidate skippedWithScore(String score) {
        ReviewCandidate c = new ReviewCandidate();
        c.setId(51L);
        c.setDocA(100L);
        c.setDocB(101L);
        c.setStatus(ReviewCandidate.SKIPPED);
        c.setScore(score == null ? null : new BigDecimal(score));
        c.setSource("L2_SCAN");
        return c;
    }

    private void stubDocsExistAndPair(ReviewCandidate exist) {
        when(docMapper.selectById(100L)).thenReturn(new com.exphub.entity.Doc());
        when(docMapper.selectById(101L)).thenReturn(new com.exphub.entity.Doc());
        when(candidateMapper.selectOne(any())).thenReturn(exist);
    }

    @Test
    void smallScoreRiseDoesNotBother() {
        ReviewCandidate exist = skippedWithScore("0.50");
        stubDocsExistAndPair(exist);

        var r = service.enqueue(100L, 101L, new BigDecimal("0.55"), "又算到了", "L2_SCAN");

        assertFalse(r.created(), "小幅波动不该复活，否则忽略等于没忽略");
        assertEquals(ReviewCandidate.SKIPPED, exist.getStatus(), "状态不许被改动");
        assertTrue(r.message().contains("不再打扰"), "回执要说清为什么不打扰，实际=" + r.message());
    }

    @Test
    void bigScoreRiseRevivesToPending() {
        ReviewCandidate exist = skippedWithScore("0.50");
        stubDocsExistAndPair(exist);

        var r = service.enqueue(100L, 101L, new BigDecimal("0.62"), "正文高度重合", "L2_SCAN");

        assertTrue(r.created(), "涨幅超过阈值应重新提出");
        assertEquals(ReviewCandidate.PENDING, exist.getStatus());
        assertEquals(new BigDecimal("0.62"), exist.getScore());
        assertTrue(r.message().contains("重新放回待判断"), "实际=" + r.message());
    }

    @Test
    void exactlyAtThresholdRevives() {
        ReviewCandidate exist = skippedWithScore("0.50");
        stubDocsExistAndPair(exist);

        // 阈值是 0.10，0.60 正好达到 —— 边界要算「复活」，否则阈值是 0.11
        var r = service.enqueue(100L, 101L, new BigDecimal("0.60"), "刚好", "L2_SCAN");

        assertTrue(r.created(), "0.50 + 0.10 = 0.60 应判为复活");
        assertEquals(ReviewCandidate.PENDING, exist.getStatus());
    }

    @Test
    void distinctStaysSealed() {
        ReviewCandidate exist = skippedWithScore("0.50");
        exist.setStatus(ReviewCandidate.DISTINCT);
        stubDocsExistAndPair(exist);

        var r = service.enqueue(100L, 101L, new BigDecimal("0.99"), "再高也不复活", "L2_SCAN");

        assertFalse(r.created(), "DISTINCT 是明确结论，不因为分数变高就重问一遍");
        assertEquals(ReviewCandidate.DISTINCT, exist.getStatus());
    }

    @Test
    void unknownScoreBeforeRevivesOnAnyValidScore() {
        ReviewCandidate exist = skippedWithScore(null);
        stubDocsExistAndPair(exist);

        var r = service.enqueue(100L, 101L, new BigDecimal("0.53"), "上次没记分", "L2_SCAN");

        assertTrue(r.created(), "上次没记分数，无从比较「明显提高」，有分数就重新提出");
        assertEquals(ReviewCandidate.PENDING, exist.getStatus());
    }
}
