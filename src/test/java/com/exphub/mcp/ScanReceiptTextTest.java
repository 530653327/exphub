package com.exphub.mcp;

import com.exphub.service.SimilarityService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 回执文案的两条纯函数（2026-10-29）：
 * <ul>
 *   <li>P1-5 {@link ExpHubTools#tierLimitLine} —— 分层限流归因，一行说清「为什么没全展示」</li>
 *   <li>P2-8 {@link ExpHubTools#longDocHint} —— 长文软阈值提示，只提示不阻断</li>
 * </ul>
 *
 * <p>为什么单独测：这两段都是「读者据此决定下一步动作」的文案。
 * 归因算错 → 人会照着调错参数（该放宽 per-doc 的去加大 limit）；阈值口径写错 → 会被当成硬限制。
 */
class ScanReceiptTextTest {

    /** 全参构造：above, shown, perDoc, byLimit, bySample, redundant, samples, omitted, sameTopic, judged */
    private static SimilarityService.TierDetail tier(int above, int shown, int byPerDoc, int byLimit,
                                                     int bySample, int redundant, int sameTopic, int judged) {
        return new SimilarityService.TierDetail(above, shown, byPerDoc, byLimit, bySample, redundant,
                List.of(), List.of(), sameTopic, judged);
    }

    @Test
    @DisplayName("整层全部展示：明确写「无截断」，而不是留空让人猜")
    void allShown() {
        String s = ExpHubTools.tierLimitLine("① 疑似字面重复", tier(5, 5, 0, 0, 0, 0, 0, 0), 2);
        assertTrue(s.contains("超阈值 5 对 = 展示 5 条"), s);
        assertTrue(s.contains("全部展示，无截断"), s);
        assertFalse(s.contains("自相矛盾"), s);
    }

    @Test
    @DisplayName("两个原因并列时都要说出来（少说一项就等于让人漏掉一个调参方向）")
    void listsEveryCause() {
        // 超阈值 17 = 展示 2 + 已判过 15
        String s = ExpHubTools.tierLimitLine("③ 进度/日志记录", tier(17, 2, 0, 0, 0, 0, 0, 15), 2);
        assertTrue(s.contains("展示 2 条"), s);
        assertTrue(s.contains("已判过不占名额 15"), s);
        assertFalse(s.contains("limit 截断"), "只有 0 条被 limit 截断时不该出现这一项：" + s);
        assertFalse(s.contains("自相矛盾"), s);
    }

    @Test
    @DisplayName("per-doc 用实际阈值数字说话（默认 2，但不能写死）")
    void perDocUsesActualLimit() {
        String s = ExpHubTools.tierLimitLine("② 同话题演进链", tier(6, 4, 2, 0, 0, 0, 0, 0), 5);
        assertTrue(s.contains("「单篇最多 5 条」挤掉 2"), "应回显调用方传的 maxPerDoc：" + s);
    }

    @Test
    @DisplayName("加总不闭合时不许替读者凑数 —— 直接报未归因条数")
    void reportsUnaccounted() {
        // 超阈值 10，展示 3，但各因只解释 2 条 → 还差 5 条没人认领
        String s = ExpHubTools.tierLimitLine("① 疑似字面重复", tier(10, 3, 0, 2, 0, 0, 0, 0), 2);
        assertTrue(s.contains("未归因 5 条"), s);
        assertTrue(s.contains("自相矛盾"), "必须提示这是缺陷，不是正常现象：" + s);
    }

    @Test
    @DisplayName("多算也要报（计数重复累加同样会让结论失真）")
    void reportsOvercount() {
        // 超阈值 5，展示 5（本该 0 条被丢），却报了 3 条 limit 截断
        String s = ExpHubTools.tierLimitLine("① 疑似字面重复", tier(5, 5, 0, 3, 0, 0, 0, 0), 2);
        assertTrue(s.contains("多算 3 条"), s);
        assertTrue(s.contains("自相矛盾"), s);
    }

    @Test
    @DisplayName("长文阈值：8000 字不提示，8001 字提示（边界必须能对上，否则会被当硬限制）")
    void longDocThresholdBoundary() {
        assertEquals("", ExpHubTools.longDocHint(0));
        assertEquals("", ExpHubTools.longDocHint(8000), "8000 字本身不该提示");
        assertFalse(ExpHubTools.longDocHint(8001).isEmpty(), "8001 字应该提示");
    }

    @Test
    @DisplayName("长文提示必须写明「不阻断」和口径，否则会被误读成写入被拒")
    void longDocHintWording() {
        String s = ExpHubTools.longDocHint(12345);
        assertTrue(s.contains("不阻断"), "必须说清操作已生效：" + s);
        assertTrue(s.contains("12345"), "要报实际字数：" + s);
        assertTrue(s.contains("字符数"), "口径要写明，否则和字节/行数混用：" + s);
        assertTrue(s.contains("8000"), "要给出阈值本身：" + s);
    }

    @Test
    @DisplayName("告警分支边界：差 1 也要叫，多算 1 也要叫，恰好闭合时一声都不能响")
    void alertBranchBoundary() {
        // 这是 #751 第 1 条要的「正向用例」：故意让加总不成立，确认告警分支真的会触发。
        // 一个永远不触发的报警器，用「它没响」是测不出来的（死分支照样绿）。
        String under = ExpHubTools.tierLimitLine("① 疑似字面重复", tier(6, 3, 0, 2, 0, 0, 0, 0), 2);
        assertTrue(under.contains("未归因 1 条"), "差 1 也得报：" + under);
        assertTrue(under.contains("自相矛盾"), under);

        String over = ExpHubTools.tierLimitLine("① 疑似字面重复", tier(6, 3, 0, 4, 0, 0, 0, 0), 2);
        assertTrue(over.contains("多算 1 条"), "多算也要报，否则计数重复累加会被当成正常：" + over);
        assertTrue(over.contains("自相矛盾"), over);

        // 反向守卫：恰好闭合时禁止出现告警（否则每次扫描都在喊狼来了）
        String exact = ExpHubTools.tierLimitLine("① 疑似字面重复", tier(6, 3, 1, 2, 0, 0, 0, 0), 2);
        assertFalse(exact.contains("自相矛盾"), "3 + 1 + 2 = 6，闭合，不该报警：" + exact);
        assertFalse(exact.contains("未归因"), exact);
        assertFalse(exact.contains("多算"), exact);
    }

    @Test
    @DisplayName("整层被挤空时原因行仍然打印（这条由渲染解耦保证，闭合测试管不到）")
    void limitBlockStillPrintsEmptyTier() {
        // #751 第 3 条：三层、名额只够第一层 —— ③层展示 0，但它那行必须还在。
        java.util.Map<com.exphub.service.SimilarityScorer.Kind, SimilarityService.TierDetail> detail =
                new java.util.LinkedHashMap<>();
        detail.put(com.exphub.service.SimilarityScorer.Kind.DUPLICATE, tier(3, 3, 0, 0, 0, 0, 0, 0));
        detail.put(com.exphub.service.SimilarityScorer.Kind.SERIES, tier(0, 0, 0, 0, 0, 0, 0, 0));
        detail.put(com.exphub.service.SimilarityScorer.Kind.PROGRESS, tier(5, 0, 0, 5, 0, 0, 0, 0));

        String block = ExpHubTools.tierLimitBlock(detail, 2);
        assertTrue(block.contains("① 疑似字面重复：超阈值 3 对 = 展示 3 条"), block);
        assertTrue(block.contains("③ 进度/日志记录：超阈值 5 对 = 展示 0 条 + limit 截断 5"),
                "整层展示 0 时原因行必须仍然打印：" + block);
        assertFalse(block.contains("同话题演进链"), "该层一对都没有(above=0)，不该占一行：" + block);
        // 顺序固定 ①②③，不随 Map 插入顺序变（插入顺序被人改成 ③ 在前也不会乱）
        assertTrue(block.indexOf("① 疑似") < block.indexOf("③ 进度"),
                "行顺序必须是 ①→③：" + block);
    }

    @Test
    @DisplayName("没有任何截断时说「全部展示」，不是留空让人猜")
    void limitBlockAllShown() {
        java.util.Map<com.exphub.service.SimilarityScorer.Kind, SimilarityService.TierDetail> detail =
                new java.util.LinkedHashMap<>();
        detail.put(com.exphub.service.SimilarityScorer.Kind.DUPLICATE, tier(2, 2, 0, 0, 0, 0, 0, 0));
        String block = ExpHubTools.tierLimitBlock(detail, 2);
        assertTrue(block.contains("各层全部展示，无截断"), block);
        assertFalse(block.contains("①"), "无截断时不必列层：" + block);
    }
}
