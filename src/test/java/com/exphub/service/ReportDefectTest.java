package com.exphub.service;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 阁主 2026-10-25 验收报告里的三条缺陷（烛龙修复）的防回归测试。
 *
 * <p>真实链路：忆阁 `ToolRegistry` 把所有数值参数一律 `asInt()` 取整
 * → `0.33` / `0.92` / `0.5` 到 ExpHub 时**已经是 0**。
 * ExpHub 侧无法还原小数，能做的是**不把缺陷藏起来**：
 * <ol>
 *   <li>回执区分「你传了什么」和「ExpHub 实际收到什么」，收到 0 时点明「疑似被截成整数」；</li>
 *   <li>非法阈值**明拒**，不再静默 clamp 成 0.30（那是缺陷被藏起来的直接原因）。</li>
 * </ol>
 */
class ReportDefectTest {

    // ===== 缺陷一：score 的文案把责任推给调用方 =====

    @Test
    void zeroScoreBlamesTruncationNotCaller() {
        String msg = ReviewCandidateService.scoreDiscardReason(BigDecimal.ZERO);
        assertNotNull(msg);
        // 关键：得说「ExpHub 收到的是 0」，不能说「传了 0」——后者让人以为自己传错了
        assertTrue(msg.contains("ExpHub 实际收到的是 0"), msg);
        assertFalse(msg.contains("传了 0"), "不许把链路问题写成「传了 0」：" + msg);
        // 必须给出可执行的排查方向
        assertTrue(msg.contains("asInt"), "要点明取整的可能位置：" + msg);
        assertTrue(msg.contains("调用链路"), msg);
    }

    @Test
    void negativeScoreAlsoReportsReceivedValue() {
        String msg = ReviewCandidateService.scoreDiscardReason(new BigDecimal("-0.2"));
        assertNotNull(msg);
        assertTrue(msg.contains("收到的是 -0.2"), msg);
    }

    @Test
    void aboveOneScoreSuggestsPercentScale() {
        String msg = ReviewCandidateService.scoreDiscardReason(new BigDecimal("85"));
        assertNotNull(msg);
        assertTrue(msg.contains("收到的是 85"), msg);
        assertTrue(msg.contains("除以 100"), "百分制是最常见的误用，要给出换算：" + msg);
    }

    @Test
    void validScoreHasNoDiscardReason() {
        assertNull(ReviewCandidateService.scoreDiscardReason(new BigDecimal("0.33")));
        assertNull(ReviewCandidateService.scoreDiscardReason(new BigDecimal("0.92")));
        assertNull(ReviewCandidateService.scoreDiscardReason(BigDecimal.ONE));
        assertNull(ReviewCandidateService.scoreDiscardReason(null));
    }

    /** 关键区分：0.33 是有效值，绝不能被归一化掉（ExpHub 侧本来就收得到小数） */
    @Test
    void validDecimalsSurviveNormalization() {
        assertEquals(0, new BigDecimal("0.33").compareTo(
                ReviewCandidateService.normalizeScore(new BigDecimal("0.33"))));
        assertEquals(0, new BigDecimal("0.92").compareTo(
                ReviewCandidateService.normalizeScore(new BigDecimal("0.92"))));
    }

    // ===== 缺陷二：阈值静默 clamp =====

    @Test
    void zeroThresholdIsRejectedNotClamped() {
        // 上游把 0.5 截成 0 → 必须拒绝，不许静默变成 0.30
        assertTrue(SimilarityService.isThresholdValid(0d) == false);
        String msg = SimilarityService.thresholdRejectMessage(0.5d, 0d);
        assertTrue(msg.contains("不在允许区间"), msg);
        assertTrue(msg.contains("没有比较任何文档"), "必须说清「什么都没做」：" + msg);
        // 必须点出「收到 0 = 疑似被截成整数」，这是让人找到真因的唯一线索
        assertTrue(msg.contains("收到的是 0"), msg);
        assertTrue(msg.contains("asInt"), msg);
    }

    @Test
    void validThresholdsPass() {
        assertTrue(SimilarityService.isThresholdValid(0.30));
        assertTrue(SimilarityService.isThresholdValid(0.50));
        assertTrue(SimilarityService.isThresholdValid(0.55));
        assertTrue(SimilarityService.isThresholdValid(0.95));
        assertFalse(SimilarityService.isThresholdValid(0.29), "低于下限要把全库都算成候选");
        assertFalse(SimilarityService.isThresholdValid(0.96));
        assertFalse(SimilarityService.isThresholdValid(Double.NaN));
    }

    @Test
    void defaultThresholdMatchesDocumentation() {
        // 工具描述写的是「默认 0.50」，实现必须一致 —— 上次就是这两个数字不一致
        assertEquals(0.50, SimilarityService.DEFAULT_THRESHOLD, 1e-9);
    }

    @Test
    void belowMinThresholdExplainsWhy() {
        String msg = SimilarityService.thresholdRejectMessage(0.1d, 0.1d);
        assertTrue(msg.contains("低于下限"), msg);
        assertFalse(msg.contains("asInt"), "0.1 不是截断问题，不要误导去查链路");
    }

    @Test
    void aboveMaxThresholdExplainsWhy() {
        String msg = SimilarityService.thresholdRejectMessage(0.99d, 0.99d);
        assertTrue(msg.contains("高于上限"), msg);
    }

    // ===== 缺陷三：候选列表不显示单条状态 =====

    @Test
    void statusLabelsCoverAllStates() {
        assertEquals("待判断", ReviewCandidateService.statusLabel("PENDING"));
        assertEquals("已标重复", ReviewCandidateService.statusLabel("DUPLICATE"));
        assertEquals("已标取代", ReviewCandidateService.statusLabel("SUPERSEDED"));
        assertEquals("已判不同", ReviewCandidateService.statusLabel("DISTINCT"));
        assertEquals("已忽略", ReviewCandidateService.statusLabel("SKIPPED"));
        // 未知值原样返回，不要假装认识
        assertEquals("WHATEVER", ReviewCandidateService.statusLabel("WHATEVER"));
        assertEquals("未知", ReviewCandidateService.statusLabel(null));
    }
}
