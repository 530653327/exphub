package com.exphub.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 判定说明合并（{@code reopen → resolve} 往返不能抹掉原判定）。
 *
 * <p>背景：reopen 把原判定人/时间/依据拼进 note，而 resolve 原来一律
 * {@code setNote(新说明)} 整条覆盖 —— 往返一次，审计链就没了。
 * 这条测试钉住「旧 note 必须活下来」。
 */
class MergeNoteTest {

    @Test
    @DisplayName("旧 note 里没有撤回痕迹 → 维持原行为，只写新说明")
    void noReopenHistory() {
        assertEquals("判为不同", ReviewCandidateService.mergeNote("判为不同", null));
        assertEquals("判为不同", ReviewCandidateService.mergeNote("判为不同", "  "));
        assertEquals("判为不同", ReviewCandidateService.mergeNote("判为不同", "共同 0 行，凭证不足"));
    }

    @Test
    @DisplayName("旧 note 带「撤回：」→ 新说明在前、原判定在后，不能被吃掉")
    void keepsReopenHistory() {
        String old = "撤回：DISTINCT → PENDING｜原判定人：阁主｜原判定说明：共同 3 行两侧独有多";
        String merged = ReviewCandidateService.mergeNote("验收还原：往返一次", old);
        assertTrue(merged.startsWith("验收还原：往返一次｜前次："), merged);
        assertTrue(merged.contains("原判定人：阁主"), "原判定人必须保留：" + merged);
        assertTrue(merged.contains("共同 3 行"), "原判定依据必须保留：" + merged);
    }

    @Test
    @DisplayName("新说明为空时也要把原判定留下来")
    void emptyNewNote() {
        String old = "撤回：DISTINCT → PENDING｜原判定人：阁主";
        String merged = ReviewCandidateService.mergeNote(null, old);
        assertTrue(merged.startsWith("前次："), merged);
        assertTrue(merged.contains("原判定人：阁主"), merged);
    }

    @Test
    @DisplayName("总长不超 NOTE_MAX，且超长时截的是新说明不是旧 note")
    void truncatesNewNotOld() {
        String old = "撤回：DISTINCT → PENDING｜原判定人：阁主｜原判定说明：" + "证".repeat(60);
        String merged = ReviewCandidateService.mergeNote("新".repeat(3000), old);
        assertTrue(merged.length() <= ReviewCandidateService.NOTE_MAX, "长度 " + merged.length());
        assertTrue(merged.contains("原判定人：阁主"), "旧 note 不能被截：" + merged);
        assertTrue(merged.contains("…"), "新说明超长应有省略号：" + merged);
        assertFalse(merged.contains("新".repeat(3000)), "3000 字新说明必须被截短");
    }

    /**
     * 回归：2026-10-29 拿线上 #17 真跑一次往返，原判定说明的结尾被切掉了。
     *
     * <p>根因是 note 列只有 500 字符、旧 note 只留 380 —— 一条 434 字符的撤回记录
     * 装不进「新说明 + 完整旧说明」。这条用当时的真实长度钉住：旧部分必须一字不少。
     */
    @Test
    @DisplayName("434 字符的回撤记录必须完整保留（#17 实测尺寸）")
    void keepsFullReopenNote() {
        String old = "撤回：DISTINCT → PENDING｜原因：验收往返｜原判定人：阁主｜原判定时间：2026-10-26 00:07:36"
                + "｜原判定说明：" + "判".repeat(300) + "本条判 distinct 让系统以后别再问这一对。";
        assertTrue(old.length() > 380, "样本要超过旧上限 380，否则测不出问题：" + old.length());
        String merged = ReviewCandidateService.mergeNote("验收往返还原：维持原判 DISTINCT", old);
        assertTrue(merged.endsWith("本条判 distinct 让系统以后别再问这一对。"),
                "原判定说明的结尾不能被吃掉：" + merged.substring(Math.max(0, merged.length() - 60)));
        assertTrue(merged.length() <= ReviewCandidateService.NOTE_MAX, "长度 " + merged.length());
    }
}
