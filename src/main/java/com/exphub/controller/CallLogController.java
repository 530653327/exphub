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

    // 璁板綍璋冪敤鏃ュ織
    @PostMapping
    public R<CallLog> logCall(@RequestBody CallLog log) {
        CallLog result = callLogService.logCall(log);
        return R.ok(result);
    }

    // 鏌ヨ璋冪敤鏃ュ織
    @GetMapping
    public R<?> list(
            @RequestParam(required = false) Long docId,
            @RequestParam(required = false) String assistantId,
            @RequestParam(required = false) Boolean success,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size) {
        Page<CallLog> result = callLogService.list(docId, assistantId, success, page, size);
        Map<String, Object> data = new HashMap<String, Object>();
        data.put("total", result.getTotal());
        data.put("page", result.getCurrent());
        data.put("pageSize", result.getSize());
        data.put("list", result.getRecords());
        return R.ok(data);
    }
}