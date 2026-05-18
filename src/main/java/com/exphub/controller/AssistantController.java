package com.exphub.controller;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.exphub.common.R;
import com.exphub.entity.AiAssistant;
import com.exphub.service.AiAssistantService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.Map;

@RestController
@RequestMapping("/api/assistants")
public class AssistantController {

    @Autowired
    private AiAssistantService assistantService;

    // 创建助手
    @PostMapping
    public R<AiAssistant> create(@RequestBody AiAssistant assistant) {
        if (assistant.getAssistantId() == null || assistant.getAssistantId().isEmpty()) {
            return R.fail("助手ID不能为空");
        }
        if (assistant.getAssistantName() == null || assistant.getAssistantName().isEmpty()) {
            return R.fail("助手名称不能为空");
        }
        AiAssistant created = assistantService.create(assistant);
        return R.ok(created);
    }

    // 列表
    @GetMapping
    public R<Map<String, Object>> list(
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size) {
        Page<AiAssistant> result = assistantService.list(page, size);
        Map<String, Object> data = new HashMap<>();
        data.put("total", result.getTotal());
        data.put("page", result.getCurrent());
        data.put("pageSize", result.getSize());
        data.put("list", result.getRecords().stream().map(a -> {
            Map<String, Object> m = new HashMap<>();
            m.put("id", a.getId());
            m.put("assistantId", a.getAssistantId());
            m.put("assistantName", a.getAssistantName());
            m.put("description", a.getDescription());
            m.put("apiKey", assistantService.maskApiKey(a.getApiKey()));
            m.put("enabled", a.getEnabled());
            m.put("totalCalls", a.getTotalCalls());
            m.put("successCalls", a.getSuccessCalls());
            m.put("failCalls", a.getFailCalls());
            m.put("lastCallAt", a.getLastCallAt());
            m.put("createdAt", a.getCreatedAt());
            return m;
        }).collect(java.util.stream.Collectors.toList()));
        return R.ok(data);
    }

    // 获取详情
    @GetMapping("/{id}")
    public R<AiAssistant> getById(@PathVariable Long id) {
        AiAssistant assistant = assistantService.getById(id);
        if (assistant == null) return R.fail(404, "助手不存在");
        return R.ok(assistant);
    }

    // 更新
    @PutMapping("/{id}")
    public R<AiAssistant> update(@PathVariable Long id, @RequestBody AiAssistant data) {
        AiAssistant updated = assistantService.update(id, data);
        if (updated == null) return R.fail(404, "助手不存在");
        return R.ok(updated);
    }

    // 重置 API Key
    @PostMapping("/{id}/reset-key")
    public R<AiAssistant> resetApiKey(@PathVariable Long id) {
        AiAssistant updated = assistantService.resetApiKey(id);
        if (updated == null) return R.fail(404, "助手不存在");
        return R.ok(updated);
    }

    // 删除
    @DeleteMapping("/{id}")
    public R<Void> delete(@PathVariable Long id) {
        assistantService.delete(id);
        return R.ok();
    }

    // 验证 API Key
    @GetMapping("/verify-key")
    public R<Map<String, Object>> verifyKey(@RequestParam String apiKey) {
        AiAssistant assistant = assistantService.getByApiKey(apiKey);
        if (assistant == null) {
            return R.fail(401, "API Key无效");
        }
        Map<String, Object> data = new HashMap<>();
        data.put("assistantId", assistant.getAssistantId());
        data.put("assistantName", assistant.getAssistantName());
        data.put("enabled", assistant.getEnabled());
        return R.ok(data);
    }
}