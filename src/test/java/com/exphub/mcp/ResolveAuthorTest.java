package com.exphub.mcp;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * 双参数署名 {@code updatedName} / {@code authorName} 的优先级（P2-7）。
 *
 * <p>背景：原先各处写 {@code updatedName != null ? updatedName : authorName}，
 * 判的是「非 null」而不是「非空白」。传空串 {@code ""} 时 authorName 被顶掉，
 * 最后落到助手名上 —— 观感就是「updatedName 是死参数，两个都得传」。
 * 线上排查了很久，其实只是这一个判断写漏了 trim。
 */
class ResolveAuthorTest {

    @Test
    @DisplayName("updatedName 非空 → 用它")
    void updatedWins() {
        assertEquals("烛龙", ExpHubTools.resolveAuthor("烛龙", "白泽", null));
    }

    @Test
    @DisplayName("updatedName 是空白串 → 退回 authorName（修的就是这一条）")
    void blankUpdatedFallsBack() {
        assertEquals("白泽", ExpHubTools.resolveAuthor("   ", "白泽", null));
        assertEquals("白泽", ExpHubTools.resolveAuthor("", "白泽", null));
    }

    @Test
    @DisplayName("两个都没传 → unknown")
    void bothMissing() {
        assertEquals("unknown", ExpHubTools.resolveAuthor(null, null, null));
        assertEquals("unknown", ExpHubTools.resolveAuthor("  ", "\t", null));
    }

    @Test
    @DisplayName("前后空格被修掉，不写进库里")
    void trims() {
        assertEquals("烛龙", ExpHubTools.resolveAuthor("  烛龙  ", "白泽", null));
    }
}
