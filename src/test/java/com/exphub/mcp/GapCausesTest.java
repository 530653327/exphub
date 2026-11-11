package com.exphub.mcp;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 「逐项归因」必须真的逐项（2026-10-26 12:1x 阁主实测抓到的半成品）。
 *
 * <p>他抓到的原文：
 * <pre>
 *   另有 11 对未展示，逐项归因：③层进度/日志类只抽样 5 条，省 5 条
 * </pre>
 * 11 = 5 + **6**，那 6 条「同话题跳过」的说明就在下一段③层标题里 ——
 * 数据是有的，只是没拼进头行。<b>「逐项归因」四个字等于承诺把所有项都列出来</b>，
 * 只列一项，剩下 6 条又变成需要读者自己去找的未知数。
 *
 * <p>而且这是**退化**：上一轮 `145 = 139 + 6` 是齐全的，加了「同话题跳过」这个新因之后
 * 忘了把它拼进去。所以这里不只测文案，还测**加总必须等于未展示数**：
 * 新加一种「因」的时候，漏拼就会让加总对不上，立刻被这条守卫抓住。
 */
class GapCausesTest {

    @Test
    void everyCauseMustBeListedAndSumMustMatch() {
        // 真实口径：23 超阈值、展示 12、未展示 11 = ③层抽样省 5 + 同话题跳过 6
        String s = ExpHubTools.causesText(11, 5, 0, 0, 0, 6, 0, 2, 5);
        assertTrue(s.contains("省 5 条"), "抽样那一项要在：" + s);
        assertTrue(s.contains("同话题跳过") && s.contains("6 条"),
                "同话题跳过的 6 条必须拼进头行（这就是这次漏掉的那一项）：" + s);
        assertFalse(s.contains("未归因"), "加总吻合时不该出现「未归因」告警：" + s);
    }

    @Test
    void sumMismatchMustConfessInsteadOfHiding() {
        // 只报 5 条、实际未展示 11 条 → 必须自己说出「另有 6 条未归因」，不许沉默
        String s = ExpHubTools.causesText(11, 5, 0, 0, 0, 0, 0, 2, 5);
        assertTrue(s.contains("未归因") && s.contains("6"),
                "加总对不上时必须自报缺口，而不是让读者去凑：" + s);
        assertTrue(s.contains("自相矛盾"), "要标明这是缺陷而不是正常现象：" + s);
    }

    @Test
    void overCountMustAlsoConfess() {
        String s = ExpHubTools.causesText(5, 5, 6, 0, 0, 0, 0, 2, 5);
        assertTrue(s.contains("超过未展示数"), "各项之和比未展示多也要说出来：" + s);
    }

    @Test
    void fullLimitVariantAlsoListsEveryCause() {
        // 满员口径：未展示 145 = 抽样省 139 + 同族略去 6（上一轮已验收的形态，不许退化）
        String s = ExpHubTools.shortCausesText(145, 139, 0, 6, 0, 0, 0);
        assertTrue(s.equals("③层抽样省 139 + 同族交叉对略去 6"),
                "满员口径要保持「A + B」可加的样子：" + s);
        String t = ExpHubTools.shortCausesText(145, 139, 0, 0, 0, 0, 0);
        assertTrue(t.contains("未归因 6"), "漏项时同样要自报缺口：" + t);
        assertFalse(t.contains("自相矛盾") && !t.contains("6"), t);
    }

    /**
     * 2026-11-11 派单 A：新加的「行重合不足门槛、未入队」这一项**必须进求和**。
     *
     * <p>它特别容易漏：门槛拦下的对仍然算在「超阈值」里（阈值是超没超过，门槛是够不够入队），
     * 但不进 tierStats（根本没进候选池），于是「未展示」里凭空多出一截——
     * 忘了拼这一个加数，回执就会自报「另有 N 条未归因」的假缺陷（与 10-26 那次同型）。
     */
    @Test
    void gatedPairsMustEnterCauseSum() {
        // 超阈值 7 对未展示 = 已判过 5 + 门槛拦下 2
        String s = ExpHubTools.causesText(7, 0, 0, 0, 0, 0, 5, 2, 2, 5);
        assertTrue(s.contains("未入队** 2 条"), "门槛拦下的那批必须出现在归因里：" + s);
        assertTrue(s.contains("已判过") && s.contains("5 条"), s);
        assertFalse(s.contains("未归因"), "5 + 2 = 7，加总吻合就不该报警：" + s);

        String t = ExpHubTools.shortCausesText(7, 0, 0, 0, 0, 0, 5, 2);
        assertTrue(t.contains("行重合不足门槛未入队 2"), t);
        assertTrue(t.contains("已判过不占名额 5"), t);
        assertFalse(t.contains("未归因"), t);
    }

    /** 老签名（不带门槛）保持原样：加参数不许改掉老调用方的口径 */
    @Test
    void oldSignatureWithoutGateStillBalances() {
        String s = ExpHubTools.causesText(5, 5, 0, 0, 0, 0, 0, 2, 5);
        assertFalse(s.contains("未归因"), s);
        assertEquals("③层抽样省 139 + 同族交叉对略去 6",
                ExpHubTools.shortCausesText(145, 139, 0, 6, 0, 0, 0));
    }
}
