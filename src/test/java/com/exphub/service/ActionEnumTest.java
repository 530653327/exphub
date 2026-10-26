package com.exphub.service;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 动作枚举与「证据同源」守卫（2026-10-26 02:51 越哥定）。
 *
 * <p>他的两句要求：
 * <ol>
 *   <li><b>枚举</b>：一对候选只允许一个动作（把「同时给出两个动作」从结构上消掉）</li>
 *   <li><b>证据同源</b>（比枚举更要紧）：内容类动作必须有内容证据（共享行 ≥1）；
 *       结构信号（行数比 / 标题日期编号）<b>只能否决，不能立论</b></li>
 * </ol>
 * 有了这两条，第八版那个 bug（0 行重合却被推成母集-子集）在<b>渲染层</b>就出不来：
 * 没有共享行 → 内容类动作的前置条件不满足 → 只能落 distinct 或 merge。
 */
class ActionEnumTest {

    /** 一对候选只渲染一个动作 —— 数「动作：」出现次数 */
    @Test
    void exactlyOneActionIsRenderedPerPair() {
        Object[][] cases = {
                {SimilarityScorer.Kind.DUPLICATE, 39, 195, 47, "地图设计", "地图3提示词"},
                {SimilarityScorer.Kind.DUPLICATE, 21, 42, 41, "前端开发专家", "系统测试工程师"},
                {SimilarityScorer.Kind.SERIES, 0, 120, 5, "个人档案", "个人档案补充：饮食"},
                {SimilarityScorer.Kind.SERIES, 0, 7, 54, "10-23午餐实录", "10-24 全天饮食实录"},
                {SimilarityScorer.Kind.SERIES, 0, 40, 44, "地图1提示词", "地图2提示词"},
                {SimilarityScorer.Kind.SERIES, 1, 49, 23, "水系特效", "电系特效"},
                {SimilarityScorer.Kind.PROGRESS, 0, 30, 30, "进度101", "进度102"},
                {SimilarityScorer.Kind.UNKNOWN, 0, 3, 3, "甲", "乙"},
        };
        for (Object[] c : cases) {
            String d = SimilarityScorer.advice((SimilarityScorer.Kind) c[0], (Integer) c[1], 0.5, 25,
                    (Integer) c[2], (Integer) c[3], (String) c[4], (String) c[5]);
            int n = d.split("动作：", -1).length - 1;
            assertEquals(1, n, "一对候选只能有一个动作，实际 " + n + " 个：" + d);
        }
    }

    /** 0 行重合时，内容类动作（duplicate / superseded / merge）的前置条件都不成立 */
    @Test
    void zeroSharedLinesNeverYieldsContentActions() {
        int[][] sizes = {{120, 5}, {5, 120}, {7, 54}, {40, 44}, {1, 300}};
        for (int[] sz : sizes) {
            for (String[] t : new String[][]{{null, null}, {"甲", "乙"}, {"甲档案", "甲档案补充：饮食"}}) {
                SimilarityScorer.Action a = SimilarityScorer.decide(
                        SimilarityScorer.Kind.SERIES, 0, sz[0], sz[1], t[0], t[1]);
                assertNotEquals(SimilarityScorer.Action.DUPLICATE, a, "0 行重合不可能判 duplicate");
                assertNotEquals(SimilarityScorer.Action.SUPERSEDED, a, "0 行重合不可能判 superseded");
                if (a == SimilarityScorer.Action.MERGE_THEN_CLEAR) {
                    // 唯一允许的结构例外：标题明说是补充页 + 行数悬殊
                    assertTrue(SimilarityScorer.isSupplementTitle(sz[0] <= sz[1] ? t[0] : t[1]),
                            "0 行重合还判合并，必须是因为短那篇是补充页：" + sz[0] + ":" + sz[1]);
                }
            }
        }
        // ①层理论上不会出现 0 共享，但 decide 也要守住（防止上游把数算错时给出越界动作）
        assertEquals(SimilarityScorer.Action.DISTINCT,
                SimilarityScorer.decide(SimilarityScorer.Kind.DUPLICATE, 0, 100, 5, "甲", "乙"));
    }

    /** 结构信号只能否决，不能立论 */
    @Test
    void structureOnlyVetoesNeverEstablishes() {
        // 行数悬殊但不是补充页 → 不许立论成「母集」，只能 distinct
        assertEquals(SimilarityScorer.Action.DISTINCT,
                SimilarityScorer.decide(SimilarityScorer.Kind.SERIES, 0, 120, 5, "甲档案", "乙清单"),
                "行数比只能说明长短，不能推出归属");
        // 补充页是结构能支持的唯一例外（它是「要并进去」，不是「已包含」）
        assertEquals(SimilarityScorer.Action.MERGE_THEN_CLEAR,
                SimilarityScorer.decide(SimilarityScorer.Kind.SERIES, 0, 120, 5,
                        "史越 个人档案", "史越个人档案补充：家庭、饮食偏好与饮茶习惯"));
        // 但结构否决优先于结构例外：日期不同 → 即使是补充页也直接 distinct
        assertEquals(SimilarityScorer.Action.DISTINCT,
                SimilarityScorer.decide(SimilarityScorer.Kind.SERIES, 0, 7, 54,
                        "10-23午餐实录", "10-24 全天饮食实录补充"),
                "否决永远优先于立论");
    }

    /** 缺省是 distinct；没证据就不给动作 */
    @Test
    void distinctIsTheDefault() {
        assertEquals(SimilarityScorer.Action.DISTINCT,
                SimilarityScorer.decide(SimilarityScorer.Kind.SERIES, 0, 40, 44, "甲", "乙"));
        assertEquals(SimilarityScorer.Action.DISTINCT,
                SimilarityScorer.decide(SimilarityScorer.Kind.UNKNOWN, 0, 3, 3, null, null));
        assertEquals(SimilarityScorer.Action.NONE,
                SimilarityScorer.decide(SimilarityScorer.Kind.PROGRESS, 0, 30, 30, "甲", "乙"));
        // 真重复（两侧独有都很少 + 有内容证据）才给 duplicate
        assertEquals(SimilarityScorer.Action.DUPLICATE,
                SimilarityScorer.decide(SimilarityScorer.Kind.DUPLICATE, 39, 40, 40, "甲", "乙"));
        // 母集-子集（有内容证据 + 一侧独有很少）→ 合并后清；**标题不带编号**（带编号由下面那条挡）
        assertEquals(SimilarityScorer.Action.MERGE_THEN_CLEAR,
                SimilarityScorer.decide(SimilarityScorer.Kind.DUPLICATE, 39, 195, 47, "中央平原地图设计", "场景补充稿"));
        // ①层编号排除（2026-10-26 11:0x 阁主，阻塞项）：子页带编号、母档没编号 → 并列实例，不许合并
        assertEquals(SimilarityScorer.Action.DISTINCT,
                SimilarityScorer.decide(SimilarityScorer.Kind.DUPLICATE, 39, 195, 47,
                        "中央平原地图设计", "中央平原地图3提示词"),
                "地图1/2/3/4 是「一套里的第 N 份」，逐个并进母档等于把并列压成一份");
    }
}
