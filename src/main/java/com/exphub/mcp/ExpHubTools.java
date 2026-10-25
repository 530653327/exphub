package com.exphub.mcp;

import com.exphub.entity.AiAssistant;
import com.exphub.entity.Doc;
import com.exphub.entity.DocTemplate;
import com.exphub.entity.DocVersion;
import com.exphub.entity.Project;
import com.exphub.interceptor.ApiKeyInterceptor;
import com.exphub.service.CallLogService;
import com.exphub.service.DocService;
import com.exphub.service.ReviewCandidateService;
import com.exphub.entity.ReviewCandidate;
import com.exphub.service.SimilarityScorer;
import com.exphub.service.SimilarityService;
import com.exphub.service.DocTemplateService;
import com.exphub.service.ProjectService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

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

    @Autowired
    private CallLogService callLogService;

    @Autowired
    private ProjectService projectService;

    @Autowired
    private ReviewCandidateService reviewCandidateService;

    @Autowired
    private SimilarityService similarityService;

    /**
     * 获取当前调用者权限信息
     */
    private AiAssistant getCaller() {
        AiAssistant assistant = ApiKeyInterceptor.getCurrentAssistant();
        if (assistant == null) {
            // 身份缺失意味着隔离校验会被跳过，属安全相关，保留告警
            log.warn("getCaller: CURRENT_ASSISTANT is NULL, thread={}", Thread.currentThread().getName());
        }
        return assistant;
    }

    /**
     * 🔔 检查待办事项 - 连接后优先调用
     * 重要提示：本MCP连接后，请优先调用 check_my_todos() 检查是否有待处理任务！
     */
    @Tool(name = "check_my_todos", description = "【连接后请优先调用】检查当前AI助手在ExpHub中记录的待办事项（todo_list类型），包括任务状态、截止日期等信息。每次会话开始时都应该调用此工具，确保不遗漏重要任务。默认只看**全局未完成**（ACTIVE）的待办；要看某个项目的、或想看已完成/归档的，传 projectId / status。⚠️ 完成待办事项后，务必调用 update_experience_status 将状态更新为 COMPLETED。")
    public String checkMyTodos(
            @ToolParam(description = "可选。只看某个项目的待办，填项目ID（用 list_projects 查）。不传 或传 0 = 全局待办", required = false) Long projectId,
            @ToolParam(description = "可选。状态过滤，默认 ACTIVE（未完成）。可选 ACTIVE / COMPLETED / DEPRECATED / BROKEN，传 ALL 表示不限状态", required = false) String status) {
        try {
            AiAssistant assistant = getCaller();
            if (assistant == null) {
                return "⚠️ 无法识别调用者身份，请检查API Key配置。";
            }

            // 指定项目时先校验访问权限，避免用别人的 projectId 探到内容
            Long pid = null;
            if (projectId != null && projectId > 0) {
                if (projectService.getProject(projectId) == null) {
                    return "❌ 未找到项目ID " + projectId + "，或你没有访问权限。可用 list_projects 查看你有权访问的项目。";
                }
                pid = projectId;
            }

            // 直接按 template_type 查询，不做全文搜索
            var todos = docService.listByTemplateType("todo_list", pid, status);

            String scopeLabel = pid == null ? "全局" : ("项目 " + pid);
            String statusLabel = (status == null || status.trim().isEmpty()) ? "ACTIVE（未完成）"
                    : ("ALL".equalsIgnoreCase(status.trim()) ? "全部状态" : status.trim().toUpperCase());

            if (todos.isEmpty()) {
                return "📋 " + scopeLabel + " 范围、状态 " + statusLabel + " 下没有待办事项。\n\n"
                        + "可以用 create_experience（templateType=todo_list）创建，"
                        + "或换 projectId / status 再看（status=ALL 可看已完成的）。";
            }

            StringBuilder sb = new StringBuilder();
            sb.append("📋 待办事项 · ").append(scopeLabel).append(" · ").append(statusLabel)
              .append("（共 ").append(todos.size()).append(" 条）\n\n");
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
            
            sb.append("\n使用 get_experience_detail(id=?) 查看具体任务详情。\n");
            sb.append("⚠️ 重要提醒：完成任何待办事项后，请立即调用 update_experience_status(id=?, status=\"COMPLETED\") 标记为已完成，否则任务会一直显示在待办列表中。");
            
            return sb.toString();
        } catch (Exception e) {
            log.error("ExpHubTools.checkMyTodos: FAILED", e);
            callLogService.incrementFailCalls();
            return "检查待办事项失败: " + e.getMessage();
        }
    }

    /**
     * 获取经验详情
     */
    /**
     * 经验详情/历史版本单次读取上限（字符）。默认 20000，覆盖常规长文档；
     * 超过时提示使用 export_document 读取完整内容。
     */
    @Value("${exphub.mcp.detail-max-chars:20000}")
    private int detailMaxChars = 20000;

    @Tool(name = "get_experience_detail", description = "获取经验的详细内容。超长内容会在合理长度处截断；需要完整内容请使用 export_document。")
    public String getExperienceDetail(
            @ToolParam(description = "经验ID。") Long id) {

        Doc doc = docService.getById(id);

        if (doc == null) {
            return "未找到ID为" + id + "的经验";
        }

        StringBuilder sb = new StringBuilder();
        sb.append("# ").append(doc.getTitle()).append("\n\n");
        sb.append("**分类**: ").append(doc.getCategory()).append("\n");
        sb.append("**作者**: ").append(doc.getAuthorName()).append("\n");
        sb.append("**版本**: v").append(doc.getVersion()).append("\n");
        sb.append("**标签**: ").append(doc.getTags() != null ? doc.getTags() : "无").append("\n");
        // 存疑横幅：取代关系在前（比待复核更硬），标记在后；两块都在正文之前，
        // 避免 AI 读完长文才发现结论存疑。文案与 export_document 共用同一个方法，
        // 因为剥离器认的是这里的字面前缀 —— 两边各写一份，总有一天会对不上。
        sb.append(doubtBanner(doc, id));

        // 显示关联经验
        if (doc.getRelatedIds() != null && !doc.getRelatedIds().isEmpty()) {
            sb.append("**关联经验**: ").append(doc.getRelatedIds()).append("\n");
        }

        sb.append("\n---\n\n");

        // 📚 历史导读：有历史版本时自动引导 AI 查看历史版本了解来龙去脉
        List<DocVersion> versions = docService.getVersions(id);
        if (!versions.isEmpty()) {
            sb.append("> 📚 **历史导读**：本文档经历多次迭代，当前为 v").append(doc.getVersion())
              .append("，共保存 ").append(versions.size()).append(" 个历史版本（v1 ~ v")
              .append(doc.getVersion() - 1).append("）。\n");
            sb.append("> 如需了解本文档的**来龙去脉、演进过程或历史解决方案**，请调用\n");
            sb.append("> `get_experience_history(id=").append(id).append(")` 查看历史版本列表；\n");
            sb.append("> `get_experience_history(id=").append(id).append(", version=版本号)` 查看指定版本完整内容（如 version=1 可看最初版本）。\n\n");
            sb.append("---\n\n");
        }

        // 截断超长内容，防止 AI 助手输出超限
        String content = doc.getContent();
        boolean truncated = false;
        if (content != null && content.length() > detailMaxChars) {
            content = content.substring(0, detailMaxChars);
            truncated = true;
        }
        if (content != null) {
            sb.append(content);
        }
        if (truncated) {
            sb.append("\n\n> ⚠️ 内容过长已截断（仅显示开头部分）。如需完整内容请使用 export_document(id=")
                    .append(id)
                    .append(") 导出完整文档，或分段说明具体关注的部分。");
        }

        // 关联经验详情
        if (doc.getRelatedIds() != null && !doc.getRelatedIds().isEmpty()) {
            sb.append("\n\n---\n\n## 📎 关联经验\n\n");
            String[] ids = doc.getRelatedIds().split(",");
            for (String rid : ids) {
                rid = rid.trim();
                if (rid.isEmpty()) continue;
                try {
                    Doc related = docService.getById(Long.parseLong(rid));
                    if (related != null) {
                        sb.append("- **ID:").append(related.getId()).append("** ").append(related.getTitle());
                        sb.append("（").append(getStatusLabel(related.getStatus())).append("）\n");
                        sb.append("  摘要: ").append(related.getSummary() != null ? related.getSummary() : "无").append("\n");
                    }
                } catch (NumberFormatException ignored) {}
            }
        }

        return sb.toString();
    }

    /**
     * 剥离「读取端渲染产生的痕迹」——规则实现已下沉到
     * {@link com.exphub.util.RenderArtifactStripper#strip(String)}，
     * 供 MCP 工具层与 service 写入层共用（E13：service 路径原先漏网）。
     */
    static String stripRenderArtifacts(String content) {
        return com.exphub.util.RenderArtifactStripper.strip(content);
    }

    /**
     * 导出完整文档内容（无截断，适合长文档维护）
     */
    @Tool(name = "export_document", description = "【文档维护专用】将经验完整导出为Markdown内容（无字符限制截断）。适用于超长文档（如项目需求文档、规格说明书等）。返回的内容可以直接保存为本地.md文件进行编辑。短经验可直接使用 get_experience_detail 查看。")
    public String exportDocument(
            @ToolParam(description = "经验ID（必填）") Long id) {

        try {
            Doc doc = docService.getById(id);
            if (doc == null) {
                return "未找到ID为" + id + "的经验";
            }

            StringBuilder sb = new StringBuilder();
            sb.append("📄 文档导出成功！经验 ID:").append(id).append("「").append(doc.getTitle()).append("」\n\n");
            sb.append("内容已完整导出（共 ").append(doc.getContent() != null ? doc.getContent().length() : 0).append(" 字）。\n\n");
            // 存疑横幅（D1）：export 是长文读取的主路径，横幅必须也出现在这里。
            // 只加在 detail 上等于漏了最该提醒的场景 —— 长文档恰恰是最容易被整篇写回的那类。
            sb.append(doubtBanner(doc, id));
            sb.append("---\n\n");

            String content = doc.getContent();
            if (content != null) {
                sb.append(content);
            }

            sb.append("\n\n---\n").append("📌 文档结尾");

            return sb.toString();
        } catch (Exception e) {
            log.error("ExpHubTools.exportDocument: FAILED", e);
            callLogService.incrementFailCalls();
            return "导出文档失败: " + e.getMessage();
        }
    }

    /**
     * 导入完整文档内容（信任式，无截断检测，与 export_document 配对使用）
     */
    @Tool(name = "import_document", description = "【文档维护专用】将本地编辑好的完整Markdown内容上传并替换指定经验的内容。与 export_document 配对使用。上传后自动创建版本历史快照。此工具不做内容截断检测，适合用于大型文档的全量替换。对于小修改，仍可使用 update_experience。")
    public String importDocument(
            @ToolParam(description = "经验ID（必填）") Long id,
            @ToolParam(description = "完整的Markdown正文内容，将替换整个经验内容") String content,
            @ToolParam(description = "可选。**改文档作者**（作品归属），不是操作人署名。如果你只想记「谁改的」而不动归属，请用 updatedName", required = false) String authorName,
            @ToolParam(description = "可选。**谁改的**——操作人署名（正式名，建议统一用这个）。与 `authorName` 等价，两者都传时以本参数为准", required = false) String updatedName) {

        try {
            log.info("ExpHubTools.importDocument: ENTRY - id={}, contentLength={}", id, content != null ? content.length() : 0);

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
            // E11：导入前剥离渲染痕迹（历史导读/关联经验/文档结尾/终端残留）
            String cleaned = stripRenderArtifacts(content);
            updateDoc.setContent(cleaned);
            if (authorName != null && !authorName.isEmpty()) updateDoc.setAuthorName(authorName);

            Doc updated = docService.update(id, updateDoc, java.util.Set.of(), updatedName);

            int stripped = content.length() - cleaned.length();
            StringBuilder sb = new StringBuilder();
            sb.append("✅ 文档导入成功！\n\n**ID**: ").append(id)
              .append("\n**标题**: ").append(existing.getTitle())
              .append("\n**新版本**: v").append(updated != null ? updated.getVersion() : existing.getVersion() + 1)
              .append("\n**内容长度**: ").append(cleaned.length()).append(" 字")
              .append(stripped > 0 ? "（已剥离 " + stripped + " 字渲染痕迹：历史导读/关联经验/文档结尾等）" : "")
              .append("\n\n文档已更新，当前版本保留了完整内容。");
            // D1 包 4 · L0 写入查重：整篇替换后内容可能已经和别的条目重合，顺手查一次
            if (updated != null) {
                sb.append(similarityDigest(similarityService.checkOnCreate(updated)));
            }
            return sb.toString();
        } catch (Exception e) {
            log.error("ExpHubTools.importDocument: FAILED", e);
            callLogService.incrementFailCalls();
            return "导入文档失败: " + e.getClass().getName() + " - " + e.getMessage();
        }
    }

    /**
     * 获取经验的历史版本内容
     */
    @Tool(name = "get_experience_history", description = "获取经验的某个历史版本内容。不传version时返回所有历史版本列表（含版本号、更新者、更新时间），传version时返回该版本的完整内容。当 get_experience_detail 返回的当前版本引用了历史版本时，可通过此工具读取被引用的历史版本内容。")
    public String getExperienceHistory(
            @ToolParam(description = "经验ID。") Long id,
            @ToolParam(description = "可选。要读取的特定版本号，如 7 表示读取 v7 的历史内容。不传则返回所有历史版本列表。") Integer version) {

        try {
            // 检查经验是否存在
            Doc doc = docService.getById(id);
            if (doc == null) {
                return "未找到ID为" + id + "的经验";
            }

            List<DocVersion> versions = docService.getVersions(id);

            if (version != null) {
                // 查找指定版本
                DocVersion target = null;
                for (DocVersion v : versions) {
                    if (v.getVersion() == version) {
                        target = v;
                        break;
                    }
                }
                if (target == null) {
                    StringBuilder sb = new StringBuilder();
                    sb.append("未找到经验 ID:").append(id).append(" 的 v").append(version).append(" 历史版本。\n\n");
                    sb.append("当前最新版本: v").append(doc.getVersion()).append("\n");
                    if (!versions.isEmpty()) {
                        sb.append("可用历史版本: ");
                        for (DocVersion v : versions) {
                            sb.append("v").append(v.getVersion()).append(" ");
                        }
                        sb.append("\n");
                    }
                    return sb.toString();
                }

                StringBuilder sb = new StringBuilder();
                sb.append("# ").append(doc.getTitle()).append("（v").append(version).append(" 历史版本）\n\n");
                sb.append("**更新者**: ").append(target.getUpdatedName() != null ? target.getUpdatedName() : "未知").append("\n");
                sb.append("**更新时间**: ").append(target.getCreatedAt() != null ? target.getCreatedAt() : "未知").append("\n");
                sb.append("**当前最新版本**: v").append(doc.getVersion()).append("\n\n---\n\n");

                // A3：该次改动只动了元数据，正文没变，因此没有正文副本。
                // 必须明确告诉调用方「不是丢了」，否则会被当成数据损坏。
                if (target.getContent() == null) {
                    StringBuilder nb = new StringBuilder();
                    nb.append("# ").append(doc.getTitle()).append("（v").append(version).append(" 变更记录）\n\n");
                    nb.append("**变更类型**: 仅元数据（标签 / 摘要 / 标题 / 归属等）—— **正文未变**\n");
                    nb.append("**更新者**: ").append(target.getUpdatedName() != null ? target.getUpdatedName() : "未知").append("\n");
                    nb.append("**更新时间**: ").append(target.getCreatedAt() != null ? target.getCreatedAt() : "未知").append("\n");
                    nb.append("**当前最新版本**: v").append(doc.getVersion()).append("\n\n---\n\n");
                    nb.append("> 该版本**没有保存正文副本**：那次改动没动正文，再存一份就是纯冗余（A3 之前的做法，\n");
                    nb.append("> 实测 463 条快照里有 21 条正文与更早版本完全相同，就是这个原因）。\n");
                    DocVersion earlier = null;
                    for (DocVersion v : versions) {
                        if (v.getVersion() < version && !"META".equals(v.getChangeType())) {
                            earlier = v;
                            break;
                        }
                    }
                    if (earlier != null) {
                        nb.append("> 要读当时的正文，请调用 get_experience_history(id=").append(id)
                          .append(", version=").append(earlier.getVersion()).append(")（v")
                          .append(earlier.getVersion()).append("，正文版本）。\n");
                    } else {
                        nb.append("> 这是该文档最早的变更记录，当时正文即为初版内容，")
                          .append("可用 get_experience_detail(id=").append(id).append(") 查看当前正文。\n");
                    }
                    return nb.toString();
                }

                String content = target.getContent();
                boolean truncated = false;
                if (content != null && content.length() > detailMaxChars) {
                    content = content.substring(0, detailMaxChars);
                    truncated = true;
                }
                if (content != null) {
                    sb.append(content);
                }
                if (truncated) {
                sb.append("\n\n> ⚠️ 内容过长已截断（仅显示开头部分）。如需完整内容请使用 export_document(id=")
                        .append(id)
                        .append(") 导出完整文档，或分段说明具体关注的部分。");
            }
            sb.append("\n\n---\n> 📌 此为历史版本 v").append(version).append(" 的内容，当前最新版本为 v").append(doc.getVersion())
                  .append("。如需查看最新内容请使用 get_experience_detail(id=").append(id).append(")。");

                return sb.toString();
            }

            // 不传 version → 列出所有历史版本
            if (versions.isEmpty()) {
                return "经验 ID:" + id + "「" + doc.getTitle() + "」没有历史版本。当前仅有 v" + doc.getVersion() + "。";
            }

            StringBuilder sb = new StringBuilder();
            sb.append("# ").append(doc.getTitle()).append(" — 历史版本列表\n\n");
            sb.append("**当前版本**: v").append(doc.getVersion());
            sb.append(" | **状态**: ").append(getStatusLabel(doc.getStatus())).append("\n\n");
            sb.append("| 版本号 | 变更类型 | 更新者 | 更新时间 |\n");
            sb.append("|--------|----------|--------|----------|\n");
            for (DocVersion v : versions) {
                // A3：META 版只记事件、没有正文副本；老数据无 change_type 时按正文处理
                String changeLabel = "META".equals(v.getChangeType()) ? "仅元数据" : "正文";
                sb.append("| v").append(v.getVersion())
                  .append(" | ").append(changeLabel)
                  .append(" | ").append(v.getUpdatedName() != null ? v.getUpdatedName() : "未知")
                  .append(" | ").append(v.getCreatedAt() != null ? v.getCreatedAt() : "未知")
                  .append(" |\n");
            }
            sb.append("\n> 「仅元数据」的版本只改了标签/摘要/标题/归属，**没有保存正文副本**（正文当时没变）。\n");
            sb.append("\n需要查看某个历史版本的内容，请调用 get_experience_history(id=").append(id)
              .append(", version=版本号)。\n");
            sb.append("如需查看当前最新版本内容，请调用 get_experience_detail(id=").append(id).append(")。");

            return sb.toString();
        } catch (Exception e) {
            log.error("ExpHubTools.getExperienceHistory: FAILED", e);
            callLogService.incrementFailCalls();
            return "获取历史版本失败: " + e.getMessage();
        }
    }

    /**
     * 搜索经验库
     */
    @Tool(name = "search_experience", description = "搜索经验库中相关经验。返回标题、摘要、标签、模板类型、状态等信息。默认只搜 ACTIVE（正常）状态；如需历史/归档经验，传 includeStatus=COMPLETED,DEPRECATED,BROKEN 或 ALL。检索会做宽松召回并按相关度排序，不必保证每个词都命中；用完整自然语言描述场景效果最好。⚠️ 未传 projectName 时只搜全局经验，项目专属经验不会出现在结果里；问题属于某个项目时请传 projectName。若 0 结果，会返回相近经验与排查建议，请先确认确实没有可复用经验，再考虑 create_experience 沉淀。")
    public String searchExperience(
            @ToolParam(description = "完整的场景描述，用空格分隔多个关键词。例如：'Nginx 反向代理 HTTPS 配置'、'MySQL 慢查询 性能优化'、'Docker 部署 Spring Boot'。不要只传单个笼统的词如'nginx'") String query,
            @ToolParam(description = "可选。指定搜索的模板类型：problem_solution(问题解决方案)、knowledge_doc(知识文档)、todo_list(待办事项)、bug_fix(Bug修复记录)、config_guide(配置指南)、how_to(操作指南)、schedule_plan(计划排期)、goal(目标)。不传则搜索所有类型。", required = false) String templateType,
            @ToolParam(description = "可选。指定搜索的状态，默认只搜索 ACTIVE（正常可用）。如需搜索已完成或已过期的经验，传：COMPLETED,DEPRECATED,BROKEN。传 ALL 则搜索所有状态。", required = false) String includeStatus,
            @ToolParam(description = "可选。限定在某个项目内搜索，填项目名称或别名（如'回金算'、'huijinsuan'）。不传则只搜索全局经验——项目专属经验不会出现在全局搜索结果中。", required = false) String projectName,
            @ToolParam(description = "可选。本次返回条数，默认 20，上限 50。要一次看更多就调大（例如 50），不要靠反复换关键词。", required = false) Integer limit,
            @ToolParam(description = "可选。偏移量，默认 0。上一页末尾提示「下一页 offset=N」时照抄该值翻页。", required = false) Integer offset,
            @ToolParam(description = "可选。true=精简模式，只给 ID/标题/类型/摘要（省 token，适合先扫一遍挑目标）；false=默认完整信息（含标签/作者/更新时间/历史版本提示）。", required = false) Boolean brief) {
        
        try {
            // 权限验证
            AiAssistant assistant = getCaller();
            if (assistant != null && !Boolean.TRUE.equals(assistant.getCanSearch())) {
                return "❌ 权限不足：该API Key没有查询经验的权限";
            }

            // 查询词无实质内容（空白 / 纯标点 / 纯停用词）时直接拒绝：
            // 空 query 走搜索会命中全库兜底、返回一两百条无关经验，误导 AI 判断
            if (!DocService.hasMeaningfulQuery(query)) {
                return "❌ 查询词无实质内容（空白、纯标点或全是停用词），已拒绝搜索。\n\n"
                        + "请用完整的场景描述（2-3 个核心词）调用，例如：\n"
                        + "- 「Nginx 反向代理 HTTPS 配置」\n"
                        + "- 「MySQL 慢查询 性能优化」\n"
                        + "- 「Docker 部署 Spring Boot」\n\n"
                        + "如果想枚举最近的经验，请用 list_recent_memories，不要传空 query 给 search_experience。";
            }
            
            // 解析项目范围（支持项目名称 / 别名 / 编码）
            Long projectId = null;
            if (projectName != null && !projectName.trim().isEmpty()) {
                var candidates = projectService.resolveProject(projectName);
                if (candidates.isEmpty()) {
                    return "❌ 未找到名为「" + projectName + "」的项目。可用 list_projects 查看你的项目列表。";
                }
                if (candidates.size() > 1) {
                    StringBuilder cb = new StringBuilder();
                    cb.append("⚠️ 匹配到 ").append(candidates.size()).append(" 个项目，请用更精确的名称重试：\n\n");
                    for (Project p : candidates) {
                        cb.append("- 【ID:").append(p.getId()).append("】").append(p.getName())
                          .append("（编码: ").append(p.getProjectCode()).append("）\n");
                    }
                    return cb.toString();
                }
                projectId = candidates.get(0).getId();
            }
            
            // 默认只搜索 ACTIVE 状态
            String statusFilter = includeStatus;
            if (statusFilter == null || statusFilter.isEmpty()) {
                statusFilter = "ACTIVE";
            } else if ("ALL".equalsIgnoreCase(statusFilter.trim())) {
                // M10：ALL 不再等价于「什么状态都要」，而是「除待删外的全部状态」。
                // 待删项是等用户确认要删的东西，让 AI 搜到只会拿错资料。
                statusFilter = "ACTIVE,COMPLETED,BROKEN,DEPRECATED";
            }

            // M18-b：分页与精简（原实现固定 page=1 size=20，合并项目域后约 30 条，
            // 第 26 条起永远拿不到 = 隐式 25 条上限。现在由调用方控制 limit/offset）
            int effLimit = (limit == null || limit <= 0) ? 20 : Math.min(limit, LIST_MAX_SIZE);
            int effOffset = (offset == null || offset < 0) ? 0 : offset;
            // 召回池 = offset + limit，上限 200：offset 很大时不至于让 DB 全表排序
            int poolSize = Math.min(effOffset + effLimit, SEARCH_POOL_MAX);

            var result = docService.search(query, templateType, statusFilter, projectId, 1, poolSize);
            long matchedTotal = result.getTotal();
            java.util.List<Doc> docs = new ArrayList<>(result.getRecords());

            // E5：全局搜索时若查询词里含项目名/别名，自动 OR 召回该项目的专属经验。
            // 背景：项目经验默认隔离不进全局索引，导致搜「拆解宝」前 10 条全是「回金算」的内容，
            // 而拆解宝自己 24 条经验一条都召回不到 —— 数据在库里，只是没进索引。
            //
            // ⚠️ 2026-10-24 忆阁复测：第一版把项目结果 append 到末尾，等于「召回了但排在后面」，
            // 实际使用时仍要手填 projectName。这次改为**项目域结果前置并分组显示**：
            // 查询词里明确写了项目名，说明用户就是在找该项目的东西，它就应该是第一等公民。
            Project autoProject = null;
            java.util.List<Doc> projDocsHit = java.util.List.of();
            if (projectId == null) {
                autoProject = detectProjectInQuery(query);
                if (autoProject != null) {
                    try {
                        var projResult = docService.search(query, templateType, statusFilter, autoProject.getId(), 1, poolSize);
                        Set<Long> projIds = new LinkedHashSet<>();
                        for (Doc d : projResult.getRecords()) projIds.add(d.getId());
                        if (!projIds.isEmpty()) {
                            // 项目域命中的排前面，全局里与项目重复的去掉
                            projDocsHit = new ArrayList<>(projResult.getRecords());
                            java.util.List<Doc> rest = new ArrayList<>();
                            for (Doc d : docs) {
                                if (!projIds.contains(d.getId())) rest.add(d);
                            }
                            java.util.List<Doc> merged = new ArrayList<>(projDocsHit);
                            merged.addAll(rest);
                            docs = merged;
                        }
                    } catch (Exception e) {
                        log.debug("searchExperience: 项目域补充召回失败 project={}, {}", autoProject.getName(), e.getMessage());
                    }
                }
            }
            
            String filterInfo = "";
            if (projectId != null) {
                filterInfo += "【项目范围: " + projectName + "】";
            }
            if (templateType != null && !templateType.isEmpty()) {
                filterInfo += "【类型过滤: " + templateType + "】";
            }
            if (statusFilter != null && !"ACTIVE".equals(statusFilter)) {
                filterInfo += "【状态过滤: " + statusFilter + "】";
            }
            
            // 未指定项目时，尝试从查询里识别项目（如 “exphub 架构”）
            Project detected = autoProject;

            if (docs.isEmpty()) {
                // 2) 相近经验兜底：避免 AI 误判“没有经验”而重复创建
                // （原第 1 步「识别到项目后再搜一次」已前移到上面合并召回，此处不再重复）
                var similar = docService.findSimilar(query, templateType, statusFilter, projectId, 3);
                StringBuilder sb = new StringBuilder();
                sb.append(filterInfo).append("未找到\"").append(query).append("\"的精确匹配。\n\n");
                if (!similar.isEmpty()) {
                    sb.append("🔍 相近经验（可能相关，请先确认再决定是否新建）：\n\n");
                    sb.append(renderDocs(similar));
                }
                sb.append("💡 排查建议：\n");
                if (projectId == null) {
                    sb.append("1. **未指定项目时，项目专属经验不参与全局搜索**；如果这是某个项目的问题，请加 projectName 参数重试\n");
                } else {
                    sb.append("1. 尝试减少关键词（保留 2-3 个核心词），或换用更通用的说法\n");
                }
                sb.append("2. 换用同义词/英文缩写（如：部署 ↔ 上线、连不上 ↔ 连接失败/超时）\n");
                sb.append("3. includeStatus=COMPLETED,DEPRECATED,BROKEN 可查历史或已归档经验\n");
                sb.append("4. 确认确实没有可用经验后，再 create_experience 沉淀\n");
                return sb.toString();
            }

            // M18-b：内存分页。项目域合并后的顺序已固定为「项目内命中在前 + 全局在后」，
            // 所以直接对合并结果切片即可，翻页不会串位。
            int poolTotal = docs.size();
            int[] bounds = searchPageBounds(poolTotal, effOffset, effLimit);
            int fromIdx = bounds[0], toIdx = bounds[1];
            java.util.List<Doc> pageDocs = docs.subList(fromIdx, toIdx);
            boolean briefMode = Boolean.TRUE.equals(brief);

            // 「匹配总数」与「召回池大小」是两回事：limit=5 时池子只有 5 条，
            // 但库里可能匹配 138 条。翻页提示必须按匹配总数判断，否则 limit 一调小
            // 就会输出「以上为全部结果」——AI 会误以为库里真的只有 5 条（实测踩到过）。
            long effTotal = Math.max(matchedTotal, poolTotal);
            boolean hasMore = toIdx < effTotal;

            StringBuilder sb = new StringBuilder();
            sb.append(filterInfo).append("匹配 ").append(effTotal).append(" 条");
            if (effOffset > 0 || pageDocs.size() < effTotal) {
                sb.append("，本次返回第 ").append(fromIdx + 1).append("-").append(toIdx).append(" 条");
            }
            sb.append("：\n\n");

            if (projDocsHit.isEmpty()) {
                sb.append(renderDocs(pageDocs, briefMode));
            } else {
                // 分组显示：项目内命中在前、全局相关在后，AI 一眼能看出哪些属于该项目
                Set<Long> projIdsInPage = new LinkedHashSet<>();
                for (Doc d : projDocsHit) projIdsInPage.add(d.getId());
                java.util.List<Doc> pageProj = new ArrayList<>();
                java.util.List<Doc> pageRest = new ArrayList<>();
                for (Doc d : pageDocs) {
                    if (projIdsInPage.contains(d.getId())) pageProj.add(d); else pageRest.add(d);
                }
                if (!pageProj.isEmpty()) {
                    sb.append("【项目「").append(detected.getName()).append("」内命中 ")
                      .append(pageProj.size()).append(" 条】\n\n");
                    sb.append(renderDocs(pageProj, briefMode));
                }
                if (!pageRest.isEmpty()) {
                    sb.append("\n【全局相关 ").append(pageRest.size()).append(" 条】\n\n");
                    sb.append(renderDocs(pageRest, briefMode));
                }
            }

            // 翻页提示：不提示的话调用方以为「只有这些」，这正是原来隐式 25 条上限的根源
            sb.append("\n---\n");
            if (hasMore) {
                sb.append("📄 还有 ").append(effTotal - toIdx).append(" 条未显示，下一页传 offset=")
                  .append(toIdx).append("（limit 不变即 ").append(effLimit).append("）；")
                  .append("想一次多看就调大 limit（上限 ").append(LIST_MAX_SIZE).append("）。\n");
            } else {
                sb.append("📄 以上为全部结果（本次返回第 ").append(fromIdx + 1).append("-").append(toIdx)
                  .append(" 条，共 ").append(effTotal).append(" 条）。\n");
            }
            if (detected != null) {
                sb.append("---\n💡 查询词含项目「").append(detected.getName()).append("」（")
                  .append(detected.getProjectCode()).append("），项目内结果已优先展示。")
                  .append("只想看项目内资料时传 projectName=").append(detected.getProjectCode()).append("。\n");
            }
            return sb.toString();
        } catch (Exception e) {
            log.error("ExpHubTools.searchExperience: FAILED", e);
            callLogService.incrementFailCalls();
            return "搜索经验失败: " + e.getMessage();
        }
    }
    
    /**
     * 列表摘要截断长度（E6）：原始 summary 常带完整首段（200+ 字），
     * 列表里本该是「扫一眼挑目标」，压到 60 字足够判断相关性。
     */
    private static final int SUMMARY_PREVIEW_CHARS = 60;

    /** 列表类工具的默认/最大条数（E6）：默认 10 条省 token，需要全量走 offset 翻页 */
    private static final int LIST_DEFAULT_SIZE = 10;
    private static final int LIST_MAX_SIZE = 50;

    /**
     * 列表里的摘要：压到 60 字并折叠换行。
     * 需要完整摘要请用 get_experience_detail。
     */
    static String previewSummary(String summary) {
        if (summary == null || summary.isBlank()) {
            return "无";
        }
        String s = summary.replaceAll("\\s+", " ").trim();
        return s.length() > SUMMARY_PREVIEW_CHARS
                ? s.substring(0, SUMMARY_PREVIEW_CHARS) + "…"
                : s;
    }

    /** M18-b：搜索结果召回池上限。offset 很大时避免 DB 全量排序，同时保证翻页能翻到 */
    private static final int SEARCH_POOL_MAX = 200;

    /**
     * M18-b：计算本次返回区间 [from, to)。包级可见便于单测。
     * 越界一律夹到区间内，不抛异常：AI 传了个离谱的 offset 时应拿到「空页 + 提示」，而不是一个错误。
     */
    static int[] searchPageBounds(int poolTotal, int offset, int limit) {
        int total = Math.max(poolTotal, 0);
        int from = Math.min(Math.max(offset, 0), total);
        int to = Math.min(from + Math.max(limit, 0), total);
        return new int[]{from, to};
    }

    /** M18-b：精简模式的一条结果（单行，省 token）。包级可见便于单测。 */
    static String briefLine(int index, Doc doc) {
        // 标记前置，扫一眼就知道哪条结论存疑（flags 为空时不改变原有输出格式）
        String flagMark = "";
        if (doc.getFlags() != null && !doc.getFlags().isBlank()) {
            flagMark = " ⚑" + DocService.flagLabelText(doc.getFlags());
        }
        // D1 取代链：被取代的条目必须一眼可见，否则 AI 会把旧结论当现行结论用
        String supersededMark = (doc.getSupersededBy() != null)
                ? " ⛔已被#" + doc.getSupersededBy() + "取代" : "";
        return index + ". 【ID:" + doc.getId() + "】" + doc.getTitle()
                + " —— " + getTemplateTypeLabel(doc.getTemplateType())
                + " " + getStatusLabel(doc.getStatus()) + flagMark + supersededMark
                + " · " + previewSummary(doc.getSummary()) + "\n";
    }

    private String renderDocs(List<Doc> docs) {
        return renderDocs(docs, false);
    }

    /**
     * @param brief true=精简模式（M18-b）：一条一行，只留 ID/标题/类型/状态/摘要。
     *              省 token，适合「先扫一遍挑目标」；要看标签、作者、历史版本提示就传 false。
     */
    private String renderDocs(List<Doc> docs, boolean brief) {
        StringBuilder sb = new StringBuilder();
        int i = 1;
        for (Doc doc : docs) {
            if (brief) {
                sb.append(briefLine(i++, doc));
                continue;
            }
            String typeLabel = getTemplateTypeLabel(doc.getTemplateType());
            String statusLabel = getStatusLabel(doc.getStatus());
            sb.append(i++).append(". 【ID:").append(doc.getId()).append("】").append(doc.getTitle()).append("\n");
            sb.append("   类型: ").append(typeLabel).append(" | 状态: ").append(statusLabel)
              .append(" | 分类: ").append(doc.getCategory()).append(" | 查看: ").append(doc.getCallCount()).append("次\n");
            sb.append("   摘要: ").append(previewSummary(doc.getSummary())).append("\n");
            sb.append("   标签: ").append(doc.getTags() != null ? doc.getTags() : "无").append("\n");
            // set_flags：标记回显。给 AI 一个明确的「别把这条当确定结论」信号
            if (doc.getFlags() != null && !doc.getFlags().isBlank()) {
                sb.append("   ⚑ 标记: ").append(DocService.flagLabelText(doc.getFlags()))
                  .append(" (").append(doc.getFlags()).append(")");
                if (doc.getFlagNote() != null && !doc.getFlagNote().isBlank()) {
                    sb.append("｜说明: ").append(abbreviate(doc.getFlagNote(), 80));
                }
                sb.append("\n");
            }
            if (doc.getSupersededBy() != null) {
                sb.append("   ⛔ 已被 #").append(doc.getSupersededBy())
                  .append(" 取代 —— 结论以新条为准，本条仅作历史留存\n");
            }
            sb.append("   作者: ").append(doc.getAuthorName()).append(" | 更新: ").append(doc.getUpdatedAt()).append("\n");
            // 📚 历史版本提示：用 doc.getVersion() 直接判断，不查库。
            // 原实现对每条结果都调 getVersions()，20 条结果 = 20 次额外查询（N+1），
            // 而这里只需要「有没有历史」这一个布尔信息，version 字段本身就够了。
            if (doc.getVersion() != null && doc.getVersion() > 1) {
                sb.append("   📚 历史版本: 共 ").append(doc.getVersion() - 1).append(" 个（v1 ~ v")
                  .append(doc.getVersion() - 1).append("），如需了解演进过程请调用 get_experience_history(id=")
                  .append(doc.getId()).append(", version=版本号)").append("\n");
            }
            sb.append("\n");
        }
        return sb.toString();
    }

    /**
     * 从查询词里识别项目名/别名/编码。
     * 项目经验默认不参与全局搜索，识别到项目时会在 0 结果场景自动补搜该项目。
     */
    private Project detectProjectInQuery(String query) {
        if (query == null || query.trim().isEmpty()) return null;
        for (String raw : query.split("[\\s,，、;；]+")) {
            String token = raw.trim();
            if (token.length() < 2) continue;
            try {
                var candidates = projectService.resolveProject(token);
                if (candidates.size() == 1) return candidates.get(0);
            } catch (Exception e) {
                log.debug("detectProjectInQuery: '{}' 解析失败: {}", token, e.getMessage());
            }
        }
        return null;
    }

    static String getTemplateTypeLabel(String type) {
        if (type == null) return "通用";
        return switch (type) {
            case "problem_solution" -> "🛠️ 问题解决方案";
            case "knowledge_doc" -> "📚 知识文档";
            case "todo_list" -> "✅ 待办事项";
            case "bug_fix" -> "🐛 Bug修复";
            case "config_guide" -> "⚙️ 配置指南";
            case "how_to" -> "📖 操作指南";
            case "schedule_plan" -> "📅 计划排期";
            case "goal" -> "🎯 目标";
            default -> "📄 " + type;
        };
    }
    
    static String getStatusLabel(String status) {
        if (status == null) return "未知";
        return switch (status) {
            case "ACTIVE" -> "🟢 正常";
            case "COMPLETED" -> "✅ 已完成";
            case "BROKEN" -> "🔴 已失效";
            case "DEPRECATED" -> "⏸️ 已过期";
            default -> "❓ " + status;
        };
    }

    /**
     * 更新经验状态
     */
    @Tool(name = "update_experience_status", description = "更新经验的状态。用于标记经验的生命周期状态：ACTIVE(正常可用)、COMPLETED(已完成，用于待办事项)、BROKEN(已失效，方案不再可用)、DEPRECATED(已过期，内容过时)。当待办事项完成时标记为COMPLETED，当经验不可用时标记为BROKEN或DEPRECATED。")
    public String updateExperienceStatus(
            @ToolParam(description = "经验ID（必填）") Long id,
            @ToolParam(description = "新状态，可选值：ACTIVE(正常)、COMPLETED(已完成)、BROKEN(已失效)、DEPRECATED(已过期)") String status,
            @ToolParam(description = "可选。**谁改的**——操作人署名（本工具只写调用日志，不影响版本快照）", required = false) String updatedName) {
        
        try {
            log.info("ExpHubTools.updateExperienceStatus: ENTRY - id={}, status={}", id, status);
            
            // 权限验证
            AiAssistant assistant = getCaller();
            if (assistant != null && !Boolean.TRUE.equals(assistant.getCanUpdate())) {
                return "❌ 权限不足：该API Key没有编辑经验的权限";
            }
            
            // 校验状态值
            String upperStatus = status != null ? status.toUpperCase() : "";
            // M10：待删必须走专用工具（要带原因，且删不删由用户定），这里挡掉绕过路径
            if (DocService.PENDING_DELETE.equals(upperStatus)) {
                return "❌ 待删标记不能用状态接口设置。请改用 mark_for_deletion(id, reason)："
                        + "它要求说明删除原因，且是否真删由用户在后台确认。";
            }
            if (!List.of("ACTIVE", "COMPLETED", "BROKEN", "DEPRECATED").contains(upperStatus)) {
                return "❌ 无效的状态值: " + status + "，可选：ACTIVE、COMPLETED、BROKEN、DEPRECATED";
            }
            
            // 检查经验是否存在
            Doc existing = docService.getById(id);
            if (existing == null) {
                return "❌ 未找到ID为" + id + "的经验";
            }
            
            Doc updated = docService.updateStatus(id, upperStatus);
            String statusLabel = getStatusLabel(upperStatus);
            
            return "✅ 经验状态已更新！\n\n**ID**: " + id + "\n**标题**: " + updated.getTitle() + "\n**新状态**: " + statusLabel + "\n\n注意：非ACTIVE状态的经验默认不会被 search_experience 搜索到，其他AI助手将不会看到此经验。";
        } catch (Exception e) {
            log.error("ExpHubTools.updateExperienceStatus: FAILED", e);
            callLogService.incrementFailCalls();
            return "更新状态失败: " + e.getClass().getName() + " - " + e.getMessage();
        }
    }

    /**
     * M10：标记经验为待删（AI 侧唯一可用的「删除」动作）。
     *
     * <p>红线：模型没有删除能力。本工具只把文档挂上待删标记并写明原因，
     * 真正删除必须由用户在后台「待删队列」里确认执行。
     * 标记后该经验立刻从搜索结果中消失（检索只查 ACTIVE），但内容仍在库里，用户可一键恢复。
     */
    @Tool(name = "mark_for_deletion", description = "【标记待删，不是删除】把一条错误的/过时的/重复的经验标记为「待删」，由用户在后台确认后才真正删除。你没有删除权限，这是你能做的唯一动作。适用场景：发现自己写错了内容、创建了重复经验、记录了已失效的方案。⚠️ 必须填 reason 说明为什么该删（用户据此决定删不删）；标记后该经验立即从搜索结果中消失，但内容仍可恢复。如误标，用 cancel_deletion 撤回。")
    public String markForDeletion(
            @ToolParam(description = "要标记待删的经验 ID") Long id,
            @ToolParam(description = "删除原因（必填）。说清为什么该删，例如：重复经验（与 ID:123 同内容）/ 内容错误（命令已失效）/ 测试数据误写。用户在后台据此决定是否真删。") String reason,
            @ToolParam(description = "可选。署名（谁标的），显示在后台待删队列。不传=用当前 API Key 绑定的助手名。同一 Key 多助手共用时建议传，否则都记成同一个人", required = false) String authorName,
            @ToolParam(description = "可选。**谁改的**——操作人署名（正式名，建议统一用这个）。与 `authorName` 等价，两者都传时以本参数为准", required = false) String updatedName) {

        try {
            AiAssistant assistant = getCaller();
            if (assistant != null && !Boolean.TRUE.equals(assistant.getCanUpdate())) {
                return "❌ 权限不足：该API Key没有编辑经验的权限，不能标记待删";
            }
            if (reason == null || reason.trim().isEmpty()) {
                return "❌ 必须填写 reason（删除原因）。用户在后台看到的就是这句话，没有原因他不会知道该不该删。";
            }
            Doc existing = docService.getById(id);
            if (existing == null) {
                return "❌ 未找到ID为" + id + "的经验";
            }
            // B1：署名优先用调用方自报的（同一 Key 可能被多个助手共用，Key 上只存得下一个名字）
            String by = (authorName != null && !authorName.trim().isEmpty())
                    ? authorName.trim()
                    : ((assistant != null && assistant.getAssistantName() != null)
                        ? assistant.getAssistantName() : "unknown");
            Doc marked = docService.markForDeletion(id, reason, by);

            StringBuilder sb = new StringBuilder();
            sb.append("✅ 已标记为待删（**尚未删除**）\n\n");
            sb.append("**ID**: ").append(id).append("\n");
            sb.append("**标题**: ").append(marked.getTitle()).append("\n");
            sb.append("**原因**: ").append(reason.trim()).append("\n");
            sb.append("**标记人**: ").append(by).append("\n\n");
            sb.append("现在起该经验不再出现在搜索结果中，但内容仍在库里。\n");
            sb.append("真正删除需要用户在后台「经验管理 → 待删队列」确认执行；如需撤回，调用 cancel_deletion(id=").append(id).append(")。\n\n");
            sb.append("⚠️ 请在回复里明确告诉用户：你只是打了删除标记，需要他确认才会真正删除。");
            return sb.toString();
        } catch (Exception e) {
            log.error("ExpHubTools.markForDeletion: FAILED", e);
            callLogService.incrementFailCalls();
            return "标记待删失败: " + e.getMessage();
        }
    }

    /**
     * M10：撤销待删标记，恢复为 ACTIVE。
     */
    @Tool(name = "cancel_deletion", description = "撤销「待删」标记，把经验恢复为正常（ACTIVE）状态。用于：自己标错了、或用户看过之后说「这条不要删」。只有被标记为待删的经验才需要调用。")
    public String cancelDeletion(
            @ToolParam(description = "要恢复的经验 ID") Long id,
            @ToolParam(description = "可选。**谁改的**——操作人署名（本工具只写日志）", required = false) String updatedName) {

        try {
            AiAssistant assistant = getCaller();
            if (assistant != null && !Boolean.TRUE.equals(assistant.getCanUpdate())) {
                return "❌ 权限不足：该API Key没有编辑经验的权限";
            }
            Doc existing = docService.getById(id);
            if (existing == null) {
                return "❌ 未找到ID为" + id + "的经验";
            }
            if (!DocService.PENDING_DELETE.equals(existing.getStatus())) {
                return "ℹ️ 经验 ID:" + id + " 当前状态是 " + getStatusLabel(existing.getStatus())
                        + "，并没有被标记待删，无需恢复。";
            }
            Doc restored = docService.cancelDeletion(id);
            return "✅ 已撤销待删标记\n\n**ID**: " + id + "\n**标题**: " + restored.getTitle()
                    + "\n**当前状态**: " + getStatusLabel(restored.getStatus())
                    + "\n\n该经验已恢复正常，会重新出现在搜索结果中。";
        } catch (Exception e) {
            log.error("ExpHubTools.cancelDeletion: FAILED", e);
            callLogService.incrementFailCalls();
            return "撤销待删标记失败: " + e.getMessage();
        }
    }

    /**
     * AI 反馈经验是否有帮助
     * 这是 ExpHub 核心价值指标：经验是否真正帮助了 AI 助手解决问题
     */
    @Tool(name = "report_experience_result", description = "反馈经验是否有帮助。当你参考了某条经验去执行任务后，无论成功还是失败都应该调用此工具反馈结果。⚠️ 重要：如果经验没有帮助，必须填写 feedback 说明原因（如方案过时、环境不匹配、步骤缺失等），并且你应该用自己找到的正确方案完成任务后，创建一篇新经验并通过 newExperienceId 参数关联起来，这样后续的 AI 助手就能同时看到新旧两篇经验，获得更完整的参考。")
    public String reportExperienceResult(
            @ToolParam(description = "经验ID（必填），你参考过的经验ID") Long id,
            @ToolParam(description = "该经验是否有帮助。true=成功帮助你解决了问题，false=没有帮助") boolean helpful,
            @ToolParam(description = "反馈说明。helpful=true 时可选（可简述哪个部分有用）；helpful=false 时必填！必须说明为什么没用（如：方案过时、环境不匹配、缺少关键步骤、命令报错等），这样管理员可以优化经验内容。") String feedback,
            @ToolParam(description = "可选。仅当 helpful=false 且你已经创建了新经验记录正确方案时，填新经验ID，系统会自动把新旧两篇互相关联。不传=只反馈不关联（helpful=true 时通常无需传）。", required = false) Long newExperienceId) {

        try {
            // 检查经验是否存在
            Doc existing = docService.getById(id);
            if (existing == null) {
                return "❌ 未找到ID为" + id + "的经验";
            }

            // helpful=false 时 feedback 必填
            if (!helpful && (feedback == null || feedback.trim().isEmpty())) {
                return "❌ 反馈失败：当经验没有帮助时，必须填写 feedback 说明原因（方案过时、环境不匹配、缺少步骤等）";
            }

            Doc updated = docService.reportResult(id, helpful, feedback);

            StringBuilder sb = new StringBuilder();
            if (helpful) {
                sb.append("✅ 感谢反馈！经验 ID:").append(id).append("「").append(existing.getTitle()).append("」\n");
                sb.append("已记录为「有帮助」，成功次数 +1。");
            } else {
                sb.append("📝 反馈已记录。经验 ID:").append(id).append("「").append(existing.getTitle()).append("」\n");
                sb.append("已记录为「没有帮助」，失败次数 +1。\n");
                sb.append("**原因**: ").append(feedback).append("\n\n");

                if (newExperienceId != null) {
                    Doc newDoc = docService.getById(newExperienceId);
                    if (newDoc != null) {
                        docService.linkExperiences(id, newExperienceId);
                        sb.append("🔗 已将旧经验（ID:").append(id).append("）和新经验（ID:").append(newExperienceId)
                          .append("「").append(newDoc.getTitle()).append("」）互相关联。\n");
                        sb.append("后续 AI 查看任一经验时都能看到另一篇，获得更完整的参考。");
                    } else {
                        sb.append("⚠️ 未找到 newExperienceId:").append(newExperienceId).append("，关联未建立。请确认新经验ID是否正确。");
                    }
                } else {
                    sb.append("💡 建议下一步：\n");
                    sb.append("1. 用你找到的正确方案完成任务\n");
                    sb.append("2. 调用 get_template() 选择模板，然后 create_experience() 创建新经验\n");
                    sb.append("3. 再次调用 report_experience_result()，传入 newExperienceId 参数关联新旧经验");
                }
            }

            return sb.toString();
        } catch (Exception e) {
            log.error("ExpHubTools.reportExperienceResult: FAILED", e);
            callLogService.incrementFailCalls();
            return "反馈失败: " + e.getClass().getName() + " - " + e.getMessage();
        }
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
    @Tool(name = "create_experience", description = "创建新经验。【重要】创建前必须先调用 get_template() 选择模板！必须填写 category、tags、aliases、summary 等元数据，否则其他AI无法搜索到你的经验。可选 projectId：把经验直接写进项目空间（不传则写全局）；项目经验默认隔离、不出现在全局搜索中，属于该项目的内容应传 projectId。🔒 安全提醒：严禁在 content 中包含任何敏感信息（账号密码、API密钥、Token、数据库连接串、私钥、证书等），这些内容会被存储到服务器并可被其他人搜索到。")
    public String createExperience(
            @ToolParam(description = "经验标题，简短明确。⚠️ 不要把版本号写进标题（如「v2」「v3」）——系统有独立的 version 字段自动管理版本，手写版本号必然与系统脱节。想体现迭代可在标题里用内容描述") String title,
            @ToolParam(description = "经验正文，使用Markdown格式，按 get_template 返回的结构填写。🔒 严禁包含账号密码、API密钥、Token、数据库连接串等敏感信息") String content,
            @ToolParam(description = "模板类型标识（必填），从 get_template 返回的 type 值中选择，如：problem_solution、knowledge_doc、todo_list") String templateType,
            @ToolParam(description = "分类（必填），如：服务器、开发、运维、部署、数据库、前端等，如不确定可填 未分类") String category,
            @ToolParam(description = "标签（必填），逗号分隔，至少3-5个关键词，如：Nginx,HTTPS,代理，如不确定可填 待分类") String tags,
            @ToolParam(description = "别名/同义词（必填），逗号分隔，用户可能用不同关键词搜索，如：反向代理,reverse proxy，如不确定可填 无") String aliases,
            @ToolParam(description = "一句话摘要（必填），让其他AI快速判断是否相关") String summary,
            @ToolParam(description = "作者名称。填写你自己的名字/标识，用于区分不同AI助手创建的经验。如：CodeBuddy、Claude、GPT-4等。") String authorName,
            @ToolParam(description = "可选。归属项目ID：把这条经验直接写进项目空间。不传=写全局。只能填你名下的项目（用 list_projects 查）", required = false) Long projectId) {
        
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
            // E11：写入前剥离渲染痕迹（防止把「读出来的视图」原样存成新文档）
            doc.setContent(stripRenderArtifacts(content));
            doc.setTemplateType(templateType != null && !templateType.isEmpty() ? templateType : "generic");
            doc.setCategory(category != null && !category.isEmpty() ? category : "未分类");
            doc.setTags(tags != null ? tags : "");
            doc.setAliases(aliases != null ? aliases : "");
            doc.setSummary(summary != null ? summary : "");

            // 项目归属（E7）：允许直接把经验写进项目空间，避免重要文档只能落全局无法归位
            if (projectId != null && projectId > 0) {
                Project target = projectService.getProject(projectId);
                if (target == null) {
                    return "❌ 未找到项目ID " + projectId + "，或你没有访问权限。可用 list_projects 查看你的项目。";
                }
                doc.setProjectId(projectId);
            }
            // AI 助手自报名称，区分不同助手创建的经验
            if (authorName != null && !authorName.trim().isEmpty()) {
                doc.setAuthorName(authorName.trim());
            }
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
            if (created.getProjectId() != null) {
                result.append("**归属项目**: ").append(created.getProjectId()).append("（项目内经验，不出现在全局搜索）\n");
                result.append("\n经验已保存到项目空间，同项目的 AI 助手可搜索到。");
            } else {
                result.append("\n经验已保存，其他AI助手可以通过搜索找到这条经验。");
            }
            // D1 包 4 · L0 写入查重：旁路执行，失败只降级成一句提示，绝不影响创建结果
            result.append(similarityDigest(similarityService.checkOnCreate(created)));
            return result.toString();
        } catch (Exception e) {
            log.error("ExpHubTools.createExperience: FAILED", e);
            callLogService.incrementFailCalls();
            return "创建失败: " + e.getClass().getName() + " - " + e.getMessage();
        }
    }

    /**
     * 更新经验
     */
    @Tool(name = "update_experience", description = "更新已有经验的指定字段。**只传要改的字段即可，其余不传**（2026-10-25 起不再要求全字段必填）。要清空某字段用 clearFields=\"tags,aliases\" 显式声明 —— 不要靠传 null 表达清空（协议层分不出「没传」和「传 null」）。改一小段正文优先用 patch_experience（find/replace，不读全文不截断）；追加内容用 append_experience；整体重写长篇文档仍建议 export_document + import_document。🔒 严禁在 content 中包含账号密码、API密钥、Token 等敏感信息。")
    public String updateExperience(
            @ToolParam(description = "经验ID（必填）。通过搜索或查看详情获取") Long id,
            @ToolParam(description = "可选。新标题。不传=不改。⚠️ 不要把版本号写进标题（如「v2」），版本由系统 version 字段管理", required = false) String title,
            @ToolParam(description = "可选。新正文（整体替换）。不传=不改。⚠️ 只改一小段请用 patch_experience，追加内容请用 append_experience —— 用本参数整篇写回容易把没读到的部分覆盖掉。🔒 严禁含账号密码/密钥/Token", required = false) String content,
            @ToolParam(description = "可选。分类，如：服务器、开发、运维。不传=不改", required = false) String category,
            @ToolParam(description = "可选。标签，逗号分隔。不传=不改。要清空请用 clearFields", required = false) String tags,
            @ToolParam(description = "可选。别名/同义词，逗号分隔。不传=不改。要清空请用 clearFields", required = false) String aliases,
            @ToolParam(description = "可选。一句话摘要。不传=不改。要清空请用 clearFields", required = false) String summary,
            @ToolParam(description = "可选。关联经验ID，逗号分隔。不传=不改。要清空请用 clearFields", required = false) String relatedIds,
            @ToolParam(description = "可选。修改作者名称。不传=不改", required = false) String authorName,
            @ToolParam(description = "可选。强制覆盖开关，设为 true 可跳过截断检测。默认 false。一般不需要——局部修改请用 patch_experience", required = false) Boolean force,
            @ToolParam(description = "可选。显式清空字段，逗号分隔。可清空：tags / aliases / summary / relatedIds / category（category 置为「未分类」）。title 与 content 不允许清空。例：clearFields=\"tags,aliases\"", required = false) String clearFields,
            @ToolParam(description = "可选。调整归属：传项目ID=归入该项目；传 0=移回全局；不传=不改归属。用于把误落全局的文档归位，或把跨项目通用的经验从项目里移回全局", required = false) Long projectId,
            @ToolParam(description = "可选。**谁改的**——写进本版快照的操作人署名（审计用）。与 authorName 完全不同：authorName 改的是「文档作者」（作品归属），本参数只记「这一次是谁动的」。一个 API Key 常被多个助手共用，不传就都记成 Key 名，回溯时分不清谁改的（烛龙/白泽/阁主 共用一个 Key 时必须传）", required = false) String updatedName) {
        
        try {
            log.info("ExpHubTools.updateExperience: ENTRY - id={}, title={}, force={}", id, title, force);
            
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
            
            // === 截断更新检测（策略二：自动拦截+引导） ===
            String storedContent = existing.getContent();
            if (content != null && !content.isEmpty() && storedContent != null
                    && storedContent.length() > detailMaxChars) {
                
                String contentHead = content.substring(0, Math.min(100, content.length()));
                boolean isPrefixMatch = storedContent.startsWith(contentHead);
                boolean isShorter = content.length() < storedContent.length();
                
                if (isShorter && isPrefixMatch) {
                    if (force != null && force) {
                        // force=true 逃生门：用户确认要覆盖
                        log.info("ExpHubTools.updateExperience: truncation detected but force=true, proceeding. id={}", id);
                    } else {
                        // 拦截并引导到正确的文档维护流程
                        log.warn("ExpHubTools.updateExperience: truncation detected, blocked. id={}, contentLen={}, storedLen={}",
                                id, content.length(), storedContent.length());
                        return "⚠️ **检测到截断更新风险！**\n\n"
                             + "你提供的 content（" + content.length() + "字）比现有文档（" + storedContent.length() + "字）短了很多，\n"
                             + "且新内容是旧文档的开头部分，这很可能是因为 get_experience_detail 只返回了开头部分，\n"
                             + "你只看到了部分内容，直接 update 会丢失未展示的部分。\n\n"
                             + "📋 **请改用文档维护专用流程：**\n"
                             + "1. export_document(id=" + id + ") → 导出完整文档（无截断）\n"
                             + "2. 将返回的内容保存为本地 .md 文件\n"
                             + "3. 修改 .md 文件（人类或AI均可编辑）\n"
                             + "4. 用 read_file 读取修改后的文件内容\n"
                             + "5. import_document(id=" + id + ", content=...) → 上传完整版本\n\n"
                             + "⚡ **如果确认要覆盖**（例如想精简文档为短版本），请设置 force=true 跳过此检查。";
                    }
                }
            }
            
            Doc updateDoc = new Doc();
            updateDoc.setId(id);
            // E9：记录实际写了哪些字段，用于返回回显（解决「传空串也返回已更新」的假成功）
            List<String> changedFields = new java.util.ArrayList<>();
            String cleanedContent = null;
            if (title != null && !title.isEmpty()) { updateDoc.setTitle(title); changedFields.add("title"); }
            if (content != null && !content.isEmpty()) {
                // E11：写入前剥离渲染痕迹（历史导读/关联经验/文档结尾/终端残留）
                // 这是 ID=101 导读叠了 5 层的根因：读出来是渲染视图，写回去就把注入内容固化了
                cleanedContent = stripRenderArtifacts(content);
                updateDoc.setContent(cleanedContent);
                changedFields.add("content");
            }
            if (category != null && !category.isEmpty()) { updateDoc.setCategory(category); changedFields.add("category"); }
            if (tags != null && !tags.isEmpty()) { updateDoc.setTags(tags); changedFields.add("tags"); }
            if (aliases != null && !aliases.isEmpty()) { updateDoc.setAliases(aliases); changedFields.add("aliases"); }
            if (summary != null && !summary.isEmpty()) { updateDoc.setSummary(summary); changedFields.add("summary"); }
            if (relatedIds != null && !relatedIds.isEmpty()) { updateDoc.setRelatedIds(relatedIds); changedFields.add("relatedIds"); }
            if (authorName != null && !authorName.isEmpty()) { updateDoc.setAuthorName(authorName); changedFields.add("改文档作者"); }
            // updatedName 不进 changedFields：它只影响本版署名，不改任何内容。
            // 若算进去，只传 updatedName 也会「成功」并产生一个没有任何内容变化的空版本。

            // A2：显式清空（null 无法表达清空，见 DocService.update 注释）
            java.util.Set<String> clearSet = parseClearFields(clearFields);
            for (String f : clearSet) changedFields.add("清空:" + f);

            // 一个字段都没变 + 也没要求改归属 → 明确告知「什么都没改」，不要报假成功
            if (changedFields.isEmpty() && projectId == null) {
                return "⚠️ 没有任何字段被修改：所有参数都是空值，或与传入值相同。\n\n"
                     + "本次未写入任何内容，文档仍是 v" + existing.getVersion() + "。\n"
                     + "如果要修改某个字段，请传入非空值；如果只想调整归属，请传 projectId（0=移回全局）。";
            }

            // 归属调整（E7 的对称面）：0 = 移回全局，>0 = 归入项目，不传 = 不动
            // 注意必须在 docService.update 之后单独处理：
            // updateById 会忽略 null 字段，无法把 project_id 置空（会静默失败）
            Long targetProjectId = null;
            boolean changeScope = false;
            if (projectId != null) {
                changeScope = true;
                if (projectId > 0) {
                    Project target = projectService.getProject(projectId);
                    if (target == null) {
                        return "❌ 未找到项目ID " + projectId + "，或你没有访问权限。可用 list_projects 查看你的项目。";
                    }
                    targetProjectId = projectId;
                }
            }

            docService.update(id, updateDoc, clearSet, updatedName);
            if (changeScope) {
                docService.updateProjectId(id, targetProjectId);
            }

            // E9：回显改动，让调用方当场就能核对，不必再读一次
            StringBuilder res = new StringBuilder();
            res.append("✅ 经验更新成功！\n\n");
            res.append("**ID**: ").append(id).append("\n");
            res.append("**新版本**: v").append(existing.getVersion() + 1).append("\n");
            res.append("**已修改字段**: ").append(changedFields.isEmpty() ? "（无，仅调整归属）" : String.join(", ", changedFields)).append("\n");
            // 回执自证值（AGENTS.md 第 10 章）：署名必回显，别让调用方事后去列表里才发现记成了别人。
            // 回填后重新读一次，显示的是**库里真实的值**，不是复述入参
            Doc afterUpdate = docService.getById(id);
            String realUpdatedName = lastSnapshotAuthor(id);
            res.append("**本版署名**: ").append(realUpdatedName == null ? "（未记录）" : realUpdatedName);
            if (updatedName == null || updatedName.trim().isEmpty()) {
                res.append("　← 本次未传 updatedName，走 Key 绑定的助手名兜底");
            }
            res.append("\n");
            res.append("**文档作者**: ")
               .append(afterUpdate != null && afterUpdate.getAuthorName() != null ? afterUpdate.getAuthorName() : "未知")
               .append(changedFields.contains("改文档作者") ? "（本条已改归属）" : "（未改动）")
               .append("\n");
            if (changedFields.contains("content")) {
                int oldLen = existing.getContent() == null ? 0 : existing.getContent().length();
                int stripped = content.length() - (cleanedContent == null ? content.length() : cleanedContent.length());
                res.append("**正文字数**: ").append(oldLen).append(" → ").append(cleanedContent.length()).append(" 字\n");
                if (stripped > 0) {
                    res.append("**已剥离渲染痕迹**: ").append(stripped)
                       .append(" 字（历史导读/关联经验/文档结尾/终端残留）——它们由读取端生成，不应存进正文\n");
                }
                res.append("\n**改后开头**:\n> ")
                   .append(cleanedContent.substring(0, Math.min(200, cleanedContent.length())).replace("\n", "\n> "))
                   .append(cleanedContent.length() > 200 ? "…" : "")
                   .append("\n");
            }
            if (changeScope) {
                res.append("\n**归属**: ").append(targetProjectId == null
                        ? "已移回全局（全局搜索可命中）"
                        : "已归入项目 " + targetProjectId + "（项目内隔离，不进全局搜索）").append("\n");
            }
            res.append("\n经验已更新，其他AI助手可以看到最新内容。");
            return res.toString();
        } catch (Exception e) {
            log.error("ExpHubTools.updateExperience: FAILED", e);
            callLogService.incrementFailCalls();
            return "更新失败: " + e.getClass().getName() + " - " + e.getMessage();
        }
    }

    /**
     * 读该文档**最新一条**快照的操作人署名（回执自证值用）。
     * 不缓存、不猜，直接查库 —— 回执要展示的是库里真实的值。
     */
    private String lastSnapshotAuthor(Long id) {
        try {
            java.util.List<DocVersion> vs = docService.getVersions(id);
            if (vs == null || vs.isEmpty()) return null;
            // getVersions 按版本倒序返回最新在前；取第一条的署名
            return vs.get(0).getUpdatedName();
        } catch (Exception e) {
            log.warn("lastSnapshotAuthor failed: id={} err={}", id, e.getMessage());
            return null;
        }
    }

    /**
     * A1：追加内容到正文末尾。
     *
     * <p>解决的核心问题：以前「补充一段进展」只能 update_experience 整篇写回，
     * 而 AI 读到的正文是截断过的，写回就等于删掉没读到的部分。
     * 这里服务端直接拼接，客户端完全不持有全文。
     */
    @Tool(name = "append_experience", description = "在经验正文**末尾追加**一段内容。这是补充进展/追加结论的首选方式：服务端直接拼接，你不需要读全文，因此绝不会因为读取被截断而丢掉原文（用 update_experience 整篇写回才会丢）。只在尾部追加、不会缩短原文，不触发截断保护。一次传一段；要多段就多次调用。")
    public String appendExperience(
            @ToolParam(description = "经验ID") Long id,
            @ToolParam(description = "要追加的 Markdown 片段（不要带原正文）。建议以小标题开头，如「### 2026-10-25 补充：xxx」，便于日后区分批次") String text,
            @ToolParam(description = "可选。署名，记入版本快照。不传=用当前 API Key 绑定的助手名。同一 Key 被多个助手共用时建议传，否则所有改动都会记在同一个人名下", required = false) String authorName,
            @ToolParam(description = "可选。**谁改的**——操作人署名（正式名，建议统一用这个）。与 `authorName` 等价，两者都传时以本参数为准", required = false) String updatedName) {

        try {
            AiAssistant assistant = getCaller();
            if (assistant != null && !Boolean.TRUE.equals(assistant.getCanUpdate())) {
                return "❌ 权限不足：该API Key没有编辑经验的权限";
            }
            if (text == null || text.trim().isEmpty()) {
                return "❌ 追加内容不能为空。请传入要追加的 Markdown 片段。";
            }
            Doc existing = docService.getById(id);
            if (existing == null) {
                return "❌ 未找到ID为" + id + "的经验";
            }

            int oldVer = existing.getVersion();
            int oldLen = existing.getContent() == null ? 0 : existing.getContent().length();
            Doc updated = docService.appendContent(id, text, updatedName != null && !updatedName.trim().isEmpty() ? updatedName.trim() : authorName);
            int newLen = updated.getContent() == null ? 0 : updated.getContent().length();

            StringBuilder sb = new StringBuilder();
            sb.append("✅ 已追加到正文末尾\n\n");
            sb.append("**ID**: ").append(id).append("\n");
            sb.append("**标题**: ").append(updated.getTitle()).append("\n");
            sb.append("**版本**: v").append(oldVer).append(" → v").append(updated.getVersion()).append("\n");
            sb.append("**正文字数**: ").append(oldLen).append(" → ").append(newLen)
              .append("（+").append(newLen - oldLen).append("）\n");
            String addPreview = text.trim();
            if (addPreview.length() > 120) addPreview = addPreview.substring(0, 120) + "…";
            sb.append("**追加内容开头**:\n> ").append(addPreview.replace("\n", "\n> ")).append("\n\n");
            sb.append("原文完整保留（服务端拼接，未经过任何截断），旧版本已存为快照 v").append(oldVer).append("。");
            return sb.toString();
        } catch (Exception e) {
            log.error("ExpHubTools.appendExperience: FAILED", e);
            callLogService.incrementFailCalls();
            return "追加失败: " + e.getClass().getName() + " - " + e.getMessage();
        }
    }

    /**
     * 署名口径（B1）：调用方自报优先，其次 Key 绑定的助手名。
     * 一个 Key 只能绑一个名字，而同一 Key 常被多个助手共用，所以必须允许自报。
     */
    static String resolveAuthor(String authorName, AiAssistant assistant) {
        if (authorName != null && !authorName.trim().isEmpty()) return authorName.trim();
        if (assistant != null && assistant.getAssistantName() != null
                && !assistant.getAssistantName().trim().isEmpty()) {
            return assistant.getAssistantName().trim();
        }
        return "unknown";
    }

    /** 可被 clearFields 清空的字段白名单（title/content 不在内：清空它们没有合理语义） */
    static final java.util.Set<String> CLEARABLE_FIELDS = java.util.Set.of(
            "tags", "aliases", "summary", "relatedIds", "related_ids", "category");

    /**
     * 解析 clearFields 参数。包级静态便于单测。
     * 只保留白名单内的字段，其余忽略 —— 防止调用方写 "content" 把正文清空。
     */
    static java.util.Set<String> parseClearFields(String clearFields) {
        java.util.Set<String> out = new java.util.LinkedHashSet<>();
        if (clearFields == null || clearFields.trim().isEmpty()) return out;
        for (String f : clearFields.split("[,，]")) {
            String v = f.trim();
            if (!v.isEmpty() && CLEARABLE_FIELDS.contains(v)) out.add(v);
            else if (!v.isEmpty()) log.warn("parseClearFields: 忽略不可清空字段 '{}'", v);
        }
        return out;
    }

    /** patch 锚点的最小长度：太短的 find 必然多命中，改了就是事故 */
    private static final int PATCH_MIN_ANCHOR = 4;

    /**
     * A1：局部替换正文（服务端执行）。
     *
     * <p>比 update 整篇写回安全得多：只替换命中的那一段，其余原样保留。
     * 但必须保证 find 唯一 —— 多处命中时宁可报错让调用方加长锚点，也不能替它猜改哪一处。
     */
    @Tool(name = "patch_experience", description = "在经验正文里做**局部替换**（find → replace）。改一个错字、更新一行结论、替换一个失效命令时用这个，不要用 update_experience 整篇写回。⚠️ find 必须在正文里**恰好出现一次**：0 次会报错（锚点不存在，先 get_experience_detail 确认原文），多次也会报错（会误改别处，请加长 find 使其唯一）。find 至少 4 个字符。replace 传空字符串表示删除该段。")
    public String patchExperience(
            @ToolParam(description = "经验ID") Long id,
            @ToolParam(description = "要被替换的原文片段，必须在正文中恰好出现一次，至少 4 个字符。建议包含前后文让锚点唯一") String find,
            @ToolParam(description = "替换成的新内容。传空字符串表示删除这段") String replace,
            @ToolParam(description = "可选。署名，记入版本快照。不传=用当前 API Key 绑定的助手名", required = false) String authorName,
            @ToolParam(description = "可选。**谁改的**——操作人署名（正式名，建议统一用这个）。与 `authorName` 等价，两者都传时以本参数为准", required = false) String updatedName) {

        try {
            AiAssistant assistant = getCaller();
            if (assistant != null && !Boolean.TRUE.equals(assistant.getCanUpdate())) {
                return "❌ 权限不足：该API Key没有编辑经验的权限";
            }
            if (find == null || find.trim().isEmpty()) {
                return "❌ find 不能为空。请传入要替换的原文片段。";
            }
            if (find.length() < PATCH_MIN_ANCHOR) {
                return "❌ find 太短（" + find.length() + " 字符，最少 " + PATCH_MIN_ANCHOR
                        + "）。太短的锚点几乎必然命中多处，会改错地方。请带上前后文让它唯一。";
            }
            Doc existing = docService.getById(id);
            if (existing == null) {
                return "❌ 未找到ID为" + id + "的经验";
            }

            String body = existing.getContent() == null ? "" : existing.getContent();
            int hits = DocService.countOccurrences(body, find);
            if (hits == 0) {
                return "❌ 正文里没有找到该片段，未做任何修改（仍是 v" + existing.getVersion() + "）。\n\n"
                        + "**你找的**: `" + abbreviate(find, 80) + "`\n\n"
                        + "可能原因：原文已被改过、换行/空格不一致、或你记错了。\n"
                        + "建议先 get_experience_detail(id=" + id + ") 看当前正文，或 export_document 拿完整内容。";
            }
            if (hits > 1) {
                return "❌ 该片段在正文中出现 **" + hits + " 次**，未做任何修改（仍是 v" + existing.getVersion() + "）。\n\n"
                        + "多处命中时替换会误改别的地方，所以拒绝执行。请把 find 加长到唯一（多带前后各一行最有效）。\n\n"
                        + "**各处位置**:\n" + describeHits(body, find, 3);
            }

            int oldVer = existing.getVersion();
            int oldLen = body.length();
            Doc updated = docService.patchContent(id, find, replace, updatedName != null && !updatedName.trim().isEmpty() ? updatedName.trim() : authorName);
            String newBody = updated.getContent() == null ? "" : updated.getContent();

            int pos = body.indexOf(find);
            StringBuilder sb = new StringBuilder();
            sb.append("✅ 已局部替换（唯一命中，未动其他内容）\n\n");
            sb.append("**ID**: ").append(id).append("\n");
            sb.append("**版本**: v").append(oldVer).append(" → v").append(updated.getVersion()).append("\n");
            sb.append("**替换位置**: 第 ").append(pos + 1).append(" 个字符起\n");
            sb.append("**字数**: ").append(oldLen).append(" → ").append(newBody.length())
              .append("（").append(newBody.length() - oldLen >= 0 ? "+" : "")
              .append(newBody.length() - oldLen).append("）\n\n");
            sb.append("**原文**: `").append(abbreviate(find, 100)).append("`\n");
            sb.append("**改为**: `").append(replace == null || replace.isEmpty() ? "（已删除）" : abbreviate(replace, 100)).append("`\n\n");
            sb.append("旧版本已存为快照 v").append(oldVer).append("。");
            return sb.toString();
        } catch (Exception e) {
            log.error("ExpHubTools.patchExperience: FAILED", e);
            callLogService.incrementFailCalls();
            return "局部替换失败: " + e.getClass().getName() + " - " + e.getMessage();
        }
    }

    @Tool(name = "set_flags", description = "给经验打**机器可读标记**（待复核 / 疑似重复 / 已被取代 / 可能失效 / 质量存疑）。整理记忆、去重、标注可疑结论时用这个，**不要用 update_experience 改 tags 来打标记**。关键差别：本工具不递增版本号、不写版本快照、不动更新时间，所以反复打标记不会让版本表膨胀。flags 传空字符串 = 清空所有标记（清空时说明与署名也一并清掉）。检索和详情会回显标记，其他 AI 看到标记就知道这条结论存疑。")
    public String setFlags(
            @ToolParam(description = "经验ID") Long id,
            @ToolParam(description = "标记，逗号分隔。规范值：needs_review(待复核) / duplicate(疑似重复) / superseded(已被取代) / stale(可能失效) / low_quality(质量存疑)。也认中文：待复核、重复、取代、过期/失效、存疑/低质。传空字符串表示清空全部标记。不在白名单里的词会被忽略并在返回里列出") String flags,
            @ToolParam(description = "可选。标记说明：为什么打这个标记、复核了什么、和哪条重复（如「与 ID:377 结论重复，以 377 为准」）。这是给人和后续 AI 看的关键信息，建议写", required = false) String note,
            @ToolParam(description = "可选。署名（谁打的标记）。不传=用当前 API Key 绑定的助手名。同一 Key 被多个助手共用时建议传", required = false) String authorName,
            @ToolParam(description = "可选。**谁改的**——操作人署名（正式名，建议统一用这个）。与 `authorName` 等价，两者都传时以本参数为准", required = false) String updatedName) {

        try {
            AiAssistant assistant = getCaller();
            if (assistant != null && !Boolean.TRUE.equals(assistant.getCanUpdate())) {
                return "❌ 权限不足：该API Key没有编辑经验的权限";
            }
            Doc existing = docService.getById(id);
            if (existing == null) {
                return "❌ 未找到ID为" + id + "的经验";
            }

            List<String> parsed = DocService.parseFlags(flags);
            List<String> rejected = DocService.rejectedFlagTokens(flags);
            // 署名优先用调用方自报的（同一 Key 只能绑一个 assistant_name，多助手共用时会打架）
            String by = (authorName != null && !authorName.trim().isEmpty())
                    ? authorName.trim()
                    : ((assistant != null && assistant.getAssistantName() != null)
                        ? assistant.getAssistantName() : "unknown");

            Doc updated = docService.setFlags(id, parsed, note, by);

            StringBuilder sb = new StringBuilder();
            if (parsed.isEmpty()) {
                sb.append("✅ 已清空标记\n\n");
            } else {
                sb.append("✅ 已设置标记\n\n");
            }
            sb.append("**ID**: ").append(id).append("\n");
            sb.append("**标题**: ").append(updated.getTitle()).append("\n");
            if (!parsed.isEmpty()) {
                List<String> human = new ArrayList<>();
                for (String f : parsed) human.add(DocService.flagLabel(f) + "(" + f + ")");
                sb.append("**标记**: ").append(String.join("、", human)).append("\n");
                if (note != null && !note.trim().isEmpty()) {
                    sb.append("**说明**: ").append(note.trim()).append("\n");
                }
                sb.append("**署名**: ").append(by).append("\n");
            }
            sb.append("**版本**: v").append(updated.getVersion()).append("（未变）\n");
            sb.append("**版本快照**: 未产生（本工具只改标记，不写版本表）\n");

            if (!rejected.isEmpty()) {
                sb.append("\n⚠️ 以下词不在白名单、已忽略：").append(String.join("、", rejected))
                  .append("\n可用值：needs_review / duplicate / superseded / stale / low_quality\n");
            }

            // 部分生效要说清楚，别让调用方以为全都打上了
            if (!rejected.isEmpty() && !parsed.isEmpty()) {
                sb.append("\n（其余标记已生效。如需这些词，请改用上面的规范值重传）\n");
            }
            sb.append("\n说明：标记不改变正文、不产生历史版本，检索与详情会回显；不需要时再传空串即可清空。");
            return sb.toString();
        } catch (Exception e) {
            log.error("ExpHubTools.setFlags: FAILED", e);
            callLogService.incrementFailCalls();
            return "设置标记失败: " + e.getClass().getName() + " - " + e.getMessage();
        }
    }

    /** 截断显示用（patch 报错信息里回显片段，太长会把返回撑爆） */
    static String abbreviate(String s, int max) {
        if (s == null) return "";
        String one = s.replace("\n", "\\n");
        return one.length() > max ? one.substring(0, max) + "…" : one;
    }

    /** 多处命中时，把前 N 处的位置和上下文列出来，方便调用方加长锚点 */
    private String describeHits(String body, String find, int limit) {
        StringBuilder sb = new StringBuilder();
        int idx = 0, n = 0;
        while (n < limit && (idx = body.indexOf(find, idx)) >= 0) {
            int from = Math.max(0, idx - 30);
            int to = Math.min(body.length(), idx + find.length() + 30);
            sb.append("- 第 ").append(idx + 1).append(" 字符处：…")
              .append(body.substring(from, to).replace("\n", " "))
              .append("…\n");
            idx += find.length();
            n++;
        }
        return sb.toString();
    }

    /**
     * 详情页的取代关系块。
     *
     * <p>双向都展示：被谁取代（硬结论，放最前）、本条取代了谁（留存历史用）。
     * 反向查询只在详情做，列表不做 —— 列表每行查一次就是 N+1。
     */
    /**
     * 存疑横幅：取代关系 + 标记。detail 与 export 共用。
     *
     * <p>为什么必须共用一份文案：RenderArtifactStripper 是**按字面前缀**剥的
     * （`**⛔ 已被取代**:` / `> 结论以新条为准` / `**⚑ 标记**:` / `> ⚠️ 本条被标记为`）。
     * 如果 detail 和 export 各写一套措辞，剥离规则必然只能覆盖其中一边，
     * 另一边就会在「读出来 → 改一点 → 整篇写回」时被固化进正文 —— 而且那行提示
     * 不会随后续清标记而消失，等于给文档刻了个假碑。
     */
    private String doubtBanner(Doc doc, Long id) {
        StringBuilder sb = new StringBuilder();
        sb.append(renderSupersedeBlock(doc, id));
        sb.append(renderFlagBlock(doc));
        return sb.toString();
    }

    /** 标记块（set_flags 回显）：只输出 ⚑ 相关几行，与 detail / export 共用 */
    private String renderFlagBlock(Doc doc) {
        if (doc.getFlags() == null || doc.getFlags().isBlank()) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        sb.append("**⚑ 标记**: ").append(DocService.flagLabelText(doc.getFlags()))
          .append(" (").append(doc.getFlags()).append(")\n");
        if (doc.getFlagNote() != null && !doc.getFlagNote().isBlank()) {
            sb.append("**标记说明**: ").append(doc.getFlagNote()).append("\n");
        }
        if (doc.getFlagMarkedBy() != null) {
            sb.append("**标记人**: ").append(doc.getFlagMarkedBy())
              .append(doc.getFlagMarkedAt() != null ? " | " + doc.getFlagMarkedAt() : "").append("\n");
        }
        sb.append("> ⚠️ 本条被标记为「").append(DocService.flagLabelText(doc.getFlags()))
          .append("」，引用前请先核实；如标记已过时，可调用 cancel 或 set_flags 清空。\n");
        return sb.toString();
    }

    private String renderSupersedeBlock(Doc doc, Long id) {
        StringBuilder sb = new StringBuilder();
        if (doc.getSupersededBy() != null) {
            Doc rep = docService.getById(doc.getSupersededBy());
            sb.append("**⛔ 已被取代**: 本条（#").append(id).append("）已被 **#")
              .append(doc.getSupersededBy()).append("**");
            if (rep != null && rep.getTitle() != null) {
                sb.append("「").append(rep.getTitle()).append("」");
            }
            sb.append(" 取代\n");
            sb.append("> 结论以新条为准，本条保留仅为留存历史。若判断有误，"
                    + "可调用 mark_superseded / clear_superseded 修正。\n\n");
        }
        var replaced = docService.listSuperseding(id);
        if (!replaced.isEmpty()) {
            sb.append("**📌 本条取代了**: ");
            for (int i = 0; i < replaced.size(); i++) {
                Doc r = replaced.get(i);
                if (i > 0) sb.append("、");
                sb.append("#").append(r.getId()).append("「").append(abbreviate(r.getTitle(), 40)).append("」");
            }
            sb.append("\n\n");
        }
        return sb.toString();
    }

    /**
     * D1 施工包 1：标记取代关系。
     *
     * <p>为什么不做「合并」：合并要重写正文，且合并错了不可逆。
     * 只标指针，旧条原文完整保留，判断错了 clear 一下就行。
     */
    @Tool(name = "mark_superseded", description = "标记「旧经验已被新经验取代」（D1 整理动作）。只写一条指针：不合并正文、不改状态、不递增版本、不写版本快照，旧条原文完整保留。之后检索和详情会在被取代的那条上显示「⛔ 已被 #N 取代」，让 AI 知道结论以新条为准。⚠️ 只标关系，不删除任何内容；关系错了用 clear_superseded 撤销。")
    public String markSuperseded(
            @ToolParam(description = "被取代的旧经验 ID") Long id,
            @ToolParam(description = "取代它的新经验 ID（必须真实存在）。通常是内容更准确、时间更新的那一条") Long byId,
            @ToolParam(description = "可选。操作人署名，仅写日志。不传=用当前 API Key 的助手名", required = false) String authorName,
            @ToolParam(description = "可选。**谁改的**——操作人署名（正式名，建议统一用这个）。与 `authorName` 等价，两者都传时以本参数为准（本工具只写日志，两者等价）", required = false) String updatedName) {

        try {
            AiAssistant assistant = getCaller();
            if (assistant != null && !Boolean.TRUE.equals(assistant.getCanUpdate())) {
                return "❌ 权限不足：该API Key没有编辑经验的权限";
            }
            if (id == null || byId == null) {
                return "❌ id 与 byId 都必填。id=被取代的旧条，byId=取代它的新条。";
            }
            String by = resolveAuthor(updatedName != null ? updatedName : authorName, assistant);
            DocService.SupersedeResult r = docService.markSuperseded(id, byId, by);
            if (!r.ok()) {
                return "❌ " + r.message() + "\n\n未做任何改动。";
            }
            Doc updated = r.doc();
            StringBuilder sb = new StringBuilder();
            sb.append("✅ ").append(r.message()).append("\n\n");
            sb.append("**被取代**: #").append(id).append("「").append(abbreviate(updated.getTitle(), 50)).append("」\n");
            Doc rep = docService.getById(byId);
            sb.append("**取代者**: #").append(byId).append("「")
              .append(rep != null ? abbreviate(rep.getTitle(), 50) : "?").append("」\n");
            sb.append("**版本**: v").append(updated.getVersion()).append("（未变）｜")
              .append("**版本快照**: 未产生（本工具只写关系，不写版本表）\n\n");
            sb.append("检索与详情现在会在 #").append(id).append(" 上显示「⛔ 已被 #")
              .append(byId).append(" 取代」。原文完整保留，随时可 clear_superseded 撤销。");
            return sb.toString();
        } catch (Exception e) {
            log.error("ExpHubTools.markSuperseded: FAILED", e);
            callLogService.incrementFailCalls();
            return "标记取代关系失败: " + e.getClass().getName() + " - " + e.getMessage();
        }
    }

    /** 撤销取代关系（把指针清空，两条都回到「谁也没取代谁」） */
    @Tool(name = "clear_superseded", description = "撤销「已被取代」标记，把该经验恢复成独立条目（不影响正文、状态、标记）。用于标错了、或两条其实都还有效的情况。")
    public String clearSuperseded(
            @ToolParam(description = "要撤销取代关系的经验 ID（原本被标记为「已被取代」的那条）") Long id,
            @ToolParam(description = "可选。操作人署名，仅写日志", required = false) String authorName,
            @ToolParam(description = "可选。**谁改的**——操作人署名（正式名，建议统一用这个）。与 `authorName` 等价，两者都传时以本参数为准（本工具只写日志，两者等价）", required = false) String updatedName) {
        try {
            AiAssistant assistant = getCaller();
            if (assistant != null && !Boolean.TRUE.equals(assistant.getCanUpdate())) {
                return "❌ 权限不足：该API Key没有编辑经验的权限";
            }
            Doc before = docService.getById(id);
            if (before == null) {
                return "❌ 未找到 ID 为 " + id + " 的经验";
            }
            if (before.getSupersededBy() == null) {
                return "ℹ️ #" + id + " 本来就没有被标记为「已被取代」，未做改动。";
            }
            Long was = before.getSupersededBy();
            Doc updated = docService.clearSuperseded(id);
            return "✅ 已撤销取代关系\n\n**ID**: " + id
                    + "\n**原来被 #" + was + " 取代**，现已恢复为独立条目"
                    + "\n**版本**: v" + updated.getVersion() + "（未变）｜**版本快照**: 未产生";
        } catch (Exception e) {
            log.error("ExpHubTools.clearSuperseded: FAILED", e);
            callLogService.incrementFailCalls();
            return "撤销失败: " + e.getClass().getName() + " - " + e.getMessage();
        }
    }

    // ==================== D1 施工包 3：整理候选队列 ====================
    //
    // 候选对必须落库。只放在返回文本里的话，下一次调用就没了，人也没机会判断，
    // 于是同一对重复被算出来、重复被忽略 —— 整理永远推进不下去。

    /**
     * 入队一对候选。L0/L1/L2 或人工都能调。
     * 已经判成「不同」的对不会重新入队，避免每周重复打扰。
     */
    @Tool(name = "add_review_candidate", description = "把一对「可能重复/可能互相取代」的经验放进整理候选队列，等人工或后续判断。用于整理记忆时发现两条内容高度重合的场景。⚠️ 入队本身**不改任何数据**，只是记一笔待判断；同一对不会重复入队，已判为「不同」的更不会。")
    public String addReviewCandidate(
            @ToolParam(description = "候选对的第一条经验 ID") Long docA,
            @ToolParam(description = "候选对的第二条经验 ID") Long docB,
            @ToolParam(description = "可选。相似度得分，取值 (**0, 1]**，只用于排序，不是判决依据。没算出来就别传（传 0 会被当「未提供」存 NULL，因为相似度 0 的语义是「完全不像」；传大于 1 的值会被拒，若是百分制请除以 100）", required = false) java.math.BigDecimal score,
            @ToolParam(description = "可选但强烈建议。为什么判为相似，例如「标签交集:3（nginx,https,证书）；标题重叠 0.62；正文共享 2-gram 41%」。写清理由，人才判得快", required = false) String reasons,
            @ToolParam(description = "可选。来源标记：L0_CREATE / L1_DAILY / L2_WEEKLY / MANUAL。默认 MANUAL", required = false) String source,
            @ToolParam(description = "可选。**谁改的**——操作人署名（建议统一用这个）。不传=用当前 API Key 绑定的助手名", required = false) String updatedName) {
        try {
            AiAssistant assistant = getCaller();
            if (assistant != null && !Boolean.TRUE.equals(assistant.getCanUpdate())) {
                return "❌ 权限不足：该API Key没有编辑经验的权限";
            }
            // 谁入的队：写进日志，便于回溯（候选表本身不存 operator，避免过度设计）
            String operator = resolveAuthor(updatedName, assistant);
            ReviewCandidateService.EnqueueResult r =
                    reviewCandidateService.enqueue(docA, docB, score, reasons, source);
            log.info("addReviewCandidate: by={} docA={} docB={}", operator, docA, docB);
            if (r.candidate() == null) {
                return "❌ " + r.message();
            }

            // 回执自证值：不能只说「已刷新得分」，必须说清记成了多少。
            // 上一版就是因为只说动作不说结果，调用方以为传 0.92 就记了 0.92，
            // 实际库里是 0.0000，第二天从列表里才看出来。
            StringBuilder sb = new StringBuilder();
            sb.append(r.created() ? "✅ " : "ℹ️ ").append(r.message()).append("\n\n");
            sb.append("**入库值核对**\n");
            sb.append("- 候选 ID: ").append(r.candidate().getId()).append("\n");
            // formatScore 已经把 null 渲染成「未记录」，这里不要再拼一次括号
            sb.append("- 相似度: ").append(ReviewCandidateService.formatScore(r.candidate().getScore())).append("\n");
            String discard = ReviewCandidateService.scoreDiscardReason(score);
            if (discard != null) {
                sb.append("- ⚠️ 得分未入库：").append(discard).append("\n");
            }
            sb.append("- 来源: ").append(r.candidate().getSource()).append("\n");
            sb.append("- 状态: ").append(r.candidate().getStatus()).append("\n");
            if (r.candidate().getReasons() != null && !r.candidate().getReasons().isBlank()) {
                sb.append("- 理由: ").append(abbreviate(r.candidate().getReasons(), 120)).append("\n");
            }
            sb.append("\n用 list_review_candidates 查看队列，用 resolve_candidate 做判断。");
            return sb.toString();
        } catch (Exception e) {
            log.error("ExpHubTools.addReviewCandidate: FAILED", e);
            callLogService.incrementFailCalls();
            return "入队失败: " + e.getClass().getName() + " - " + e.getMessage();
        }
    }

    /** 列出候选队列（默认只看待判断的） */
    @Tool(name = "list_review_candidates", description = "查看记忆整理候选队列：哪些经验对可能重复或互相取代、相似度多少、为什么判为相似。默认只看 PENDING（待判断）的，按得分倒序；status=ALL 可看全部状态。看完用 resolve_candidate 处理。")
    public String listReviewCandidates(
            @ToolParam(description = "可选。状态：PENDING（默认，待判断）/ DUPLICATE / SUPERSEDED / DISTINCT / SKIPPED；**ALL = 不限状态**（与 check_my_todos 的 ALL 语义一致）", required = false) String status,
            @ToolParam(description = "可选。最多返回几条，默认 20，上限 50", required = false) Integer limit) {
        try {
            int size = (limit == null || limit < 1) ? 20 : Math.min(limit, 50);
            String st;
            try {
                st = ReviewCandidateService.normalizeStatusFilter(status);
            } catch (IllegalArgumentException bad) {
                // 非法状态值不能静默返回空：拼错一个字母就「队列空了」，
                // 人会以为真的没数据，而不是「我查错了」。直接给出可用值。
                return "❌ " + bad.getMessage();
            }
            List<ReviewCandidate> list = reviewCandidateService.listByStatus(status, size);
            String scope = (st == null) ? "ALL（不限状态）" : st;
            if (list.isEmpty()) {
                return "📭 队列为空（状态 " + scope + "）。\n\n"
                        + (st == null
                            ? "全部状态都没有任何候选记录。\n"
                            : "整理新候选：发现两条内容高度重合时用 add_review_candidate 入队。\n")
                        + "想看所有状态用 status=ALL。";
            }
            // 批量取标题，避免逐条查
            java.util.Set<Long> ids = new java.util.LinkedHashSet<>();
            for (ReviewCandidate c : list) { ids.add(c.getDocA()); ids.add(c.getDocB()); }
            Map<Long, Doc> docMap = new HashMap<>();
            for (Doc d : docService.listByIds(ids)) docMap.put(d.getId(), d);

            StringBuilder sb = new StringBuilder();
            sb.append("🗂 整理候选队列 · ").append(scope).append("（").append(list.size()).append(" 条）\n\n");
            int i = 1;
            for (ReviewCandidate c : list) {
                Doc a = docMap.get(c.getDocA());
                Doc b = docMap.get(c.getDocB());
                sb.append(i++).append(". 「候选 #").append(c.getId()).append("」");
                // ⚠️ 2026-10-25 阁主验收发现：原来这里不输出状态，
                // status=ALL 时 PENDING 和 DISTINCT 混排、长得一模一样，只能靠数总数推断。
                // 状态是这个列表最关键的字段之一，必须逐行给出。
                sb.append(" **").append(c.getStatus()).append("**（")
                  .append(ReviewCandidateService.statusLabel(c.getStatus())).append("）");
                // 得分缺失时明确写「未记录」，不要静默不显示 ——
                // 否则「没记分」和「记了 0 分」在列表里看起来一样
                sb.append("｜相似度 ").append(ReviewCandidateService.formatScore(c.getScore()))
                  .append(c.getScore() == null ? "（未记录）" : "");
                sb.append("｜来源 ").append(c.getSource()).append("\n");
                // 已判过的显示谁判的、什么时候判的、判词是什么 —— 复盘时「当时为什么这么判」比结论更重要
                if (!ReviewCandidate.PENDING.equals(c.getStatus()) && c.getResolvedAt() != null) {
                    sb.append("   判定: ").append(c.getResolvedBy() == null ? "（未记录）" : c.getResolvedBy())
                      .append(" · ").append(c.getResolvedAt().format(java.time.format.DateTimeFormatter
                              .ofPattern("yyyy-MM-dd HH:mm")));
                    if (c.getNote() != null && !c.getNote().isBlank()) {
                        sb.append(" · ").append(abbreviate(c.getNote(), 80));
                    }
                    sb.append("\n");
                }
                sb.append("   A: #").append(c.getDocA()).append("「")
                  .append(a != null ? abbreviate(a.getTitle(), 50) : "已不存在").append("」")
                  .append(a != null && a.getSupersededBy() != null ? " ⛔已被#" + a.getSupersededBy() + "取代" : "").append("\n");
                sb.append("   B: #").append(c.getDocB()).append("「")
                  .append(b != null ? abbreviate(b.getTitle(), 50) : "已不存在").append("」")
                  .append(b != null && b.getSupersededBy() != null ? " ⛔已被#" + b.getSupersededBy() + "取代" : "").append("\n");
                if (c.getReasons() != null && !c.getReasons().isBlank()) {
                    sb.append("   理由: ").append(abbreviate(c.getReasons(), 160)).append("\n");
                }
                sb.append("\n");
            }
            sb.append("---\n处理方式：resolve_candidate(candidateId=..., action=duplicate|superseded|distinct|skip, note=...)\n")
              .append("· duplicate = 两条都打重复标记（**不合并**）\n")
              .append("· superseded = 较旧那条标为被较新那条取代（**只写指针**）\n")
              .append("· distinct = 判为不同，以后不再重新入队\n")
              .append("· skip = 本次忽略，下次还会提出来");
            return sb.toString();
        } catch (Exception e) {
            log.error("ExpHubTools.listReviewCandidates: FAILED", e);
            callLogService.incrementFailCalls();
            return "查询候选队列失败: " + e.getClass().getName() + " - " + e.getMessage();
        }
    }

    /** 处理一条候选 */
    @Tool(name = "resolve_candidate", description = "处理一条整理候选：判为重复（duplicate）/ 标取代（superseded）/ 判为不同（distinct）/ 忽略（skip）。⚠️ duplicate 只打标记不合并正文；superseded 只写指针；两种都不会删除任何内容。distinct 之后这一对不再重新入队。")
    public String resolveCandidate(
            @ToolParam(description = "候选 ID（list_review_candidates 返回的「候选 #N」里的 N）") Long candidateId,
            @ToolParam(description = "处理动作：duplicate（确为重复）/ superseded（旧被新取代）/ distinct（判为不同）/ skip（忽略）") String action,
            @ToolParam(description = "可选。判断依据，例如「两条命令完全相同，后者多了 systemd 部分」。写下来，日后回看才知道当时为什么这么判", required = false) String note,
            @ToolParam(description = "可选。处理人署名", required = false) String authorName,
            @ToolParam(description = "可选。**谁改的**——操作人署名（正式名，建议统一用这个）。与 `authorName` 等价，两者都传时以本参数为准", required = false) String updatedName) {
        try {
            AiAssistant assistant = getCaller();
            if (assistant != null && !Boolean.TRUE.equals(assistant.getCanUpdate())) {
                return "❌ 权限不足：该API Key没有编辑经验的权限";
            }
            String by = resolveAuthor(updatedName != null ? updatedName : authorName, assistant);
            ReviewCandidateService.ResolveResult r =
                    reviewCandidateService.resolve(candidateId, action, note, by);
            if (!r.ok()) {
                return "❌ " + r.message();
            }
            String extra = ReviewCandidate.DUPLICATE.equals(r.candidate().getStatus())
                    || ReviewCandidate.SUPERSEDED.equals(r.candidate().getStatus())
                    ? "\n\n记住：没有删除、没有合并正文。要彻底清理仍需人用 mark_for_deletion 标记后由用户在后台确认。"
                    : "";
            return r.message() + "\n\n**候选 #" + candidateId + " 状态** → " + r.candidate().getStatus() + extra;
        } catch (Exception e) {
            log.error("ExpHubTools.resolveCandidate: FAILED", e);
            callLogService.incrementFailCalls();
            return "处理候选失败: " + e.getClass().getName() + " - " + e.getMessage();
        }
    }

    // ==================== D1 施工包 4：相似度候选生成 ====================
    //
    // 包 3 解决了「候选对存哪、谁判过」，但候选从哪来一直靠人工发现。
    // 包 4 补两条自动来源：L0 写入时查重（create / import 之后顺手比一次）、
    // L2 全库两两打分（一条命令把整个库扫一遍）。

    /**
     * 分层标签：告诉人「这一对像的是什么」——比分数更能指导动作。
     *
     * <p>为什么必须有：实测「同话题演进链」（正文 0 行重合）能拿 0.62，
     * 而「正文 33 行完全一致」的真重复只拿 0.51。光看分数人会把演进链当真重复判。
     */
    private String kindLabel(SimilarityScorer.Kind kind, int sharedLines) {
        if (kind == null) return "";
        return switch (kind) {
            case DUPLICATE -> "🏷 疑似**字面重复**（正文 " + sharedLines + " 行完全一致）→ 优先判 duplicate / superseded";
            case SERIES -> "🔗 同话题**演进链**（正文一行不重，只是标签/标题像）→ 大概率该判 superseded，别判 duplicate";
            case UNKNOWN -> "❔ 信息不足（行太少，走 2-gram 兜底）→ 建议人工读一眼";
        };
    }

    /** L0 查重结果转成人能秒读的几行；查重失败也只说失败，不影响主回执 */
    private String similarityDigest(SimilarityService.L0Result r) {
        StringBuilder sb = new StringBuilder();
        sb.append("\n\n🔎 **写入查重**：").append(r.note()).append("\n");
        if (!r.ok()) {
            return sb.toString();
        }
        for (SimilarityService.L0Hit h : r.hits()) {
            sb.append("- #").append(h.docId()).append("「").append(abbreviate(h.title(), 40))
              .append("」相似度 ").append(SimilarityScorer.Score.fmt(h.score()))
              .append("　→ ").append(h.queueState()).append("\n");
            sb.append("  ").append(kindLabel(h.kind(), h.sharedLines())).append("\n");
            sb.append("  ").append(abbreviate(h.reasons(), 200)).append("\n");
        }
        if (!r.hits().isEmpty()) {
            sb.append("判断用 resolve_candidate(candidateId=..., action=duplicate|superseded|distinct)：\n")
              .append("· 字面重复 → duplicate（只打标记不合并）｜ · 后者是前者的新版 → superseded（只写指针）\n")
              .append("· 同一主题的两条独立记录 → distinct（此后这一对不再打扰）\n");
        }
        return sb.toString();
    }

    /** 分层分组标题 */
    private String kindGroupTitle(SimilarityScorer.Kind kind) {
        if (kind == null) return "其他";
        return switch (kind) {
            case DUPLICATE -> "① 疑似字面重复 —— 正文有重合，最该先判";
            case SERIES -> "② 同话题演进链 —— 正文一行不重，别当重复判";
            case UNKNOWN -> "③ 信息不足 —— 需人工读一眼";
        };
    }

    @Tool(name = "scan_similar_docs", description = "全库两两打分，把「可能重复 / 可能互相取代」的经验对放进整理候选队列。整理记忆的主力工具。默认只比同一作用域内的文档（全局库 ↔ 全局库、项目内 ↔ 项目内），跨项目不比 —— 实测跨项目全是假阳性（各项目的 AGENTS.md 章节标题标签全同，两两 0.87 但各自属于不同项目）。⚠️ 只入队、不判定、不改任何正文：重复/取代最终由人用 resolve_candidate 定。结果按性质分层（疑似字面重复 / 同话题演进链 / 信息不足），**层内才按分数排** —— 只看分数会把最该判的那对埋掉。建议先 dryRun=true 看清单再决定是否入队。")
    public String scanSimilarDocs(
            @ToolParam(description = "可选。扫描范围：不传 = 全局库 + 我名下所有项目；0 = 仅全局库；传项目ID = 只看该项目", required = false) Long projectId,
            @ToolParam(description = "可选。阈值，默认 0.50（实测本库 0.50 时约 23 对，人工十分钟能过一遍；想更宽松用 0.45，想更严用 0.55）。允许区间 [0.30, 0.95]，**超出区间会被直接拒绝并说明收到了多少**（不再静默改成 0.30 —— 静默修改会让「参数没生效」这种链路问题查不出来）", required = false) Double threshold,
            @ToolParam(description = "可选。最多返回/入队几条，默认 20，上限 50", required = false) Integer limit,
            @ToolParam(description = "可选。一篇文档最多出现在几条候选里，默认 2 —— 防止同一个模板族（如一批同格式提示词）把队列占满", required = false) Integer maxPerDoc,
            @ToolParam(description = "可选。true = 只算不入队，用来先看清单。默认 false", required = false) Boolean dryRun,
            @ToolParam(description = "可选。**谁改的**——操作人署名（建议统一用这个）。不传=用当前 API Key 绑定的助手名", required = false) String updatedName) {
        try {
            AiAssistant assistant = getCaller();
            if (assistant != null && !Boolean.TRUE.equals(assistant.getCanUpdate())) {
                return "❌ 权限不足：该API Key没有编辑经验的权限";
            }
            boolean dry = Boolean.TRUE.equals(dryRun);
            SimilarityService.ScanResult r = similarityService.scan(projectId, threshold, limit, maxPerDoc, dry,
                    resolveAuthor(updatedName, assistant));
            if (!r.ok()) {
                return "❌ " + r.summary();
            }
            StringBuilder sb = new StringBuilder();
            sb.append(dry ? "🔍 扫描完成（dryRun：**没有入队任何东西**）\n\n" : "🗂 扫描完成并已入队\n\n");
            sb.append("**范围**: ").append(r.summary()).append("\n");
            sb.append("**阈值**: ").append(SimilarityScorer.Score.fmt(r.threshold()))
              .append("｜**比较对数**: ").append(r.pairsCompared())
              .append("｜**超阈值**: ").append(r.aboveThreshold());
            if (r.droppedByCap() > 0) sb.append("｜**限流丢掉**: ").append(r.droppedByCap());
            sb.append("\n");
            if (!dry) {
                sb.append("**入队结果**: 新建 ").append(r.enqueued())
                  .append(" 对｜已在队列(待判断) ").append(r.alreadyPending())
                  .append(" 对｜已判过(不重复入队) ").append(r.alreadyResolved()).append(" 对\n");
            }
            if (r.rows().isEmpty()) {
                sb.append("\n📭 没有超过阈值的候选对。阈值可以调到 0.45 再看一次，或用 add_review_candidate 手工入队。\n");
            } else {
                // 分层统计：让人一眼看出「有几条真该判 duplicate，有几条只是同话题」
                long dup = r.rows().stream().filter(x -> x.kind() == SimilarityScorer.Kind.DUPLICATE).count();
                long ser = r.rows().stream().filter(x -> x.kind() == SimilarityScorer.Kind.SERIES).count();
                long unk = r.rows().size() - dup - ser;
                sb.append("\n**按性质分层**（正文有重合的排前面，不按分数混排）：")
                  .append("疑似字面重复 ").append(dup).append(" 对")
                  .append("｜同话题演进链 ").append(ser).append(" 对");
                if (unk > 0) sb.append("｜信息不足 ").append(unk).append(" 对");
                sb.append("\n\n");
                int i = 1;
                SimilarityScorer.Kind last = null;
                for (SimilarityService.Row row : r.rows()) {
                    // 层与层之间插一条分隔，让「分数低的反而更重要」这件事无法被忽略
                    if (row.kind() != last) {
                        if (last != null) sb.append("---\n");
                        sb.append("**【").append(kindGroupTitle(row.kind())).append("】**\n");
                        last = row.kind();
                    }
                    sb.append(i++).append(". ").append(SimilarityScorer.Score.fmt(row.score()))
                      .append("｜#").append(row.docA()).append("「").append(abbreviate(row.titleA(), 34))
                      .append("」 ↔ #").append(row.docB()).append("「").append(abbreviate(row.titleB(), 34)).append("」");
                    if (row.queueState() != null) sb.append("　→ ").append(row.queueState());
                    sb.append("\n   ").append(kindLabel(row.kind(), row.sharedLines())).append("\n");
                    sb.append("   ").append(row.reason()).append("\n");
                }
            }
            sb.append("\n---\n");
            sb.append("· **分数只反映「三路信号加权相似度」，不承担排序职责**：先按「正文有没有重合」分层，")
              .append("层内再比分数。实测教训——正文 0 行重合的演进链能拿 0.62，正文 33 行一致的真重复只有 0.51，")
              .append("只按分数排会把最该判的那对压到十几名之后\n");
            for (String n : r.notes()) sb.append("· ").append(n).append("\n");
            if (dry) {
                sb.append("\n确认清单没问题后，用 dryRun=false 再跑一次（相同参数）才会真正入队。\n");
            } else {
                sb.append("\n处理：list_review_candidates 看队列 → resolve_candidate 判 duplicate / superseded / distinct / skip。\n");
            }
            return sb.toString();
        } catch (Exception e) {
            log.error("ExpHubTools.scanSimilarDocs: FAILED", e);
            callLogService.incrementFailCalls();
            return "扫描失败: " + e.getClass().getName() + " - " + e.getMessage();
        }
    }

    // ==================== 项目空间工具（2026-09-20 新增）====================

    @Tool(name = "create_project", description = "创建一个项目（项目空间）。项目归集该项目的 AGENTS.md、进度记录和专属经验，这些数据默认隔离、不会出现在全局搜索中。")
    public String createProject(
            @ToolParam(description = "项目名称，如'回金算'") String name,
            @ToolParam(description = "项目编码，英文唯一标识，如'huijinsuan'") String projectCode,
            @ToolParam(description = "项目别名，逗号分隔，用于模糊搜索，如'废旧件系统,HJS'。可留空", required = false) String aliases,
            @ToolParam(description = "项目描述，可留空", required = false) String description,
            @ToolParam(description = "默认测试命令，工作流模板用，如'mvn test'。可留空", required = false) String defaultTestCmd,
            @ToolParam(description = "默认约束，工作流模板用。可留空", required = false) String defaultConstraints) {
        try {
            Project p = new Project();
            p.setName(name);
            p.setProjectCode(projectCode);
            p.setAliases(aliases);
            p.setDescription(description);
            p.setDefaultTestCmd(defaultTestCmd);
            p.setDefaultConstraints(defaultConstraints);
            Project saved = projectService.createProject(p);
            return "✅ 项目创建成功！\n\n**ID**: " + saved.getId() + "\n**名称**: " + saved.getName()
                 + "\n**编码**: " + saved.getProjectCode() + "\n\n"
                 + "接下来：update_project_agents_main 建立索引 → update_project_agents_chapter 逐章写入。";
        } catch (Exception e) {
            log.error("ExpHubTools.createProject: FAILED", e);
            return "创建失败: " + e.getMessage();
        }
    }

    @Tool(name = "list_projects", description = "列出当前API Key下的所有项目。")
    public String listProjects() {
        try {
            var list = projectService.listProjects();
            if (list.isEmpty()) {
                return "当前没有项目。可用 create_project 创建。";
            }
            StringBuilder sb = new StringBuilder("共 " + list.size() + " 个项目：\n\n");
            for (Project p : list) {
                sb.append("- 【ID:").append(p.getId()).append("】").append(p.getName())
                  .append("（编码: ").append(p.getProjectCode()).append("）");
                if (p.getAliases() != null && !p.getAliases().isEmpty()) {
                    sb.append(" | 别名: ").append(p.getAliases());
                }
                sb.append("\n");
            }
            sb.append("\n提示：搜索项目内经验用 search_experience 的 projectName 参数，填项目名称或别名即可。");
            return sb.toString();
        } catch (Exception e) {
            log.error("ExpHubTools.listProjects: FAILED", e);
            return "查询失败: " + e.getMessage();
        }
    }

    @Tool(name = "get_project", description = "获取项目详情，含默认测试命令与约束。")
    public String getProject(
            @ToolParam(description = "项目ID") Long projectId) {
        try {
            Project p = projectService.getProject(projectId);
            if (p == null) {
                return "❌ 未找到项目ID " + projectId + "，或你没有访问权限。";
            }
            StringBuilder sb = new StringBuilder();
            sb.append("# ").append(p.getName()).append("\n\n");
            sb.append("**ID**: ").append(p.getId()).append("\n");
            sb.append("**编码**: ").append(p.getProjectCode()).append("\n");
            sb.append("**别名**: ").append(p.getAliases() != null ? p.getAliases() : "无").append("\n");
            sb.append("**描述**: ").append(p.getDescription() != null ? p.getDescription() : "无").append("\n");
            sb.append("**默认测试命令**: ").append(p.getDefaultTestCmd() != null ? p.getDefaultTestCmd() : "未设置").append("\n");
            sb.append("**默认约束**: ").append(p.getDefaultConstraints() != null ? p.getDefaultConstraints() : "未设置").append("\n");
            sb.append("**状态**: ").append(p.getStatus()).append("\n");
            return sb.toString();
        } catch (Exception e) {
            log.error("ExpHubTools.getProject: FAILED", e);
            return "查询失败: " + e.getMessage();
        }
    }

    @Tool(name = "update_project", description = "更新项目信息（名称、别名、描述、默认测试命令、默认约束）。不需要修改的字段留空即可。")
    public String updateProject(
            @ToolParam(description = "项目ID") Long projectId,
            @ToolParam(description = "新名称，留空表示不修改", required = false) String name,
            @ToolParam(description = "新别名，留空表示不修改", required = false) String aliases,
            @ToolParam(description = "新描述，留空表示不修改", required = false) String description,
            @ToolParam(description = "默认测试命令，留空表示不修改", required = false) String defaultTestCmd,
            @ToolParam(description = "默认约束，留空表示不修改", required = false) String defaultConstraints) {
        try {
            Project patch = new Project();
            patch.setName(name);
            patch.setAliases(aliases);
            patch.setDescription(description);
            patch.setDefaultTestCmd(defaultTestCmd);
            patch.setDefaultConstraints(defaultConstraints);
            Project updated = projectService.updateProject(projectId, patch);
            if (updated == null) {
                return "❌ 未找到项目ID " + projectId + "，或你没有访问权限。";
            }
            return "✅ 项目已更新！\n\n**ID**: " + updated.getId() + "\n**名称**: " + updated.getName();
        } catch (Exception e) {
            log.error("ExpHubTools.updateProject: FAILED", e);
            return "更新失败: " + e.getMessage();
        }
    }

    @Tool(name = "get_project_agents", description = "读取项目的 AGENTS.md。不传 chapterNo 返回 MAIN 索引（含章节目录与绝对红线）；传 chapterNo 返回该章全文。建议先读索引再按需读章节，避免浪费上下文。")
    public String getProjectAgents(
            @ToolParam(description = "项目ID") Long projectId,
            @ToolParam(description = "可选。章节号，如'1'、'14'。不传则返回 MAIN 索引", required = false) String chapterNo) {
        try {
            Project proj = projectService.getProject(projectId);
            if (proj == null) {
                return "❌ 未找到项目ID " + projectId + "，或你没有访问权限。";
            }

            if (chapterNo != null && !chapterNo.trim().isEmpty()) {
                Doc ch = projectService.getAgentsChapter(projectId, chapterNo.trim());
                if (ch == null) {
                    var chapters = projectService.listAgentsChapters(projectId);
                    StringBuilder sb = new StringBuilder("❌ 项目「" + proj.getName() + "」没有第 " + chapterNo + " 章。\n\n可用章节：\n");
                    for (Doc c : chapters) {
                        sb.append("- 第 ").append(c.getChapterNo()).append(" 章 · ").append(c.getChapterTitle()).append("\n");
                    }
                    return sb.toString();
                }
                StringBuilder sb = new StringBuilder();
                sb.append("# 第 ").append(ch.getChapterNo()).append(" 章 · ").append(ch.getChapterTitle()).append("\n\n");
                sb.append("> 加载策略: ").append(ch.getLoadStrategy()).append(" | 版本: v").append(ch.getVersion()).append("\n\n");
                // 与 MAIN 同理：标题行、加载策略行由代码生成，content 里手写的要剥掉，
                // 否则返回两份标题且版本号脱节（2026-10-24 实测：82 个章节里 6 个中招）
                sb.append(stripChapterHeader(ch.getContent()));
                return sb.toString();
            }

            Doc main = projectService.getAgentsMain(projectId);
            if (main == null) {
                return "⚠️ 项目「" + proj.getName() + "」还没有 AGENTS.md 索引。请用 update_project_agents_main 创建。";
            }
            // 兼容历史脏数据：剥掉 content 里手写的标题行 / 版本行 / 章节目录。
            // 这三样统一由本方法生成（单一数据源）；旧数据重复写过会导致返回两份目录互相矛盾
            // （2026-10-24 实测：6 个项目里 5 个中招，且手写版本号停在 v6、系统已 v7）。
            String body = stripSelfGeneratedParts(main.getContent());

            StringBuilder sb = new StringBuilder();
            sb.append("# ").append(proj.getName()).append(" · AGENTS.md 索引\n\n");
            sb.append("> 版本: v").append(main.getVersion()).append(" | 项目ID: ").append(projectId).append("\n\n");
            sb.append(body);
            var chapters = projectService.listAgentsChapters(projectId);
            if (!chapters.isEmpty()) {
                sb.append("\n\n---\n\n## 章节目录\n\n");
                sb.append("用 get_project_agents 传 chapterNo 读取指定章节全文：\n\n");
                for (Doc c : chapters) {
                    sb.append("- 第 ").append(c.getChapterNo()).append(" 章 · ").append(c.getChapterTitle())
                      .append(" [").append(c.getLoadStrategy()).append("]");
                    // E10：历史数据里大量章节 summary 是创建时的占位值
                    // 「项目 AGENTS.md 第 N 章」，显示出来纯属浪费 token 且无助于判断相关性。
                    // 读取端过滤掉，不要求补写数据（62 个章节全靠人补不现实）。
                    if (c.getSummary() != null && !c.getSummary().isEmpty()
                            && !isPlaceholderSummary(c.getSummary())) {
                        sb.append(" —— ").append(c.getSummary());
                    }
                    sb.append("\n");
                }
            }
            // 完整性标记（2026-10-24）：忆阁复测报告「MAIN 读到一半就断」，
            // 但服务端实测返回完整（4183 字符 / 113 行，末尾有完整章节目录），
            // 说明截断发生在其读取层。加个尾部标记，任何截断都能立刻看出来：
            // 末尾没有这行 = 传输/读取层被截断，而不是 ExpHub 少给了内容。
            String out = sb.toString();
            if (out.length() > 2500) {
                out = out + "\n---\n📄 **返回完整**（共 " + out.length() + " 字符 / "
                        + (out.split("\n", -1).length) + " 行）。"
                        + "若你看到的末尾没有这一行，说明读取层截断了，请分段读取或调大客户端返回上限。\n";
            }
            return out;
        } catch (Exception e) {
            log.error("ExpHubTools.getProjectAgents: FAILED", e);
            return "读取失败: " + e.getMessage();
        }
    }

    /**
     * 剥掉 MAIN content 里手写的标题行、版本行和章节目录段。
     *
     * 设计原则：**单一数据源**——标题 / 版本行 / 章节目录统一由 getProjectAgents 生成，
     * content 只存正文。历史数据里 AI 按旧工具描述把这些写进了 content，
     * 直接返回会出现两份目录且版本号不一致（2026-10-24 实测：手写 v6、系统已 v7）。
     *
     * 只做「读取时清洗」，不改库内容：旧数据仍可正常读；
     * 想要彻底干净，由调用方用 update_project_agents_main 覆盖一次正文。
     */
    /**
     * 判断章节 summary 是否为「创建时的占位默认值」。
     *
     * 实测（2026-10-24）：6 个项目 82 个章节里有 62 个 summary 是
     * 「项目 AGENTS.md 第 N 章」——这是 updateAgentsChapter 新建时填的默认文案，
     * 没人后续补写。目录里显示出来等于括号里写了一句废话。
     */
    static boolean isPlaceholderSummary(String summary) {
        if (summary == null) {
            return true;
        }
        String s = summary.trim();
        if (s.isEmpty()) {
            return true;
        }
        return s.matches("^项目\\s*AGENTS\\.md\\s*第\\s*\\d+\\s*章.*$");
    }

    /** 自动摘要取整段前的最大字数 */
    private static final int AUTO_SUMMARY_MAX = 30;

    /**
     * 从正文自动提取一句目录描述（≤30 字）。
     *
     * 用途：写章节时没传 summary 就自动补，避免目录里出现
     * 「项目 AGENTS.md 第 N 章」这种占位废话（E10 的治本面）。
     *
     * ⚠️ 实测教训 1：很多章节 content 开头就是表格（| 项 | 值 |）或代码块（```bash），
     * 直接取首行会提取出「项  值」「bash」这种垃圾。所以必须跳过
     * 代码块 / 表格行 / 标题 / 引用 / 分隔线，且要求含中文。
     *
     * ⚠️ 实测教训 2（2026-10-24 23:2x 阁主复测）：占位符没了，但换成了「标题回声」——
     * 拆解宝 16 章里有 6 章的摘要等于章节内的小标签或章节标题本身，挑章节时一样没用：
     *   「后端 Controller」（第 3 章，整行加粗的小标签）
     *   「业务术语」「关键文件索引」（第 5/14 章，全篇只有表格，找不到段落就回退成标题）
     * 根因是只认「普通段落」这一种形态，其余全文直接放弃。
     *
     * 所以改成**分级取值**，取不到上一档就用下一档，直到真的没有才回退章节标题：
     *   A 首段正文（跳过整行加粗的小标签——那不是描述，是下一级的标题）
     *   B 整行加粗的小标签清单（如「后端 Controller、PC 页面」）
     *   C 表格首列清单（业务术语章 →「报废车回收单、供货方、拆解厂」）
     *   D 小标题清单（## xxx，最多 3 个）
     *   E 章节标题
     */
    static String autoSummaryFromContent(String content, String chapterTitle) {
        if (content == null) {
            return chapterTitle == null ? "" : chapterTitle;
        }
        String para = null;                       // A
        List<String> labels = new ArrayList<>();  // B
        List<String> col1 = new ArrayList<>();    // C
        List<String> heads = new ArrayList<>();   // D
        boolean inCode = false;
        boolean tableBody = false;
        for (String line : content.split("\n", -1)) {
            String t = line.trim();
            if (t.startsWith("```")) {
                inCode = !inCode;
                tableBody = false;
                continue;
            }
            if (inCode) {
                continue;
            }
            if (t.isEmpty()) {
                continue;
            }
            if (t.startsWith("#")) {
                String h = t.replaceAll("^#+\\s*", "").trim();
                if (!h.isEmpty() && heads.size() < 3) {
                    heads.add(h);
                }
                tableBody = false;
                continue;
            }
            if (t.startsWith(">") || t.startsWith("---")) {
                tableBody = false;
                continue;
            }
            if (t.startsWith("|")) {
                // |---|---| 分隔行之后才是数据行，这样能自动跳过表头
                if (t.replace("|", "").replace("-", "").replace(":", "").trim().isEmpty()) {
                    tableBody = true;
                    continue;
                }
                if (tableBody && col1.size() < 3) {
                    String[] cells = t.split("\\|");
                    if (cells.length >= 2) {
                        String v = cells[1].replaceAll("[*`~_]", "").trim();
                        // 首列太长的（整条文件路径、整句话）不适合当目录摘要
                        if (v.length() >= 2 && v.length() <= 14 && v.matches(".*[\\u4e00-\\u9fa5].*")) {
                            col1.add(v);
                        }
                    }
                }
                continue;
            }
            tableBody = false;
            // 整行加粗＝小标签（**后端 Controller**），不是描述；留给 B 档当候选
            if (t.matches("^\\*\\*[^*]+\\*\\*\\s*$")) {
                String v = t.replace("*", "").trim();
                if (v.length() <= 14 && labels.size() < 3) {
                    labels.add(v);
                }
                continue;
            }
            if (para == null) {
                if (!t.matches(".*[\\u4e00-\\u9fa5].*")) {
                    continue;   // 至少含一个中文，避免「bash」「npm run build」
                }
                String clean = t.replaceAll("[*`>\\[\\]()|]", "").replaceAll("^[-+]\\s+", "").trim();
                if (clean.length() < 4) {
                    continue;
                }
                para = clean;
            }
        }
        String s;
        if (para != null) {
            s = para;
        } else if (!labels.isEmpty()) {
            s = String.join("、", labels);
        } else if (!col1.isEmpty()) {
            s = String.join("、", col1);
        } else if (!heads.isEmpty()) {
            s = String.join("、", heads);
        } else {
            s = chapterTitle == null ? "" : chapterTitle;
        }
        return smartCut(s, AUTO_SUMMARY_MAX);
    }

    /**
     * 按字数截断，但尽量不断在半个词上。
     *
     * 硬切 30 字会切出「提交必须 push，禁止只 commit 不 push。 未」这种残句，
     * 末尾那半个字比不截断还难读。所以优先断在最近的中文标点处；
     * 只有断完剩不到 12 字（说明这句本身就没标点，比如纯英文配置串）才硬切。
     */
    static String smartCut(String s, int max) {
        if (s == null || s.length() <= max) {
            return s;
        }
        int cut = -1;
        for (int i = max - 1; i >= 0; i--) {
            if ("。，、；：！？｜→".indexOf(s.charAt(i)) >= 0) {
                cut = i;
                break;
            }
        }
        if (cut >= 15) {
            return s.substring(0, cut).trim();
        }
        return s.substring(0, max);
    }

    /**
     * 剥掉章节 content 开头手写的标题行与元信息行。
     *
     * 与 MAIN 的 stripSelfGeneratedParts 同一原则（单一数据源：标题、版本、加载策略
     * 都由读取端生成），但只处理**开头**——章节正文里可能正常引用「第 3 章」，
     * 不能全文删，只跳过开头连续的手写头部。
     *
     * ⚠️ 2026-10-24 22:30 忆阁复测发现的漏网形态（上一版只认「# 第 N 章」+ 半角冒号）：
     *   # 产品定位与核心决策        ← 裸 H1，不带章节号
     *   > 版本：v6（2026-10-24 …）   ← 中文冒号「：」，且带括号说明
     * 所以规则改为「泛化」：开头连续的**一级标题**（# 任意文字）和**元信息行**
     * （> 开头且含 版本/加载策略）一律剥掉，直到遇到 ## 及以下或普通段落。
     */
    static String stripChapterHeader(String content) {
        if (content == null || content.isEmpty()) {
            return "";
        }
        String[] lines = content.split("\n", -1);
        int start = 0;
        while (start < lines.length) {
            String t = lines[start].trim();
            if (t.isEmpty()) {
                start++;
                continue;
            }
            // 一级标题（# xxx，任意文字）——章节标题由读取端生成，content 里不该有
            if (t.matches("^#\\s+\\S.*$")) {
                start++;
                continue;
            }
            // 元信息行：> 版本: / > 加载策略: / > 版本：v6（…）——含中文冒号
            if (t.startsWith(">") && (t.contains("版本") || t.contains("加载策略"))) {
                start++;
                continue;
            }
            break;
        }
        StringBuilder kept = new StringBuilder();
        for (int i = start; i < lines.length; i++) {
            kept.append(lines[i]).append("\n");
        }
        return kept.toString().trim();
    }

    static String stripSelfGeneratedParts(String content) {
        if (content == null || content.isEmpty()) {
            return "";
        }
        StringBuilder kept = new StringBuilder();
        boolean hitToc = false;   // 遇到手写目录标题后，后续内容全部丢弃（目录一律由代码生成）
        for (String line : content.split("\n", -1)) {
            String t = line.trim();
            if (t.matches("^#{1,6}\\s*章节目录.*$")) {
                hitToc = true;
                continue;
            }
            if (hitToc) {
                continue;
            }
            if (t.matches("^#{1,6}\\s*.{0,60}AGENTS\\.md\\s*索引.*$")) {
                continue;   // 手写标题行（实测存在「…索引（MAIN）」带后缀的写法）
            }
            if (t.matches("^>\\s*版本:\\s*v\\d+.*$")) {
                continue;   // 手写版本行
            }
            kept.append(line).append("\n");
        }
        String result = kept.toString().trim();
        // 剥掉目录后残留在末尾的分隔线
        while (result.endsWith("---")) {
            result = result.substring(0, result.length() - 3).trim();
        }
        // 首行 H1 若含「索引」字样（实测存在「# 拆解宝 · 报废车回收模块 — MAIN 索引」这种写法），
        // 与系统自动生成的标题重复，剥掉；不含「索引」的 H1（如「# 项目定位」）是正文标题，保留
        int nl = result.indexOf('\n');
        if (result.startsWith("# ") && nl > 0 && result.substring(0, nl).contains("索引")) {
            result = result.substring(nl).trim();
        }
        return result;
    }

    @Tool(name = "update_project_agents_main", description = "写入或更新项目 AGENTS.md 的 MAIN 索引**正文**。⚠️ 不要写标题行（「# 项目名 · AGENTS.md 索引」）、版本行（「> 版本: vN」）和章节目录——这三样由系统自动生成并在返回时自动追加，写进 content 会导致内容重复且与系统版本号脱节。你只负责：项目一句话定位、当前状态、3-5 条绝对红线（内联，确保AI每次都能看到）、关键配置。建议 1500 字以内。")
    public String updateProjectAgentsMain(
            @ToolParam(description = "项目ID") Long projectId,
            @ToolParam(description = "索引正文（Markdown）：项目定位 + 当前状态 + 绝对红线 + 关键配置。不要包含标题行、版本行、章节目录（系统自动生成）") String content) {
        try {
            Project proj = projectService.getProject(projectId);
            if (proj == null) {
                return "❌ 未找到项目ID " + projectId + "，或你没有访问权限。";
            }
            Doc saved = projectService.updateAgentsMain(projectId, content);
            return "✅ MAIN 索引已更新！\n\n**项目**: " + proj.getName() + "\n**版本**: v" + saved.getVersion()
                 + "\n\n提示：红线内联在这里，AI 每次读索引都会看到。";
        } catch (Exception e) {
            log.error("ExpHubTools.updateProjectAgentsMain: FAILED", e);
            return "更新失败: " + e.getMessage();
        }
    }

    @Tool(name = "update_project_agents_chapter", description = "写入或更新项目 AGENTS.md 的某一章正文。按章独立更新，不触发长文档覆盖保护，每章独立保留版本快照。⚠️ 不要写「# 第 N 章 · 标题」和「> 加载策略: ...」这两行——由系统自动生成并拼接，写进 content 会导致重复且版本号脱节。建议单章3000字以内。")
    public String updateProjectAgentsChapter(
            @ToolParam(description = "项目ID") Long projectId,
            @ToolParam(description = "章节号，如'1'、'14'") String chapterNo,
            @ToolParam(description = "章节标题，如'项目概述'") String chapterTitle,
            @ToolParam(description = "章节正文（Markdown）。不要包含「# 第 N 章」标题行和「加载策略」行（系统自动生成）") String content,
            @ToolParam(description = "加载策略：ALWAYS(每次会话必读) / ON_DEMAND(按需)。默认 ON_DEMAND", required = false) String loadStrategy,
            @ToolParam(description = "排序号，默认0", required = false) Integer sortOrder,
            @ToolParam(description = "目录描述：AGENTS.md 自动生成的章节目录里那一行说明，建议 30 字内。不传则新建时用默认文案、更新时保持原值", required = false) String summary) {
        try {
            Project proj = projectService.getProject(projectId);
            if (proj == null) {
                return "❌ 未找到项目ID " + projectId + "，或你没有访问权限。";
            }
            if (loadStrategy != null && !"ALWAYS".equalsIgnoreCase(loadStrategy) && !"ON_DEMAND".equalsIgnoreCase(loadStrategy)) {
                return "❌ loadStrategy 只能是 ALWAYS 或 ON_DEMAND。";
            }
            // E10 治本：summary 缺失或是占位值时，自动从正文首段提取一句。
            // 否则新建章节永远带着「项目 AGENTS.md 第 N 章」这种废话进目录
            // （实测 62/82 个章节中招），读取端只能过滤，目录就退化成只剩标题。
            // 更新时若原 summary 是真实描述，保留原值——调用方没说就不覆盖。
            String effSummary = summary;
            if (content != null && !content.trim().isEmpty()
                    && (summary == null || summary.trim().isEmpty() || isPlaceholderSummary(summary))) {
                Doc existingCh = projectService.getAgentsChapter(projectId, chapterNo);
                boolean keepExisting = existingCh != null
                        && existingCh.getSummary() != null
                        && !isPlaceholderSummary(existingCh.getSummary());
                if (!keepExisting) {
                    effSummary = autoSummaryFromContent(content, chapterTitle);
                }
            }
            Doc saved = projectService.updateAgentsChapter(projectId, chapterNo, chapterTitle, content, loadStrategy, sortOrder, effSummary);
            String summaryNote = (effSummary != null && !effSummary.equals(summary))
                    ? "\n**目录描述**: " + effSummary + "（由正文自动提取，如需自定义请显式传 summary）"
                    : "\n**目录描述**: " + saved.getSummary();
            return "✅ 章节已更新！\n\n**项目**: " + proj.getName() + "\n**章节**: 第 " + chapterNo + " 章 · " + chapterTitle
                 + "\n**版本**: v" + saved.getVersion() + " | 加载策略: " + saved.getLoadStrategy()
                 + summaryNote;
        } catch (Exception e) {
            log.error("ExpHubTools.updateProjectAgentsChapter: FAILED", e);
            return "更新失败: " + e.getMessage();
        }
    }

    @Tool(name = "export_project_agents", description = "导出项目完整的 AGENTS.md（MAIN索引 + 所有章节按序拼接）。需要全量查看或备份时使用，日常按需读章节即可。")
    public String exportProjectAgents(
            @ToolParam(description = "项目ID") Long projectId) {
        try {
            Project proj = projectService.getProject(projectId);
            if (proj == null) {
                return "❌ 未找到项目ID " + projectId + "，或你没有访问权限。";
            }
            String md = projectService.exportAgents(projectId);
            if (md.trim().isEmpty()) {
                return "⚠️ 项目「" + proj.getName() + "」还没有 AGENTS.md 内容。";
            }
            return md;
        } catch (Exception e) {
            log.error("ExpHubTools.exportProjectAgents: FAILED", e);
            return "导出失败: " + e.getMessage();
        }
    }

    @Tool(name = "append_project_progress", description = "追加一条项目进度/交接记录。一次交接一条，按时间倒序保存，每条独立快照，不会因文件增长而撞上长文档限制。")
    public String appendProjectProgress(
            @ToolParam(description = "项目ID") Long projectId,
            @ToolParam(description = "进度内容：本次完成了什么、改了哪些文件、下次从哪继续、注意事项/遗留问题") String content,
            @ToolParam(description = "标题，留空则自动截取内容前30字", required = false) String title) {
        try {
            Project proj = projectService.getProject(projectId);
            if (proj == null) {
                return "❌ 未找到项目ID " + projectId + "，或你没有访问权限。";
            }
            String t = (title != null && !title.isEmpty()) ? title
                     : (content.length() > 30 ? content.substring(0, 30) + "..." : content);
            Doc saved = projectService.appendProgress(projectId, content, t);
            return "✅ 进度已记录！\n\n**项目**: " + proj.getName() + "\n**ID**: " + saved.getId() + "\n**标题**: " + t;
        } catch (Exception e) {
            log.error("ExpHubTools.appendProjectProgress: FAILED", e);
            return "记录失败: " + e.getMessage();
        }
    }

    @Tool(name = "get_project_progress", description = "读取项目最近的进度/交接记录（按时间倒序）。新会话开场读最近3条即可接续。")
    public String getProjectProgress(
            @ToolParam(description = "项目ID") Long projectId,
            @ToolParam(description = "读取条数，默认3，最多50", required = false) Integer limit) {
        try {
            Project proj = projectService.getProject(projectId);
            if (proj == null) {
                return "❌ 未找到项目ID " + projectId + "，或你没有访问权限。";
            }
            int n = (limit == null || limit <= 0) ? 3 : Math.min(limit, 50);
            var list = projectService.listProgress(projectId, n);
            if (list.isEmpty()) {
                return "项目「" + proj.getName() + "」还没有进度记录。";
            }
            StringBuilder sb = new StringBuilder("项目「" + proj.getName() + "」最近 " + list.size() + " 条进度：\n\n");
            for (Doc d : list) {
                sb.append("### ").append(d.getTitle()).append("\n");
                sb.append("**时间**: ").append(d.getCreatedAt()).append(" | **作者**: ").append(d.getAuthorName()).append("\n\n");
                sb.append(d.getContent()).append("\n\n---\n\n");
            }
            return sb.toString();
        } catch (Exception e) {
            log.error("ExpHubTools.getProjectProgress: FAILED", e);
            return "读取失败: " + e.getMessage();
        }
    }

    @Tool(name = "list_recent_memories", description = "按更新时间倒序列出当前 API Key 的记忆（自己的全局经验 + 自有项目内经验），游标分页。用于「最近记忆 / 记忆列表」这类枚举场景；要按关键词找内容请用 search_experience。整理记忆时用 since 只看最近一段（如 since=today 看今天新增/改动），不要靠翻页去数。")
    public String listRecentMemories(
            @ToolParam(description = "每页条数，默认 10，上限 50（超过 50 按 50）", required = false) Integer pageSize,
            @ToolParam(description = "翻页游标：首页不传；后续页传上一页返回的 NEXT_CURSOR 值。非法格式按首页处理", required = false) String cursor,
            @ToolParam(description = "可选。只看某时间之后更新过的（D1 每日增量用）。支持：today / yesterday / -7d（近 7 天）/ -12h（近 12 小时）/ 2026-10-25 / \"2026-10-25 08:00\"。不传=不限时间", required = false) String since) {
        try {
            int size = (pageSize == null || pageSize < 1) ? 10 : Math.min(pageSize, 50);
            java.time.LocalDateTime sinceAt;
            try {
                sinceAt = parseSince(since);
            } catch (IllegalArgumentException bad) {
                return "❌ " + bad.getMessage();
            }
            DocService.RecentPage page = docService.listRecentAccessible(size, cursor, sinceAt);

            if (page.docs().isEmpty()) {
                return (sinceAt != null ? "「" + since.trim() + "」之后没有更新的记忆了。" : "没有更多记忆了。")
                        + "\nNEXT_CURSOR: -";
            }

            Map<Long, String> projectNames = new HashMap<>();
            for (Project p : projectService.listProjects()) {
                projectNames.put(p.getId(), p.getName());
            }

            StringBuilder sb = new StringBuilder();
            if (sinceAt != null) {
                sb.append("（范围：").append(since.trim()).append(" 之后，即 ")
                  .append(MEMORY_TIME.format(sinceAt)).append(" 起）\n");
            }
            for (Doc d : page.docs()) {
                String project = (d.getProjectId() == null)
                        ? "全局" : projectNames.getOrDefault(d.getProjectId(), "未知项目");
                sb.append("- [").append(sanitizeMemoryTitle(d.getTitle()))
                  .append("](id:").append(d.getId()).append(") — ")
                  .append(project)
                  .append(" · ").append(memoryTypeLabel(d.getTemplateType()))
                  .append(" · ").append(formatMemoryTime(d.getUpdatedAt()))
                  .append("\n");
            }
            sb.append("NEXT_CURSOR: ").append(page.nextCursor());
            return sb.toString();
        } catch (Exception e) {
            log.error("ExpHubTools.listRecentMemories: FAILED", e);
            return "查询失败: " + e.getMessage();
        }
    }

    /**
     * 解析 since 参数（D1 施工包 2）。包级静态便于单测。
     *
     * <p>为什么要有它：L1「每日增量」的前提是能准确回答「今天动过哪些」。
     * 靠游标翻页去数，条目一多就不可靠（翻到第 5 页时前面可能已被新写入挤走）。
     *
     * @return null = 不限时间
     * @throws IllegalArgumentException 无法识别的格式（由工具层转成可读提示，不抛给框架）
     */
    static java.time.LocalDateTime parseSince(String since) {
        if (since == null || since.trim().isEmpty()) return null;
        // 只转小写，不在这里把 T 换成空格 —— toLowerCase 之后 'T' 已经变成 't'，
        // 提前替换等于白替换（第一次跑测试就踩到了）。日期带 T 的分支里单独处理。
        String v = since.trim().toLowerCase();
        if ("today".equals(v) || "今天".equals(v)) return java.time.LocalDate.now().atStartOfDay();
        if ("yesterday".equals(v) || "昨天".equals(v))
            return java.time.LocalDate.now().minusDays(1).atStartOfDay();

        // 相对天数：-7d / 7d / -7天
        java.util.regex.Matcher dm = java.util.regex.Pattern.compile("^-?(\\d+)d$|^-?(\\d+)天$").matcher(v);
        if (dm.matches()) {
            int n = Integer.parseInt(dm.group(1) != null ? dm.group(1) : dm.group(2));
            return java.time.LocalDate.now().minusDays(n).atStartOfDay();
        }
        // 相对小时：-12h
        java.util.regex.Matcher hm = java.util.regex.Pattern.compile("^-?(\\d+)h$").matcher(v);
        if (hm.matches()) {
            return java.time.LocalDateTime.now().minusHours(Long.parseLong(hm.group(1)));
        }
        // 纯日期
        if (v.matches("^\\d{4}-\\d{2}-\\d{2}$")) {
            return java.time.LocalDate.parse(v).atStartOfDay();
        }
        // 日期 + 时分（秒可省）；同时接受 ISO 的 T 分隔符（已转小写，这里按 't' 判断）
        if (v.matches("^\\d{4}-\\d{2}-\\d{2}[ t]\\d{2}:\\d{2}(:\\d{2})?$")) {
            String norm = v.replace('t', ' ');
            norm = norm.length() == 16 ? norm + ":00" : norm;
            return java.time.LocalDateTime.parse(norm.replace(' ', 'T'));
        }
        throw new IllegalArgumentException("无法识别的 since 格式：" + since
                + "。可用：today / yesterday / -7d / -12h / 2026-10-25 / \"2026-10-25 08:00\"");
    }

    private static final DateTimeFormatter MEMORY_TIME =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

    private String formatMemoryTime(java.time.LocalDateTime time) {
        return time == null ? "-" : MEMORY_TIME.format(time);
    }

    /**
     * 列表行用 Markdown 链接格式 `[标题](id:123)`，标题里的方括号/换行会破坏解析，必须清洗。
     */
    private String sanitizeMemoryTitle(String title) {
        if (title == null) return "无标题";
        String s = title.replace("[", "【").replace("]", "】")
                .replace("\r", " ").replace("\n", " ").trim();
        return s.isEmpty() ? "无标题" : s;
    }

    /** 记忆列表的类型标签：项目文档/进度记录用更贴近记忆流的说法 */
    private String memoryTypeLabel(String type) {
        if (type == null) return "通用";
        return switch (type) {
            case "project_agents" -> "项目文档";
            case "project_progress" -> "进度记录";
            default -> getTemplateTypeLabel(type);
        };
    }

    @Tool(name = "list_project_experiences", description = "列出项目专属经验。项目经验默认隔离，不会出现在全局 search_experience 结果中。默认返回 10 条，摘要压缩到 60 字便于扫读；要翻页用 offset，要看完整摘要用 get_experience_detail。")
    public String listProjectExperiences(
            @ToolParam(description = "项目ID") Long projectId,
            @ToolParam(description = "可选。按模板类型过滤，如 bug_fix、config_guide", required = false) String templateType,
            @ToolParam(description = "可选。每页条数，默认 10，上限 50", required = false) Integer pageSize,
            @ToolParam(description = "可选。跳过前 N 条（从 0 开始），配合 pageSize 翻页。默认 0", required = false) Integer offset) {
        try {
            Project proj = projectService.getProject(projectId);
            if (proj == null) {
                return "❌ 未找到项目ID " + projectId + "，或你没有访问权限。";
            }
            var list = projectService.listProjectDocs(projectId, templateType);
            if (list.isEmpty()) {
                return "项目「" + proj.getName() + "」还没有专属经验。";
            }

            int size = (pageSize == null || pageSize < 1) ? LIST_DEFAULT_SIZE : Math.min(pageSize, LIST_MAX_SIZE);
            int from = (offset == null || offset < 0) ? 0 : offset;
            if (from >= list.size()) {
                return "项目「" + proj.getName() + "」共 " + list.size() + " 条专属经验，offset=" + from
                        + " 已超出范围（从 0 开始）。";
            }
            int to = Math.min(from + size, list.size());
            var page = list.subList(from, to);

            StringBuilder sb = new StringBuilder();
            sb.append("项目「").append(proj.getName()).append("」共 ").append(list.size())
              .append(" 条专属经验，本次返回第 ").append(from + 1).append("-").append(to).append(" 条：\n\n");
            for (Doc d : page) {
                sb.append("- 【ID:").append(d.getId()).append("】").append(d.getTitle())
                  .append(" [").append(getTemplateTypeLabel(d.getTemplateType())).append("]\n");
                sb.append("   ").append(previewSummary(d.getSummary())).append("\n");
            }
            if (to < list.size()) {
                sb.append("\n---\n还有 ").append(list.size() - to).append(" 条未显示，下一页：offset=")
                  .append(to).append("&pageSize=").append(size).append("\n");
            }
            return sb.toString();
        } catch (Exception e) {
            log.error("ExpHubTools.listProjectExperiences: FAILED", e);
            return "查询失败: " + e.getMessage();
        }
    }

    @Tool(name = "promote_to_global", description = "把项目专属经验提升为全局经验（解除项目归属），之后可被全局 search_experience 搜到。适用于跨项目可复用的经验。")
    public String promoteToGlobal(
            @ToolParam(description = "要提升的经验ID") Long docId) {
        try {
            Doc updated = projectService.promoteToGlobal(docId);
            if (updated == null) {
                return "❌ 未找到经验ID " + docId + "，或你没有访问权限。";
            }
            return "✅ 已提升为全局经验！\n\n**ID**: " + docId + "\n**标题**: " + updated.getTitle()
                 + "\n\n现在它可被全局 search_experience 搜索到（仍遵循 API Key 隔离）。";
        } catch (Exception e) {
            log.error("ExpHubTools.promoteToGlobal: FAILED", e);
            return "提升失败: " + e.getMessage();
        }
    }

    /** 内置工作流模板正文：exphub 未配置 workflow_template 时的兜底 */
    private static final String BUILTIN_WORKFLOW_TEMPLATE =
            "阶段 0：会话开场（S 级跳过）\n" +
            "1. 读项目 AGENTS.md 索引（get_project_agents）\n" +
            "2. 读最近 3 条进度（get_project_progress）\n" +
            "3. 复述你准备从哪里开始，先不要写代码\n\n" +
            "阶段 0.5：查既有经验（S 级跳过）\n" +
            "用 exphub 搜这个问题有没有现成方案：search_experience（中英文各搜一次 + includeStatus=ALL）；\n" +
            "命中则 get_experience_detail，见「历史导读」再调 get_experience_history；\n" +
            "搜不到则标记为待沉淀，阶段 5 必须补。这一步不许跳过。\n\n" +
            "阶段 1：任务定义确认（S 级跳过）\n" +
            "复述要改哪些文件、为什么、输入输出是什么。说不清涉及文件就告诉我任务太大，不要硬着头皮开写。\n\n" +
            "阶段 2：探索（S 级跳过）\n" +
            "读涉及文件及依赖 → 复述职责与关键逻辑 → 列出不确定项，并逐条自己先查，给我结论和依据。\n" +
            "先不要写代码，等我确认。\n\n" +
            "阶段 3：实现\n" +
            "1. 按子步骤推进，每完成一步停下来报告改了什么、下一步做什么\n" +
            "2. 遵循项目 AGENTS.md 的编码规范和禁忌\n" +
            "3. 决策分级：架构/技术选型必须问我；影响面大的实现选择先说方案再动手；局部细节你自定\n\n" +
            "阶段 4：验证\n" +
            "逐条核对验收标准，给出「满足 / 不满足 + 依据」；运行测试命令，并把实际输出贴出来（不要只说通过）。\n\n" +
            "阶段 5：收尾\n" +
            "1. 追加进度记录（append_project_progress）\n" +
            "2. git diff 自查：确认没有账号密码、API Key、Token、数据库连接串、私钥、证书\n" +
            "3. 沉淀 exphub：可复用的经验用 create_experience 创建；参考过的经验用 report_experience_result 反馈\n" +
            "4. 提交代码\n\n" +
            "红灯规则：说不清涉及文件 → 停下拆分；不停下来一口气写完 → 叫停；跳过阶段 0.5 → 叫停。\n" +
            "现在开始：S 级从阶段 3 开始，其余从阶段 0 开始。";

    @Tool(name = "generate_workflow_prompt", description = "生成一份填空完成的开发任务提示词：系统工作流模板 + 项目默认变量 + 本次任务变量，拼成可直接整段复制给 AI 助手的完整指令。项目的默认约束和测试命令会自动带入，只需填本次任务相关的几项。")
    public String generateWorkflowPrompt(
            @ToolParam(description = "项目ID") Long projectId,
            @ToolParam(description = "本次任务，一句话，如'实现采购订单导出Excel接口'") String task,
            @ToolParam(description = "涉及文件，每行一个，格式：路径 —— 要改什么", required = false) String files,
            @ToolParam(description = "验收标准，每行一条可测条件", required = false) String criteria,
            @ToolParam(description = "任务级别：S(小修小补)/M(单点功能)/L(跨模块)。默认 M", required = false) String level,
            @ToolParam(description = "额外约束，会与项目默认约束合并。可留空", required = false) String constraints) {
        try {
            Project proj = projectService.getProject(projectId);
            if (proj == null) {
                return "❌ 未找到项目ID " + projectId + "，或你没有访问权限。";
            }
            String lv = (level == null || level.trim().isEmpty()) ? "M" : level.trim().toUpperCase();
            if (!"S".equals(lv) && !"M".equals(lv) && !"L".equals(lv)) {
                return "❌ level 只能是 S / M / L。";
            }
            String testCmd = (proj.getDefaultTestCmd() != null && !proj.getDefaultTestCmd().isEmpty())
                    ? proj.getDefaultTestCmd() : "（见项目 AGENTS.md）";
            StringBuilder cons = new StringBuilder();
            if (proj.getDefaultConstraints() != null && !proj.getDefaultConstraints().isEmpty()) {
                cons.append(proj.getDefaultConstraints());
            }
            if (constraints != null && !constraints.trim().isEmpty()) {
                if (cons.length() > 0) cons.append("\n");
                cons.append(constraints.trim());
            }
            String body = projectService.getWorkflowTemplate();
            boolean fromLib = body != null && !body.trim().isEmpty();
            if (!fromLib) {
                body = BUILTIN_WORKFLOW_TEMPLATE;
            }

            StringBuilder sb = new StringBuilder();
            sb.append("# 开发任务 · ").append(proj.getName()).append("\n\n");
            sb.append("> 模板来源：").append(fromLib ? "exphub 系统模板" : "内置默认模板（可在 exphub 建 workflow_template 类型经验覆盖）").append("\n");
            sb.append("> 用法：整段复制下方代码块内容，粘贴给 AI 助手即可。\n\n");
            sb.append("```\n");
            sb.append("我要你按《AI 辅助开发工作流 v2.0》执行本次任务。严格按阶段推进，每阶段结束后停下来等我确认，不要一口气做完。\n\n");
            sb.append("【本次任务参数】\n");
            sb.append("- 项目：").append(proj.getName()).append("\n");
            sb.append("- 任务：").append(task).append("\n");
            sb.append("- 涉及文件：\n").append(files != null && !files.trim().isEmpty() ? files.trim() : "（待补充）").append("\n");
            sb.append("- 验收标准：\n").append(criteria != null && !criteria.trim().isEmpty() ? criteria.trim() : "（待补充）").append("\n");
            sb.append("- 约束：\n").append(cons.length() > 0 ? cons : "（见项目 AGENTS.md）").append("\n");
            sb.append("- 任务级别：").append(lv).append("S".equals(lv) ? "（跳过阶段 0、0.5、1、2，直接从阶段 3 开始）" : "").append("\n");
            sb.append("- 测试命令：").append(testCmd).append("\n\n");
            sb.append(body).append("\n");
            sb.append("```\n\n");
            sb.append("提示：读项目上下文用 get_project_agents(project_id=").append(projectId)
              .append(")；搜项目内既有经验用 search_experience 的 projectName 参数。");
            return sb.toString();
        } catch (Exception e) {
            log.error("ExpHubTools.generateWorkflowPrompt: FAILED", e);
            return "生成失败: " + e.getMessage();
        }
    }
}
