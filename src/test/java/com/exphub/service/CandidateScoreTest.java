package com.exphub.service;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 候选得分的归一化（2026-10-25）。
 *
 * <p>现实问题（阁主线上实测发现）：传 `score=0` 时库里落 `0.0000`，
 * 而列表按得分倒序 —— 0 分排最后，看起来像「真的算了 0 分」。
 * 但相似度 0 的语义是「完全不像」，实际几乎一定是「没算出来就填了 0」。
 *
 * <p>两条原则：
 * ① **不假装有值**：0 / 负数 / 超范围的都存 NULL，别污染排序；
 * ② **不在沉默中丢弃**：每个被丢掉的都得有一句可读说明，供回执自证。
 */
class CandidateScoreTest {

    private static BigDecimal d(String v) {
        return new BigDecimal(v);
    }

    @Test
    void keepsValidScore() {
        assertEquals(d("0.85"), ReviewCandidateService.normalizeScore(d("0.85")));
        assertEquals(d("0.01"), ReviewCandidateService.normalizeScore(d("0.01")), "极小但合法的分数应保留");
        assertEquals(d("1"), ReviewCandidateService.normalizeScore(d("1")), "上界 1 是合法的");
    }

    @Test
    void zeroBecomesNull() {
        // 线上踩到的就是这个：0 不是「很像 0%」，是「没算」
        assertNull(ReviewCandidateService.normalizeScore(d("0")));
        assertNull(ReviewCandidateService.normalizeScore(d("0.00")));
        assertNull(ReviewCandidateService.normalizeScore(d("0.0000")));
    }

    @Test
    void negativeBecomesNull() {
        assertNull(ReviewCandidateService.normalizeScore(d("-0.3")));
    }

    @Test
    void aboveOneBecomesNull() {
        assertNull(ReviewCandidateService.normalizeScore(d("1.5")));
        assertNull(ReviewCandidateService.normalizeScore(d("85")), "百分制没除以 100 的典型误传");
    }

    @Test
    void nullStaysNull() {
        assertNull(ReviewCandidateService.normalizeScore(null));
    }

    @Test
    void discardReasonExplainsZero() {
        String r = ReviewCandidateService.scoreDiscardReason(d("0"));
        assertNotNull(r, "丢弃必须有说明，不能静默");
        assertTrue(r.contains("0"), "应回显传入的原值: " + r);
        assertTrue(r.contains("NULL"), "应说明存成了 NULL: " + r);
    }

    @Test
    void discardReasonExplainsPercentScale() {
        String r = ReviewCandidateService.scoreDiscardReason(d("85"));
        assertNotNull(r);
        assertTrue(r.contains("百分制"), "85 这种误传要提示口径问题: " + r);
    }

    @Test
    void noDiscardReasonForValidScore() {
        assertNull(ReviewCandidateService.scoreDiscardReason(d("0.77")));
        assertNull(ReviewCandidateService.scoreDiscardReason(null), "没传分不是「丢弃」");
    }

    @Test
    void formatsScoreConsistently() {
        // 回执里必须两位小数：0.9 与 0.9000 长得不一样会被当成不同的值
        assertEquals("0.90", ReviewCandidateService.formatScore(d("0.9")));
        assertEquals("0.85", ReviewCandidateService.formatScore(d("0.8500")));
        assertEquals("1.00", ReviewCandidateService.formatScore(d("1")));
        assertEquals("未记录", ReviewCandidateService.formatScore(null));
    }
}
