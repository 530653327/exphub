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
            "搜索经验：查找已有的问题和解决方案",
            "创建经验：将学到的新方法记录下来",
            "更新经验：完善已有的经验内容",
            "版本历史：查看经验的所有修改记录"
        });

        // 使用指南
        info.put("usageGuidelines", new String[]{
            "1. 开始任务前，先搜索是否已有相关经验可借鉴",
            "2. 完成任务后，将学到的新方法记录到经验库",
            "3. 如果发现经验有错误或过时，及时更新",
            "4. 经验标题要简洁明确，内容要包含完整的问题描述和解决方案"
        });

        // 模板
        info.put("template", templateService.getDefault() != null ? 
            templateService.getDefault().getTemplateContent() : getDefaultTemplate());

        return info;
    }

    /**
     * 获取当前 API Key 的权限信息
     */
    @GetMapping("/info/me")
    public Map<String, Object> getMyInfo() {
        AiAssistant assistant = ApiKeyInterceptor.CURRENT_ASSISTANT.get();
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
