package com.exphub.controller;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.exphub.common.R;
import com.exphub.entity.Doc;
import com.exphub.entity.DocVersion;
import com.exphub.service.DocService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/docs")
public class DocController {

    @Autowired
    private DocService docService;

    // 创建文档
    @PostMapping
    public R<Doc> create(@RequestBody Doc doc) {
        if (doc.getTitle() == null || doc.getTitle().isEmpty()) {
            return R.fail("标题不能为空");
        }
        if (doc.getContent() == null || doc.getContent().isEmpty()) {
            return R.fail("内容不能为空");
        }
        Doc created = docService.create(doc);
        return R.ok(created);
    }

    // 全文检索
    @GetMapping("/search")
    public R<Map<String, Object>> search(
            @RequestParam(required = false) String q,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size) {
        Page<Doc> result = docService.search(q, page, size);
        Map<String, Object> data = new java.util.HashMap<>();
        data.put("total", result.getTotal());
        data.put("page", result.getCurrent());
        data.put("pageSize", result.getSize());
        data.put("list", result.getRecords().stream().map(doc -> {
            java.util.Map<String, Object> m = new java.util.LinkedHashMap<>();
            m.put("id", doc.getId());
            m.put("title", doc.getTitle());
            m.put("category", doc.getCategory());
            m.put("authorName", doc.getAuthorName());
            m.put("summary", doc.getSummary());
            m.put("tags", doc.getTags());
            m.put("version", doc.getVersion());
            m.put("callCount", doc.getCallCount());
            m.put("successRate", doc.getCallCount() > 0 ? (double) doc.getSuccessCount() / doc.getCallCount() : 0);
            m.put("rating", doc.getRating());
            m.put("status", doc.getStatus());
            m.put("updatedAt", doc.getUpdatedAt());
            return m;
        }).collect(Collectors.toList()));
        return R.ok(data);
    }

    // 列表（按分类）
    @GetMapping
    public R<Map<String, Object>> list(
            @RequestParam(required = false) String category,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size) {
        Page<Doc> result = docService.listByCategory(category, page, size);
        Map<String, Object> data = new java.util.HashMap<>();
        data.put("total", result.getTotal());
        data.put("page", result.getCurrent());
        data.put("pageSize", result.getSize());
        data.put("list", result.getRecords());
        return R.ok(data);
    }

    // 获取详情
    @GetMapping("/{id}")
    public R<Doc> getById(@PathVariable Long id) {
        Doc doc = docService.getById(id);
        if (doc == null) {
            return R.fail(404, "文档不存在");
        }
        return R.ok(doc);
    }

    // 更新文档
    @PutMapping("/{id}")
    public R<Doc> update(@PathVariable Long id, @RequestBody Doc updateDoc) {
        Doc doc = docService.update(id, updateDoc);
        if (doc == null) {
            return R.fail(404, "文档不存在");
        }
        return R.ok(doc);
    }

    // 删除文档
    @DeleteMapping("/{id}")
    public R<Void> delete(@PathVariable Long id) {
        docService.delete(id);
        return R.ok();
    }

    // 版本历史
    @GetMapping("/{id}/versions")
    public R<List<DocVersion>> versions(@PathVariable Long id) {
        return R.ok(docService.getVersions(id));
    }

    // 所有分类
    @GetMapping("/categories")
    public R<List<String>> categories() {
        return R.ok(docService.getCategories());
    }
}