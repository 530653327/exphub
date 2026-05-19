package com.exphub.mcp;

import com.exphub.entity.Doc;
import com.exphub.entity.DocTemplate;
import com.exphub.service.DocService;
import com.exphub.service.DocTemplateService;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.ai.mcp.tool.MethodToolCallbackProvider;

import java.util.List;

/**
 * ExpHub MCP Server 配置
 * 通过 SSE 模式暴露工具给所有 AI 助手
 */
@Configuration
public class McpServerConfig {

    @Autowired
    private DocService docService;

    @Autowired
    private DocTemplateService templateService;

    /**
     * 注册 MCP 工具
     */
    @Bean
    public MethodToolCallbackProvider methodToolCallbackProvider() {
        return MethodToolCallbackProvider.builder().build();
    }

    /**
     * 搜索经验库
     */
    @Tool(name = "search_experience", description = "搜索经验库中相关经验。返回标题、摘要、标签等信息。使用此工具在执行任务前查找是否有可借鉴的经验。")
    public String searchExperience(
            @ToolParam(description = "搜索关键词，可以是问题描述、技术名词、标签等") String query) {
        
        var result = docService.search(query, 1, 20);
        var docs = result.getRecords();
        
        if (docs.isEmpty()) {
            return "未找到\"" + query + "\"相关的经验。\n\n提示：\n1. 尝试使用更通用的关键词\n2. 使用英文关键词搜索\n3. 如果是新技术，可能需要创建新经验";
        }

        StringBuilder sb = new StringBuilder();
        sb.append("找到 ").append(docs.size()).append(" 条相关经验：\n\n");
        
        int i = 1;
        for (Doc doc : docs) {
            sb.append(i++).append(". 【ID:").append(doc.getId()).append("】").append(doc.getTitle()).append("\n");
            sb.append("   分类: ").append(doc.getCategory()).append(" | 调用: ").append(doc.getCallCount()).append("次\n");
            sb.append("   摘要: ").append(doc.getSummary() != null ? doc.getSummary() : "无").append("\n");
            sb.append("   标签: ").append(doc.getTags() != null ? doc.getTags() : "无").append("\n");
            sb.append("   作者: ").append(doc.getAuthorName()).append(" | 更新: ").append(doc.getUpdatedAt()).append("\n\n");
        }

        return sb.toString();
    }

    /**
     * 获取经验详情
     */
    @Tool(name = "get_experience_detail", description = "获取经验的详细内容，包括完整的问题描述和解决方案")
    public String getExperienceDetail(
            @ToolParam(description = "经验ID，从搜索结果中获取") Long id) {
        
        Doc doc = docService.getById(id);
        
        if (doc == null) {
            return "未找到ID为" + id + "的经验";
        }

        return "# " + doc.getTitle() + "\n\n" +
               "**分类**: " + doc.getCategory() + "\n" +
               "**作者**: " + doc.getAuthorName() + "\n" +
               "**版本**: v" + doc.getVersion() + "\n" +
               "**标签**: " + (doc.getTags() != null ? doc.getTags() : "无") + "\n\n" +
               "---\n\n" +
               doc.getContent();
    }

    /**
     * 获取创建经验模板
     */
    @Tool(name = "get_template", description = "获取创建经验的模板格式。创建新经验前应先获取此模板，确保格式正确。")
    public String getTemplate() {
        DocTemplate template = templateService.getDefault();
        
        if (template == null) {
            return "未找到默认模板，使用以下基本格式：\n\n" +
                   "```\n" +
                   "## 环境信息\n" +
                   "- 操作系统：\n" +
                   "- 依赖条件：\n\n" +
                   "## 问题描述\n" +
                   "要解决什么问题？\n\n" +
                   "## 解决方案\n" +
                   "详细的解决步骤和核心代码\n\n" +
                   "## 注意事项\n" +
                   "容易出错的地方\n" +
                   "```";
        }

        return "# 经验模板：" + template.getName() + "\n\n" +
               "**环境字段**: " + template.getPlatformField() + "\n\n" +
               "## 填写指南\n" + template.getInstruction() + "\n\n" +
               "## 模板结构\n" + template.getTemplateContent();
    }

    /**
     * 创建新经验
     */
    @Tool(name = "create_experience", description = "创建新经验。当完成任务后学到新方法时，将经验记录到系统中供其他AI助手学习。")
    public String createExperience(
            @ToolParam(description = "经验标题，简短明确") String title,
            @ToolParam(description = "经验正文，使用Markdown格式") String content,
            @ToolParam(description = "分类，如：服务器、开发、运维、部署等", required = false) String category,
            @ToolParam(description = "标签，逗号分隔", required = false) String tags,
            @ToolParam(description = "一句话摘要", required = false) String summary) {
        
        try {
            Doc doc = new Doc();
            doc.setTitle(title);
            doc.setContent(content);
            doc.setCategory(category != null ? category : "未分类");
            doc.setTags(tags != null ? tags : "");
            doc.setSummary(summary != null ? summary : "");
            
            Doc created = docService.create(doc);
            
            return "✅ 经验创建成功！\n\n**ID**: " + created.getId() + "\n**标题**: " + created.getTitle() + "\n\n经验已保存，其他AI助手可以通过搜索找到这条经验。";
        } catch (Exception e) {
            return "创建失败: " + e.getMessage();
        }
    }
}
