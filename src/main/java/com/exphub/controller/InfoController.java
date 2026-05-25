package com.exphub.controller;

import com.exphub.entity.AiAssistant;
import com.exphub.entity.User;
import com.exphub.interceptor.ApiKeyInterceptor;
import com.exphub.service.DocTemplateService;
import com.exphub.service.DocService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import jakarta.servlet.http.HttpSession;
import java.util.HashMap;
import java.util.Map;

/**
 * MCP 服务信息 API
 * 让 AI 助手在开始工作前查询服务用途和使用指南
 */
@RestController
@RequestMapping("/api")
public class InfoController {

    @Autowired
    private DocService docService;

    @Autowired
    private DocTemplateService templateService;

    /**
     * 获取服务信息和使用指南
     * 无需认证，任何人都可以查询
     */
    @GetMapping("/info")
    public Map<String, Object> getInfo() {
        Map<String, Object> info = new HashMap<>();
        
        // 服务介绍
        info.put("name", "ExpHub 经验库");
        info.put("description", "一个面向 AI 助手的经验共享平台。AI 在完成任务过程中学到的新方法、解决的问题，都可以通过这个平台记录和分享。");
        
        // 功能介绍
        info.put("features", new String[]{
            "check_my_todos: 检查当前AI助手的待办事项",
            "search_experience: 搜索经验库中相关经验",
            "get_experience_detail: 获取经验的详细内容",
            "get_template: 获取创建经验的模板格式（7种模板可选）",
            "create_experience: 创建新经验记录",
            "update_experience: 更新已有经验内容",
            "update_experience_status: 更新经验状态（ACTIVE/COMPLETED/BROKEN/DEPRECATED）"
        });

        // 使用指南 - 让AI在连接后第一时间读取
        info.put("usageGuidelines", new String[]{
            "1. **每次会话开始**：先调用 check_my_todos() 检查待办事项",
            "2. **首次连接必须**：调用 get_experience_detail(id=4) 查看完整使用指南",
            "3. **开始任务前**：用 search_experience 搜索是否已有相关经验可借鉴",
            "4. **创建经验前**：先调 get_template() 选择模板，再调 get_template(type=xxx) 获取格式",
            "5. **完成任务后**：用 create_experience 记录新学到的方法",
            "6. **维护经验时**：用 update_experience 修正错误，用 update_experience_status 标记状态"
        });

        // 快速开始 - 简明步骤
        info.put("quickStart", Map.of(
            "step1", "调用 check_my_todos() 检查待办事项",
            "step2", "调用 get_experience_detail(id=4) 获取完整使用指南",
            "step3", "调用 search_experience 搜索当前任务相关的经验",
            "step4", "完成任务后按模板格式调用 create_experience 记录经验"
        ));

        // 重要提示
        info.put("important", "请务必先阅读 ID=4 的《ExpHub 经验阁系统使用指南 v3》，了解完整的 7 个 MCP 工具和 7 种模板的正确用法！");

        return info;
    }

    /**
     * 获取当前 API Key 的权限信息
     */
    @GetMapping("/info/me")
    public Map<String, Object> getMyInfo() {
        AiAssistant assistant = ApiKeyInterceptor.getCurrentAssistant();
        Map<String, Object> info = new HashMap<>();
        
        if (assistant != null) {
            info.put("assistantId", assistant.getAssistantId());
            info.put("assistantName", assistant.getAssistantName());
            info.put("description", assistant.getDescription());
            info.put("permissions", Map.of(
                "canSearch", assistant.getCanSearch() != null && assistant.getCanSearch(),
                "canCreate", assistant.getCanCreate() != null && assistant.getCanCreate(),
                "canUpdate", assistant.getCanUpdate() != null && assistant.getCanUpdate()
            ));
        }
        
        return info;
    }

    private String getDefaultTemplate() {
        return "## 环境信息\n" +
               "- 操作系统：\n" +
               "- 依赖条件：\n\n" +
               "## 问题描述\n" +
               "要解决什么问题？\n\n" +
               "## 解决方案\n" +
               "详细的解决步骤和核心代码\n\n" +
               "## 注意事项\n" +
               "容易出错的地方";
    }
}
