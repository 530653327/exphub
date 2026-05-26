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

    // 总览统计
    @GetMapping("/overview")
    public R<Map<String, Object>> overview() {
        Map<String, Object> data = callLogService.getOverview();
        
        // 文档总数
        long totalDocs = docMapper.selectCount(null);
        data.put("totalDocs", totalDocs);
        
        // 今日新增
        // (简化：查询更新时间为今天的文档)
        
        // 助手总数
        data.put("totalAssistants", assistantService.count());
        
        // 有问题文档
        data.put("problemDocs", docService.countProblemDocs());
        
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