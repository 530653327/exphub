package com.exphub.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.exphub.entity.CallLog;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;
import java.util.Map;

@Mapper
public interface CallLogMapper extends BaseMapper<CallLog> {

    @Select("SELECT DATE(created_at) as date, " +
            "COUNT(*) as total, " +
            "SUM(CASE WHEN action = 'SEARCH' THEN 1 ELSE 0 END) as searchCalls, " +
            "SUM(CASE WHEN action = 'CREATE' THEN 1 ELSE 0 END) as createCalls, " +
            "SUM(CASE WHEN action = 'UPDATE' THEN 1 ELSE 0 END) as updateCalls, " +
            "SUM(CASE WHEN action = 'DELETE' THEN 1 ELSE 0 END) as deleteCalls " +
            "FROM call_logs " +
            "WHERE created_at >= DATE_SUB(CURDATE(), INTERVAL #{days} DAY) " +
            "GROUP BY DATE(created_at) " +
            "ORDER BY date ASC")
    List<Map<String, Object>> getCallTrend(@Param("days") int days);

    // ===== 工具维度统计（action='TOOL'，由 ToolCallAspect 写入）=====

    /** 工具总览：总调用、今日、成功数、平均耗时、有调用的工具数 */
    @Select("SELECT COUNT(*) AS total, " +
            "SUM(CASE WHEN DATE(created_at) = CURDATE() THEN 1 ELSE 0 END) AS today, " +
            "SUM(CASE WHEN success = 1 THEN 1 ELSE 0 END) AS successCalls, " +
            "ROUND(AVG(cost_ms)) AS avgCostMs, " +
            "COUNT(DISTINCT tool_name) AS activeTools " +
            "FROM call_logs WHERE action = 'TOOL'")
    Map<String, Object> getToolOverview();

    /** 工具使用榜（按调用次数倒序） */
    @Select("SELECT tool_name AS toolName, COUNT(*) AS calls, " +
            "SUM(CASE WHEN success = 1 THEN 1 ELSE 0 END) AS successCalls, " +
            "ROUND(AVG(cost_ms)) AS avgCostMs, " +
            "MAX(cost_ms) AS maxCostMs, " +
            "MAX(created_at) AS lastCallAt, " +
            "COUNT(DISTINCT caller_name) AS callerCount " +
            "FROM call_logs " +
            "WHERE action = 'TOOL' AND tool_name IS NOT NULL " +
            "AND created_at >= DATE_SUB(NOW(), INTERVAL #{days} DAY) " +
            "GROUP BY tool_name ORDER BY calls DESC")
    List<Map<String, Object>> getToolRanking(@Param("days") int days);

    /** 每日工具调用趋势（含当日活跃工具数） */
    @Select("SELECT DATE(created_at) AS date, COUNT(*) AS calls, " +
            "COUNT(DISTINCT tool_name) AS tools " +
            "FROM call_logs WHERE action = 'TOOL' " +
            "AND created_at >= DATE_SUB(CURDATE(), INTERVAL #{days} DAY) " +
            "GROUP BY DATE(created_at) ORDER BY date ASC")
    List<Map<String, Object>> getToolDailyTrend(@Param("days") int days);

    /** 按调用者（助手）分布 */
    @Select("SELECT caller_name AS callerName, COUNT(*) AS calls, " +
            "SUM(CASE WHEN success = 1 THEN 1 ELSE 0 END) AS successCalls " +
            "FROM call_logs WHERE action = 'TOOL' " +
            "AND created_at >= DATE_SUB(NOW(), INTERVAL #{days} DAY) " +
            "GROUP BY caller_name ORDER BY calls DESC")
    List<Map<String, Object>> getToolByAssistant(@Param("days") int days);
}