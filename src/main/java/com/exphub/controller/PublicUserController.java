package com.exphub.controller;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.exphub.common.R;
import com.exphub.entity.AiAssistant;
import com.exphub.entity.Doc;
import com.exphub.entity.PublicUser;
import com.exphub.interceptor.PortalAuthInterceptor;
import com.exphub.mapper.DocMapper;
import com.exphub.service.PublicUserService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.Map;

/**
 * 公开用户 API（门户注册/登录/Key 管理/我的经验）
 */
@RestController
@RequestMapping("/portal/api")
public class PublicUserController {

    private static final Logger log = LoggerFactory.getLogger(PublicUserController.class);

    @Autowired
    private PublicUserService publicUserService;

    @Autowired
    private DocMapper docMapper;

    /**
     * 邮箱注册
     */
    @PostMapping("/register")
    public R<Map<String, Object>> register(@RequestBody Map<String, String> body) {
        String email = body.get("email");
        String password = body.get("password");

        if (email == null || email.trim().isEmpty() || !email.contains("@")) {
            return R.fail("请输入有效的邮箱地址");
        }
        if (password == null || password.length() < 6) {
            return R.fail("密码至少 6 位");
        }

        try {
            PublicUser user = publicUserService.register(email.trim(), password);
            AiAssistant assistant = publicUserService.getAssistant(user.getId());

            Map<String, Object> data = new HashMap<>();
            data.put("email", user.getEmail());
            data.put("apiKey", user.getApiKey());
            data.put("assistantId", assistant != null ? assistant.getAssistantId() : null);
            data.put("displayName", user.getDisplayName());

            return R.ok(data, "注册成功");
        } catch (RuntimeException e) {
            return R.fail(e.getMessage());
        }
    }

    /**
     * 邮箱登录
     */
    @PostMapping("/login")
    public R<Map<String, Object>> login(@RequestBody Map<String, String> body) {
        String email = body.get("email");
        String password = body.get("password");

        if (email == null || email.trim().isEmpty()) {
            return R.fail("请输入邮箱");
        }
        if (password == null || password.isEmpty()) {
            return R.fail("请输入密码");
        }

        try {
            String token = publicUserService.login(email.trim(), password);
            PublicUser user = publicUserService.getByToken(token);
            AiAssistant assistant = publicUserService.getAssistant(user.getId());

            Map<String, Object> data = new HashMap<>();
            data.put("token", token);
            data.put("email", user.getEmail());
            data.put("displayName", user.getDisplayName());
            data.put("apiKey", user.getApiKey());
            data.put("maskedApiKey", maskKey(user.getApiKey()));
            data.put("assistantId", assistant != null ? assistant.getAssistantId() : null);
            data.put("totalCalls", assistant != null ? assistant.getTotalCalls() : 0);
            data.put("successCalls", assistant != null ? assistant.getSuccessCalls() : 0);
            data.put("enabled", assistant != null ? assistant.getEnabled() : true);

            return R.ok(data, "登录成功");
        } catch (RuntimeException e) {
            return R.fail(e.getMessage());
        }
    }

    /**
     * 获取当前用户信息
     */
    @GetMapping("/me")
    public R<Map<String, Object>> me() {
        PublicUser user = PortalAuthInterceptor.getCurrentPortalUser();
        if (user == null) return R.fail(401, "未登录");

        AiAssistant assistant = publicUserService.getAssistant(user.getId());

        Map<String, Object> data = new HashMap<>();
        data.put("email", user.getEmail());
        data.put("displayName", user.getDisplayName());
        data.put("apiKey", user.getApiKey());
        data.put("maskedApiKey", maskKey(user.getApiKey()));
        data.put("assistantId", assistant != null ? assistant.getAssistantId() : null);
        data.put("totalCalls", assistant != null ? assistant.getTotalCalls() : 0);
        data.put("successCalls", assistant != null ? assistant.getSuccessCalls() : 0);
        data.put("enabled", assistant != null ? assistant.getEnabled() : true);

        // 统计我的经验数量
        QueryWrapper<Doc> wrapper = new QueryWrapper<>();
        wrapper.eq("api_key", user.getApiKey());
        data.put("myDocCount", docMapper.selectCount(wrapper));

        return R.ok(data);
    }

    /**
     * 重置 API Key
     */
    @PostMapping("/reset-key")
    public R<Map<String, Object>> resetKey() {
        PublicUser user = PortalAuthInterceptor.getCurrentPortalUser();
        if (user == null) return R.fail(401, "未登录");

        try {
            String newKey = publicUserService.resetApiKey(user.getId());
            Map<String, Object> data = new HashMap<>();
            data.put("apiKey", newKey);
            data.put("maskedApiKey", maskKey(newKey));
            return R.ok(data, "API Key 已重置");
        } catch (RuntimeException e) {
            return R.fail(e.getMessage());
        }
    }

    /**
     * 退出登录
     */
    @PostMapping("/logout")
    public R<Void> logout() {
        PublicUser user = PortalAuthInterceptor.getCurrentPortalUser();
        if (user != null) {
            publicUserService.logout(user.getId());
        }
        return R.ok(null, "已退出");
    }

    /**
     * 我的经验列表
     */
    @GetMapping("/my-docs")
    public R<Map<String, Object>> myDocs(
            @RequestParam(required = false) String keyword,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size) {
        PublicUser user = PortalAuthInterceptor.getCurrentPortalUser();
        if (user == null) return R.fail(401, "未登录");

        Page<Doc> p = new Page<>(page, size);
        QueryWrapper<Doc> wrapper = new QueryWrapper<>();
        wrapper.eq("api_key", user.getApiKey());

        if (keyword != null && !keyword.trim().isEmpty()) {
            wrapper.and(w -> w.like("title", keyword.trim())
                    .or().like("content", keyword.trim())
                    .or().like("summary", keyword.trim())
                    .or().like("tags", keyword.trim()));
        }
        wrapper.orderByDesc("updated_at");

        Page<Doc> result = docMapper.selectPage(p, wrapper);
        Map<String, Object> data = new HashMap<>();
        data.put("records", result.getRecords());
        data.put("total", result.getTotal());
        data.put("currentPage", result.getCurrent());
        data.put("totalPages", result.getPages());

        return R.ok(data);
    }

    /**
     * 更新我的经验
     */
    @PutMapping("/my-docs/{id}")
    public R<Doc> updateMyDoc(@PathVariable Long id, @RequestBody Doc doc) {
        PublicUser user = PortalAuthInterceptor.getCurrentPortalUser();
        if (user == null) return R.fail(401, "未登录");

        Doc existing = docMapper.selectById(id);
        if (existing == null) return R.fail("经验不存在");
        if (!user.getApiKey().equals(existing.getApiKey())) return R.fail("无权操作此经验");

        doc.setId(id);
        doc.setApiKey(existing.getApiKey());
        doc.setAuthorId(existing.getAuthorId());
        doc.setAuthorName(existing.getAuthorName());
        doc.setVersion(existing.getVersion() != null ? existing.getVersion() + 1 : 1);
        doc.setCallCount(existing.getCallCount());
        doc.setSuccessCount(existing.getSuccessCount());
        doc.setFailCount(existing.getFailCount());
        doc.setRating(existing.getRating());
        doc.setRatingCount(existing.getRatingCount());
        docMapper.updateById(doc);

        return R.ok(docMapper.selectById(id), "更新成功");
    }

    /**
     * 更新我的经验状态
     */
    @PutMapping("/my-docs/{id}/status")
    public R<Doc> updateMyDocStatus(@PathVariable Long id, @RequestBody Map<String, String> body) {
        PublicUser user = PortalAuthInterceptor.getCurrentPortalUser();
        if (user == null) return R.fail(401, "未登录");

        Doc existing = docMapper.selectById(id);
        if (existing == null) return R.fail("经验不存在");
        if (!user.getApiKey().equals(existing.getApiKey())) return R.fail("无权操作此经验");

        String status = body.get("status");
        if (status == null || !status.matches("ACTIVE|COMPLETED|BROKEN|DEPRECATED")) {
            return R.fail("无效状态，可选值：ACTIVE/COMPLETED/BROKEN/DEPRECATED");
        }

        existing.setStatus(status);
        docMapper.updateById(existing);
        return R.ok(docMapper.selectById(id), "状态已更新");
    }

    /**
     * 删除我的经验
     */
    @DeleteMapping("/my-docs/{id}")
    public R<Void> deleteMyDoc(@PathVariable Long id) {
        PublicUser user = PortalAuthInterceptor.getCurrentPortalUser();
        if (user == null) return R.fail(401, "未登录");

        Doc existing = docMapper.selectById(id);
        if (existing == null) return R.fail("经验不存在");
        if (!user.getApiKey().equals(existing.getApiKey())) return R.fail("无权操作此经验");

        docMapper.deleteById(id);
        return R.ok(null, "已删除");
    }

    private String maskKey(String key) {
        if (key == null || key.length() < 12) return "****";
        return key.substring(0, 8) + "..." + key.substring(key.length() - 4);
    }
}
