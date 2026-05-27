package com.exphub.controller;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.exphub.common.R;
import com.exphub.entity.CallLog;
import com.exphub.service.CallLogService;
import org.springframework.beans.factory.annotation.Autowired;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/logs")
public class CallLogController {

    @Autowired
    private CallLogService callLogService;

    // 系统自动记录搜索日志（由检索服务调用）
    @PostMapping
    public R<CallLog> logSearch(@RequestParam String keyword, @RequestParam int hitCount) {
        CallLog result = callLogService.logSearch(keyword, hitCount, null);
        return R.ok(result);
    }

    // 查询调用日志（支持多维度筛选）
    @GetMapping
    public R<?> list(
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "10") int size,
            @RequestParam(required = false) String action,
            @RequestParam(required = false) String callerName,
            @RequestParam(required = false) String apiKey,
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) String startTime,
            @RequestParam(required = false) String endTime) {
        Page<CallLog> result = callLogService.list(page, size, action, callerName, apiKey, keyword, startTime, endTime);
        Map<String, Object> data = new HashMap<>();
        data.put("total", result.getTotal());
        data.put("totalPages", result.getPages());
        data.put("page", result.getCurrent());
        data.put("pageSize", result.getSize());
        data.put("list", result.getRecords());
        return R.ok(data);
    }

    // 删除单条日志
    @DeleteMapping("/{id}")
    public R<Void> delete(@PathVariable Long id) {
        callLogService.deleteLog(id);
        return R.ok(null);
    }

    // 批量删除日志（body 传 id 列表）
    @DeleteMapping("/batch")
    public R<Void> deleteBatch(@RequestBody List<Long> ids) {
        callLogService.deleteBatch(ids);
        return R.ok(null);
    }

    // 按时间清理日志（删除指定时间之前的日志）
    @DeleteMapping("/clean")
    public R<Integer> clean(@RequestParam String beforeTime) {
        int deleted = callLogService.cleanLogs(beforeTime);
        return R.ok(deleted);
    }
}