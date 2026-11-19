package com.exphub.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 「dispatch_list 不写埋点」的守卫（2026-11-19 越哥）。
 *
 * <p>为什么这件事需要守卫：这个开关一旦只做了**一半**，回执就会自相矛盾 ——
 * 少写切面那一半，4.5 万行/周继续灌进表里；少做聚合排除那一半，历史行还霸着榜一；
 * 少做零调用差集那一半，一个每回合都在调的工具会被列成「精简候选」。
 * 三种半成品都能启动、都不报错，只有读回执的人才发现不对。
 */
class ToolTelemetryPolicyTest {

    private static String read(String rel) throws Exception {
        Path p = Path.of(rel);
        assertTrue(Files.exists(p), "找不到源码：" + p.toAbsolutePath());
        return new String(Files.readAllBytes(p), StandardCharsets.UTF_8);
    }

    private static String between(String code, String from, String to) {
        int a = code.indexOf(from);
        int b = code.indexOf(to, a + 1);
        assertTrue(a >= 0, "找不到起止标记：" + from);
        assertTrue(b > a, "找不到结束标记：" + to);
        return code.substring(a, b);
    }

    @Test
    @DisplayName("默认跳过 dispatch_list；空串 = 全写（恢复埋点）")
    void parsesConfigList() {
        assertTrue(new ToolTelemetryPolicy(null).isSkipped(ToolTelemetryPolicy.DEFAULT_SKIP),
                "默认值必须是 dispatch_list，否则配置缺失时埋点照写");
        assertFalse(new ToolTelemetryPolicy("").isSkipped("dispatch_list"), "空串应当什么都没跳过（恢复埋点）");
        assertFalse(new ToolTelemetryPolicy(" ").isSkipped("dispatch_list"), "空白配置等于恢复埋点");
        assertEquals(List.of("a", "b"), ToolTelemetryPolicy.parse(" a , ,b , a "),
                "去空白、去空项、去重");
        assertFalse(new ToolTelemetryPolicy("dispatch_list").isSkipped("search_experience"),
                "名单外的工具必须照写");
        assertFalse(new ToolTelemetryPolicy("dispatch_list").isSkipped(null));
    }

    @Test
    @DisplayName("切面在写行之前就问策略：跳过 = 根本不留行（不是写了再排除）")
    void aspectSkipsTheInsertItself() throws Exception {
        String aspect = read("src/main/java/com/exphub/mcp/ToolCallAspect.java");
        String body = between(aspect, "public Object aroundToolCall(", "private boolean skipTelemetry(");
        assertTrue(body.contains("!skipTelemetry(toolName)"),
                "写埋点前没有判断跳过：dispatch_list 还会继续灌 4.5 万行/周: " + body);
        assertTrue(body.indexOf("!skipTelemetry(toolName)") < body.indexOf("logToolCall("),
                "顺序反了：先写了行再判断跳过，等于没做");
        assertTrue(body.contains("callLogService.logToolCall(toolName, success, System.currentTimeMillis() - start, isPoll)"),
                "is_poll 那一版调用被改掉了（会丢 mail_start 的按行口径）");
    }

    @Test
    @DisplayName("历史行必须同时被统计口径排除，否则开关上线前那 4.5 万行继续当榜一")
    void historicalRowsAreStillExcludedBySql() {
        assertTrue(ToolPollPolicy.EXCLUDE_POLL_SQL.contains("'dispatch_list'"),
                "EXCLUDE_POLL_SQL 没排 dispatch_list：历史行仍在 7 天窗口里，榜一还是它");
        assertTrue(ToolPollPolicy.isStatsExcludedTool("dispatch_list"));
        assertFalse(ToolPollPolicy.isStatsExcludedTool("search_experience"));
    }

    @Test
    @DisplayName("不写埋点的工具不许进「从未被调用」清单（否则等于推荐精简一个每回合都调的工具）")
    void skippedToolsNeverAppearInZeroCallList() throws Exception {
        String svc = read("src/main/java/com/exphub/service/ToolStatsService.java");
        String zeroLoop = between(svc, "// 3. 零调用工具", "overview.put(\"zeroTools\"");
        assertTrue(zeroLoop.contains("ToolPollPolicy.isStatsExcludedTool(entry.getKey())"),
                "看板的零调用差集没跳过 dispatch_list: " + zeroLoop);

        String tools = read("src/main/java/com/exphub/mcp/ExpHubTools.java");
        String neverCalled = between(tools, "java.util.List<String> neverCalled", "sb.append(\"\\n**从未被调用");
        assertTrue(neverCalled.contains("ToolPollPolicy.isStatsExcludedTool(t)"),
                "get_tool_stats 的 neverCalled 没跳过 dispatch_list: " + neverCalled);
        assertTrue(neverCalled.contains("telemetryPolicy.isSkipped(t)"),
                "配置里新增的跳过工具也必须自动生效，否则改配置还得改代码: " + neverCalled);
    }
}
