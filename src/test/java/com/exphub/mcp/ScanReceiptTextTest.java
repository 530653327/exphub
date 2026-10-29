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
}
