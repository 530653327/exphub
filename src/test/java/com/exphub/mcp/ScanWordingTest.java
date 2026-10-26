package com.exphub.mcp;

import com.exphub.service.SimilarityScorer;
import com.exphub.service.SimilarityService;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 2026-10-26 13:2x 阁主实测抓到的两条「同一份回执自相矛盾」。
 *
 * <ol>
 *   <li><b>dryRun 下说「需要你判 N 对」</b>：dryRun 不入队，根本不存在「待判」——
 *       同一份回执里别处还写着「未入队（dryRun）」。</li>
 *   <li><b>已判过的对还在渲染动作句</b>：行尾「已判 DISTINCT —— 不必再判」，
 *       下一行又给「⛔ 动作：判 distinct」。动作已经执行过了，再给一遍就是让他重复劳动。</li>
 * </ol>
 */
class ScanWordingTest {

    @Test
    void dryRunMustNotClaimAnythingIsPending() {
        String s = ExpHubTools.triageLine(true, 5, 2, 5);
        assertTrue(s.contains("dryRun") && s.contains("不存在「待判」"), "dryRun 必须说清没有待判：" + s);
        assertFalse(s.contains("需要你判"), "dryRun 不许出现「需要你判」：" + s);
        assertTrue(s.contains("扫出 5 对"), "要给出实际扫出多少对：" + s);
    }

    @Test
    void realRunSaysPendingAndExplainsRest() {
        String s = ExpHubTools.triageLine(false, 5, 2, 5);
        assertTrue(s.contains("需要你判") && s.contains("5 对"), s);
        assertTrue(s.contains("已判过 2 对"), "要说明剩下那些为什么不用判：" + s);
        assertTrue(s.contains("进度类 5 条"), s);
        assertFalse(s.contains("dryRun"), "真入队时不该提 dryRun：" + s);
    }

    @Test
    void judgedPairsMustNotCarryActionSentence() {
        // 真实形态：证据 + 动作句
        String advice = "**正文重合度高**（共享 39 行）\n　独有行数：A 156 行、B 8 行。\n"
                + "　→ 先过身份检查：子页一侧带编号 —— 不构成合并理由。\n　　"
                + SimilarityScorer.actionSentence(SimilarityScorer.Action.DISTINCT, 8);
        assertTrue(advice.contains("动作：判 distinct"), "前置条件：原文确实带动作句");

        String stripped = SimilarityScorer.withoutAction(advice);
        assertFalse(stripped.contains("动作"), "已判过的对不许再给动作：" + stripped);
        assertTrue(stripped.contains("共享 39 行"), "证据部分必须保留：" + stripped);
        assertTrue(stripped.contains("不构成合并理由"), "结论也要保留：" + stripped);
    }

    @Test
    void actionSentenceRemovalMustWorkForAllActions() {
        for (SimilarityScorer.Action a : SimilarityScorer.Action.values()) {
            String advice = "证据一行。\n　　" + SimilarityScorer.actionSentence(a, 5);
            String out = SimilarityScorer.withoutAction(advice);
            assertFalse(out.contains("动作"), "动作 " + a + " 没被去掉：" + out);
            assertTrue(out.contains("证据一行"), "证据被误删：" + out);
        }
    }

    @Test
    void rowIsJudgedOnlyWhenQueueStateSaysSo() {
        SimilarityService.Row judged = new SimilarityService.Row(1L, "A", 2L, "B", 0.5, "r",
                "**已判 DISTINCT**（候选 #17 · 10-26 00:07）—— 本次只是重新扫出，**不必再判**",
                SimilarityScorer.Kind.DUPLICATE, 10, 0.5, "adv");
        SimilarityService.Row pending = new SimilarityService.Row(1L, "A", 2L, "B", 0.5, "r",
                "未入队（dryRun）", SimilarityScorer.Kind.DUPLICATE, 10, 0.5, "adv");
        assertTrue(ExpHubTools.isJudged(judged));
        assertFalse(ExpHubTools.isJudged(pending), "没判过的不许当成已判（否则动作句会被误删）");
    }
}
