package com.exphub.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 「排除条件只此一处」守卫（W-20261115-17）。
 *
 * <p>阁主验收第 7 条：排除条件做成常量/配置一处，别散在 SQL 和前端。
 * 这条守卫枚举**所有**工具维度聚合，逐个要求拼 {@link ToolPollPolicy#EXCLUDE_SQL}；
 * 漏一个，页面和工具的口径就会各说各话，而两边单独看都「有数字」。
 */
class ToolStatsPollExclusionTest {

    private static String read(String rel) throws Exception {
        Path p = Path.of(rel);
        assertTrue(Files.exists(p), "找不到源码：" + p.toAbsolutePath());
        return new String(Files.readAllBytes(p), StandardCharsets.UTF_8);
    }

    private static String between(String code, String from, String to) {
        int a = code.indexOf(from);
        int b = code.indexOf(to, a + 1);
        assertTrue(a >= 0, "找不到起止标记：" + from);
        assertTrue(b > a, "找不到结束标记：" + to + "（起点 " + from + "）");
        return code.substring(a, b);
    }

    @Test
    @DisplayName("call_logs 的所有工具聚合都拼同一个排除片段")
    void everyToolAggregationUsesSharedExclusion() throws Exception {
        String mapper = read("src/main/java/com/exphub/mapper/CallLogMapper.java");

        // 注意：@Select SQL 在方法签名**之前**，所以区间要从前一个方法签名算起。
        assertTrue(mapper.substring(0, mapper.indexOf("getCallTrend(@Param")).contains("ToolPollPolicy.EXCLUDE_POLL_SQL"),
                "仪表盘趋势 getCallTrend 没排除轮询：趋势线会和榜单对不上");
        assertTrue(between(mapper, "getCallTrend(@Param", "getOverviewStats()")
                        .contains("ToolPollPolicy.EXCLUDE_POLL_SQL"),
                "仪表盘 totalTool/todayTool/toolSuccess 没排除轮询：两个页面的「总调用」又会出现互斥差");
        assertTrue(between(mapper, "getSearchHitStats(@Param", "getToolOverview()")
                        .contains("ToolPollPolicy.EXCLUDE_POLL_SQL"),
                "工具总览没排除轮询：累计调用、成功率、平均耗时都会被 19.8 万行拉偏");
        assertTrue(between(mapper, "getToolOverview()", "getToolRanking(@Param")
                        .contains("ToolPollPolicy.EXCLUDE_POLL_SQL"),
                "使用榜没排除轮询：榜一还是 mail_start");
        assertTrue(between(mapper, "getToolRanking(@Param", "getToolDailyTrend(@Param")
                        .contains("ToolPollPolicy.EXCLUDE_POLL_SQL"),
                "每日趋势没排除轮询");
        assertTrue(between(mapper, "getToolDailyTrend(@Param", "getToolByAssistant(@Param")
                        .contains("ToolPollPolicy.EXCLUDE_POLL_SQL"),
                "按助手分布没排除轮询：哨兵的调用会算到某个助手头上");
        assertTrue(between(mapper, "getToolByAssistant(@Param", "getMailStartPollStats(@Param")
                        .contains("is_poll"),
                "排除说明查询必须按 is_poll 分段，否则报不出轮询/真实读信各多少");
    }

    @Test
    @DisplayName("is_poll 由切面写入，且解析失败落 NULL 不抛异常")
    void aspectWritesPollFlagWithoutThrowing() throws Exception {
        String aspect = read("src/main/java/com/exphub/mcp/ToolCallAspect.java");
        assertTrue(aspect.contains("safeResolvePollFlag(toolName, pjp)"),
                "切面没有取 is_poll");
        assertTrue(aspect.contains("logToolCall(toolName, success, System.currentTimeMillis() - start, isPoll)"),
                "取到的 is_poll 没写进日志：列永远 NULL，等于没做");
        assertTrue(aspect.contains("catch (Exception e)") && aspect.contains("return null"),
                "参数解析异常必须吞掉落 NULL，埋点不许影响 mail_start 返回");

        String service = read("src/main/java/com/exphub/service/CallLogService.java");
        assertTrue(SourceScan.methodBody(service,
                        "public void logToolCall(String toolName, boolean success, long costMs, Integer isPoll)")
                        .contains("setIsPoll(isPoll)"),
                "CallLogService 没把 is_poll 交给实体，列会一直是 NULL");
    }

    @Test
    @DisplayName("页面与 get_tool_stats 共用同一句排除说明，并跳过 mail_start 的零调用差集")
    void pageAndToolShareTheSameSentence() throws Exception {
        String svc = read("src/main/java/com/exphub/service/ToolStatsService.java");
        assertTrue(svc.contains("getMailStartPollStats(days)"), "看板没取排除分段数");
        assertTrue(svc.contains("ToolPollPolicy.explain("), "看板自己拼了口径说明（必须共用 explain）");
        assertTrue(svc.contains("ToolPollPolicy.isPollExcludedTool(entry.getKey())"),
                "零调用差集没跳过 mail_start：调用量最大的工具会被列成「精简候选」");
        assertTrue(svc.contains("out.put(\"excluded\""), "看板返回体缺 excluded 段");

        String tools = read("src/main/java/com/exphub/mcp/ExpHubTools.java");
        String body = SourceScan.methodBody(tools, "public String getToolStats(");
        assertTrue(!body.isEmpty(), "getToolStats 方法体没取到（签名变了？）——守卫不能静默放行");
        assertTrue(body.contains("ToolPollPolicy.explain("), "MCP 工具自己拼了口径说明（必须共用 explain）");
        assertTrue(body.contains("ToolPollPolicy.isPollExcludedTool(t)"),
                "MCP 的 neverCalled 没跳过 mail_start：它会出现在「从未被调用」清单里");

        String page = read("src/main/resources/templates/tool-stats.html");
        assertTrue(page.contains("excluded.text"), "页面没渲染统一口径说明");
        assertTrue(page.contains("真实读信仍写日志"), "页面没说明真实读信仍计入");
    }

    @Test
    @DisplayName("迁移与建表脚本都带 is_poll 与轮询索引")
    void migrationAndInitCarryPollColumn() throws Exception {
        String migration = read("sql/migrations/migration_20261115a_call_logs_is_poll.sql");
        assertTrue(migration.contains("ADD COLUMN `is_poll`"), "迁移没加列");
        assertTrue(migration.contains("idx_tool_poll"), "迁移没加索引");
        assertTrue(migration.contains("call_logs_bak_20261115"), "ALTER 前没有备份");

        String init = read("sql/init.sql");
        assertTrue(init.contains("is_poll"), "init.sql 缺 is_poll：新装库会被旧结构挡住");
        assertTrue(init.contains("idx_tool_poll"), "init.sql 缺 idx_tool_poll");
    }
}
