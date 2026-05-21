package com.exphub.mcp;

import com.exphub.entity.AiAssistant;
import com.exphub.entity.Doc;
import com.exphub.entity.DocTemplate;
import com.exphub.interceptor.ApiKeyInterceptor;
import com.exphub.service.DocService;
import com.exphub.service.DocTemplateService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * ExpHub MCP 工具类
 * 经验库服务，让AI助手可以搜索和记录经验
 */
@Component
public class ExpHubTools {

    private static final Logger log = LoggerFactory.getLogger(ExpHubTools.class);

    @Autowired
    private DocService docService;

    @Autowired
    private DocTemplateService templateService;

    /**
     * 获取当前调用者权限信息
     */
    private AiAssistant getCaller() {
        return ApiKeyInterceptor.getCurrentAssistant();
    }

    /**
     * 获取经验详情 - 首次使用必须调用
     * 重要提示：本MCP连接后，请立即调用 get_experience_detail(id=1) 查看《ExpHub使用指南》！
     */
    @Tool(name = "get_experience_detail", description = "获取经验的详细内容。首次连接MCP时必须先调用此工具查看ID=1的《ExpHub使用指南》！之后再根据任务需要调用此工具查看其他经验详情。")
    public String getExperienceDetail(
            @ToolParam(description = "经验ID。首次使用请传1查看使用指南，之后从搜索结果中获取") Long id) {
        
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
     * 搜索经验库
     */
    @Tool(name = "search_experience", description = "搜索经验库中相关经验。返回标题、摘要、标签等信息。在开始任务前使用此工具查找是否有可借鉴的经验。")
    public String searchExperience(
            @ToolParam(description = "搜索关键词，可以是问题描述、技术名词、标签等") String query) {
        
        // 权限验证
        AiAssistant assistant = getCaller();
        if (assistant != null && !Boolean.TRUE.equals(assistant.getCanSearch())) {
            return "❌ 权限不足：该API Key没有查询经验的权限";
        }
        
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
     * 获取创建经验模板
     */
    @Tool(name = "get_template", description = "【创建经验前必须调用】获取创建经验的完整指南，包括模板结构和元数据填写要求。")
    public String getTemplate() {
        DocTemplate template = templateService.getDefault();
        
        StringBuilder sb = new StringBuilder();
        
        if (template == null) {
            sb.append("# 经验模板：通用模板\n\n");
            sb.append("**环境字段**: 操作系统，运行环境\n\n");
            sb.append("## 填写指南\n");
            sb.append("请按以下格式记录经验，包括：环境信息、场景描述、问题描述、解决方案、示例代码和注意事项。\n\n");
            sb.append("## 模板结构\n");
            sb.append("## 环境信息\n- 操作系统：\n- 依赖条件：\n\n");
            sb.append("## 场景描述\n描述在什么情况下需要这个经验。\n\n");
            sb.append("## 问题描述\n具体要解决什么问题？\n\n");
            sb.append("## 解决方案\n详细的解决步骤和核心代码。\n\n");
            sb.append("## 示例\n```\n代码示例\n```\n\n");
            sb.append("## 注意事项\n容易出错的地方和需要注意的坑。\n");
        } else {
            sb.append("# 经验模板：").append(template.getName()).append("\n\n");
            sb.append("**环境字段**: ").append(template.getPlatformField()).append("\n\n");
            sb.append("## 填写指南\n").append(template.getInstruction()).append("\n\n");
            sb.append("## 模板结构\n").append(template.getTemplateContent()).append("\n");
        }

        // ⭐ 追加元数据填写指南（关键！）
        sb.append("\n---\n");
        sb.append("## ⚠️ 创建经验时必须填写的元数据\n\n");
        sb.append("创建经验时除了正文，必须填写以下元数据字段，否则经验搜索不到：\n\n");
        sb.append("1. **category（分类）** - 必填！如：服务器、开发、运维、部署、数据库、前端等\n");
        sb.append("2. **tags（标签）** - 必填！逗号分隔，至少3-5个关键词，如：Nginx,HTTPS,代理,配置\n");
        sb.append("3. **aliases（别名/同义词）** - 必填！逗号分隔，用户可能用不同称呼搜索，如：反向代理,reverse proxy,nginx proxy\n");
        sb.append("4. **summary（摘要）** - 必填！一句话概括，让其他AI快速判断是否相关\n\n");
        sb.append("**示例**：\n");
        sb.append("- title: \"Nginx HTTPS反向代理配置\"\n");
        sb.append("- category: \"服务器\"\n");
        sb.append("- tags: \"Nginx,HTTPS,反向代理,SSL,配置\"\n");
        sb.append("- aliases: \"nginx proxy,ssl termination,https proxy,加密代理\"\n");
        sb.append("- summary: \"Nginx配置HTTPS反向代理的完整步骤，解决证书配置和端口转发问题\"\n");

        return sb.toString();
    }

    /**
     * 创建新经验
     */
    @Tool(name = "create_experience", description = "创建新经验。【重要】创建前必须先调用 get_template 获取填写指南！必须填写 category、tags、aliases、summary 等元数据，否则其他AI无法搜索到你的经验。")
    public String createExperience(
            @ToolParam(description = "经验标题，简短明确") String title,
            @ToolParam(description = "经验正文，使用Markdown格式，按 get_template 返回的结构填写") String content,
            @ToolParam(description = "分类（必填），如：服务器、开发、运维、部署、数据库、前端等，如不确定可填 未分类") String category,
            @ToolParam(description = "标签（必填），逗号分隔，至少3-5个关键词，如：Nginx,HTTPS,代理，如不确定可填 待分类") String tags,
            @ToolParam(description = "别名/同义词（必填），逗号分隔，用户可能用不同关键词搜索，如：反向代理,reverse proxy，如不确定可填 无") String aliases,
            @ToolParam(description = "一句话摘要（必填），让其他AI快速判断是否相关") String summary) {
        
        try {
            log.info("ExpHubTools.createExperience: ENTRY - title={}, tags={}", title, tags);
            
            // 权限验证
            AiAssistant assistant = getCaller();
            log.info("ExpHubTools.createExperience: caller={}, canCreate={}", 
                assistant != null ? assistant.getAssistantId() : "NULL",
                assistant != null ? assistant.getCanCreate() : "N/A");
            
            if (assistant != null && !Boolean.TRUE.equals(assistant.getCanCreate())) {
                return "❌ 权限不足：该API Key没有创建经验的权限";
            }
            
            Doc doc = new Doc();
            doc.setTitle(title);
            doc.setContent(content);
            doc.setCategory(category != null && !category.isEmpty() ? category : "未分类");
            doc.setTags(tags != null ? tags : "");
            doc.setAliases(aliases != null ? aliases : "");
            doc.setSummary(summary != null ? summary : "");
            log.info("ExpHubTools.createExperience: Doc object before create: tags={}, aliases={}", 
                doc.getTags(), doc.getAliases());
            
            Doc created = docService.create(doc);
            
            StringBuilder result = new StringBuilder();
            result.append("✅ 经验创建成功！\n\n");
            result.append("**ID**: ").append(created.getId()).append("\n");
            result.append("**标题**: ").append(created.getTitle()).append("\n");
            if (tags != null && !tags.isEmpty()) {
                result.append("**标签**: ").append(tags).append("\n");
            }
            result.append("\n经验已保存，其他AI助手可以通过搜索找到这条经验。");
            return result.toString();
        } catch (Exception e) {
            log.error("ExpHubTools.createExperience: FAILED", e);
            return "创建失败: " + e.getClass().getName() + " - " + e.getMessage();
        }
    }

    /**
     * 更新经验
     */
    @Tool(name = "update_experience", description = "更新已有经验。当发现经验有错误或需要补充内容时，使用此工具更新。")
    public String updateExperience(
            @ToolParam(description = "经验ID（必填）。通过搜索或查看详情获取") Long id,
            @ToolParam(description = "经验标题，不需要修改时传空字符串即可") String title,
            @ToolParam(description = "经验正文，使用Markdown格式，不需要修改时传空字符串即可") String content,
            @ToolParam(description = "分类，如：服务器、开发、运维、部署等，不需要修改时传空字符串即可") String category,
            @ToolParam(description = "标签，逗号分隔，不需要修改时传空字符串即可") String tags,
            @ToolParam(description = "别名/同义词，逗号分隔，不需要修改时传空字符串即可") String aliases,
            @ToolParam(description = "一句话摘要，不需要修改时传空字符串即可") String summary) {
        
        try {
            log.info("ExpHubTools.updateExperience: ENTRY - id={}, title={}", id, title);
            
            // 权限验证
            AiAssistant assistant = getCaller();
            if (assistant != null && !Boolean.TRUE.equals(assistant.getCanUpdate())) {
                return "❌ 权限不足：该API Key没有编辑经验的权限";
            }
            
            // 检查经验是否存在
            Doc existing = docService.getById(id);
            if (existing == null) {
                return "❌ 未找到ID为" + id + "的经验";
            }
            
            Doc updateDoc = new Doc();
            updateDoc.setId(id);
            if (title != null && !title.isEmpty()) updateDoc.setTitle(title);
            if (content != null && !content.isEmpty()) updateDoc.setContent(content);
            if (category != null && !category.isEmpty()) updateDoc.setCategory(category);
            if (tags != null && !tags.isEmpty()) updateDoc.setTags(tags);
            if (aliases != null && !aliases.isEmpty()) updateDoc.setAliases(aliases);
            if (summary != null && !summary.isEmpty()) updateDoc.setSummary(summary);
            
            docService.update(id, updateDoc);
            
            return "✅ 经验更新成功！\n\n**ID**: " + id + "\n**新版本**: v" + (existing.getVersion() + 1) + "\n\n经验已更新，其他AI助手可以看到最新内容。";
        } catch (Exception e) {
            log.error("ExpHubTools.updateExperience: FAILED", e);
            return "更新失败: " + e.getClass().getName() + " - " + e.getMessage();
        }
    }
}
