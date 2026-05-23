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
     * 🔔 检查待办事项 - 连接后优先调用
     * 重要提示：本MCP连接后，请优先调用 check_my_todos() 检查是否有待处理任务！
     */
    @Tool(name = "check_my_todos", description = "【连接后请优先调用】检查当前AI助手在ExpHub中记录的待办事项（todo_list类型），包括任务状态、截止日期等信息。每次会话开始时都应该调用此工具，确保不遗漏重要任务。")
    public String checkMyTodos() {
        try {
            AiAssistant assistant = getCaller();
            if (assistant == null) {
                return "⚠️ 无法识别调用者身份，请检查API Key配置。";
            }
            
            // 直接按 template_type 查询，不做全文搜索
            var todos = docService.listByTemplateType("todo_list");
            
            if (todos.isEmpty()) {
                return "📋 当前没有待办事项。\n\n你可以通过 create_experience（templateType=todo_list）创建新的待办任务。";
            }
            
            StringBuilder sb = new StringBuilder();
            sb.append("📋 待办事项（共 ").append(todos.size()).append(" 条）\n\n");
            sb.append("| # | ID | 标题 | 创建时间 | 更新时间 |\n");
            sb.append("|---|-----|------|----------|----------|\n");
            
            int i = 1;
            for (Doc todo : todos) {
                sb.append("| ").append(i++).append(" | ")
                  .append(todo.getId()).append(" | ")
                  .append(todo.getTitle()).append(" | ")
                  .append(todo.getCreatedAt() != null ? todo.getCreatedAt().toLocalDate() : "-").append(" | ")
                  .append(todo.getUpdatedAt() != null ? todo.getUpdatedAt().toLocalDate() : "-").append(" |\n");
            }
            
            sb.append("\n使用 get_experience_detail(id=?) 查看具体任务详情。");
            
            return sb.toString();
        } catch (Exception e) {
            log.error("ExpHubTools.checkMyTodos: FAILED", e);
            return "检查待办事项失败: " + e.getMessage();
        }
    }

    /**
     * 获取经验详情
     */
    @Tool(name = "get_experience_detail", description = "获取经验的详细内容。")
    public String getExperienceDetail(
            @ToolParam(description = "经验ID。") Long id) {
        
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
    @Tool(name = "search_experience", description = "搜索经验库中相关经验。返回标题、摘要、标签、模板类型等信息。在开始任务前使用此工具查找是否有可借鉴的经验。⚠️ 必须用完整的自然语言描述问题场景，例如：'Nginx HTTPS反向代理Spring Boot配置'，而不是只传单个词如'nginx'。多个关键词用空格分隔，会进行精确匹配。建议指定 templateType 缩小范围提高准确度。可选类型：problem_solution、knowledge_doc、todo_list、bug_fix、config_guide、how_to、schedule_plan。")
    public String searchExperience(
            @ToolParam(description = "完整的场景描述，用空格分隔多个关键词。例如：'Nginx 反向代理 HTTPS 配置'、'MySQL 慢查询 性能优化'、'Docker 部署 Spring Boot'。不要只传单个笼统的词如'nginx'") String query,
            @ToolParam(description = "指定搜索的模板类型，缩小搜索范围提高准确度。可选：problem_solution(问题解决方案)、knowledge_doc(知识文档)、todo_list(待办事项)、bug_fix(Bug修复记录)、config_guide(配置指南)、how_to(操作指南)、schedule_plan(计划排期)。不传则搜索所有类型。") String templateType) {
        
        // 权限验证
        AiAssistant assistant = getCaller();
        if (assistant != null && !Boolean.TRUE.equals(assistant.getCanSearch())) {
            return "❌ 权限不足：该API Key没有查询经验的权限";
        }
        
        var result = docService.search(query, templateType, 1, 100);
        var docs = result.getRecords();
        
        String filterInfo = (templateType != null && !templateType.isEmpty()) ? "【类型过滤: " + templateType + "】" : "";
        
        if (docs.isEmpty()) {
            return filterInfo + "未找到\"" + query + "\"相关的经验。\n\n提示：\n1. 尝试使用更通用的关键词\n2. 尝试不指定 templateType 扩大搜索范围\n3. 使用英文关键词搜索\n4. 如果是新技术，可能需要创建新经验";
        }

        StringBuilder sb = new StringBuilder();
        sb.append(filterInfo).append("找到 ").append(docs.size()).append(" 条相关经验：\n\n");
        
        int i = 1;
        for (Doc doc : docs) {
            sb.append(i++).append(". 【ID:").append(doc.getId()).append("】").append(doc.getTitle()).append("\n");
            String typeLabel = getTemplateTypeLabel(doc.getTemplateType());
            sb.append("   类型: ").append(typeLabel).append(" | 分类: ").append(doc.getCategory()).append(" | 调用: ").append(doc.getCallCount()).append("次\n");
            sb.append("   摘要: ").append(doc.getSummary() != null ? doc.getSummary() : "无").append("\n");
            sb.append("   标签: ").append(doc.getTags() != null ? doc.getTags() : "无").append("\n");
            sb.append("   作者: ").append(doc.getAuthorName()).append(" | 更新: ").append(doc.getUpdatedAt()).append("\n\n");
        }

        return sb.toString();
    }
    
    private String getTemplateTypeLabel(String type) {
        if (type == null) return "通用";
        return switch (type) {
            case "problem_solution" -> "🛠️ 问题解决方案";
            case "knowledge_doc" -> "📚 知识文档";
            case "todo_list" -> "✅ 待办事项";
            case "bug_fix" -> "🐛 Bug修复";
            case "config_guide" -> "⚙️ 配置指南";
            case "how_to" -> "📖 操作指南";
            case "schedule_plan" -> "📅 计划排期";
            default -> "📄 " + type;
        };
    }

    /**
     * 获取创建经验模板
     */
    @Tool(name = "get_template", description = "【创建经验前必须调用】获取经验模板。不传type时返回所有可用模板列表（含适用场景），传type时返回指定模板的完整结构和填写指南。")
    public String getTemplate(
            @ToolParam(description = "模板类型标识，如：problem_solution、knowledge_doc、todo_list。传空字符串则返回所有模板列表。") String type) {
        
        // 不传 type → 列出所有可用模板
        if (type == null || type.isEmpty()) {
            var templates = templateService.listAll();
            if (templates.isEmpty()) {
                return "# 可用模板\n\n暂无可选模板，请按通用格式创建经验。";
            }
            StringBuilder sb = new StringBuilder();
            sb.append("# 可用经验模板（共 ").append(templates.size()).append(" 种）\n\n");
            sb.append("请根据你要记录的内容类型选择合适的模板，然后用对应的 type 再次调用 get_template 获取详细格式：\n\n");
            for (DocTemplate t : templates) {
                sb.append("## ").append(t.getName()).append("\n");
                sb.append("- **type**: `").append(t.getType()).append("`\n");
                sb.append("- **适用场景**: ").append(t.getDescription()).append("\n\n");
            }
            sb.append("---\n");
            sb.append("选定模板后，调用 `get_template(type=\"你选的type\")` 获取完整的填写指南。");
            return sb.toString();
        }
        
        // 传了 type → 返回指定模板的完整内容
        DocTemplate template = templateService.getByType(type);
        
        StringBuilder sb = new StringBuilder();
        
        if (template == null) {
            sb.append("# 未找到模板类型: ").append(type).append("\n\n");
            sb.append("可用的模板类型：\n");
            var all = templateService.listAll();
            for (DocTemplate t : all) {
                sb.append("- `").append(t.getType()).append("` → ").append(t.getName()).append("\n");
            }
            return sb.toString();
        }
        
        sb.append("# ").append(template.getName()).append("\n\n");
        sb.append("**适用场景**: ").append(template.getDescription()).append("\n\n");
        sb.append("## 📝 填写指南\n").append(template.getInstruction()).append("\n\n");
        sb.append("## 📋 模板结构（请按此格式填写 content）\n").append(template.getTemplateContent()).append("\n");
        
        // ⭐ 追加元数据填写指南
        sb.append("\n---\n");
        sb.append("## ⚠️ 创建经验时必须填写的元数据\n\n");
        sb.append("调用 create_experience 时除了 content，必须填写以下字段：\n\n");
        sb.append("1. **templateType** - 本模板的 type 值：`").append(template.getType()).append("`\n");
        sb.append("2. **category** - 必填！如：服务器、开发、运维、部署、数据库、前端等\n");
        sb.append("3. **tags** - 必填！逗号分隔，至少3-5个关键词，如：Nginx,HTTPS,代理,配置\n");
        sb.append("4. **aliases** - 必填！逗号分隔，用户可能用不同称呼搜索，如：反向代理,reverse proxy\n");
        sb.append("5. **summary** - 必填！一句话概括，让其他AI快速判断是否相关\n\n");
        sb.append("**示例**：\n");
        sb.append("- title: \"Nginx HTTPS反向代理配置\"\n");
        sb.append("- templateType: \"").append(template.getType()).append("\"\n");
        sb.append("- category: \"服务器\"\n");
        sb.append("- tags: \"Nginx,HTTPS,反向代理,SSL,配置\"\n");
        sb.append("- aliases: \"nginx proxy,ssl termination,https proxy\"\n");
        sb.append("- summary: \"Nginx配置HTTPS反向代理的完整步骤\"\n");

        return sb.toString();
    }

    /**
     * 创建新经验
     */
    @Tool(name = "create_experience", description = "创建新经验。【重要】创建前必须先调用 get_template() 选择模板！必须填写 category、tags、aliases、summary 等元数据，否则其他AI无法搜索到你的经验。")
    public String createExperience(
            @ToolParam(description = "经验标题，简短明确") String title,
            @ToolParam(description = "经验正文，使用Markdown格式，按 get_template 返回的结构填写") String content,
            @ToolParam(description = "模板类型标识（必填），从 get_template 返回的 type 值中选择，如：problem_solution、knowledge_doc、todo_list") String templateType,
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
            doc.setTemplateType(templateType != null && !templateType.isEmpty() ? templateType : "generic");
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
