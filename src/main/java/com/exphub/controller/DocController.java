package com.exphub.controller;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.exphub.common.R;
import com.exphub.entity.AiAssistant;
import com.exphub.entity.Doc;
import com.exphub.entity.DocShare;
import com.exphub.entity.DocVersion;
import com.exphub.entity.User;
import com.exphub.interceptor.ApiKeyInterceptor;
import com.exphub.service.DocService;
import com.exphub.service.DocShareService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import jakarta.servlet.http.HttpSession;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/docs")
public class DocController {

    @Autowired
    private DocService docService;

    @Autowired
    private DocShareService docShareService;

    // 创建文档（需校验 canCreate 权限）
    @PostMapping
    public R<Doc> create(@RequestBody Doc doc) {
        AiAssistant assistant = ApiKeyInterceptor.getCurrentAssistant();
        if (assistant != null && !Boolean.TRUE.equals(assistant.getCanCreate())) {
            return R.fail(403, "该API Key没有创建经验的权限");
        }
        if (doc.getTitle() == null || doc.getTitle().isEmpty()) {
            return R.fail("标题不能为空");
        }
        if (doc.getContent() == null || doc.getContent().isEmpty()) {
            return R.fail("内容不能为空");
        }
        // 日志记录已在 DocService.create() 中自动完成
        Doc created = docService.create(doc);
        return R.ok(created);
    }

    // 全文检索（自动记录调用日志，需校验 canSearch 权限）
    @GetMapping("/search")
    public R<Map<String, Object>> search(
            @RequestParam(required = false) String q,
            @RequestParam(required = false) String templateType,
            @RequestParam(required = false) String includeStatus,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size) {
        AiAssistant assistant = ApiKeyInterceptor.getCurrentAssistant();
        if (assistant != null && !Boolean.TRUE.equals(assistant.getCanSearch())) {
            return R.fail(403, "该API Key没有查询经验的权限");
        }
        
        // 日志记录已在 DocService.search() 中自动完成
        Page<Doc> result = docService.search(q, templateType, includeStatus, page, size);
        
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
            m.put("templateType", doc.getTemplateType());
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

    // 问题文档（移动端 /m/problems 用）
    // 5 类的筛选条件集中在 DocService.problemWrapper，与 PC 的 /docs?problem= 同源 ——
    // 这样「看板上的数」和「点进来筛出的条数」不会各算各的。
    @GetMapping("/problems")
    public R<Map<String, Object>> problems(
            @RequestParam String type,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size) {
        if (!DocService.isProblemType(type)) {
            return R.fail("未知的问题类型：" + type);
        }
        Page<Doc> result = docService.listProblems(type, page, size);

        Map<String, Object> data = new java.util.HashMap<>();
        data.put("type", type);
        data.put("total", result.getTotal());
        data.put("page", result.getCurrent());
        data.put("size", result.getSize());
        data.put("pages", result.getPages());
        data.put("counts", docService.countProblemDocsByType(DocService.STALE_DAYS));
        data.put("staleDays", DocService.STALE_DAYS);
        data.put("list", result.getRecords().stream().map(doc -> {
            java.util.Map<String, Object> m = new java.util.LinkedHashMap<>();
            m.put("id", doc.getId());
            m.put("title", doc.getTitle());
            m.put("category", doc.getCategory());
            m.put("authorName", doc.getAuthorName());
            m.put("summary", doc.getSummary());
            m.put("status", doc.getStatus());
            m.put("flags", doc.getFlags());
            m.put("deleteReason", doc.getDeleteReason());
            m.put("lastVerifiedAt", doc.getLastVerifiedAt());
            m.put("supersededBy", doc.getSupersededBy());
            m.put("updatedAt", doc.getUpdatedAt());
            m.put("contentLength", doc.getContent() == null ? 0 : doc.getContent().length());
            return m;
        }).collect(Collectors.toList()));
        return R.ok(data);
    }

    // 待删队列（移动端 /m/pending 用）
    // 为什么单开一个端点而不复用 /search：search 是「按相关度找经验」，语义是检索；
    // 待删队列是「按时间列出等我确认的东西」，语义是清单。混在一起以后一定会有人给 search 加排序参数，
    // 然后两边的默认顺序互相打架。
    // ⚠️ 这里只读，不提供删除 —— 删除走 PageController 的 POST /docs/{id}/delete（Session 登录才可用），
    // 刻意不给 API Key 开物理删除的口子。
    @GetMapping("/pending")
    public R<Map<String, Object>> pendingDelete(
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size) {
        Page<Doc> result = docService.listByStatus(DocService.PENDING_DELETE, page, size);

        Map<String, Object> data = new java.util.HashMap<>();
        data.put("total", result.getTotal());
        data.put("page", result.getCurrent());
        data.put("pageSize", result.getSize());
        data.put("pages", result.getPages());
        data.put("list", result.getRecords().stream().map(doc -> {
            java.util.Map<String, Object> m = new java.util.LinkedHashMap<>();
            m.put("id", doc.getId());
            m.put("title", doc.getTitle());
            m.put("category", doc.getCategory());
            m.put("authorName", doc.getAuthorName());
            m.put("summary", doc.getSummary());
            m.put("templateType", doc.getTemplateType());
            m.put("deleteReason", doc.getDeleteReason());
            m.put("markedBy", doc.getDeleteMarkedBy());
            m.put("markedAt", doc.getDeleteMarkedAt());
            m.put("updatedAt", doc.getUpdatedAt());
            // 正文长度：手机上一屏放不下正文，先让人知道这是 800 字还是 2 万字，再决定点不点开
            m.put("contentLength", doc.getContent() == null ? 0 : doc.getContent().length());
            return m;
        }).collect(Collectors.toList()));
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

    // 更新文档（需校验 canUpdate 权限）
    @PutMapping("/{id}")
    public R<Doc> update(@PathVariable Long id, @RequestBody Doc updateDoc) {
        AiAssistant assistant = ApiKeyInterceptor.getCurrentAssistant();
        if (assistant != null && !Boolean.TRUE.equals(assistant.getCanUpdate())) {
            return R.fail(403, "该API Key没有编辑经验的权限");
        }
        // 日志记录已在 DocService.update() 中自动完成
        Doc doc = docService.update(id, updateDoc);
        if (doc == null) {
            return R.fail(404, "文档不存在");
        }
        return R.ok(doc);
    }

    // 删除功能仅限后台管理员操作（PageController），REST API 不提供删除接口

    // 版本历史
    @GetMapping("/{id}/versions")
    public R<List<DocVersion>> versions(@PathVariable Long id) {
        return R.ok(docService.getVersions(id));
    }

    // 更新经验状态（ACTIVE / COMPLETED / BROKEN / DEPRECATED）
    @PutMapping("/{id}/status")
    public R<Doc> updateStatus(@PathVariable Long id, @RequestBody Map<String, String> body) {
        AiAssistant assistant = ApiKeyInterceptor.getCurrentAssistant();
        if (assistant != null && !Boolean.TRUE.equals(assistant.getCanUpdate())) {
            return R.fail(403, "该API Key没有编辑经验的权限");
        }
        String status = body.get("status");
        if (status == null || status.isEmpty()) {
            return R.fail("status 不能为空，可选值：ACTIVE、COMPLETED、BROKEN、DEPRECATED");
        }
        // 校验状态值
        if (!List.of("ACTIVE", "COMPLETED", "BROKEN", "DEPRECATED").contains(status.toUpperCase())) {
            return R.fail("无效的状态值，可选：ACTIVE、COMPLETED、BROKEN、DEPRECATED");
        }
        Doc doc = docService.updateStatus(id, status.toUpperCase());
        if (doc == null) {
            return R.fail(404, "文档不存在");
        }
        return R.ok(doc);
    }

    // 所有分类
    @GetMapping("/categories")
    public R<List<String>> categories() {
        return R.ok(docService.getCategories());
    }

    // ========== 分享链接管理（需登录） ==========

    /**
     * 创建分享链接
     * @param id 经验ID
     * @param body {days: 7} 过期天数，-1=永不过期，0=当天过期
     */
    @PostMapping("/{id}/shares")
    public R<Map<String, Object>> createShare(@PathVariable Long id,
                                               @RequestBody Map<String, Object> body,
                                               HttpSession session) {
        User user = (User) session.getAttribute("user");
        if (user == null) {
            return R.fail(401, "未登录");
        }
        Doc doc = docService.getById(id);
        if (doc == null) {
            return R.fail(404, "经验不存在");
        }

        int days = -1; // 默认永不过期
        if (body.containsKey("days")) {
            days = ((Number) body.get("days")).intValue();
        }

        String token = docShareService.createShare(id, days, user.getId());
        String shareUrl = "/share/" + token;

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("token", token);
        result.put("shareUrl", shareUrl);
        result.put("days", days);
        return R.ok(result);
    }

    /**
     * 列出经验的分享链接
     */
    @GetMapping("/{id}/shares")
    public R<List<DocShare>> listShares(@PathVariable Long id, HttpSession session) {
        User user = (User) session.getAttribute("user");
        if (user == null) {
            return R.fail(401, "未登录");
        }
        return R.ok(docShareService.listByDocId(id));
    }

    // ========== 分享链接删除（独立路径，跨经验） ==========

    /**
     * 删除分享链接
     */
    @DeleteMapping("/shares/{shareId}")
    public R<Void> deleteShare(@PathVariable Long shareId, HttpSession session) {
        User user = (User) session.getAttribute("user");
        if (user == null) {
            return R.fail(401, "未登录");
        }
        docShareService.deleteShare(shareId);
        return R.ok();
    }
}