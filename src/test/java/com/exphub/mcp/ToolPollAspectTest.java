package com.exphub.mcp;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * is_poll 判定守卫（W-20261115-17）。
 *
 * <p>判定只认 mail_start 的 {@code unreadOnly} 入参：
 * true → 1（轮询，统计排除）；false/未传 → 0（真实读信，照常计入）。
 * 其余工具一律 0 —— 不允许因为参数里恰好有个 unreadOnly 就被误判。
 */
class ToolPollAspectTest {

    private static final String[] MAIL_START_PARAMS =
            {"q", "limit", "role", "sent", "unreadOnly", "roles", "includeRead", "lastId", "beforeId"};

    private static Object[] argsWith(Object unreadOnly) {
        return new Object[]{null, null, null, null, unreadOnly, null, null, null, null};
    }

    @Test
    @DisplayName("mail_start(unreadOnly=true) 标记为轮询")
    void unreadOnlyTrueIsPoll() {
        assertEquals(1, ToolCallAspect.resolvePollFlag("mail_start", MAIL_START_PARAMS, argsWith(Boolean.TRUE)));
    }

    @Test
    @DisplayName("mail_start 默认视图与 unreadOnly=false 都算真实读信")
    void defaultAndFalseAreRealCalls() {
        assertEquals(0, ToolCallAspect.resolvePollFlag("mail_start", MAIL_START_PARAMS, argsWith(null)));
        assertEquals(0, ToolCallAspect.resolvePollFlag("mail_start", MAIL_START_PARAMS, argsWith(Boolean.FALSE)));
    }

    @Test
    @DisplayName("其它工具即使带 unreadOnly 也必须是 0")
    void otherToolsAreNeverPoll() {
        assertEquals(0, ToolCallAspect.resolvePollFlag("mail_thread", MAIL_START_PARAMS, argsWith(Boolean.TRUE)));
        assertEquals(0, ToolCallAspect.resolvePollFlag("search_experience", MAIL_START_PARAMS, argsWith(Boolean.TRUE)));
    }

    @Test
    @DisplayName("参数名解析不到时落 NULL，不许默认成真实读信")
    void unresolvedFallsToNull() {
        assertNull(ToolCallAspect.resolvePollFlag("mail_start", null, argsWith(Boolean.TRUE)),
                "拿不到参数名就无法区分轮询与读信，必须落 NULL（统计排除），而不是 0");
        assertNull(ToolCallAspect.resolvePollFlag("mail_start", new String[]{"q"}, argsWith(Boolean.TRUE)),
                "参数名与实参数量对不上说明解析失败，不能猜");
        assertNull(ToolCallAspect.resolvePollFlag("mail_start",
                        new String[]{"q", "limit", "role", "sent", "sent", "roles", "includeRead", "lastId", "beforeId"},
                        argsWith(Boolean.TRUE)),
                "参数名里没有 unreadOnly 说明方法签名或编译参数变了，不能默默记成真实调用");
    }
}
