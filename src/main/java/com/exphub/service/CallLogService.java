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
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Service
public class CallLogService {

    @Autowired
    private CallLogMapper callLogMapper;

    @Autowired
    private DocMapper docMapper;

    @Autowired
    private AiAssistantMapper assistantMapper;

    // 系统自动记录调用日志（从拦截器获取调用者信息）
    @Transactional
    public CallLog logSearch(String keyword, int hitCount) {
        AiAssistant assistant = ApiKeyInterceptor.getCurrentAssistant();
        
        CallLog log = new CallLog();
        log.setAction("SEARCH");
        log.setKeyword(keyword);
        log.setHitCount(hitCount);
        
        if (assistant != null) {
            log.setApiKey(assistant.getApiKey());
            log.setCallerName(assistant.getAssistantName());
            
            // 更新助手调用统计
            assistant.setTotalCalls(assistant.getTotalCalls() + 1);
            assistant.setLastCallAt(LocalDateTime.now());
            assistantMapper.updateById(assistant);
        } else {
            // 后台管理操作
            log.setApiKey("MANUAL");
            log.setCallerName("后台管理");
        }
        
        callLogMapper.insert(log);
        return log;
    }

    // 记录创建经验
    @Transactional
    public CallLog logCreate(Doc doc) {
        AiAssistant assistant = ApiKeyInterceptor.getCurrentAssistant();
        
        CallLog log = new CallLog();
        log.setAction("CREATE");
        log.setDocId(doc.getId());
        log.setDocTitle(doc.getTitle());
        log.setDetail("创建新经验");
        
        if (assistant != null) {
            log.setApiKey(assistant.getApiKey());
            log.setCallerName(assistant.getAssistantName());
            
            assistant.setTotalCalls(assistant.getTotalCalls() + 1);
            assistant.setLastCallAt(LocalDateTime.now());
            assistantMapper.updateById(assistant);
        } else {
            log.setApiKey("MANUAL");
            log.setCallerName("后台管理");
        }
        
        callLogMapper.insert(log);
        return log;
    }

    // 记录更新经验
    @Transactional
    public CallLog logUpdate(Doc doc) {
        AiAssistant assistant = ApiKeyInterceptor.getCurrentAssistant();
        
        CallLog log = new CallLog();
        log.setAction("UPDATE");
        log.setDocId(doc.getId());
        log.setDocTitle(doc.getTitle());
        log.setDetail("更新经验至 v" + doc.getVersion());
        
        if (assistant != null) {
            log.setApiKey(assistant.getApiKey());
            log.setCallerName(assistant.getAssistantName());
            
            assistant.setTotalCalls(assistant.getTotalCalls() + 1);
            assistant.setLastCallAt(LocalDateTime.now());
            assistantMapper.updateById(assistant);
        } else {
            log.setApiKey("MANUAL");
            log.setCallerName("后台管理");
        }
        
        callLogMapper.insert(log);
        return log;
    }

    // 记录删除经验
    @Transactional
    public CallLog logDelete(Long docId, String docTitle) {
        AiAssistant assistant = ApiKeyInterceptor.getCurrentAssistant();
        
        CallLog log = new CallLog();
        log.setAction("DELETE");
        log.setDocId(docId);
        log.setDocTitle(docTitle);
        log.setDetail("删除经验");
        
        if (assistant != null) {
            log.setApiKey(assistant.getApiKey());
            log.setCallerName(assistant.getAssistantName());
            
            assistant.setTotalCalls(assistant.getTotalCalls() + 1);
            assistant.setLastCallAt(LocalDateTime.now());
            assistantMapper.updateById(assistant);
        } else {
            log.setApiKey("MANUAL");
            log.setCallerName("后台管理");
        }
        
        callLogMapper.insert(log);
        return log;
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
        long totalSuccess = assistants.stream().mapToLong(AiAssistant::getSuccessCalls).sum();
        long totalFail = assistants.stream().mapToLong(AiAssistant::getFailCalls).sum();
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
        return Collections.emptyList();
    }

    public List<Map<String, Object>> getAssistantRanking(int limit) {
        return getTopAssistants(limit);
    }
}
