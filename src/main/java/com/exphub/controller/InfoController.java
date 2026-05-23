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
            "search_experience: 搜索经验库中相关经验",
            "get_experience_detail: 获取经验的详细内容",
            "get_template: 获取创建经验的模板格式",
            "create_experience: 创建新经验记录"
        });

        // 使用指南 - 让AI在连接后第一时间读取
        info.put("usageGuidelines", new String[]{
            "1. **首次连接必须**：先调用 get_experience_detail 工具查看 ID=4 的《ExpHub使用指南》经验",
            "2. 开始任务前：用 search_experience 搜索是否已有相关经验可借鉴",
            "3. 完成任务后：用 create_experience 将学到的新方法记录下来",
            "4. 发现错误时：用 create_experience 创建新版本或更新已有经验"
        });

        // 快速开始 - 简明步骤
        info.put("quickStart", Map.of(
            "step1", "调用 get_experience_detail(id=4) 获取详细使用指南",
            "step2", "调用 search_experience 搜索当前任务相关的经验",
            "step3", "完成任务后调用 create_experience 记录新学到的方法"
        ));

        // 重要提示
        info.put("important", "请务必先阅读 ID=4 的使用指南经验，了解如何在ExpHub中记录和管理经验！");

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
