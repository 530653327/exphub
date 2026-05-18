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

    @Autowired
    private DocService docService;

    @Transactional
    public CallLog logCall(CallLog log) {
        AiAssistant assistant = ApiKeyInterceptor.CURRENT_ASSISTANT.get();
        log.setAssistantId(assistant.getAssistantId());
        log.setAssistantName(assistant.getAssistantName());

        Doc doc = docMapper.selectById(log.getDocId());
        if (doc != null) {
            log.setDocTitle(doc.getTitle());
        }

        callLogMapper.insert(log);

        assistant.setTotalCalls(assistant.getTotalCalls() + 1);
        if (log.getSuccess()) {
            assistant.setSuccessCalls(assistant.getSuccessCalls() + 1);
            docService.updateCallResult(log.getDocId(), true);
        } else {
            assistant.setFailCalls(assistant.getFailCalls() + 1);
            docService.updateCallResult(log.getDocId(), false);
        }
        assistant.setLastCallAt(LocalDateTime.now());
        assistantMapper.updateById(assistant);

        if (log.getRating() != null) {
            docService.updateRating(log.getDocId(), log.getRating());
        }

        return log;
    }

    public Page<CallLog> list(Long docId, String assistantId, Boolean success, int page, int size) {
        Page<CallLog> p = new Page<>(page, size);
        QueryWrapper<CallLog> wrapper = new QueryWrapper<>();
        if (docId != null) wrapper.eq("doc_id", docId);
        if (assistantId != null && !assistantId.isEmpty()) wrapper.eq("assistant_id", assistantId);
        if (success != null) wrapper.eq("success", success);
        wrapper.orderByDesc("created_at");
        return callLogMapper.selectPage(p, wrapper);
    }

    public Map<String, Object> getStats() {
        Map<String, Object> stats = new HashMap<>();
        long total = callLogMapper.selectCount(null);
        stats.put("totalCalls", total);

        QueryWrapper<CallLog> successWrapper = new QueryWrapper<>();
        successWrapper.eq("success", true);
        long successCount = callLogMapper.selectCount(successWrapper);
        stats.put("successCalls", successCount);
        stats.put("failCalls", total - successCount);
        stats.put("successRate", total > 0 ? (double) successCount / total : 0.0);

        return stats;
    }

    public Map<String, Object> getOverview() {
        Map<String, Object> stats = getStats();
        stats.put("totalDocs", docService.countAll());
        stats.put("problemDocs", docService.countProblemDocs());
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
