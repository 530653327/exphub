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

    @Autowired
    private com.exphub.service.ProjectService projectService;

    @Autowired
    private com.exphub.service.ReviewCandidateService reviewCandidateService;

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

        // 2026-11-08 移动端看板要「全面」，把 PC 仪表盘另外 4 个指标补进来。
        // 只加字段不改已有字段，PC 页面仍走自己的 PageController（不依赖本接口）。
        Map<String, Long> byType = docService.countProblemDocsByType(90);
        data.put("staleDocs", byType.getOrDefault("STALE", 0L));
        List<com.exphub.entity.Project> projects = projectService.listProjects();
        data.put("totalProjects", projects == null ? 0 : projects.size());
        data.put("projectDocs", docService.countProjectDocs());
        data.put("globalDocs", docService.countGlobalRegularDocs());
        data.put("candidatePending", reviewCandidateService.pendingCount());

        // 搜索零命中率：反映「搜不到」的严重程度，和热门榜是一对
        Map<String, Object> searchHit = callLogService.getSearchHitStats(30);
        data.put("searchTotal", searchHit.get("totalSearch"));
        data.put("searchZeroHit", searchHit.get("zeroHit"));
        Object zeroRate = searchHit.get("zeroRate");
        data.put("searchZeroRate", zeroRate == null ? 0.0
                : ((Number) zeroRate).doubleValue() * 100);

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