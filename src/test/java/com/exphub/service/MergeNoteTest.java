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
    @DisplayName("总长不超 500，且超长时截的是新说明不是旧 note")
    void truncatesNewNotOld() {
        String old = "撤回：DISTINCT → PENDING｜原判定人：阁主｜原判定说明：" + "证".repeat(60);
        String merged = ReviewCandidateService.mergeNote("新".repeat(800), old);
        assertTrue(merged.length() <= 500, "长度 " + merged.length());
        assertTrue(merged.contains("原判定人：阁主"), "旧 note 不能被截：" + merged);
        assertTrue(merged.contains("…"), "新说明超长应有省略号：" + merged);
        assertFalse(merged.contains("新".repeat(800)), "800 字新说明必须被截短");
    }
}
