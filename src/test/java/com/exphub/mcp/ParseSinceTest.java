package com.exphub.mcp;

import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * D1 施工包 2：since 解析（2026-10-25）。
 *
 * <p>为什么要单独测：它是「每日增量」的唯一入口。解析错了不会报错，
 * 只会安静地返回「今天没有新东西」，然后整理永远不跑 —— 这种 bug 最难发现。
 */
class ParseSinceTest {

    @Test
    void blankMeansNoLimit() {
        assertNull(ExpHubTools.parseSince(null));
        assertNull(ExpHubTools.parseSince(""));
        assertNull(ExpHubTools.parseSince("   "));
    }

    @Test
    void todayIsMidnight() {
        assertEquals(LocalDate.now().atStartOfDay(), ExpHubTools.parseSince("today"));
        assertEquals(LocalDate.now().atStartOfDay(), ExpHubTools.parseSince("今天"));
        assertEquals(LocalDate.now().atStartOfDay(), ExpHubTools.parseSince("TODAY"));
    }

    @Test
    void yesterdayIsPreviousMidnight() {
        assertEquals(LocalDate.now().minusDays(1).atStartOfDay(), ExpHubTools.parseSince("yesterday"));
        assertEquals(LocalDate.now().minusDays(1).atStartOfDay(), ExpHubTools.parseSince("昨天"));
    }

    @Test
    void relativeDays() {
        assertEquals(LocalDate.now().minusDays(7).atStartOfDay(), ExpHubTools.parseSince("-7d"));
        // 不带减号也认，避免「7d」被当成非法格式直接报错
        assertEquals(LocalDate.now().minusDays(7).atStartOfDay(), ExpHubTools.parseSince("7d"));
        assertEquals(LocalDate.now().minusDays(3).atStartOfDay(), ExpHubTools.parseSince("-3天"));
    }

    @Test
    void relativeHours() {
        LocalDateTime got = ExpHubTools.parseSince("-12h");
        LocalDateTime expect = LocalDateTime.now().minusHours(12);
        // 允许几秒误差（now() 两次调用之间的时间差）
        assertTrue(Math.abs(java.time.Duration.between(expect, got).getSeconds()) < 5,
                "应为 12 小时前，实际 " + got);
    }

    @Test
    void absoluteDate() {
        assertEquals(LocalDateTime.of(2026, 10, 25, 0, 0), ExpHubTools.parseSince("2026-10-25"));
    }

    @Test
    void absoluteDateTime() {
        assertEquals(LocalDateTime.of(2026, 10, 25, 8, 30), ExpHubTools.parseSince("2026-10-25 08:30"));
        assertEquals(LocalDateTime.of(2026, 10, 25, 8, 30), ExpHubTools.parseSince("2026-10-25T08:30"));
        assertEquals(LocalDateTime.of(2026, 10, 25, 8, 30, 15), ExpHubTools.parseSince("2026-10-25 08:30:15"));
    }

    @Test
    void invalidFormatThrowsReadableError() {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> ExpHubTools.parseSince("上周"));
        // 错误信息必须带可用写法，否则调用方只能瞎猜
        assertTrue(e.getMessage().contains("today"));
        assertTrue(e.getMessage().contains("-7d"));
    }
}
