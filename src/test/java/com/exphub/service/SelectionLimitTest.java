package com.exphub.service;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 限流守卫：**分层必须优先于 limit**（2026-10-26 02:1x 越哥实测发现）。
 *
 * <p>他看到的现场：
 * <pre>
 *   ①层统计 6 对，清单里只有 4 条（0.54、0.54、0.54、0.45）
 *   ②层统计 15 对，只展示 10 条
 *   ④层却展示 36 条，最低 0.47 都进来了
 * </pre>
 * 于是判断「limit 按总分取前 50，分层只决定显示顺序」，也就是最该判的①层项可能根本不出现。
 *
 * <p>实测根因不是按分数截断，而是 <b>per-doc 限流</b>（默认「单篇最多 2 条候选」）：
 * #63 在①层命中 4 个子页（65/66/67/68），第 3、4 条被限流砍掉，
 * 空出的名额被低层填满 —— <b>观感与「按分数截断」完全一样，但病在别处</b>。
 * 所以修法是：①层豁免 per-doc，并且每层把「挤掉了哪几对」如实写进回执。
 */
class SelectionLimitTest {

    private static SimilarityService.Row row(SimilarityScorer.Kind kind, long a, long b, double score) {
        return new SimilarityService.Row(a, "A" + a, b, "B" + b, score, "r", null, kind, 0, 0.0, "advice");
    }

    /** 模拟 scan：按层+分数排好序的输入 */
    private static List<SimilarityService.Row> sorted() {
        List<SimilarityService.Row> all = new ArrayList<>();
        // ①层 4 对，全部挂在 #63 上（真实现场：#63↔65/66/67/68）
        all.add(row(SimilarityScorer.Kind.DUPLICATE, 63, 67, 0.54));
        all.add(row(SimilarityScorer.Kind.DUPLICATE, 63, 66, 0.54));
        all.add(row(SimilarityScorer.Kind.DUPLICATE, 63, 65, 0.53));
        all.add(row(SimilarityScorer.Kind.DUPLICATE, 63, 68, 0.45));
        // ②层 4 对，也都挂在 #74 上
        all.add(row(SimilarityScorer.Kind.SERIES, 74, 85, 0.62));
        all.add(row(SimilarityScorer.Kind.SERIES, 74, 79, 0.61));
        all.add(row(SimilarityScorer.Kind.SERIES, 74, 81, 0.60));
        all.add(row(SimilarityScorer.Kind.SERIES, 77, 83, 0.50));
        // ④层 3 对
        all.add(row(SimilarityScorer.Kind.PROGRESS, 470, 477, 0.55));
        all.add(row(SimilarityScorer.Kind.PROGRESS, 464, 471, 0.54));
        all.add(row(SimilarityScorer.Kind.PROGRESS, 478, 479, 0.53));
        all.sort(SimilarityService.Row.order());
        return all;
    }

    @Test
    void tierOneIsNeverSqueezedOutByPerDocLimit() {
        List<SimilarityService.Row> all = sorted();
        SimilarityService.Selection sel = SimilarityService.select(all, 50, 2);

        SimilarityService.TierDetail dup = sel.detail().get(SimilarityScorer.Kind.DUPLICATE);
        assertEquals(4, dup.above(), "①层统计 4 对");
        assertEquals(4, dup.shown(), "①层必须 4 对全部展示 —— 豁免 per-doc，否则 #63 的 4 个子页只剩 2 条");
        assertEquals(0, dup.droppedPerDoc(), "①层不该被 per-doc 挤掉任何一对");

        // ①层 4 条必须排在②层、④层任何一条之前（分层优先）
        for (int i = 0; i < 4; i++) {
            assertEquals(SimilarityScorer.Kind.DUPLICATE, sel.rows().get(i).kind(),
                    "第 " + i + " 条应是①层，实际 " + sel.rows().get(i).kind()
                            + " —— 分层必须先于 limit");
        }
    }

    @Test
    void lowerTierDropsAreReportedWithSamples() {
        List<SimilarityService.Row> all = sorted();
        SimilarityService.Selection sel = SimilarityService.select(all, 50, 2);

        SimilarityService.TierDetail ser = sel.detail().get(SimilarityScorer.Kind.SERIES);
        assertEquals(4, ser.above());
        assertEquals(3, ser.shown(), "#74 挂 3 对，per-doc=2 → 前 2 条过，#74↔#81 被挤掉；#77↔#83 正常展示");
        assertEquals(1, ser.droppedPerDoc(), "只掉了 #74↔#81 这一对");
        assertTrue(ser.hasDrop());

        // 挤掉的必须点名，不能只说「丢了几条」
        SimilarityService.TierDetail detail = sel.detail().get(SimilarityScorer.Kind.SERIES);
        assertTrue(detail.droppedSamples().size() >= 1,
                "被挤掉的对必须列出 ID，否则读的人不知道少了什么：" + detail);
        assertTrue(detail.droppedSamples().get(0).startsWith("#74") || detail.droppedSamples().get(0).startsWith("#77"),
                "样本格式应为 #A↔#B：" + detail.droppedSamples());
    }

    @Test
    void truncationOnlyHitsTheLastTier() {
        List<SimilarityService.Row> all = sorted();
        SimilarityService.Selection sel = SimilarityService.select(all, 6, 2);

        assertEquals(6, sel.rows().size());
        // 前 4 条是①层全部
        assertEquals(4, sel.detail().get(SimilarityScorer.Kind.DUPLICATE).shown());
        // 剩 2 条给②层，④层整层被 limit 截断（而且是在②层之后）
        SimilarityService.TierDetail prog = sel.detail().get(SimilarityScorer.Kind.PROGRESS);
        assertEquals(3, prog.droppedByLimit(), "④层应被 limit 整层截断");
        assertEquals(0, prog.shown());
    }
}
