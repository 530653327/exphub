package com.exphub.controller;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.exphub.common.R;
import com.exphub.entity.CallLog;
import com.exphub.service.CallLogService;
import org.springframework.beans.factory.annotation.Autowired;
import java.util.HashMap;
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
        CallLog result = callLogService.logSearch(keyword, hitCount);
        return R.ok(result);
    }

    // 查询调用日志
    @GetMapping
    public R<?> list(
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size) {
        Page<CallLog> result = callLogService.list(page, size);
        Map<String, Object> data = new HashMap<>();
        data.put("total", result.getTotal());
        data.put("totalPages", result.getPages());
        data.put("page", result.getCurrent());
        data.put("pageSize", result.getSize());
        data.put("list", result.getRecords());
        return R.ok(data);
    }
}