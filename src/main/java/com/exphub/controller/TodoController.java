package com.exphub.controller;

import com.exphub.entity.Doc;
import com.exphub.service.DocService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.util.*;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/todos")
public class TodoController {

    @Autowired
    private DocService docService;

    private static final Logger log = LoggerFactory.getLogger(TodoController.class);

    /**
     * 拖放卡片后更新状态
     */
    @PutMapping("/{id}/status")
    public Map<String, Object> updateStatus(@PathVariable Long id, @RequestBody Map<String, String> body) {
        String status = body.get("status");
        Map<String, Object> result = new LinkedHashMap<>();
        try {
            if (status == null || (!"ACTIVE".equals(status) && !"COMPLETED".equals(status))) {
                result.put("success", false);
                result.put("message", "无效的状态值: " + status);
                return result;
            }
            docService.updateStatus(id, status);
            result.put("success", true);
            result.put("message", "状态已更新");
        } catch (Exception e) {
            log.error("更新待办状态失败 id={}", id, e);
            result.put("success", false);
            result.put("message", "更新失败: " + e.getMessage());
        }
        return result;
    }

    /**
     * 获取看板数据：查询所有 todo_list 类型的经验，按状态映射为看板列
     */
    @GetMapping("/kanban")
    public Map<String, Object> getKanbanBoard() {
        List<Doc> todos = docService.listByTemplateType("todo_list");

        List<Map<String, Object>> tasks = todos.stream().map(doc -> {
            Map<String, Object> task = new LinkedHashMap<>();
            task.put("id", doc.getId());
            task.put("title", doc.getTitle());
            task.put("desc", doc.getSummary() != null ? doc.getSummary() : "");
            task.put("assignee", doc.getAuthorName() != null ? doc.getAuthorName() : "管理员");
            task.put("category", doc.getCategory() != null ? doc.getCategory() : "通用");

            // 从标签中提取优先级
            String priority = "P3";
            if (doc.getTags() != null) {
                String tags = doc.getTags().toUpperCase();
                if (tags.contains("P0")) priority = "P0";
                else if (tags.contains("P1")) priority = "P1";
                else if (tags.contains("P2")) priority = "P2";
            }
            task.put("priority", priority);

            // 状态 → 看板列映射
            String column = "backlog";
            if ("ACTIVE".equals(doc.getStatus())) column = "todo";
            else if ("COMPLETED".equals(doc.getStatus())) column = "done";
            task.put("column", column);

            task.put("createdAt", doc.getCreatedAt() != null
                    ? doc.getCreatedAt().toString().substring(0, 10) : "");
            task.put("updatedAt", doc.getUpdatedAt() != null
                    ? doc.getUpdatedAt().toString().substring(0, 10) : "");

            return task;
        }).collect(Collectors.toList());

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("tasks", tasks);
        return result;
    }
}
