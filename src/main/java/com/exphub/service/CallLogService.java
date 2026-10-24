package com.exphub.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.exphub.entity.AiAssistant;
import com.exphub.entity.CallLog;
import com.exphub.entity.Doc;
import com.exphub.mapper.AiAssistantMapper;
import com.exphub.mapper.CallLogMapper;
import com.exphub.mapper.DocMapper;
import com.exphub.interceptor.ApiKeyInterceptor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 调用日志服务。
 *
 * ⚠️ 铁律（2026-10-25 P0 之后确立）：**日志与统计都是副作用，任何失败都不允许影响业务返回。**
 * 曾经的故障：logSearch 在搜索主流程里，它的 insert 因 detail 超列宽抛异常，
 * 导致整个 search_experience 失败——用户拿不到搜索结果，日志也没写成，两头空。
 *
 * 现在的约定：
 * 1. 所有 insert 一律走 {@link CallLogWriter}（独立事务 + 截断 + 吞异常）；
 * 2. 所有 assistant 统计更新走 {@link #safeUpdateAssistant}（失败只记 warn）；
 * 3. 本类方法**不再标 @Transactional**——日志和业务本来就没有一致性要求，
 *    绑在一起只会让一方拖死另一方。需要原子性的业务在各自的 Service 里控制。
 */
@Service
public class CallLogService {

    private static final Logger log = LoggerFactory.getLogger(CallLogService.class);

    @Autowired
    private CallLogMapper callLogMapper;

    @Autowired
    private CallLogWriter callLogWriter;

    @Autowired
    private DocMapper docMapper;

    @Autowired
    private AiAssistantMapper assistantMapper;

    /**
     * 更新助手调用统计。失败只记 warn 不上抛——
     * 统计数字差一次没人会发现，但让它拖垮搜索就是事故。
     */
    private void safeUpdateAssistant(AiAssistant assistant) {
        if (assistant == null) {
            return;
        }
        try {
            assistant.setTotalCalls(nvl(assistant.getTotalCalls()) + 1);
            assistant.setSuccessCalls(nvl(assistant.getSuccessCalls()) + 1);
            assistant.setLastCallAt(LocalDateTime.now());
            assistantMapper.updateById(assistant);
        } catch (Exception e) {
            log.warn("助手调用统计更新失败（已忽略）: assistant={} err={}",
                    assistant.getAssistantName(), e.getMessage());
        }
    }

    private static int nvl(Integer v) {
        return v == null ? 0 : v;
    }

    /** 填充调用者身份；无助手上下文时记为后台管理 */
    private void fillCaller(CallLog entry) {
        AiAssistant assistant = ApiKeyInterceptor.getCurrentAssistant();
        if (assistant != null) {
            entry.setApiKey(assistant.getApiKey());
            entry.setCallerName(assistant.getAssistantName());
        } else {
            entry.setApiKey("MANUAL");
            entry.setCallerName("后台管理");
        }
    }

    // 系统自动记录调用日志（从拦截器获取调用者信息）
    public CallLog logSearch(String keyword, int hitCount, List<Doc> matchedDocs) {
        CallLog entry = new CallLog();
        entry.setAction("SEARCH");
        entry.setKeyword(keyword);
        entry.setHitCount(hitCount);

        // 记录匹配到的经验标题。拼接长度由 CallLogWriter 按列宽兜底截断，
        // 这里保留完整拼接意图，不再手工控制长度。
        if (matchedDocs != null && !matchedDocs.isEmpty()) {
            Doc top = matchedDocs.get(0);
            entry.setDocId(top.getId());
            entry.setDocTitle(top.getTitle());
            StringBuilder titles = new StringBuilder();
            for (int i = 0; i < Math.min(matchedDocs.size(), 10); i++) {
                if (i > 0) {
                    titles.append("；");
                }
                titles.append(matchedDocs.get(i).getTitle());
            }
            if (matchedDocs.size() > 10) {
                titles.append("…等").append(matchedDocs.size()).append("条");
            }
            entry.setDetail(titles.toString());
        }

        fillCaller(entry);
        safeUpdateAssistant(ApiKeyInterceptor.getCurrentAssistant());
        callLogWriter.write(entry);
        return entry;
    }

    // 记录创建经验
    public CallLog logCreate(Doc doc) {
        CallLog entry = new CallLog();
        entry.setAction("CREATE");
        entry.setDocId(doc.getId());
        entry.setDocTitle(doc.getTitle());
        entry.setDetail("创建新经验");

        fillCaller(entry);
        safeUpdateAssistant(ApiKeyInterceptor.getCurrentAssistant());
        callLogWriter.write(entry);
        return entry;
    }

    // 记录更新经验
    public CallLog logUpdate(Doc doc) {
        CallLog entry = new CallLog();
        entry.setAction("UPDATE");
        entry.setDocId(doc.getId());
        entry.setDocTitle(doc.getTitle());
        entry.setDetail("更新经验至 v" + doc.getVersion());

        fillCaller(entry);
        safeUpdateAssistant(ApiKeyInterceptor.getCurrentAssistant());
        callLogWriter.write(entry);
        return entry;
    }

    // 记录删除经验
    public CallLog logDelete(Long docId, String docTitle) {
        CallLog entry = new CallLog();
        entry.setAction("DELETE");
        entry.setDocId(docId);
        entry.setDocTitle(docTitle);
        entry.setDetail("删除经验");

        fillCaller(entry);
        safeUpdateAssistant(ApiKeyInterceptor.getCurrentAssistant());
        callLogWriter.write(entry);
        return entry;
    }

    // 记录 AI 反馈（经验是否有帮助）
    public CallLog logFeedback(Doc doc, boolean helpful, String feedback) {
        CallLog entry = new CallLog();
        entry.setAction(helpful ? "HELPFUL" : "NOT_HELPFUL");
        entry.setDocId(doc.getId());
        entry.setDocTitle(doc.getTitle());
        entry.setDetail(feedback != null ? feedback : "");

        fillCaller(entry);
        safeUpdateAssistant(ApiKeyInterceptor.getCurrentAssistant());
        callLogWriter.write(entry);
        return entry;
    }

    /**
     * 记录一次 MCP 工具调用（埋点，由 ToolCallAspect 在 @Tool 方法执行后调用）。
     *
     * 设计要点：
     * 1. 只写工具维度统计行（action=TOOL），**不**累加 ai_assistants.total_calls——
     *    后者已由 logSearch/logCreate/logUpdate 等业务方法按业务动作累加，这里再加会重复计数。
     * 2. 每次工具调用 +1 行，用于「哪个工具被用了多少次」，与业务日志（SEARCH/CREATE…）并存不冲突。
     * 3. 本方法自身异常必须被调用方吞掉：埋点失败不能影响工具正常返回。
     *    （切面里已有 try-catch；这里再走 CallLogWriter，双保险。）
     */
    public void logToolCall(String toolName, boolean success, long costMs) {
        if (toolName == null || toolName.isEmpty()) {
            return;
        }
        CallLog entry = new CallLog();
        entry.setAction("TOOL");
        entry.setToolName(toolName);
        entry.setCostMs((int) Math.min(costMs, Integer.MAX_VALUE));
        entry.setSuccess(success ? 1 : 0);
        entry.setDetail(success ? "成功" : "失败");

        fillCaller(entry);
        callLogWriter.write(entry);
    }

    // 记录失败调用（MCP/API 异常时递增 failCalls）
    public void incrementFailCalls() {
        AiAssistant assistant = ApiKeyInterceptor.getCurrentAssistant();
        if (assistant == null) {
            return;
        }
        try {
            assistant.setFailCalls(nvl(assistant.getFailCalls()) + 1);
            assistantMapper.updateById(assistant);
        } catch (Exception e) {
            log.warn("失败计数更新失败（已忽略）: assistant={} err={}",
                    assistant.getAssistantName(), e.getMessage());
        }
    }

    public Page<CallLog> list(int page, int size, String action, String callerName,
                              String apiKey, String keyword, String startTime, String endTime) {
        Page<CallLog> p = new Page<>(page, size);
        QueryWrapper<CallLog> wrapper = new QueryWrapper<>();

        if (action != null && !action.isEmpty()) {
            wrapper.eq("action", action);
        }
        if (callerName != null && !callerName.isEmpty()) {
            wrapper.like("caller_name", callerName);
        }
        if (apiKey != null && !apiKey.isEmpty()) {
            wrapper.like("api_key", apiKey);
        }
        if (keyword != null && !keyword.isEmpty()) {
            wrapper.like("keyword", keyword);
        }
        if (startTime != null && !startTime.isEmpty()) {
            wrapper.ge("created_at", startTime);
        }
        if (endTime != null && !endTime.isEmpty()) {
            wrapper.le("created_at", endTime);
        }

        wrapper.orderByDesc("created_at");
        return callLogMapper.selectPage(p, wrapper);
    }

    /**
     * 删除单条日志
     */
    @Transactional
    public boolean deleteLog(Long id) {
        return callLogMapper.deleteById(id) > 0;
    }

    /**
     * 批量删除日志
     */
    @Transactional
    public boolean deleteBatch(List<Long> ids) {
        if (ids == null || ids.isEmpty()) return false;
        return callLogMapper.deleteBatchIds(ids) > 0;
    }

    /**
     * 按条件清理日志（支持按时间范围清理）
     */
    @Transactional
    public int cleanLogs(String beforeTime) {
        QueryWrapper<CallLog> wrapper = new QueryWrapper<>();
        if (beforeTime != null && !beforeTime.isEmpty()) {
            wrapper.lt("created_at", beforeTime);
        }
        return callLogMapper.delete(wrapper);
    }

    public Map<String, Object> getStats() {
        Map<String, Object> stats = new HashMap<>();
        long total = callLogMapper.selectCount(null);
        stats.put("totalCalls", total);
        stats.put("totalDocs", docMapper.selectCount(null));
        return stats;
    }

    public Map<String, Object> getOverview() {
        Map<String, Object> stats = getStats();

        // 今日调用次数
        QueryWrapper<CallLog> todayWrapper = new QueryWrapper<>();
        todayWrapper.apply("DATE(created_at) = CURDATE()");
        long todayCalls = callLogMapper.selectCount(todayWrapper);
        stats.put("todayCalls", todayCalls);

        // 成功率（基于助手的成功/失败调用统计）
        double successRate = 0.0;
        List<AiAssistant> assistants = assistantMapper.selectList(null);
        long totalSuccess = assistants.stream().mapToLong(a -> nvl(a.getSuccessCalls())).sum();
        long totalFail = assistants.stream().mapToLong(a -> nvl(a.getFailCalls())).sum();
        long total = totalSuccess + totalFail;
        if (total > 0) {
            successRate = (double) totalSuccess / total;
        }
        stats.put("successRate", successRate);

        stats.put("problemDocs", docMapper.selectCount(new QueryWrapper<Doc>().eq("status", "BROKEN")));
        return stats;
    }

    public List<Map<String, Object>> getTopAssistants(int limit) {
        List<AiAssistant> assistants = assistantMapper.selectList(
            new QueryWrapper<AiAssistant>().orderByDesc("total_calls").last("LIMIT " + limit)
        );
        List<Map<String, Object>> result = new java.util.ArrayList<>();
        for (AiAssistant a : assistants) {
            Map<String, Object> m = new HashMap<>();
            m.put("assistantId", a.getAssistantId());
            m.put("assistantName", a.getAssistantName());
            m.put("totalCalls", a.getTotalCalls());
            m.put("successCalls", a.getSuccessCalls());
            m.put("failCalls", a.getFailCalls());
            result.add(m);
        }
        return result;
    }

    public List<Map<String, Object>> getCallTrend(int days) {
        return callLogMapper.getCallTrend(days);
    }

    public List<Map<String, Object>> getAssistantRanking(int limit) {
        return getTopAssistants(limit);
    }
}
