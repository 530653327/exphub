package com.exphub.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 候选对比页的逐行对齐（2026-11-05 越哥要「左右并排、一样的同色、不一样标红」）。
 *
 * <p>核心要求不是「找出差异」，而是**差异不能假**：按序号硬配的话，
 * 中间插进一行，后面全部错位，页面上满屏红——看着像完全不同，其实只差一行。
 * LCS 对齐就是为了避免这个。
 */
class DiffAlignTest {

    @Test
    @DisplayName("完全相同的两篇：每一行都标为相同")
    void identicalContentAllSame() {
        String a = "第一行\n第二行\n第三行";
        List<SimilarityScorer.DiffRow> rows = SimilarityScorer.alignLines(a, a);
        assertEquals(3, rows.size());
        for (SimilarityScorer.DiffRow r : rows) {
            assertTrue(r.same(), "完全相同的内容不该有任何一行标红");
        }
    }

    @Test
    @DisplayName("只在 B 侧插一行：其余行仍对齐，只多出那一行")
    void insertedLineDoesNotShiftEverything() {
        String a = "一\n二\n三";
        String b = "一\n插进来的\n二\n三";
        List<SimilarityScorer.DiffRow> rows = SimilarityScorer.alignLines(a, b);

        assertEquals(4, rows.size(), "B 多一行，对齐后应多出一行");
        int diff = 0;
        for (SimilarityScorer.DiffRow r : rows) {
            if (!r.same()) diff++;
        }
        assertEquals(1, diff, "只有插入的那一行是差异；若整体错位这里会变成 3");
    }

    @Test
    @DisplayName("两侧完全无关：共同 0 行，全是差异")
    void unrelatedContentAllDiff() {
        String a = "苹果\n香蕉";
        String b = "汽车\n飞机";
        List<SimilarityScorer.DiffRow> rows = SimilarityScorer.alignLines(a, b);
        for (SimilarityScorer.DiffRow r : rows) {
            assertFalse(r.same(), "毫无重合的内容不该出现「相同」行");
        }
    }

    @Test
    @DisplayName("空侧要留空位（null），页面才知道那边没有这一行")
    void emptySideIsNullNotBlankString() {
        List<SimilarityScorer.DiffRow> rows = SimilarityScorer.alignLines("只有这一行", "只有这一行\nA侧没有这行");
        assertEquals(2, rows.size());
        assertEquals("只有这一行", rows.get(0).left());
        assertEquals("只有这一行", rows.get(0).right());
        assertTrue(rows.get(0).same());
        // 第二行只存在于 B：左侧必须是 null，不能是空字符串（否则页面分不清「空行」和「没有」）
        assertEquals(null, rows.get(1).left());
        assertEquals("A侧没有这行", rows.get(1).right());
    }

    @Test
    @DisplayName("空行不计入（与 diff_experiences 同口径）")
    void blankLinesIgnored() {
        List<SimilarityScorer.DiffRow> rows = SimilarityScorer.alignLines("一\n\n\n二", "一\n二");
        assertEquals(2, rows.size(), "空行被丢弃后只剩两行");
        assertTrue(rows.get(0).same());
        assertTrue(rows.get(1).same());
    }

    @Test
    @DisplayName("顺序全反：LCS 只能对上一行——这是刻意保留的局限，页面不显示集合口径的「共同行数」")
    void reorderedLinesAreNotFullyAligned() {
        String a = "一\n二\n三";
        String b = "三\n二\n一";
        List<SimilarityScorer.DiffRow> rows = SimilarityScorer.alignLines(a, b);
        int same = 0;
        for (SimilarityScorer.DiffRow r : rows) {
            if (r.same()) same++;
        }
        // LCS 的固有特性：逆序时最长公共子序列只有 1 行。
        // 而 diff_experiences 用的是「集合交集」，同样两份内容会报共同 3 行。
        // 两个口径不同源，**所以页面上不能同时摆「共同 N 行」和这个对齐视图的红色行数** ——
        // 一个说 3、一个看起来像 4 处差异，读的人只会以为哪边算错了。
        assertEquals(1, same);
    }
}
