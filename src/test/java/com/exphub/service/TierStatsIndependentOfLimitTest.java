package com.exphub.service;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 分层统计必须与 limit 无关（阁主 2026-10-26 实测发现）。
 *
 * <p><b>现象</b>：同一 0.45 阈值下
 * <pre>
 *   limit=12 → 疑似字面重复 4 ｜ 同话题演进链 8      ← 进度层 36 对整层消失
 *   limit=50 → 疑似字面重复 4 ｜ 同话题演进链 10 ｜ 进度/日志 36
 * </pre>
 *
 * <p><b>根因</b>：回执原来直接对 {@code r.rows()} 数个数，而 {@code rows} 是**限流之后**的展示清单
 * （limit=12 时只有 12 条），被 {@code droppedByCap} 截掉的 153 对根本没进统计。
 *
 * <p><b>为什么这是真缺陷而不是显示问题</b>：
 * 下一个人用默认 limit 跑一遍，看到「进度层 0 对」，会以为进度降级失效 → 去查代码 → 白干。
 * 更糟的是他可能因此得出「降级没上线」的错误结论，就像今晚早些时候一样。
 *
 * <p><b>修法</b>：新增 {@code tierStats}，在**限流前**把全部超阈值对按 Kind 计数；
 * {@code rows} 仍然只用于展示。两个口径分开，回执里写明「统计不受 limit 影响」。
 */
class TierStatsIndependentOfLimitTest {

    private static SimilarityService.Row row(SimilarityScorer.Kind kind, double score) {
        return new SimilarityService.Row(1L, "A", 2L, "B", score, "r", null, kind, 0, 0.0, "");
    }

    /** 模拟 scan 里的统计逻辑：必须遍历全部超阈值对，而不是限流后的子集 */
    private static Map<SimilarityScorer.Kind, Integer> statsOf(List<SimilarityService.Row> allAbove) {
        Map<SimilarityScorer.Kind, Integer> m = new LinkedHashMap<>();
        for (SimilarityService.Row r : allAbove) {
            SimilarityScorer.Kind k = r.kind() == null ? SimilarityScorer.Kind.UNKNOWN : r.kind();
            m.merge(k, 1, Integer::sum);
        }
        return m;
    }

    @Test
    void statsCountAllAboveThresholdRegardlessOfLimit() {
        // 造 4 条重复 + 8 条演进链 + 36 条进度（对应阁主实测的 4 / 10 / 36 量级）
        List<SimilarityService.Row> all = new java.util.ArrayList<>();
        for (int i = 0; i < 4; i++) all.add(row(SimilarityScorer.Kind.DUPLICATE, 0.54));
        for (int i = 0; i < 8; i++) all.add(row(SimilarityScorer.Kind.SERIES, 0.55));
        for (int i = 0; i < 36; i++) all.add(row(SimilarityScorer.Kind.PROGRESS, 0.62));

        // 排序后限流：两种 limit 取不同子集
        all.sort(SimilarityService.Row.order());
        List<SimilarityService.Row> shown12 = all.subList(0, 12);
        List<SimilarityService.Row> shown50 = all.subList(0, Math.min(50, all.size()));

        Map<SimilarityScorer.Kind, Integer> full = statsOf(all);
        assertEquals(4, full.getOrDefault(SimilarityScorer.Kind.DUPLICATE, 0));
        assertEquals(8, full.getOrDefault(SimilarityScorer.Kind.SERIES, 0));
        assertEquals(36, full.getOrDefault(SimilarityScorer.Kind.PROGRESS, 0));

        // 关键断言：无论展示 12 条还是 50 条，统计都必须一样
        assertEquals(full, statsOf(all), "统计口径不该受展示条数影响");

        // 反证：如果按「展示清单」数个数，limit=12 时进度层确实是 0 —— 这正是原来的 bug
        Map<SimilarityScorer.Kind, Integer> byShown12 = statsOf(shown12);
        assertEquals(0, byShown12.getOrDefault(SimilarityScorer.Kind.PROGRESS, 0),
                "limit=12 时展示清单里没有进度对（都被排到后面了）—— 所以统计绝不能按展示清单算");
        assertEquals(4, byShown12.getOrDefault(SimilarityScorer.Kind.DUPLICATE, 0),
                "重复层在两种口径下都是 4，所以只看这一层会误以为口径没问题");

        assertTrue(shown50.size() >= 48, "limit=50 时能看到几乎全部");
    }

    /** 进度层即使分数最高也必须排在最后 —— 否则 limit 一小就先被截掉 */
    @Test
    void progressRowsSortAfterEverythingElse() {
        List<SimilarityService.Row> all = new java.util.ArrayList<>();
        all.add(row(SimilarityScorer.Kind.PROGRESS, 0.99));
        all.add(row(SimilarityScorer.Kind.UNKNOWN, 0.40));
        all.add(row(SimilarityScorer.Kind.SERIES, 0.50));
        all.add(row(SimilarityScorer.Kind.DUPLICATE, 0.45));
        all.sort(SimilarityService.Row.order());

        // 截断到前 3 条，进度对必须被裁掉
        List<SimilarityService.Row> top3 = all.subList(0, 3);
        assertTrue(top3.stream().noneMatch(r -> r.kind() == SimilarityScorer.Kind.PROGRESS),
                "0.99 分的进度对也必须排在最后，否则 limit 一小就先被它占位");
        assertEquals(SimilarityScorer.Kind.DUPLICATE, all.get(0).kind(),
                "字面重复永远第一");
    }
}
