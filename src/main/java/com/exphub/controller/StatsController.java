package com.exphub.controller;

import com.exphub.common.R;
import com.exphub.entity.Doc;
import com.exphub.mapper.DocMapper;
import com.exphub.service.CallLogService;
import com.exphub.service.DocService;
import com.exphub.service.AiAssistantService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/stats")
public class StatsController {

    @Autowired
    private DocMapper docMapper;

    @Autowired
    private DocService docService;

    @Autowired
    private CallLogService callLogService;

    @Autowired
    private AiAssistantService assistantService;

    // 总览统计（2026-11-06 第一批：与仪表盘页面共用同一口径，避免接口/页面交叉造数）
    @GetMapping("/overview")
    public R<Map<String, Object>> overview() {
        Map<String, Object> data = callLogService.getOverview();

        // 知识型文档数（排除过程流水 project_progress/todo_list + 非 ACTIVE/COMPLETED）
        long totalDocs = docService.countKnowledgeDocs();
        data.put("totalDocs", totalDocs);

        // 活跃助手（enabled 且在用，排除空壳）
        data.put("totalAssistants", assistantService.countActive());

        // 有问题文档（BROKEN/待删/取代/flag/超期 综合口径）
        data.put("problemDocs", docService.countProblemDocs(90));
        data.put("problemDocsByType", docService.countProblemDocsByType(90));

        return R.ok(data);
    }

    // 热门文档 Top10
    @GetMapping("/hot-docs")
    public R<List<Map<String, Object>>> hotDocs(@RequestParam(defaultValue = "10") int limit) {
        List<Doc> docs = docMapper.selectList(
            new com.baomidou.mybatisplus.core.conditions.query.QueryWrapper<Doc>()
                .orderByDesc("call_count")
                .last("LIMIT " + limit)
        );
        
        return R.ok(docs.stream().map(doc -> {
            Map<String, Object> m = new HashMap<>();
            m.put("id", doc.getId());
            m.put("title", doc.getTitle());
            m.put("authorName", doc.getAuthorName());
            m.put("callCount", doc.getCallCount());
            m.put("successRate", doc.getCallCount() > 0 ? (double) doc.getSuccessCount() / doc.getCallCount() : 0);
            m.put("rating", doc.getRating());
            m.put("status", doc.getStatus());
            return m;
        }).collect(Collectors.toList()));
    }

    // 调用趋势
    @GetMapping("/call-trend")
    public R<List<Map<String, Object>>> callTrend(@RequestParam(defaultValue = "7") int days) {
        return R.ok(callLogService.getCallTrend(days));
    }

    // 助手贡献排行
    @GetMapping("/assistant-ranking")
    public R<List<Map<String, Object>>> assistantRanking(@RequestParam(defaultValue = "10") int limit) {
        return R.ok(callLogService.getAssistantRanking(limit));
    }
}