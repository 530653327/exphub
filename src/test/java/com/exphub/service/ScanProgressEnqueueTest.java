package com.exphub.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * P1-4：③层（进度/日志类）默认不入队（2026-10-29 越哥指令）。
 *
 * <p>为什么必须有它：③层按设计「根本不该判重」——它们是同一件事的分阶段记录。
 * 但原来照样 enqueue，于是每轮扫描都往待判队列塞几条，人只能逐条判 {@code distinct} 清掉，
 * 而 {@code distinct} 是整条链路**唯一不可逆**的动作（永久不再入队）。
 * 每轮白干五次，还顺手把分阶段记录永久封口。
 *
 * <p>验收标准里的第 3 条「①②层行为零变化」就用这里的前四行证明 ——
 * 它不该靠人工回放 25 对去验证。
 */
class ScanProgressEnqueueTest {

    private static SimilarityService.Row row(SimilarityScorer.Kind kind) {
        return new SimilarityService.Row(1L, "A", 2L, "B", 0.62, "理由", "",
                kind, 10, 0.5, "advice");
    }

    @Test
    @DisplayName("①②层永远入队：enqueueProgress 开关不影响它们")
    void upperTiersAlwaysEnqueue() {
        for (boolean flag : new boolean[]{false, true}) {
            assertTrue(SimilarityService.shouldEnqueue(row(SimilarityScorer.Kind.DUPLICATE), flag),
                    "①层（疑似字面重复）必须入队，flag=" + flag);
            assertTrue(SimilarityService.shouldEnqueue(row(SimilarityScorer.Kind.SERIES), flag),
                    "②层（同话题演进链）必须入队，flag=" + flag);
            assertTrue(SimilarityService.shouldEnqueue(row(SimilarityScorer.Kind.UNKNOWN), flag),
                    "信息不足层也必须入队，flag=" + flag);
        }
    }

    @Test
    @DisplayName("③层默认不入队，显式传 true 才恢复旧行为")
    void progressTierRespectsFlag() {
        SimilarityService.Row progress = row(SimilarityScorer.Kind.PROGRESS);
        assertFalse(SimilarityService.shouldEnqueue(progress, false),
                "③层默认不入队 —— 这是 P1-4 的核心");
        assertTrue(SimilarityService.shouldEnqueue(progress, true),
                "显式 enqueueProgress=true 时③层照旧入队（保留回退能力）");
    }

    @Test
    @DisplayName("kind 为 null 时不因判定失败而丢候选（宁多判不漏判）")
    void nullKindStillEnqueues() {
        assertTrue(SimilarityService.shouldEnqueue(row(null), false),
                "判不出层就不该被静默丢掉");
    }
}
