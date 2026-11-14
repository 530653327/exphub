package com.exphub.service;

import com.exphub.mapper.CallLogMapper;
import com.exphub.mcp.McpToolRegistry;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * MCP 工具使用统计。数据来源：call_logs 中 action='TOOL' 的行（由 ToolCallAspect 写入）。
 *
 * 核心价值：把「已注册但零调用」的工具显式列出来——这是判断能否精简工具的依据。
 * 只看有调用的工具永远发现不了僵尸工具，必须拿 McpToolRegistry（代码里真实存在的工具）
 * 和 call_logs（实际被调用的工具）做差集。
 */
@Service
public class ToolStatsService {

    /** 低频阈值：窗口内调用数不超过该值即视为低频（提示观察，不建议直接删） */
    private static final int LOW_FREQ_THRESHOLD = 3;

    @Autowired
    private CallLogMapper callLogMapper;

    @Autowired
    private McpToolRegistry toolRegistry;

    /**
     * 看板全量数据。
     *
     * @param days 统计窗口天数
     */
    public Map<String, Object> getDashboard(int days) {
        Map<String, Object> out = new HashMap<>();

        // 1. 总览
        Map<String, Object> overview = callLogMapper.getToolOverview();
        if (overview == null) {
            overview = new HashMap<>();
        }
        long total = toLong(overview.get("total"));
        long today = toLong(overview.get("today"));
        long successCalls = toLong(overview.get("successCalls"));
        long activeTools = toLong(overview.get("activeTools"));
        int registrySize = toolRegistry.size();

        overview.put("registrySize", registrySize);
        overview.put("zeroTools", Math.max(0, registrySize - activeTools));
        overview.put("successRate", total > 0 ? Math.round(successCalls * 1000.0 / total) / 10.0 : 0.0);
        // 注册表里有、但按口径整体不计统计的工具（目前仅 mail_start）。
        // 不单独说清楚的话，「有过调用 x / 注册 y」会被读成「有工具零调用」，而零调用清单里又没有它。
        int pollExcludedTools = 0;
        for (String t : toolRegistry.getTools().keySet()) {
            if (ToolPollPolicy.isPollExcludedTool(t)) {
                pollExcludedTools++;
            }
        }
        overview.put("pollExcludedTools", pollExcludedTools);

        // 2. 工具使用榜（窗口内）
        List<Map<String, Object>> ranking = callLogMapper.getToolRanking(days);
        Set<String> called = new LinkedHashSet<>();
        long windowTotal = 0;
        for (Map<String, Object> row : ranking) {
            long calls = toLong(row.get("calls"));
            windowTotal += calls;
            called.add(String.valueOf(row.get("toolName")));
            long ok = toLong(row.get("successCalls"));
            row.put("successRate", calls > 0 ? Math.round(ok * 1000.0 / calls) / 10.0 : 0.0);
            // 状态标签：给看板做视觉标记
            String level;
            if (calls == 0) {
                level = "zero";
            } else if (calls <= LOW_FREQ_THRESHOLD) {
                level = "low";
            } else {
                level = "active";
            }
            row.put("level", level);
            // 时间统一格式化为字符串，避免模板处理 Timestamp 类型差异
            row.put("lastCallAtText", formatTime(row.get("lastCallAt")));
        }
        // 占比（用于表格里的条形图）
        for (Map<String, Object> row : ranking) {
            long calls = toLong(row.get("calls"));
            row.put("share", windowTotal > 0 ? Math.round(calls * 1000.0 / windowTotal) / 10.0 : 0.0);
        }

        // 3. 零调用工具（注册表 - 实际调用）
        List<Map<String, Object>> zeroTools = new ArrayList<>();
        for (Map.Entry<String, String> entry : toolRegistry.getTools().entrySet()) {
            // 按行排除轮询的工具整体不进使用榜，也不该被当成「零调用」——
            // 它恰恰是调用量最大的工具，列进精简候选会误导。
            if (ToolPollPolicy.isPollExcludedTool(entry.getKey())) {
                continue;
            }
            if (!called.contains(entry.getKey())) {
                Map<String, Object> m = new HashMap<>();
                m.put("toolName", entry.getKey());
                m.put("description", shorten(entry.getValue(), 60));
                zeroTools.add(m);
            }
        }

        // 零调用卡片的数字必须与下面清单同源：先减掉了轮询工具再算，避免「卡片写 1、清单 0 条」
        overview.put("zeroTools", zeroTools.size());

        // 4. 没注册但被调用过的工具（改名/已删的残留，说明统计或代码不一致）
        List<String> unknownCalled = new ArrayList<>();
        for (String name : called) {
            if (!toolRegistry.getTools().containsKey(name)) {
                unknownCalled.add(name);
            }
        }

        // 5. 被排除的轮询调用（页面与 get_tool_stats 共用同一句话）
        Map<String, Object> excluded = callLogMapper.getMailStartPollStats(days);
        if (excluded == null) {
            excluded = new HashMap<>();
        }
        long polls = toLong(excluded.get("polls"));
        long realCalls = toLong(excluded.get("realCalls"));
        long unclassified = toLong(excluded.get("unclassified"));
        excluded.put("polls", polls);
        excluded.put("realCalls", realCalls);
        excluded.put("unclassified", unclassified);
        excluded.put("since", LocalDateTime.now().minusDays(days)
                .format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")));
        excluded.put("text", ToolPollPolicy.explain(polls, realCalls, unclassified, days));

        out.put("overview", overview);
        out.put("ranking", ranking);
        out.put("zeroTools", zeroTools);
        out.put("unknownCalled", unknownCalled);
        out.put("trend", callLogMapper.getToolDailyTrend(days));
        out.put("byAssistant", callLogMapper.getToolByAssistant(days));
        out.put("excluded", excluded);
        out.put("days", days);
        out.put("lowFreqThreshold", LOW_FREQ_THRESHOLD);
        return out;
    }

    private static long toLong(Object v) {
        if (v == null) return 0L;
        if (v instanceof Number n) return n.longValue();
        try {
            return Long.parseLong(String.valueOf(v));
        } catch (NumberFormatException e) {
            return 0L;
        }
    }

    private static String shorten(String s, int max) {
        if (s == null) return "";
        return s.length() <= max ? s : s.substring(0, max) + "…";
    }

    /** 把 JDBC 返回的各种时间类型统一成 yyyy-MM-dd HH:mm；空值返回 "-" */
    private static String formatTime(Object v) {
        if (v == null) return "-";
        String s = String.valueOf(v);
        // java.sql.Timestamp -> "2026-10-24 20:30:00.0"
        if (s.length() >= 16) {
            return s.substring(0, 16);
        }
        return s;
    }
}
