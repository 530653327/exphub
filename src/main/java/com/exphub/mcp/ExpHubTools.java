package com.exphub.mcp;

import com.exphub.entity.AiAssistant;
import com.exphub.entity.Doc;
import com.exphub.entity.DocTemplate;
import com.exphub.entity.DocVersion;
import com.exphub.entity.MailMessage;
import com.exphub.entity.Project;
import com.exphub.interceptor.ApiKeyInterceptor;
import com.exphub.service.CallLogService;
import com.exphub.service.DocService;
import com.exphub.service.ReviewCandidateService;
import com.exphub.entity.ReviewCandidate;
import com.exphub.service.SectionSplicer;
import com.exphub.service.SimilarityScorer;
import com.exphub.service.SimilarityService;
import com.exphub.service.DocTemplateService;
import com.exphub.service.ProjectService;
import com.exphub.service.VersionArchiveService;
import com.exphub.service.WriteOrigin;
import com.exphub.util.ContentHashGuard;
import com.exphub.util.SensitiveContentGuard;
import com.exphub.entity.DocVersionArchive;
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

    /**
     * #1272 C8 / C14：污染源判定的**兜底** ID 表（信箱 / 施工回执 / 待办 / 口径 / 方案文档）。
     *
     * <p>C14 之后主判据已改为 {@link PollutionMarkers} 的内容判定：只要正文含验收 query 原话，
     * 不管 ID 在不在表里都会标。本表只覆盖「暂未含原话但确属回执类」的文档，
     * <b>集合齐不齐不再是正确性前提</b> —— 阁主实测漏掉的 #677 已补进表，但真正修好它的是内容判定。</p>
     *
     * <p>离线脚本 {@code tools/search_regression.py} 的 {@code SELF_POLLUTION_IDS} 仍须与这里一致
     * （由 {@code SearchScopeGuardTest} 钉住），那是**判基线时的排除口径**，与线上标注口径分开。</p>
     *
     * <p>线上处理方式是**只标注、不排除、不改排序**：排除是评测口径，不该替调用方决定看不到哪条文档；
     * 真要查信箱的人照样查得到。</p>
     */
    static final Set<Long> REGRESSION_POLLUTION_IDS = Set.of(
            101L, 677L, 678L, 1220L, 1236L, 1268L, 1272L, 1274L);

    /** 污染源判定：内容优先，ID 表兜底。回执里带上命中来源，方便线上自证是哪条判据起作用。 */
    static String pollutionReason(Doc doc) {
        if (doc == null) return null;
        if (PollutionMarkers.matches(doc)) return "内容";
        if (REGRESSION_POLLUTION_IDS.contains(doc.getId())) return "ID";
        return null;
    }

    @Autowired
    private DocService docService;

    @Autowired
    private DocTemplateService templateService;

    @Autowired
    private CallLogService callLogService;

    @Autowired
    private McpToolRegistry mcpToolRegistry;

    @Autowired
    private ProjectService projectService;

    @Autowired
    private ReviewCandidateService reviewCandidateService;

    @Autowired
    private VersionArchiveService versionArchiveService;

    @Autowired
    private SimilarityService similarityService;

    @Autowired
    private com.exphub.service.MailboxService mailboxService;

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
    @Tool(name = "check_my_todos", description = "【连接后请优先调用】检查本助手记录的待办（todo_list）。默认只看全局 ACTIVE；要看项目/已完成/归档传 projectId/status。⚠️ 完成后用 update_experience_status 标 COMPLETED。")
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
            // 2026-10-26：标签必须反映**实际生效的过滤条件**，不能凭「应该是什么」写文案。
            // 原来 status 为空时标签写「ACTIVE（未完成）」，而查询根本没加 status 条件 —— 
            // 数据和标签同时错，而且标签错得更隐蔽（人会相信标签）。
            boolean statusGiven = status != null && !status.trim().isEmpty();
            String effectiveStatus = !statusGiven ? "ACTIVE" : status.trim().toUpperCase();
            String statusLabel = "ALL".equals(effectiveStatus)
                    ? "全部状态（ACTIVE + 已完成/过期/失效）"
                    : effectiveStatus + (statusGiven ? "" : "（未传 status，默认只列未完成）");

            if (todos.isEmpty()) {
                return "📋 " + scopeLabel + " 范围、状态 " + statusLabel + " 下没有待办事项。\n\n"
                        + "可以用 create_experience（templateType=todo_list）创建，"
                        + "或换 projectId / status 再看（status=ALL 可看已完成的）。\n"
                        + "（实际查询条件：status=" 
                        + ("ALL".equals(effectiveStatus) ? "不过滤" : effectiveStatus) + "）";
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

    @Tool(name = "get_experience_detail", description = "获取经验**当前正文**。超长会分段返回。历史版本用 get_experience_history。⚠️ 回执里的 detail_fingerprint / segment_fingerprint 只是展示指纹（客户端侧附加），**不能**当 originalHash 用；写回凭证是写回工具回执里的 writeback_fingerprint（64 位）。")
    public String getExperienceDetail(
            @ToolParam(description = "经验ID。") Long id,
            @ToolParam(description = "可选。正文起始字符偏移，默认 0。只在续读超长文档时用："
                    + "照抄上一次回执末尾的「下一段 offset」。", required = false) Integer offset) {

        Doc doc = docService.getById(id);

        if (doc == null) {
            return "未找到ID为" + id + "的经验";
        }

        StringBuilder sb = new StringBuilder();
        sb.append(renderDetailHeader(doc));
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
        //
        // ⚠️ 2026-10-26 01:5x 阁主实测指出：这里原来只查主表 doc_versions（归档后只剩 14 条），
        // 于是导读写成「共保存 14 个历史版本（v1 ~ v73）」—— **括号内外自相矛盾**，
        // 还把已搬去冷存档的 59 版说没了，读者先看导读会以为这文档只有 14 版。
        // 病根：列表（get_experience_history / list_archived_versions）早就合并归档了，
        // **导读没合并** —— 同一个事实的两个出口，改一个必须回头查另一个。
        List<DocVersion> versions = docService.getVersions(id);
        VersionArchiveService.ArchivedSummary arc = versionArchiveService.archivedSummary(id);
        // ⚠️ 2026-10-26 03:1x 阁主实测 #101：导读写「共 18 条（v1 ~ v20）：主表 10 条、冷存档 8 条」，
        //    条数来自 COUNT、区间来自 doc.getVersion()-1 —— **两个来源拼成一句话**。
        //    实情是主表 v9,10,11,12,14,16,17,18,19,20（缺 v13、v15）、归档 v1~v8 连续，
        //    「18 条」与「v1 ~ v20」各自都对，但**缺的 2 个版本号没人说** → 读者只会怀疑归档搬丢了数据。
        //    现在条数、上界、缺号、重号全部由 VersionCoverage 一次算出，且缺口必须主动说明。
        java.util.List<Integer> mainVs = new java.util.ArrayList<>();
        for (DocVersion v : versions) mainVs.add(v.getVersion());
        java.util.List<Integer> arcVs = versionArchiveService.archivedVersionNumbers(id);
        VersionArchiveService.VersionCoverage cov = VersionArchiveService.VersionCoverage.of(mainVs, arcVs);
        if (cov.totalRows() > 0) {
            sb.append("> 📚 **历史导读**：本文档当前为 v").append(doc.getVersion())
              .append("，**历史版本共 ").append(cov.totalRows()).append(" 条");
            if (arc.rows() > 0) {
                sb.append("（主表 ").append(versions.size()).append(" 条、冷存档 ")
                  .append(arc.rows()).append(" 条）");
            }
            sb.append("，版本号到 v").append(cov.maxVersion()).append("**");
            if (!cov.missing().isEmpty()) {
                sb.append("。\n> ⚠️ ").append(cov.missingNote())
                  .append("（早期经门户「更新我的经验」改正文时只递增版本号、未存快照；")
                  .append("**不是归档丢的** —— 归档只搬不删，搬运前先校验插入行数）");
            }
            if (!cov.duplicated().isEmpty()) {
                sb.append("。\n> ℹ️ ").append(cov.duplicateNote())
                  .append("（同一版本号存了多条，属正常历史遗留，读的时候任取一条即可）");
            }
            sb.append("。\n");
            sb.append("> 如需了解本文档的**来龙去脉、演进过程或历史解决方案**，请调用\n");
            sb.append("> `get_experience_history(id=").append(id).append(")` 查看历史版本列表（**含冷存档**，会标注在哪个库）；\n");
            sb.append("> `get_experience_history(id=").append(id).append(", version=版本号)` 查看指定版本完整内容（如 version=1 可看最初版本，**已归档的也能读**）。\n");
            if (arc.rows() > 0) {
                sb.append("> 只看冷存档清单：`list_archived_versions(id=").append(id).append(")`。\n");
            }
            sb.append("\n---\n\n");
        }

        // 超长内容分段输出。
        //
        // ⚠️ 2026-10-26 01:1x：原来截断后建议「改用 export_document 导出完整文档」——
        // 那是把长文本整块吐出来，**不分段、也没法定位到某一段**。
        // 现在统一走 appendContentSlice：给出确切的后续 offset，能一路读到尾。
        appendContentSlice(sb, doc.getContent(), offset, id, "get_experience_detail", doc.getVersion(), doc);

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
     * 数出片段里有多少行是「终端残留」（job control 的 {@code Terminated: N}、
     * {@code xxx.sh: line N}、{@code bash: line N}、{@code curl: (N)}）。
     *
     * <p>为什么要数：剥掉内容必须**说出来**（AGENTS 第 10 章：回执必须自证值）。
     * 「已追加」而实际少了两行，读者永远不知道少了什么。
     */
    static int terminalResidueLines(String text) {
        if (text == null || text.isEmpty()) return 0;
        int n = 0;
        for (String line : text.split("\n", -1)) {
            if (com.exphub.util.RenderArtifactStripper.isTerminalResidue(line.trim())) n++;
        }
        return n;
    }

    /**
     * 导出完整文档内容（无截断，适合长文档维护）
     */
    @Tool(name = "export_document", description = "【文档维护专用】完整导出为 Markdown（无截断），存本地 .md 编辑后用 import_document 回写。它只导当前正文，不列归档清单；短经验用 get_experience_detail。")
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
            // A5 B 方案：整篇写回要带 originalHash，这是它唯一的取值来源。
            // 记的是**原文**的 hash：本地改完正文后 hash 会变，但回传的仍是这一个。
            sb.append("**content_sha256**: `").append(ContentHashGuard.sha256(doc.getContent())).append("`\n");
            sb.append("→ 用 `import_document` / `update_experience` 整篇写回时，把它作为 `originalHash` 传回")
              .append("（改了正文也用它：它代表「我读到的是这一份完整原文」）。\n\n");
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
     * 导入完整文档内容（必须带 originalHash 证明读过完整原文，与 export_document 配对使用）
     */
    @Tool(name = "import_document", description = "【文档维护专用】上传本地完整 Markdown 替换指定经验。自动创建版本快照。小修改用 update_experience。")
    public String importDocument(
            @ToolParam(description = "经验ID（必填）") Long id,
            @ToolParam(description = "完整的Markdown正文内容，将替换整个经验内容") String content,
            @ToolParam(description = "**必填**。`export_document` 回执里的 content_sha256（**原文**的 hash，不是新内容的）。与库中当前正文不一致直接拒绝——上传半篇就等于删掉另一半", required = false) String originalHash,
            @ToolParam(description = "可选。**改文档作者**（作品归属），非操作人署名；只记「谁改的」用 updatedName", required = false) String authorName,
            @ToolParam(description = "可选。**谁改的**——操作人署名（作品归属用 authorName）", required = false) String updatedName,
            @ToolParam(description = "可选。验证时间（YYYY-MM-DD 或 YYYY-MM-DD HH:mm）。"
                    + "**不传 = 本次导入视为一次验证，记当前时间**；只做格式/渲染痕迹整理时传原来的验证时间表示不刷新。",
                    required = false) String verifiedAt) {

        try {
            log.info("ExpHubTools.importDocument: ENTRY - id={}, contentLength={}", id, content != null ? content.length() : 0);

            // 权限验证
            AiAssistant assistant = getCaller();
            if (assistant != null && !Boolean.TRUE.equals(assistant.getCanUpdate())) {
                return "❌ 权限不足：该API Key没有编辑经验的权限";
            }

            java.time.LocalDateTime verified;
            try {
                verified = parseVerifiedAtParam(verifiedAt);
            } catch (IllegalArgumentException bad) {
                return "❌ " + bad.getMessage() + "（未做任何改动）";
            }

            // 检查经验是否存在
            Doc existing = docService.getById(id);
            if (existing == null) {
                return "❌ 未找到ID为" + id + "的经验";
            }

            // A5 B 方案：与 update_experience 同一条铁律 —— 整篇替换必须证明「你手上是完整原文」。
            // import 没有 force：它是「导入完整版」，不存在「明知截断仍写」的正当场景，
            // 真要做那种事应该走 update_experience(force=true) 并在快照里留痕。
            ContentHashGuard.Verdict verdict = ContentHashGuard.check(originalHash, existing.getContent());
            if (verdict != ContentHashGuard.Verdict.OK) {
                log.warn("ExpHubTools.importDocument: originalHash {} blocked. id={}, storedLen={}",
                        verdict, id, existing.getContent() == null ? 0 : existing.getContent().length());
                return verdict == ContentHashGuard.Verdict.MISSING
                        ? ContentHashGuard.missingMessage(id, existing.getContent())
                        : ContentHashGuard.mismatchMessage(id, existing.getContent(), originalHash);
            }

            Doc updateDoc = new Doc();
            updateDoc.setId(id);
            // E11：导入前剥离渲染痕迹（历史导读/关联经验/文档结尾/终端残留）
            String cleaned = stripRenderArtifacts(content);
            updateDoc.setContent(cleaned);
            updateDoc.setLastVerifiedAt(verified);
            if (authorName != null && !authorName.isEmpty()) updateDoc.setAuthorName(authorName);

            Doc updated = docService.update(id, updateDoc, java.util.Set.of(), updatedName,
                    WriteOrigin.HASH_VERIFIED);

            int stripped = content.length() - cleaned.length();
            StringBuilder sb = new StringBuilder();
            sb.append("✅ 文档导入成功！\n\n**ID**: ").append(id)
              .append("\n**标题**: ").append(existing.getTitle())
              .append("\n**新版本**: v").append(updated != null ? updated.getVersion() : existing.getVersion() + 1)
              .append("\n**内容长度**: ").append(cleaned.length()).append(" 字")
              .append(stripped > 0 ? "（已剥离 " + stripped + " 字渲染痕迹：历史导读/关联经验/文档结尾等）" : "")
              .append("\n\n文档已更新，当前版本保留了完整内容。\n");
            sb.append(verifiedLine(updated, verified != null));
            // D1 包 4 · L0 写入查重：整篇替换后内容可能已经和别的条目重合，顺手查一次
            if (updated != null) {
                sb.append(similarityDigest(similarityService.checkOnCreate(updated)));
            }
            return sb.toString();
        } catch (SensitiveContentGuard.Rejected e) {
            // 凭据拒绝：改内容才有救，重试同样内容永远过不去。不能包成「导入失败」
            callLogService.incrementFailCalls();
            return e.getMessage();
        } catch (Exception e) {
            log.error("ExpHubTools.importDocument: FAILED", e);
            callLogService.incrementFailCalls();
            return "导入文档失败: " + e.getClass().getName() + " - " + e.getMessage();
        }
    }

    /**
     * 获取经验的历史版本内容
     */
    @Tool(name = "get_experience_history", description = "读取历史版本。不传 version 返回版本列表（含位置列：主表/📦已归档）；传 version 返回该版正文。**已归档也能读**（自动回查冷存档）。两个表都没有会列出实际有哪些版本。")
    public String getExperienceHistory(
            @ToolParam(description = "经验ID。") Long id,
            @ToolParam(description = "可选。要读取的特定版本号（填 7 就是读 v7 的历史内容）。不传则返回所有历史版本列表。"
                    + "⚠️ 传当前版本号（v58 就是当前版）会明确告知「这就是当前正文」，不会误报「不存在」。") Integer version,
            @ToolParam(description = "可选。**正文起始字符偏移**，默认 0。用于分段读取超长历史版本："
                    + "先按返回的「下一段 offset」续读，直到它显示「已是最后一段」。"
                    + "主表版本和归档版本都支持。", required = false) Integer offset) {

        try {
            // 检查经验是否存在
            Doc doc = docService.getById(id);
            if (doc == null) {
                return "未找到ID为" + id + "的经验";
            }

            List<DocVersion> versions = docService.getVersions(id);

            // ⚠️ 2026-10-26 11:0x 阁主实测：`version=0` 会一路落到最后那个兜底分支，回
            //    「v0 不在主表、也不在冷存档，但落在已存范围内…**这是不一致状态，请直接报给维护者**」——
            //    把**非法输入**说成**库不一致**，让人白跑一趟还去怀疑数据坏了。
            //    版本号从 v1 开始，非法值必须在入口明拒，并给出正确的三个入口。
            if (version != null && version < 1) {
                return "❌ 版本号必须 ≥ 1（收到 " + version + "）。版本号从 v1 开始，没有 v0，也不接受负数。\n\n"
                        + "· 看**当前正文**：`get_experience_detail(id=" + id + ")`\n"
                        + "· 看**历史版本列表**：`get_experience_history(id=" + id + ")`（不传 version）\n"
                        + "· 看**某个历史版本**：`get_experience_history(id=" + id + ", version=库里存在的版本号)`";
            }

            if (version != null) {
                // ⚠️ 2026-10-26 01:1x：**先判「这是不是当前版本」**。
                // 当前正文存在 docs 表，doc_versions 里没有它 —— 原来会被归到「不存在」里，
                // 回「❌ v58 不存在（主表与冷存档都查过了）」，还补一句「那它从来没被保存过」。
                // 而 v58 就是当前正文。这和「已归档被说成不存在」是同一个病根：
                // **只分了「已归档 / 不存在」两类，漏了「就是当前版本」这第三类。**
                if (version.equals(doc.getVersion())) {
                    StringBuilder cur = new StringBuilder();
                    cur.append("# ").append(doc.getTitle())
                       .append("（v").append(version).append(" **当前版本**）\n\n");
                    cur.append("> ✅ **这就是当前正文**，不需要查历史版本。\n");
                    cur.append("> 当前版本号 = v").append(doc.getVersion())
                       .append("；它存在 `docs` 表，不在历史版本表 `doc_versions` 里，")
                       .append("所以历史列表里看不到它 —— 这不是数据缺失。\n\n");
                    cur.append("**状态**: ").append(getStatusLabel(doc.getStatus())).append("\n");
                    cur.append("**更新时间**: ").append(doc.getUpdatedAt() != null ? doc.getUpdatedAt() : "-").append("\n");
                    cur.append("**正文字数**: ").append(doc.getContent() == null ? 0 : doc.getContent().length())
                       .append("\n\n---\n\n");
                    appendContentSlice(cur, doc.getContent(), offset, id, "get_experience_detail", version, doc);
                    return cur.toString();
                }

                // 查找指定版本
                DocVersion target = null;
                for (DocVersion v : versions) {
                    if (v.getVersion() == version) {
                        target = v;
                        break;
                    }
                }
                if (target == null) {
                    // ⚠️ 2026-10-26 01:0x：主表没有不代表不存在 —— 可能是被归档搬走了。
                    // 之前这里直接 return「未找到」，等于把「已归档」谎报成「不存在」，
                    // 读的人会以为数据丢了（比「没有入口」更糟）。
                    return archivedVersionResponse(id, version, doc, versions, offset);
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

                appendContentSlice(sb, target.getContent(), offset, id, "get_experience_detail", version, doc);
                // 注意：下面这段「此为历史版本」的尾巴仍需输出，故这里不 return
            sb.append("\n\n---\n> 📌 此为历史版本 v").append(version).append(" 的内容，当前最新版本为 v").append(doc.getVersion())
                  .append("。如需查看最新内容请使用 get_experience_detail(id=").append(id).append(")。");

                return sb.toString();
            }

            // 不传 version → 列出所有历史版本（**主表 + 冷存档合并**）
            //
            // ⚠️ 2026-10-26 01:0x：原来只列主表，归档走的那几版在列表里完全看不见 ——
            // 用户会以为「这文档只有 10 版历史」，而实际有 55 版。列表缺项比查询失败更隐蔽。
            List<DocVersionArchive> archivedAll = versionArchiveService.listArchived(id);
            if (versions.isEmpty() && archivedAll.isEmpty()) {
                return "经验 ID:" + id + "「" + doc.getTitle() + "」没有历史版本。当前仅有 v" + doc.getVersion() + "。";
            }

            StringBuilder sb = new StringBuilder();
            sb.append("# ").append(doc.getTitle()).append(" — 历史版本列表\n\n");
            sb.append("**当前版本**: v").append(doc.getVersion());
            sb.append(" | **状态**: ").append(getStatusLabel(doc.getStatus())).append("\n");
            sb.append("**主表 ").append(versions.size()).append(" 条");
            if (!archivedAll.isEmpty()) {
                sb.append(" + 冷存档 ").append(archivedAll.size()).append(" 条");
            }
            sb.append("**\n\n");
            sb.append("| 版本号 | 位置 | 变更类型 | 更新者 | 更新时间 |\n");
            sb.append("|--------|------|----------|--------|----------|\n");
            for (DocVersion v : versions) {
                // A3：META 版只记事件、没有正文副本；老数据无 change_type 时按正文处理
                String changeLabel = changeTypeLabel(v.getChangeType());
                sb.append("| v").append(v.getVersion())
                  .append(" | 主表")
                  .append(" | ").append(changeLabel)
                  .append(" | ").append(v.getUpdatedName() != null ? v.getUpdatedName() : "未知")
                  .append(" | ").append(v.getCreatedAt() != null ? v.getCreatedAt() : "未知")
                  .append(" |\n");
            }
            for (DocVersionArchive a : archivedAll) {
                String changeLabel = changeTypeLabel(a.getChangeType());
                sb.append("| v").append(a.getVersion())
                  .append(" | 📦 已归档")
                  .append(" | ").append(changeLabel)
                  .append(" | ").append(a.getUpdatedName() != null ? a.getUpdatedName() : "未知")
                  .append(" | ").append(a.getCreatedAt() != null ? a.getCreatedAt() : "未知")
                  .append(" |\n");
            }
            sb.append("\n> 「仅元数据」的版本只改了标签/摘要/标题/归属，**没有保存正文副本**（正文当时没变）。\n");
            if (!archivedAll.isEmpty()) {
                sb.append("> 「📦 已归档」的版本搬进了冷存档，**正文完整保留**，")
                  .append("用 get_experience_history(id=").append(id)
                  .append(", version=版本号) 仍能读到原文 —— 归档不是删除。\n");
            }
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
     * 按 offset 输出正文的一段，并给出「下一段怎么读」。
     *
     * <p><b>为什么需要分段而不是一次性全给</b>（2026-10-26 01:1x 阁主实测）：
     * 归档里 <b>31 个版本超过 8000 字</b>（v14 起），主表 v45+ 也全部超。
     * 而原来的截断提示是「改用 export_document 按 ID 单独读取」——
     * <b>但 export_document 只接受 id、不能指定 version，导出的永远是当前正文，这条建议是死路。</b>
     *
     * <p>所以正确做法是：**这个工具自己支持分段**，回执里给出确切的下一段参数，
     * 让人能一路读到底，不再指向一个做不到的工具。
     *
     * <p>⚠️ 分段是按<b>字符</b>切的（不是按行），因为要保证「同一 offset 永远对应同一位置」；
     * 按行切在跨段边界处容易漏行或重复行。
     */
    private void appendContentSlice(StringBuilder sb, String full, Integer offset,
                                    Long id, String detailTool, Integer version, Doc doc) {
        if (full == null || full.isEmpty()) {
            sb.append("> 该版本没有正文内容（可能是仅元数据变更的版本）。\n");
            return;
        }
        int total = full.length();
        int from = (offset == null || offset < 0) ? 0 : Math.min(offset, total);
        int to = Math.min(from + detailMaxChars, total);
        boolean isLast = to >= total;

        sb.append(full, from, to);

        sb.append("\n\n---\n");
        sb.append("📖 **本段范围**: 第 ").append(from).append(" – ").append(to)
          .append(" 字符 / 共 ").append(total).append(" 字符");
        if (from == 0 && isLast) {
            sb.append("（**全文已一次读完**）\n");
        } else if (isLast) {
            sb.append("（**这是最后一段**）\n");
        } else {
            sb.append("（**还有后续**）\n");
            // ⚠️ 指引必须指向**当前这个工具**，不能写死 get_experience_history。
            // 2026-10-26 阁主实测：从 get_experience_detail 读到的续读指引写的是
            // `get_experience_history(id=654, version=66, offset=20000)` ——
            // 虽然能走通（version 等于当前版本时 history 会返回当前正文），
            // 但**指引与入口不一致**，读的人会以为 detail 自己不能分段，
            // 进而怀疑 detail 的 offset 是摆设（而它确实是好的）。
            // 「让调用方绕道」不是错，但**回执不能给出与入口不匹配的指令**。
            sb.append("\n> 继续读下一段：`").append(detailTool).append("(id=").append(id);
            // 只有读历史版本时才带 version —— detail 不认识这个参数，多传会让人误解
            if (version != null && doc != null && !version.equals(doc.getVersion())) {
                sb.append(", version=").append(version);
            }
            sb.append(", offset=").append(to).append(")`\n");
            sb.append("> 反复照抄回执里的 offset 续读，直到显示「这是最后一段」。\n");
            sb.append("> ⚠️ 不要用 `export_document` 代替 —— 它只接受 id、不能指定版本、也不分段：")
              .append("读历史版本会导出当前正文，长文档只能整块吐。\n");
        }
        // ⚠️ 2026-10-26 02:2x 越哥实测：这里原来还有一句「也可用 get_experience_detail(id=654)
        // 读当前正文（同样支持大文档，但那个工具不分段）」—— 而 detailTool 本身就是
        // get_experience_detail，上面刚教人用它分段续读，下面又说它不分段，**同一段回执自相矛盾**。
        // 病根是这段文字写于 detail 还没有 offset 的时候，后来 detail 补了分段，这句没回头改。
        // 「同一个事实有两个出口」的老毛病：分段能力只有一处真源（本方法），就别再另写一句描述。
    }

    /**
     * 主表查不到某版本时的处理：先回查归档表，并把「已归档 / 真的不存在」说清楚。
     *
     * <p><b>为什么必须有这个方法</b>（2026-10-26 01:0x 阁主实测发现的硬伤）：
     * 归档后 {@code get_experience_history(654, version=1)} 返回
     * 「未找到经验 ID:654 的 v1 历史版本」。而当时的文档写着
     * 「归档不是删除，每条都能原样取回」，还用这个理由说服人放心搬 ——
     * <b>MCP 层面一个字都取不回来</b>。
     *
     * <p>更糟的是它回「该版本不存在」而不是「已归档」：
     * 读的人会以为数据丢了。<b>「查不到」和「不存在」是两件事</b>，必须分开说。
     *
     * <p>三种情况严格区分：
     * <ol>
     *   <li>归档表里有 → 返回归档正文，并标明「已归档」+ 归档时间/归档人</li>
     *   <li>归档表没有、但该文档有归档记录 → 说「这个版本号在主表和存档里都没有」，
     *       并列出真正存在的版本范围（含归档侧）</li>
     *   <li>该文档从来没归档过 → 只剩主表可查；查不到就按「库里没有副本」+ 缺号清单回答，
     *       <b>不许替它编一个原因</b>（2026-10-26 08:3x：原来写「那它从来没被保存过
     *       （例如该文档是从 vN 直接创建的）」，把「版本号被消耗」解释掉了）</li>
     * </ol>
     */
    private String archivedVersionResponse(Long id, Integer version, Doc doc, List<DocVersion> versions,
                                           Integer offset) {
        List<DocVersionArchive> archived = versionArchiveService.findArchived(id, version);

        if (!archived.isEmpty()) {
            // 命中归档：原样返回正文，但必须标明这是归档副本
            StringBuilder sb = new StringBuilder();
            DocVersionArchive a = archived.get(0);
            sb.append("# ").append(doc.getTitle()).append("（v").append(version).append(" **已归档版本**）\n\n");
            sb.append("> 📦 该版本在 **").append(a.getArchivedAt())
              .append("** 由 **").append(a.getArchivedBy() == null ? "未知" : a.getArchivedBy())
              .append("** 从主表搬入冷存档（原因：").append(a.getArchiveReason()).append("）。\n");
            sb.append("> **正文完整保留，与归档前完全一致**。归档只是搬位置，不是删除。\n\n");
            sb.append("**更新者**: ").append(a.getUpdatedName() != null ? a.getUpdatedName() : "未知").append("\n");
            sb.append("**更新时间**: ").append(a.getCreatedAt() != null ? a.getCreatedAt() : "未知").append("\n");
            sb.append("**当前最新版本**: v").append(doc.getVersion()).append("\n");
            if (archived.size() > 1) {
                sb.append("\n> ⚠️ **该版本号在归档表里有 ").append(archived.size())
                  .append(" 条记录**（主表本身存在重复版本行，归档时原样保留、未去重）。")
                  .append("这里返回第 1 条；全部 ").append(archived.size())
                  .append(" 条可用 list_archived_versions(id=").append(id).append(") 查看。\n");
            }
            sb.append("\n---\n\n");

            if (a.getContent() == null) {
                sb.append("> 该版本**没有保存正文副本**：那次改动只动了元数据（标签/摘要/标题/归属），正文未变。\n");
                sb.append("> 要读当时的正文，请找同文档的 CONTENT 类型版本，或用 get_experience_detail(id=")
                  .append(id).append(") 看当前正文。\n");
            } else {
                // 2026-10-26：改为分段可控的读取，不再「截断后指向 export_document（那条路走不通）」
                appendContentSlice(sb, a.getContent(), offset, id, "get_experience_detail", version, doc);
            }
            sb.append("\n\n---\n> 📌 此为**归档版本** v").append(version)
              .append("，当前最新版本为 v").append(doc.getVersion())
              .append("。如需最新内容请用 get_experience_detail(id=").append(id).append(")。");
            return sb.toString();
        }

        // 归档里也没有 —— 现在要区分「该文档有归档但没这个版本号」和「根本没归档过」
        List<DocVersionArchive> allArchived = versionArchiveService.listArchived(id);
        StringBuilder sb = new StringBuilder();
        // ⚠️ 2026-10-26 08:3x 阁主实测：这里原来写「❌ v13 不存在」+「那它从来没被保存过
        //    （例如该文档是从 v21 直接创建的）」——**第二句是替缺陷编了一个听起来合理的解释**。
        //    #101 是从 v1 一路走来的，不可能「从 v21 创建」；真因是门户更新没存快照。
        //    后果比措辞严重：读的人接受了这个解释，就不会再怀疑「版本号被消耗了」——
        //    10 个永久缺失的版本号本该被发现，却被这句话解释掉了。
        //    改成：只陈述事实（库里没有副本）+ 已知原因（带日期和修复状态）+ 明确说「不要替它编原因」。
        sb.append("❌ **v").append(version).append(" 在库里没有副本**（主表与冷存档都查过了）\n\n");
        sb.append("当前最新版本: v").append(doc.getVersion()).append("\n");

        List<Integer> mainVersions = new ArrayList<>();
        for (DocVersion v : versions) mainVersions.add(v.getVersion());
        if (!mainVersions.isEmpty()) {
            sb.append("**主表里的版本**: ");
            for (Integer v : mainVersions) sb.append("v").append(v).append(" ");
            sb.append("\n");
        }
        if (!allArchived.isEmpty()) {
            sb.append("**冷存档里的版本**: ");
            for (DocVersionArchive a : allArchived) sb.append("v").append(a.getVersion()).append(" ");
            sb.append("（").append(allArchived.size()).append(" 条，")
              .append("这叫「已归档」不是「丢失」——用 get_experience_history(id=")
              .append(id).append(", version=上面的版本号) 仍能读到原文）\n");
        } else {
            sb.append("**冷存档**: 该文档从未归档过任何版本\n");
        }

        java.util.List<Integer> arcVs = new ArrayList<>();
        for (DocVersionArchive a : allArchived) arcVs.add(a.getVersion());
        VersionArchiveService.VersionCoverage cov = VersionArchiveService.VersionCoverage.of(mainVersions, arcVs);

        if (version > cov.maxVersion()) {
            // 事实可判：比「库里存在的最高版本号」还大 —— 这个号还没产生（或记错了）
            // ⚠️ 2026-10-26 08:5x 阁主指出：原来写「已存最高版本号是 v20」+「当前正文是 v21」，
            //    两个数字看着矛盾 —— v21 也是版本号，只是它不在快照表（快照存的是「上一次」的正文）。
            sb.append("\n> **已存快照最高 v").append(cov.maxVersion())
              .append("；当前正文是 v").append(doc.getVersion())
              .append("（它不在快照表里，那不是缺失）**。你要的 v").append(version)
              .append(" 比已存快照还大 —— **这个版本号还没产生**（或版本号记错了），")
              .append("先用 get_experience_detail(id=").append(id).append(") 看当前内容。");
            return sb.toString();
        }

        if (cov.missing().contains(version)) {
            sb.append("\n> ⚠️ v").append(version).append(" 属于**本库已知的缺号**（缺号清单：");
            for (int i = 0; i < cov.missing().size() && i < 8; i++) {
                if (i > 0) sb.append("、");
                sb.append("v").append(cov.missing().get(i));
            }
            sb.append("）。这类版本号**被消耗过，但当时没留下正文副本**。\n");
            sb.append("> 已知原因：早期经门户「更新我的经验」改正文时只递增版本号、未存快照")
              .append("（2026-10-26 已修，此后不再新增）。**这些副本当时就没存，不是被归档搬走的**。\n");
            // 起点要取「主表 + 冷存档」的最小版本号：只说主表会写成「从 v9 起」，
            // 读的人以为文档就是 v9 才建的（实际 v1~v8 在冷存档里躺着）。
            int lowest = cov.maxVersion();
            for (Integer mv : mainVersions) lowest = Math.min(lowest, mv);
            for (Integer av : arcVs) lowest = Math.min(lowest, av);
            sb.append("> ⚠️ **不要把它解释成「该文档是从 v").append(doc.getVersion())
              .append(" 直接创建的」** —— 这份文档的版本号从 v").append(lowest).append(" 一路上来");
            if (!arcVs.isEmpty()) {
                sb.append("（v1~v").append((int) java.util.Collections.max(arcVs))
                  .append(" 在冷存档里仍可读）");
            }
            sb.append("，只是这几版当时没留副本。")
              .append("**给缺陷编一个合理的原因，下一个人就不会再追它了。**");
            return sb.toString();
        }

        // 剩下的情况：版本号在已存范围内、但既不在主表也不在归档 —— 理论上不该出现，如实说不知道
        sb.append("\n> v").append(version).append(" 不在主表、也不在冷存档，但它落在已存范围（v1 ~ v")
          .append(cov.maxVersion()).append("）内，也不在缺号清单里 —— ")
          .append("**这是不一致状态，请直接报给维护者**（不要自行推测原因）。");
        return sb.toString();
    }

    /**
     * 搜索经验库
     */
    @Tool(name = "search_experience", description = "搜索经验库（宽松召回、按相关度排；用自然语言描述场景）。默认只搜 ACTIVE，要历史传 includeStatus=COMPLETED,DEPRECATED,BROKEN 或 ALL。**scope 默认 all**：全局经验 + 你名下所有项目的经验一起排序，结果标注来源项目；scope=global 只搜全局（旧的「项目段末尾提示」行为），scope=project 只搜 projectName 指定的项目。0 结果会给出相近经验与排查建议。")
    public String searchExperience(
            @ToolParam(description = "场景描述，空格分隔多个关键词") String query,
            @ToolParam(description = "可选。模板类型过滤：problem_solution/knowledge_doc/todo_list/bug_fix/config_guide/how_to/schedule_plan/goal", required = false) String templateType,
            @ToolParam(description = "可选。默认只搜 ACTIVE；要已完成/过期传 COMPLETED,DEPRECATED,BROKEN，ALL=全部", required = false) String includeStatus,
            @ToolParam(description = "可选。项目名或别名。传了即为「只搜该项目」", required = false) String projectName,
            @ToolParam(description = "可选。作用域：all（默认，全局+我名下全部项目一起排） / global（只搜全局） / project（只搜 projectName 指定的项目）。语义变更 2026-11-05", required = false) String scope,
            @ToolParam(description = "可选。返回条数，默认 20，上限 50", required = false) Integer limit,
            @ToolParam(description = "可选。偏移量，默认 0（翻页照抄上页提示的 offset 值）", required = false) Integer offset,
            @ToolParam(description = "可选。true=精简（只 ID/标题/类型/摘要）；false=完整", required = false) Boolean brief,
            @ToolParam(description = "可选。true=附带打分解释（各维度得分/阈值预过滤/同分决胜/候选数/公式常量），用于验收对账。默认 false，回执不变", required = false) Boolean explain) {
        
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

            // ===== 作用域解析（2026-11-05 · #1220 第③条）=====
            // 原来「不传 projectName 就只搜全局」是硬隔离：一件事散在两个项目里，
            // 就得先 list_projects 再逐个 projectName 搜（近 7 天 list_projects 203 次、
            // list_project_experiences 97 次 —— 这已经是常态动作，不是偶发）。
            // 现在作用域是可选过滤维度：默认 all = 全局 + 我名下全部项目，同一条 SQL 一起排序。
            String sc = (scope == null || scope.trim().isEmpty()) ? "all" : scope.trim().toLowerCase();
            if (!java.util.Set.of("all", "global", "project").contains(sc)) {
                return "❌ scope 只能是 all / global / project（收到：" + scope + "）。\n"
                     + "· all（默认）= 全局 + 你名下全部项目一起排，结果标 🗂 来源\n"
                     + "· global = 只搜全局经验（项目命中只在末尾提示）\n"
                     + "· project = 只搜 projectName 指定的项目";
            }
            if ("project".equals(sc) && projectId == null) {
                return "❌ scope=project 必须同时传 projectName。不知道项目名就先 list_projects 看一眼。";
            }
            if (projectId != null && "global".equals(sc)) {
                return "❌ 冲突：传了 projectName 又传 scope=global。只想搜某个项目用 scope=project，"
                     + "想全局+项目一起搜就把 scope 留空（默认 all）。";
            }
            // 指定了项目 → 按项目走；否则 scope=all 才跨域
            boolean scopeAll = "all".equals(sc) && projectId == null;

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

            DocService.SearchMeta searchMeta = new DocService.SearchMeta();
            searchMeta.explain = Boolean.TRUE.equals(explain);
            var result = docService.search(query, templateType, statusFilter, projectId, 1, poolSize, searchMeta, scopeAll);
            long matchedTotal = result.getTotal();
            java.util.List<Doc> docs = new ArrayList<>(result.getRecords());

            // L0-1b（2026-10-30，越哥拍板「B 隔离＋末尾提示」）：全局结果**只排全局**。
            // 历史包袱：E5 时期（2026-10-24）为了让「查询词里写了项目名」的项目经验能被看到，
            // 把项目域命中**前置混排**进主列表 —— 代价是全局排序被项目文档污染，
            // 并且和 project_id IS NULL 的硬隔离自相矛盾（隔离了又混进来）。
            // 现在的分工：主排序逐字不动、一条项目文档都不掺；项目侧命中单独算一份，
            // 追加在**全部全局结果之后**（见 renderProjectSegment），只给标题+摘要。
            Project autoProject = null;
            long projectTotal = 0;
            java.util.List<Doc> projectTop = java.util.List.of();
            if (projectId == null) {
                autoProject = detectProjectInQuery(query);
                // ⚠️ 2026-11-05：scopeAll 时项目文档已经并进主排序，再算一遍项目段
                //    就是同一批内容在回执里出现两遍（还会让人以为「主列表没搜到项目内容」）。
                if (autoProject != null && !scopeAll) {
                    try {
                        // N：项目侧命中的**纯 DB 计数**（宽松 OR 召回口径），能被一条 count(*) 对账。
                        // 不能用 search(...).getTotal()：那是覆盖率重排后的条数，随候选池浮动。
                        projectTotal = docService.countProjectHits(query, templateType, statusFilter, autoProject.getId());
                        // 展示取固定 5 条：项目段不参与主排序、也不吃全局分页的 offset/limit
                        var projResult = docService.search(query, templateType, statusFilter,
                                autoProject.getId(), 1, PROJECT_SEGMENT_MAX);
                        projectTop = projResult.getRecords() == null
                                ? java.util.List.of() : new ArrayList<>(projResult.getRecords());
                        if (projectTop.size() > PROJECT_SEGMENT_MAX) {
                            projectTop = new ArrayList<>(projectTop.subList(0, PROJECT_SEGMENT_MAX));
                        }
                    } catch (Exception e) {
                        log.debug("searchExperience: 项目侧命中探测失败 project={}, {}", autoProject.getName(), e.getMessage());
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
                var similar = docService.findSimilar(query, templateType, statusFilter, projectId, 3, scopeAll);
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
                if (searchMeta.explain) {
                    sb.append(renderExplain(searchMeta, java.util.List.of()));
                }
                // 全局 0 条、项目里有 → 这正是「搜不到就以为没记过、于是重复记一遍」的场景，
                // 提示比什么都重要（L0-1b）。
                sb.append(renderProjectSegment(detected, projectTotal, projectTop, query));
                return sb.toString();
            }

            // M18-b：内存分页。docs 现在只含全局命中（项目段已移出主列表），
            // 切片只切全局，翻页不会串位。
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
            sb.append(filterInfo).append(totalLabel(searchMeta.tier)).append(" ")
              .append(effTotal).append(" 条");
            if (effOffset > 0 || pageDocs.size() < effTotal) {
                sb.append("，本次返回第 ").append(fromIdx + 1).append("-").append(toIdx).append(" 条");
            }
            // L0-2：重排档的 total 是「重排接受数」，不是库里真实匹配数。两个数分开报，
            // 否则「共 60 条」既对不上 count(*)，也解释不了翻到后面为什么空了。
            sb.append(totalScopeNote(searchMeta.matchedTotal, effTotal));
            if (scopeAll) {
                // 2026-11-05：两个 scope 对「进度流水」的处置不同，回执必须说清 ——
                // 同一句 query 在 all 和 project 下第一条不一样，不写原因就会被当成工具坏了
                // 2026-11-06（#1290 C49/C71）：排除条件改成单条件、并扩到 todo_list。
                // ⚠️ 显式传了这两类 templateType 时**不排除**，文案不能再说「不含」——
                // 否则读的人会以为「我明明按类型查，怎么还是被排了」，进而怀疑工具坏了（本节第三次踩同类）。
                boolean explicitExcludedType = templateType != null
                        && DocService.EXCLUDED_FROM_RANKING.contains(templateType.trim());
                if (explicitExcludedType) {
                    sb.append("｜作用域 all（全局 + 你名下项目，**你显式按类型过滤 ")
                      .append(templateType.trim()).append("，本档不排除该类**）");
                } else {
                    sb.append("｜作用域 all（全局 + 你名下项目，**不含过程记录 project_progress 与待办 todo_list**；"
                            + "要读它们用 get_project_progress / check_my_todos，或在结果末尾的「过程记录里也命中」找）");
                }
            } else if (projectId != null) {
                sb.append("｜作用域 project（仅该项目，**含进度流水**）");
            }
            sb.append("：\n\n");

            // 2026-11-05 S2.1（#1272 C6 / #1236 S1 遗留）：scope=all 下不再输出末尾项目段，
            // 回归脚本 A 组因此解析不到「识别到哪个项目」，6 条全变假 FAIL。
            // 给一行稳定、可解析的识别回执；brief 模式保持「一条一行」，不加东西。
            if (!briefMode && projectId == null) {
                sb.append("🔎 项目识别: ")
                  .append(detected == null ? "无"
                          : ("查询词含项目「" + detected.getName() + "」（#" + detected.getId() + "）"))
                  .append("\n\n");
            }
            // #1272 C8：本页含污染源时，先给一行汇总，别让人翻到中间才发现前几条不能用。
            long pollutionCount = pageDocs.stream()
                    .filter(d -> pollutionReason(d) != null).count();
            if (pollutionCount > 0 && !briefMode) {
                sb.append("⚠️ 本页含 ").append(pollutionCount)
                  .append(" 条回归评测污染源（下面已逐条标注），判基线请排除\n\n");
            }

            // 作用域全开时，每条结果要标出「来自哪个项目」，否则读的人分不清这条是全局结论还是项目内部结论
            java.util.Map<Long, String> projNames = scopeAll ? loadProjectNames() : java.util.Map.of();

            // #752 第 2 条：时效提示（只读计算，不改正文、不改排序、不隐藏）。
            // 放顶部是因为「这条结论还作不作数」必须在读内容之前就知道；
            // 埋在每条后面会被当成噪音，读者已经在按过期结论规划了。
            sb.append(staleVerifyLine(pageDocs, java.time.LocalDate.now()));

            sb.append(renderDocs(pageDocs, briefMode, projNames, query));
            if (searchMeta.explain) {
                sb.append(renderExplain(searchMeta, pageDocs));
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
            // 项目侧命中：全部全局结果之后单独一段（末尾提示），不吃 offset/limit，只给标题+摘要。
            sb.append(renderProjectSegment(detected, projectTotal, projectTop, query));
            // 过程记录补位（#1290 C71-3）：主排名把 project_progress / todo_list 排掉了，
            // 但「昨天部署踩什么坑」这类问题的答案恰恰只在施工日志里。
            // 主排名干净 ≠ 让它消失：单独列一小段，不给摘要、不参与排名，带读法。
            sb.append(renderProcessHits(query, scopeAll, projectId, templateType, statusFilter));
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

    /** 全局搜索末尾「项目内还有 N 条」最多列几条（L0-1b）。只列标题+摘要，不列正文。 */
    static final int PROJECT_SEGMENT_MAX = 5;

    /** 过程记录补位最多列几条（只给 ID + 标题 + 读法，不给摘要，避免又变成一份排名）。 */
    static final int PROCESS_HINT_MAX = 3;

    /**
     * 过程记录补位段（#1290 C71-3）。
     *
     * <p>背景：主排名把 {@code project_progress} / {@code todo_list} 排掉了（它们有专属入口），
     * 但「昨天部署踩什么坑」「上次那个报错怎么解的」这类问题，答案本来就只存在于施工日志里。
     * 实测：排掉之后 #95 从 26 升到 15，但「昨天部署踩了什么坑」的前 5 条全是知识文档，
     * 真正记着那件事的施工回执一条都没出现 —— 那就是「砍过头」。
     *
     * <p>所以加这一段：不算排名、不给摘要、只报「过程记录里也命中 N 条」+ ID/标题/读法。
     * 显示条件与排除条件严格互补：只在 scope=all 且没显式指定 templateType 时出现
     * （显式指定时它们本来就在主排名里，再列一遍就成了重复）。
     */
    private String renderProcessHits(String query, boolean scopeAll, Long projectId,
                                     String templateType, String statusFilter) {
        if (!scopeAll || (templateType != null && !templateType.trim().isEmpty())) {
            return "";
        }
        try {
            var hits = docService.findProcessHits(query, projectId, statusFilter, PROCESS_HINT_MAX);
            if (hits == null || hits.isEmpty()) {
                return "";
            }
            StringBuilder sb = new StringBuilder();
            sb.append("\n📝 过程记录里也命中 ").append(hits.size())
              .append(" 条（施工日志/待办，不进主排名、不是知识条目）：\n");
            for (Doc d : hits) {
                String title = d.getTitle() == null ? "" : d.getTitle().replace("\n", " ");
                if (title.length() > 40) {
                    title = title.substring(0, 40) + "…";
                }
                String type = "todo_list".equals(d.getTemplateType()) ? "待办" : "过程记录";
                sb.append("- 【ID:").append(d.getId()).append("】").append(title)
                  .append(" ｜ ").append(type)
                  .append(" ｜ 读法：get_experience_detail(id=").append(d.getId()).append(")");
                if ("todo_list".equals(d.getTemplateType())) {
                    sb.append(" 或 check_my_todos");
                }
                sb.append("\n");
            }
            return sb.toString();
        } catch (Exception e) {
            // 补位是锦上添花，失败绝不能影响主结果
            log.debug("ExpHubTools.renderProcessHits 失败，已跳过: {}", e.getMessage());
            return "";
        }
    }

    /**
     * 全局搜索末尾的项目侧命中段（L0-1b，2026-10-30）。
     *
     * <p>为什么单独一段、而不是并进主列表：主排序只能是全局命中（{@code project_id IS NULL}），
     * 一旦把项目文档掺进去，tag/score 口径就混了，用户看到的「相关度第一」其实是两个域拼出来的。
     * 所以项目命中降级成**末尾提示**：告诉你「东西在那儿」，要看正文自己带 projectName 进去。
     *
     * <p>口径（写死，别顺手改）：
     * <ul>
     *   <li>N = 项目侧「<b>提到这组词的文档数</b>」（纯 DB 计数，一条 count(*) 可对账），
     *       不是这里列出来的 5 条；也<b>不等于</b>带 projectName 重搜后回执上的「匹配 N 条」——
     *       后者过了相关度重排、会少。两个口径必须都能解释，所以文案里写清；</li>
     *   <li>这段不受全局 offset/limit 影响 —— 翻到第几页都照常出现，否则翻页的人永远看不到；</li>
     *   <li>每条只给标题 + 摘要（60 字），<b>不出正文</b>：不带 projectName 就拿不到项目正文，
     *       隔离边界不能靠这一行提示破掉；</li>
     *   <li>projectName 的值前后两处都要带上（段头 + 段尾），别让读者自己回头猜 code；</li>
     *   <li>N=0 或没识别到项目 → 返回空串，回执与「没有项目命中」时逐字一致。</li>
     * </ul>
     */
    static String renderProjectSegment(Project project, long projectTotal, java.util.List<Doc> top) {
        return renderProjectSegment(project, projectTotal, top, null);
    }

    /**
     * 同上，外加一行「对账用串」：把本次真正送进 {@code countByFulltext} 的
     * {@code +(词 词 词)} 原样打出来（2-gram 展开后的样子）。
     *
     * <p>2026-10-30 阁主点出：手抄这条串会差一条（5 vs 6），而这个坑之前只写在文档里靠人记。
     * 打出来之后，对账就是「复制这一行 → 贴进 SQL → 数」，不依赖任何人的记忆。
     */
    static String renderProjectSegment(Project project, long projectTotal, java.util.List<Doc> top, String query) {
        if (project == null || projectTotal <= 0 || top == null || top.isEmpty()) {
            return "";
        }
        String code = project.getProjectCode();
        StringBuilder sb = new StringBuilder("\n---\n");
        sb.append("【项目「").append(project.getName()).append("」内提到这组词的文档共 ").append(projectTotal)
          .append(" 条，下列 ").append(top.size()).append(" 条只给标题+摘要；查项目内请传 projectName=")
          .append(code).append("】\n\n");
        int i = 1;
        for (Doc d : top) {
            sb.append(i++).append(". 【ID:").append(d.getId()).append("】").append(d.getTitle())
              .append(" —— ").append(previewSummary(d.getSummary())).append("\n");
        }
        sb.append("\n💡 不返正文。").append(projectTotal)
          .append(" 是「提到这组词的文档数」（DB 直查可对账）；带 projectName=").append(code)
          .append(" 重搜会按相关度重排，条数可能更少，不是算错。\n");
        String orQuery = query == null ? "" : DocService.toOrQueryOf(query);
        if (!orQuery.isEmpty()) {
            sb.append("🧾 对账用串（原样传 countByFulltext，别手抄）：").append(orQuery).append("\n");
        }
        return sb.toString();
    }

    /**
     * 首行「N 条」的口径标签（2026-10-30 阁主退单：没标签时 12 / 111 / 108 并列会被判成算错）。
     *
     * <p>标签必须跟着<b>实际走的档位</b>，不能一律写「重排后」：
     * 严格档的总数是 {@code countByFulltext} 的 SQL 计数，没经过重排；写「重排后」就是新的谎。
     */
    static String totalLabel(DocService.SearchTier tier) {
        if (tier == null) {
            return "匹配";
        }
        switch (tier) {
            case STRICT:
                return "严格命中（全文 AND 计数，未重排）";
            case RERANK:
                return "重排后判定相关";
            case LIKE:
                return "模糊匹配（LIKE 兜底）";
            default:
                return "匹配";
        }
    }

    /** 列表类工具的默认/最大条数（E6）：默认 10 条省 token，需要全量走 offset 翻页 */
    private static final int LIST_DEFAULT_SIZE = 10;    private static final int LIST_MAX_SIZE = 50;

    /** update_experience 允许的模板类型（与 doc_templates 表 + 进度类一致） */
    static final java.util.List<String> ALLOWED_TEMPLATE_TYPES_LIST = java.util.List.of(
            "problem_solution", "knowledge_doc", "todo_list", "bug_fix", "config_guide",
            "how_to", "schedule_plan", "goal", "project_progress", "generic");
    static final java.util.Set<String> ALLOWED_TEMPLATE_TYPES =
            new java.util.LinkedHashSet<>(ALLOWED_TEMPLATE_TYPES_LIST);

    /**
     * 归档版本清单每页条数（2026-10-26）。
     *
     * <p>取 40：一篇 55 版的文档约 4200 字符，40 条约 3000 字符 ——
     * 留在转发层通用截断上限（3500）以内，**自己就不会被剪**。
     * 剪掉的风险不只是少几行：转发层兜底文案会建议「改用 export_document」，
     * 而那个工具**根本列不出归档清单**，等于给了一条死路。
     */
    private static final int ARCHIVE_LIST_PAGE = 40;

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
        return briefLine(index, doc, java.util.Map.of());
    }

    /** 精简模式 + 来源标注（作用域全开时用） */
    static String briefLine(int index, Doc doc, java.util.Map<Long, String> projNames) {
        // 标记前置，扫一眼就知道哪条结论存疑（flags 为空时不改变原有输出格式）
        String flagMark = "";
        if (doc.getFlags() != null && !doc.getFlags().isBlank()) {
            flagMark = " ⚑" + DocService.flagLabelText(doc.getFlags());
        }
        // D1 取代链：被取代的条目必须一眼可见，否则 AI 会把旧结论当现行结论用
        String supersededMark = (doc.getSupersededBy() != null)
                ? " ⛔已被#" + doc.getSupersededBy() + "取代" : "";
        String projMark = "";
        if (projNames != null && !projNames.isEmpty() && doc.getProjectId() != null) {
            String pn = projNames.get(doc.getProjectId());
            projMark = " 🗂" + (pn != null ? pn : ("#" + doc.getProjectId()));
        }
        // #1272 C8/C14：精简模式也必须带污染源标记（仍保持一条一行），并标明判据来源
        String reason = pollutionReason(doc);
        String pollutionMark = reason != null ? " ⚠️污染源(" + reason + ")" : "";
        return index + ". 【ID:" + doc.getId() + "】" + doc.getTitle()
                + " —— " + getTemplateTypeLabel(doc.getTemplateType())
                + " " + getStatusLabel(doc.getStatus()) + flagMark + supersededMark + projMark + pollutionMark
                + " · " + previewSummary(doc.getSummary()) + "\n";
    }

    /** 命中片段：命中词前后各取这么多字（#1220 第②条，2026-11-05） */
    private static final int SNIPPET_PAD = 40;

    /**
     * 从正文里抠出命中词周围的原句，用于搜索结果列表（2026-11-05 · #1220 第②条）。
     *
     * <p>为什么不在 SQL 里做：MySQL FULLTEXT 只给相关度分数，<b>不返回命中位置</b>，
     * 想在库里取片段只能再套一遍 LIKE/正则，代价远大于在内存里 indexOf。
     * 搜索结果本来就是 {@code SELECT *}（content 已经在手上），这里零额外查询。
     *
     * <p>取「正文里最早出现的那个命中词」而不是第一个 token：查询词顺序未必等于正文顺序，
     * 按正文位置取才能看到最靠前的上下文。
     *
     * @return 片段（带 … 表示截断）；一个词都没命中返回 null（宁可不显示，也不显示无关内容）
     */
    static String hitSnippet(String content, String query) {
        if (content == null || content.isEmpty() || query == null || query.isBlank()) return null;
        int bestPos = -1;
        int bestLen = 0;
        for (String raw : query.split("[\\s,，、;；/]+")) {
            String t = raw.trim();
            // 单字太泛（「的」「为」满篇都是），至少 2 字才当命中词。
            // 2026-11-05 复核：这条是**设计取舍不是缺陷** —— 放行单字会把片段定位到
            // 「这是一个包含很多东西的正文」里的第一个「的」，比不给片段更误导（见 HitSnippetTest）。
            if (t.length() < 2) continue;
            String matched = t;
            int pos = content.indexOf(t);
            // 2026-11-05 S2.1（#1272 C1）：中文长 query 不加空格是常态，
            // 整串 indexOf 必然落空 → 命中片段对中文最典型的用法结构性失效。
            // 退化为「从长到短取连续子串」，优先长片段（越具体越能定位），最低 2 字。
            if (pos < 0 && t.length() > 2 && containsCjk(t)) {
                int[] sub = longestCjkSubstringHit(content, t);
                if (sub != null) {
                    pos = sub[0];
                    matched = t.substring(sub[1], sub[1] + sub[2]);
                }
            }
            if (pos >= 0 && (bestPos < 0 || pos < bestPos)) {
                bestPos = pos;
                bestLen = matched.length();
            }
        }
        if (bestPos < 0) return null;

        int start = Math.max(0, bestPos - SNIPPET_PAD);
        int end = Math.min(content.length(), bestPos + bestLen + SNIPPET_PAD);
        // 别把代理对（emoji 等）从中间切断 —— 切一半会变成乱码方块
        if (start > 0 && Character.isLowSurrogate(content.charAt(start))) start--;
        if (end < content.length() && Character.isLowSurrogate(content.charAt(end))) end++;

        String piece = content.substring(start, end).replaceAll("\\s+", " ").trim();
        return (start > 0 ? "…" : "") + piece + (end < content.length() ? "…" : "");
    }

    /** 常见的虚词二字组：中文长 query 降到 2 字兜底时，别拿它们当定位锚点。 */
    private static final java.util.Set<String> WEAK_BIGRAMS = java.util.Set.of(
            "怎么", "什么", "如何", "可以", "这个", "那个", "我们", "你们", "他们",
            "因为", "所以", "但是", "以及", "关于", "通过", "进行", "使用", "一个", "一些");

    private static boolean containsCjk(String s) {
        return s.codePoints().anyMatch(cp -> cp >= 0x4E00 && cp <= 0x9FFF);
    }

    /**
     * 中文长 token 里找「正文中最靠前且最长」的连续子串。
     * 长度从 6 递减到 2，某一档有命中就停（长片段更具体）；同档取正文最靠前的位置。
     *
     * @return {posInContent, startInToken, length}；一个都没命中返回 null
     */
    private static int[] longestCjkSubstringHit(String content, String token) {
        for (int len = Math.min(token.length(), 6); len >= 2; len--) {
            int best = -1, bestStart = -1;
            for (int i = 0; i + len <= token.length(); i++) {
                String sub = token.substring(i, i + len);
                if (len == 2 && WEAK_BIGRAMS.contains(sub)) continue;
                int p = content.indexOf(sub);
                if (p >= 0 && (best < 0 || p < best)) {
                    best = p;
                    bestStart = i;
                }
            }
            if (best >= 0) return new int[]{best, bestStart, len};
        }
        return null;
    }

    /**
     * 2026-11-05 S2.1（#1272 C1）：命中词只出现在标题/摘要时，原来直接不给片段。
     * 依次退到摘要、标题，至少让列表阶段能看到「它为什么被召回」。
     */
    static String hitSnippetWithFallback(String content, String summary, String title, String query) {
        String s = hitSnippet(content, query);
        if (s == null) s = hitSnippet(summary, query);
        if (s == null) s = hitSnippet(title, query);
        return s;
    }

    /**
     * L0-2（#101 S2.2）：total 双口径说明。
     *
     * <p>重排档返回的「共 N 条」是**重排接受数**（翻页以它为界）；数据库口径的真实匹配数
     * 由 {@code SearchMeta.matchedTotal} 给出，可被一条 {@code count(*)} 对账。
     * 两者不一致时必须说清，否则读的人会以为「库里只有 N 条」或「翻页坏了」。</p>
     *
     * @param matched  数据库口径匹配数；-1 表示本档未计算（STRICT / LIKE / NONE）
     * @param accepted 重排接受数（回执里的「共 N 条」）
     * @return 需要追加的说明；口径一致或未知时返回空串，不污染回执
     */
    static String totalScopeNote(long matched, long accepted) {
        if (matched < 0 || matched <= accepted) return "";
        return "（库里 OR 口径匹配 " + matched + " 条，覆盖率重排接受 " + accepted
                + " 条；翻页以接受数为界）";
    }

    /** 章号展示：空章号不能直接拼 null（`append(null)` 会打出「null」，看着像系统坏了） */
    static String chapterNoLabel(String chapterNo) {
        return (chapterNo == null || chapterNo.isBlank()) ? "（无章号）" : chapterNo;
    }

    /** 章号是字符串（"1"、"15"），按字典序排会得到 1,10,11,2…，这里转成数字比较；转不了排最后 */
    private static int chapterNoValue(String chapterNo) {
        if (chapterNo == null) return Integer.MAX_VALUE;
        try {
            return Integer.parseInt(chapterNo.trim());
        } catch (NumberFormatException e) {
            return Integer.MAX_VALUE;
        }
    }

    /** 项目 id → 名称（作用域全开时给每条结果标来源，只查一次） */
    private java.util.Map<Long, String> loadProjectNames() {
        java.util.Map<Long, String> m = new java.util.HashMap<>();
        try {
            for (Project p : projectService.listProjects()) {
                if (p.getId() != null) m.put(p.getId(), p.getName());
            }
        } catch (Exception e) {
            log.debug("loadProjectNames failed: {}", e.getMessage());
        }
        return m;
    }

    /**
     * 候选队列全量分布（#1220 第⑥条）：固定顺序输出，避免 HashMap 每次顺序都不一样。
     * 未知状态也要报出来 —— 静默吞掉等于把「多了个没人认识的状态」藏起来。
     */
    static String formatStatusCounts(java.util.Map<String, Long> counts) {
        java.util.List<String> order = java.util.List.of(
                ReviewCandidate.PENDING, ReviewCandidate.DUPLICATE, ReviewCandidate.SUPERSEDED,
                ReviewCandidate.DISTINCT, ReviewCandidate.SKIPPED);
        java.util.Map<String, Long> src = (counts == null) ? java.util.Map.of() : counts;
        StringBuilder sb = new StringBuilder();
        long total = 0;
        for (String st : order) {
            long n = src.getOrDefault(st, 0L);
            total += n;
            if (sb.length() > 0) sb.append("｜");
            sb.append(st).append(" ").append(n);
        }
        for (var e : src.entrySet()) {
            if (order.contains(e.getKey())) continue;
            long n = (e.getValue() == null) ? 0L : e.getValue();
            total += n;
            sb.append("｜").append(e.getKey()).append(" ").append(n);
        }
        sb.append("｜合计 ").append(total);
        return sb.toString();
    }

    private String renderDocs(List<Doc> docs) {
        return renderDocs(docs, false);
    }

    /**
     * 打分解释段（explain=true，2026-11-08 阁主 #134）。
     *
     * <p>为什么让服务端吐、而不让验收脚本自己算：Python 复刻是**另一套实现**，
     * 实测与线上逐位一致只有 10/33（「积分兑换提现异常」）和 23/33
     * （「代码怎么发布到线上服务器 部署」）—— 差的那几位全是同分决胜、阈值预过滤这类
     * 复刻没实现的规则。服务端自己吐出来，就不存在「两个实现对齐」这件事。
     *
     * <p>版式取舍：公式/阈值/常量都在这里出现<b>一次</b>，数值全部来自
     * {@link DocService.SearchExplain}（打分时顺手记的，不是事后重算）。
     * 明细只列本页，避免 200 条候选把回执撑爆；但「参与比较 N 条」如实给出。
     */
    private String renderExplain(DocService.SearchMeta meta, List<Doc> pageDocs) {
        DocService.SearchExplain ex = meta == null ? null : meta.explainResult;
        StringBuilder sb = new StringBuilder();
        sb.append("\n📊 打分解释（explain=true：以下数值由服务端在本次打分时记录，不是复刻）\n");
        sb.append("- 公式: ").append(DocService.formulaNote()).append("\n");
        if (ex == null) {
            sb.append("- 本次没有拿到打分快照（档位: ")
              .append(meta == null ? "未知" : meta.tier).append("）\n");
            return sb.toString();
        }
        if (ex.concepts == null || ex.concepts.isEmpty()) {
            sb.append("- 概念: 无（查询词无实质内容或未走到概念展开）｜档位: ").append(ex.tier).append("\n");
            return sb.toString();
        }
        sb.append("- 概念: ").append(String.join(" / ", ex.concepts))
          .append("（").append(ex.concepts.size()).append(" 个）｜")
          .append(DocService.thresholdNote(ex.requiredMatches, ex.concepts.size())).append("\n");
        sb.append("- 档位: ").append(ex.tier)
          .append("｜候选参与比较 ").append(ex.candidateCount).append(" 条")
          .append("｜DB 匹配 ").append(ex.matchedTotal < 0 ? "未记录" : ex.matchedTotal + " 条").append("\n");
        sb.append("- 阈值预过滤: 未命中任何概念 ").append(ex.droppedNoConcept)
          .append(" 条、命中但不足门槛 ").append(ex.droppedByThreshold)
          .append(" 条 → 进入排序 ").append(ex.scoredCount).append(" 条\n");
        sb.append("- 同分决胜: ")
          .append(ex.tieGroupCount == 0 ? "本次没有总分完全相同的文档"
                  : ex.tieGroupCount + " 组同分 → 按 updated_at 更新者在前（明细里标「同分」）")
          .append("\n");
        if (ex.tier != DocService.SearchTier.RERANK || ex.docs.isEmpty()) {
            sb.append("- 明细: 本档位不产生内存打分明细")
              .append(ex.tier == DocService.SearchTier.RERANK ? "（全被阈值/无概念命中滤掉）" : "").append("\n");
            return sb.toString();
        }
        sb.append("- 明细（只列本页 ").append(pageDocs == null ? 0 : pageDocs.size())
          .append(" 条；共 ").append(ex.scoredCount).append(" 条进入排序；候选位次 = 在 DB 召回池里的原始位次）:\n\n");
        sb.append("| 位次 | 文档 | 类型 | 总分 | coverage | field | recency | popularity | 命中 | 候选位次 | 同分 |\n");
        sb.append("|---|---|---|---|---|---|---|---|---|---|---|\n");
        int shown = 0;
        if (pageDocs != null) {
            for (Doc d : pageDocs) {
                DocService.DocExplain e = ex.byId(d.getId() == null ? -1L : d.getId());
                if (e == null) continue;
                shown++;
                String tie = "-";
                if (!e.tiePeers.isEmpty()) {
                    StringBuilder p = new StringBuilder();
                    for (Long pid : e.tiePeers) {
                        if (p.length() > 0) p.append('/');
                        p.append('#').append(pid);
                    }
                    tie = "同 " + p + (e.tieWon ? "（本条胜）" : "（本条负）");
                }
                sb.append("| ").append(e.rank)
                  .append(" | #").append(e.id)
                  .append(" | ").append(e.templateType == null ? "(未分类)" : e.templateType)
                  .append(" | ").append(String.format(java.util.Locale.ROOT, "%.4f", e.total))
                  .append(" | ").append(String.format(java.util.Locale.ROOT, "%.4f", e.coverage))
                  .append(" | ").append(String.format(java.util.Locale.ROOT, "%.4f", e.field))
                  .append(" | ").append(String.format(java.util.Locale.ROOT, "%.4f", e.recency))
                  .append(" | ").append(String.format(java.util.Locale.ROOT, "%.4f", e.popularity))
                  .append(" | ").append(e.matched).append('/').append(e.conceptTotal)
                  .append(" | ").append(e.candidateRank)
                  .append(" | ").append(tie).append(" |\n");
            }
        }
        if (shown == 0) {
            sb.append("| - | 本页文档不在本次打分明细里 | - | - | - | - | - | - | - | - | - |\n");
        }
        sb.append("\n> 对账提示：本表就是服务端判定依据。任何离线复刻的分数与顺序都只能算「诊断」，")
          .append("与这里不一致时以本表为准（不要反过来调服务端）。\n");
        return sb.toString();
    }

    private String renderDocs(List<Doc> docs, boolean brief) {
        return renderDocs(docs, brief, java.util.Map.of(), null);
    }

    /**
     * @param brief true=精简模式（M18-b）：一条一行，只留 ID/标题/类型/状态/摘要。
     *              省 token，适合「先扫一遍挑目标」；要看标签、作者、历史版本提示就传 false。
     * @param projNames 作用域全开时用于给每条标「来自哪个项目」，空 Map = 不标
     * @param query 原始查询词，用于生成命中片段（只在完整模式加，精简模式保持一行到底）
     */
    private String renderDocs(List<Doc> docs, boolean brief, java.util.Map<Long, String> projNames, String query) {
        StringBuilder sb = new StringBuilder();
        int i = 1;
        for (Doc doc : docs) {
            if (brief) {
                sb.append(briefLine(i++, doc, projNames));
                continue;
            }
            String typeLabel = getTemplateTypeLabel(doc.getTemplateType());
            String statusLabel = getStatusLabel(doc.getStatus());
            sb.append(i++).append(". 【ID:").append(doc.getId()).append("】").append(doc.getTitle()).append("\n");
            String reason = pollutionReason(doc);
            if (reason != null) {
                sb.append("   ⚠️ 回归评测污染源(").append(reason)
                  .append("): 本条含验收 query 原话或是信箱/回执/排期文档，判基线请排除\n");
            }
            sb.append("   类型: ").append(typeLabel).append(" | 状态: ").append(statusLabel)
              .append(" | 分类: ").append(doc.getCategory()).append(" | 查看: ").append(doc.getCallCount()).append("次\n");
            // 来源标注：作用域全开时全局与项目内经验混排，不标来源等于让读的人猜
            if (projNames != null && !projNames.isEmpty() && doc.getProjectId() != null) {
                String pn = projNames.get(doc.getProjectId());
                sb.append("   🗂 来源: 项目「").append(pn != null ? pn : ("#" + doc.getProjectId())).append("」\n");
            }
            sb.append("   摘要: ").append(previewSummary(doc.getSummary())).append("\n");
            // 命中片段（2026-11-05 · #1220 第②条）：只给标题+摘要时，判断「要不要读」只能靠猜，
            // 于是退化成整篇 get_experience_detail（7 天 2173 次读 vs 1228 次搜 = 1.77 倍）。
            // 这里直接从 content 抠出命中词周围的原句，让「读不读」在列表阶段就能定。
            String snippet = hitSnippetWithFallback(doc.getContent(), doc.getSummary(), doc.getTitle(), query);
            if (snippet != null) {
                sb.append("   命中: ").append(snippet).append("\n");
            }
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
                // ⚠️ 2026-10-26 03:1x：这里原来写「共 (version-1) 个（v1 ~ v(version-1)）」——
                //    和详情页导读是同一个病：**拿当前版本号推条数**。有缺号时（#101 缺 v13/v15）
                //    会多报 2 个；有重号时（#608 v2 两份）会少报 1 个。
                //    列表页为了不做 N+1 查询，本来就没查版本表 —— 那就**别报条数**，
                //    只报「当前 vN、有历史」，具体几条让人去 history 看。少说一句，好过说错一句。
                sb.append("   📚 历史版本: 当前 v").append(doc.getVersion())
                  .append("，如需了解演进过程请调用 get_experience_history(id=")
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
        // #1272 C9 的长句识别已由 L0-1a 的 ③ 分支（token ⊃ 别名项 + 边界 + df）在
        // resolveProject 内部统一处理，不再保留独立的内嵌兜底（两套判据会漂移）。
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
            // ⚠️ 2026-10-26 16:5x 补：原来漏了这个分支，回执显示成「❓ PENDING_DELETE」——
            //    机器看得懂、中文看不懂，等于没告诉人这条正等着被删。
            case DocService.PENDING_DELETE -> "🗑 待删（未删除）";
            default -> "❓ " + status;
        };
    }

    /**
     * 更新经验状态
     */
    @Tool(name = "update_experience_status", description = "更新经验状态。")
    public String updateExperienceStatus(
            @ToolParam(description = "经验ID（必填）") Long id,
            @ToolParam(description = "新状态，可选值：ACTIVE(正常)、COMPLETED(已完成)、BROKEN(已失效)、DEPRECATED(已过期)") String status,
            @ToolParam(description = "可选。**谁改的**——仅写调用日志，不影响版本快照", required = false) String updatedName) {
        
        try {
            AiAssistant assistant = getCaller();
            // ⚠️ 2026-10-25 修：`updatedName` 原来声明了却零使用。
            // 参数说明承诺「只写调用日志」，可日志里连签名都没有 —— 承诺了没兑现。
            // 现在真的写进日志，并在回执里显示，让调用方能自证署名生效（红线：回执必须自证值）。
            String operator = resolveAuthor(updatedName, assistant);
            log.info("ExpHubTools.updateExperienceStatus: ENTRY - id={}, status={}, operator={}",
                    id, status, operator);

            // 权限验证
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

            // ⚠️ 2026-10-26 16:5x 阁主实跑标记链路发现（反向路径）：
            //    已标待删的文档能用这个接口把状态改成 ACTIVE —— 等于 AI 自行撤回了
            //    「等用户确认」这道闸门，还顺手把 delete_reason / delete_marked_by 留在库里
            //    （updateStatus 只改 status，不清待删字段）。
            //    待删的进出必须走专用工具：进 = mark_for_deletion，出 = cancel_deletion。
            if (DocService.PENDING_DELETE.equals(existing.getStatus())) {
                return "❌ 该经验当前是「待删」状态，不能用状态接口改它。\n\n"
                     + "**ID**: " + id + "\n**标题**: " + existing.getTitle()
                     + "\n**当前状态**: " + getStatusLabel(existing.getStatus())
                     + "\n\n待删标记的进与出必须走专用工具（闸门在用户手里，不在 AI 手里）：\n"
                     + "· 撤销待删 → `cancel_deletion(id=" + id + ")`（它会同时清掉删除原因与标记人）\n"
                     + "· 改成别的状态 → 先 `cancel_deletion(id=" + id + ")`，再调本工具改状态";
            }

            Doc updated = docService.updateStatus(id, upperStatus, operator);
            String statusLabel = getStatusLabel(upperStatus);
            
            return "✅ 经验状态已更新！\n\n**ID**: " + id + "\n**标题**: " + updated.getTitle()
                    + "\n**新状态**: " + statusLabel
                    + "\n**操作人**: " + operator + "（只记日志，不改版本快照）"
                    + "\n\n注意：非ACTIVE状态的经验默认不会被 search_experience 搜索到，其他AI助手将不会看到此经验。";
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
    @Tool(name = "mark_for_deletion", description = "【标记待删，不是删除】把错误/过时/重复的经验标记待删，由用户在后台确认后才真正删除（你没有删除权限）。⚠️ 必须写 reason。标记后立即从搜索结果消失，内容仍可恢复；误标用 cancel_deletion 撤回。")
    public String markForDeletion(
            @ToolParam(description = "要标记待删的经验 ID") Long id,
            @ToolParam(description = "删除原因（必填）：说清为什么该删，后台据此决定是否真删。") String reason,
            @ToolParam(description = "可选。署名（谁标的），显示在后台待删队列", required = false) String authorName,
            @ToolParam(description = "可选。**谁改的**——与 authorName 等价", required = false) String updatedName) {

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
            // B1：署名优先用调用方自报的（同一 Key 可能被多个助手共用，Key 上只存得下一个名字）。
            // ⚠️ 2026-10-25 修：原来这里只认 authorName，updatedName 是死参数 ——
            // 参数说明承诺「两者都传时以本参数为准」，实际传了 updatedName 署名照样丢，
            // 后台待删队列显示的还是 Key 绑定的名字（白泽 23:41 查源码 + 阁主实测发现）。
            String by = resolveAuthor(updatedName, authorName, assistant);
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
    @Tool(name = "cancel_deletion", description = "撤销「待删」标记，恢复为 ACTIVE。只有被标待删的经验才需要调用。")
    public String cancelDeletion(
            @ToolParam(description = "要恢复的经验 ID") Long id,
            @ToolParam(description = "可选。**谁改的**——仅写日志", required = false) String updatedName) {

        try {
            AiAssistant assistant = getCaller();
            // ⚠️ 2026-10-25 修：`updatedName` 原来是死参数（参数说明写「只写日志」，但一行日志都没打）
            String operator = resolveAuthor(updatedName, assistant);
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
            Doc restored = docService.cancelDeletion(id, operator);
            log.info("ExpHubTools.cancelDeletion: #{} 撤销待删, operator={}", id, operator);
            return "✅ 已撤销待删标记\n\n**ID**: " + id + "\n**标题**: " + restored.getTitle()
                    + "\n**当前状态**: " + getStatusLabel(restored.getStatus())
                    + "\n**操作人**: " + operator
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
    @Tool(name = "report_experience_result", description = "反馈经验是否有帮助。参考某条经验执行任务后，无论成败都应反馈。⚠️ 没帮助必须写 feedback 说明原因（过时/环境不符/步骤缺失），并用 newExperienceId 关联你新建的正确经验。")
    public String reportExperienceResult(
            @ToolParam(description = "经验ID（必填），你参考过的经验ID") Long id,
            @ToolParam(description = "该经验是否有帮助。true=成功帮助你解决了问题，false=没有帮助") boolean helpful,
            @ToolParam(description = "反馈说明。helpful=true 时可选；helpful=false 时必填（说明为什么没用，管理员据此优化经验内容）。") String feedback,
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
    @Tool(name = "get_template", description = "【创建经验前必须调用】获取经验模板。不传 type 返回全部模板列表，传 type 返回指定模板结构与填写指南。")
    public String getTemplate(
            @ToolParam(description = "模板类型标识（problem_solution/knowledge_doc/todo_list/schedule_plan/bug_fix/config_guide/how_to/goal）；传空则返回全部模板列表。") String type) {
        
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
    @Tool(name = "create_experience", description = "创建新经验。必须先 get_template() 选模板；category/tags/aliases/summary 必填，否则别人搜不到。projectId=写进项目空间（项目经验不进全局搜索）。🔒 content 严禁含账号/密码/密钥/Token/连接串。")
    public String createExperience(
            @ToolParam(description = "标题。别写版本号（version 字段管理）") String title,
            @ToolParam(description = "正文（Markdown）。🔒 严禁含账号/密码/密钥/Token/连接串") String content,
            @ToolParam(description = "模板类型（必填），取 get_template 的 type 值") String templateType,
            @ToolParam(description = "分类（必填），不确定填「未分类」") String category,
            @ToolParam(description = "标签（必填），逗号分隔，3-5 个关键词") String tags,
            @ToolParam(description = "别名/同义词（必填），逗号分隔") String aliases,
            @ToolParam(description = "一句话摘要（必填）") String summary,
            @ToolParam(description = "作者标识，区分不同 AI 助手") String authorName,
            @ToolParam(description = "可选。归属项目ID，不传=写全局（只能填自己名下项目）", required = false) Long projectId) {
        
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
            // 2026-10-29 P2-8：一上来就写超长文的情况（把整份报告当一篇存），这里就该提醒
            result.append(longDocHint(created.getContent() == null ? 0 : created.getContent().length()));
            return result.toString();
        } catch (SensitiveContentGuard.Rejected e) {
            callLogService.incrementFailCalls();
            return e.getMessage();
        } catch (Exception e) {
            log.error("ExpHubTools.createExperience: FAILED", e);
            callLogService.incrementFailCalls();
            return "创建失败: " + e.getClass().getName() + " - " + e.getMessage();
        }
    }

    /**
     * 更新经验
     */
    @Tool(name = "update_experience", description = "更新已有经验的指定字段：**只传要改的字段**。清空用 clearFields 显式声明，不要传 null。改正文：改一段用 patch_experience、追加用 append_experience、整体重写用 export_document + import_document。整篇写回须带 originalHash 证明读过全文。成功回执末尾给 `writeback_fingerprint`（64 位）＝ 写完之后的新 hash，同一轮下一次整篇写回可直接当 originalHash，不必再 export 一次全文（与 export_document 的 content_sha256 同值）。🔒 严禁在 content 含账号密码/API密钥/Token。")
    public String updateExperience(
            @ToolParam(description = "经验ID（必填）") Long id,
            @ToolParam(description = "可选。新标题。别写版本号（version 管理）", required = false) String title,
            @ToolParam(description = "可选。新正文（整体替换）。只改一小段用 patch_experience，追加用 append_experience。🔒 严禁含账号/密钥/Token", required = false) String content,
            @ToolParam(description = "可选。分类", required = false) String category,
            @ToolParam(description = "可选。标签，逗号分隔。清空用 clearFields", required = false) String tags,
            @ToolParam(description = "可选。别名，逗号分隔。清空用 clearFields", required = false) String aliases,
            @ToolParam(description = "可选。一句话摘要。清空用 clearFields", required = false) String summary,
            @ToolParam(description = "可选。关联经验ID，逗号分隔。清空用 clearFields", required = false) String relatedIds,
            @ToolParam(description = "可选。修改作者名称", required = false) String authorName,
            @ToolParam(description = "可选。true=明知本次写回会截断正文、仍要写入（快照标 CONTENT_FORCED 留痕）。**它不能跳过 originalHash 校验**；局部改请用 patch_experience", required = false) Boolean force,
            @ToolParam(description = "**传 content 时必填**。`export_document` 回执里的 content_sha256（**原文**的 hash，不是新内容的）。与库中当前正文不一致直接拒绝：读半篇就写回会删掉没读到的部分", required = false) String originalHash,
            @ToolParam(description = "可选。显式清空字段，逗号分隔：tags/aliases/summary/relatedIds/category（category 置「未分类」）。title、content 不可清空", required = false) String clearFields,
            @ToolParam(description = "可选。归属：项目ID=归入项目；0=移回全局", required = false) Long projectId,
            @ToolParam(description = "可选。模板类型：problem_solution/knowledge_doc/todo_list/bug_fix/config_guide/how_to/schedule_plan/goal/project_progress/generic", required = false) String templateType,
            @ToolParam(description = "可选。**谁改的**——写进快照的审计署名（与 authorName「文档作者」不同）", required = false) String updatedName,
            @ToolParam(description = "可选。验证时间（YYYY-MM-DD 或 YYYY-MM-DD HH:mm）。"
                    + "**只传 verifiedAt、其他字段全不传 = 记录一次验证（不改正文），算一次真实改动**"
                    + "（2026-10-29 起；复核完结论仍成立、内容不需要动时用这个）；"
                    + "**不传 = 正文有变更时记为一次验证（当前时间）；只改 tags/title/summary 等元数据则不刷新**"
                    + "（口径「内容变更即验证」，白名单只认 content）；"
                    + "只改错字/润色、结论没重新确认过时，传原来的验证时间（get_experience_detail 回执里有）表示不刷新。"
                    + "格式不认识会直接报错，不会静默忽略。", required = false) String verifiedAt) {
        
        try {
            log.info("ExpHubTools.updateExperience: ENTRY - id={}, title={}, force={}", id, title, force);
            
            // 权限验证
            AiAssistant assistant = getCaller();
            if (assistant != null && !Boolean.TRUE.equals(assistant.getCanUpdate())) {
                return "❌ 权限不足：该API Key没有编辑经验的权限";
            }
            
            // 验证时间参数先解析：格式错要在任何写库动作之前拦下
            java.time.LocalDateTime verified;
            try {
                verified = parseVerifiedAtParam(verifiedAt);
            } catch (IllegalArgumentException bad) {
                return "❌ " + bad.getMessage() + "（未做任何改动）";
            }
            
            // 检查经验是否存在
            Doc existing = docService.getById(id);
            if (existing == null) {
                return "❌ 未找到ID为" + id + "的经验";
            }
            
            // === A5 B 方案：整篇写回必须带 originalHash（2026-10-30）===
            // 取代原来的「新内容更短且以旧正文开头」字数启发式。旧判定的盲区：
            // 读到前 2 万字、随手加两句，总长比原文还长 → 字数看起来「没缩水」，中间那 1 万字照丢。
            // 现在只认等式：调用方给的原文 hash == 库里当前正文的 hash。
            // 注意 force **不参与**这里 —— force 只能表示「我知道会变短」，不能表示「不用证明读过全文」。
            if (content != null && !content.isEmpty()) {
                ContentHashGuard.Verdict verdict =
                        ContentHashGuard.check(originalHash, existing.getContent());
                if (verdict != ContentHashGuard.Verdict.OK) {
                    log.warn("ExpHubTools.updateExperience: originalHash {} blocked. id={}, storedLen={}, provided={}",
                            verdict, id, existing.getContent() == null ? 0 : existing.getContent().length(),
                            ContentHashGuard.normalize(originalHash));
                    return verdict == ContentHashGuard.Verdict.MISSING
                            ? ContentHashGuard.missingMessage(id, existing.getContent())
                            : ContentHashGuard.mismatchMessage(id, existing.getContent(), originalHash);
                }
            } else if (originalHash != null && !originalHash.trim().isEmpty()) {
                // 没传 content 却传了 hash：不静默丢掉，明确告知它只在整篇写回时有意义
                return "❌ 传了 originalHash 但没有 content —— 本次没有正文要写。\n\n"
                     + "originalHash 只用于整篇写回（content 非空）时的校验。"
                     + "只改标题/标签/摘要等元数据不必传它（未做任何改动）。";
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
            // 2026-10-26 13:0x 阁主第 6 条：模板类型原来只能到网页端改（#4 是使用指南却被标成 problem_solution）
            if (templateType != null && !templateType.isBlank()) {
                String tt = templateType.trim().toLowerCase(java.util.Locale.ROOT);
                if (!ALLOWED_TEMPLATE_TYPES.contains(tt)) {
                    return "❌ 未知模板类型：「" + templateType.trim() + "」\n\n可用值："
                         + String.join(" / ", ALLOWED_TEMPLATE_TYPES_LIST) + "\n"
                         + "（模板类型影响：进度类会降到扫描最后层、列表显示的分类标签）";
                }
                updateDoc.setTemplateType(tt);
                changedFields.add("templateType");
            }
            // updatedName 不进 changedFields：它只影响本版署名，不改任何内容。
            // 若算进去，只传 updatedName 也会「成功」并产生一个没有任何内容变化的空版本。

            // A2：显式清空（null 无法表达清空，见 DocService.update 注释）
            java.util.Set<String> clearSet = parseClearFields(clearFields);
            for (String f : clearSet) changedFields.add("清空:" + f);

            // 2026-10-29 越哥裁决：**显式 verifiedAt 必须算作一次真实改动**。
            // 不这么做，「复核完、结论仍然成立、正文不需要改」这个最正常的动作会被下面的空改动拦截挡下 ——
            // 想留下验证证据的唯一出路变成「随便改点东西」，而那正是白名单要防的伪造。
            // 影响面仅限「空改动拦截」：decideVerify 的优先级一个字没动（显式值本来就最高 → manual）；
            // 也不产生任何内容字段，正文/摘要/标签都不会被改。
            if (verified != null) {
                changedFields.add("验证时间");
            }

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

            updateDoc.setLastVerifiedAt(verified);
            // 来历决定快照怎么留痕：写了正文且 force=true → FORCED（快照标 CONTENT_FORCED）；
            // 写了正文 → HASH_VERIFIED（上面已经用 originalHash 证明读过全文）；
            // 只改元数据 → INTERNAL（本来就没有正文变更，不需要任何 hash 语义）。
            boolean contentWritten = changedFields.contains("content");
            WriteOrigin origin = !contentWritten ? WriteOrigin.INTERNAL
                    : (force != null && force ? WriteOrigin.FORCED : WriteOrigin.HASH_VERIFIED);
            docService.update(id, updateDoc, clearSet, updatedName, origin);
            if (changeScope) {
                docService.updateProjectId(id, targetProjectId);
            }

            // E9：回显改动，让调用方当场就能核对，不必再读一次
            StringBuilder res = new StringBuilder();
            res.append("✅ 经验更新成功！\n\n");
            res.append("**ID**: ").append(id).append("\n");
            res.append("**新版本**: v").append(existing.getVersion() + 1).append("\n");
            res.append("**已修改字段**: ").append(changedFields.isEmpty() ? "（无，仅调整归属）" : String.join(", ", changedFields)).append("\n");
            if (contentWritten) {
                res.append("**原文校验**: originalHash 与库中正文一致（证明读到的是完整原文）\n");
                if (force != null && force) {
                    res.append("**force**: true —— 明知会截断仍写入，本版快照已标 CONTENT_FORCED\n");
                }
            }
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
            res.append(verifiedLine(afterUpdate, verified != null));
            // 2026-11-08：写回凭证。只在本版真写了正文时给 —— 只改元数据的话正文 hash 没变，调用方本来就要读全文。
            if (contentWritten) {
                res.append(ContentHashGuard.writebackLine(afterUpdate == null ? null : afterUpdate.getContent()));
            }
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
            // 2026-10-29 P2-8：整篇写回后正文多长，这里如实报（用库里回读值，不复述入参）
            res.append(longDocHint(afterUpdate == null || afterUpdate.getContent() == null
                    ? 0 : afterUpdate.getContent().length()));
            return res.toString();
        } catch (SensitiveContentGuard.Rejected e) {
            callLogService.incrementFailCalls();
            return e.getMessage();
        } catch (Exception e) {
            log.error("ExpHubTools.updateExperience: FAILED", e);
            callLogService.incrementFailCalls();
            return "更新失败: " + e.getClass().getName() + " - " + e.getMessage();
        }
    }

    /**
     * 快照变更类型的中文标签。
     *
     * <p>{@code CONTENT_FORCED} = force 强制写回（A5 B 方案 2026-10-30 起）。它和 CONTENT 一样
     * 存了旧版全文，区别只在「这一版是被强压出来的」—— 审计时最该被单独看见的正是这一种。
     */
    private String changeTypeLabel(String changeType) {
        if ("META".equals(changeType)) return "仅元数据";
        if ("CONTENT_FORCED".equals(changeType)) return "正文（force 强压）";
        return "正文";
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
    @Tool(name = "append_experience", description = "在经验正文**末尾追加**一段内容。这是补充进展/追加结论的首选方式：服务端直接拼接，你不需要读全文，因此绝不会因为读取被截断而丢掉原文（用 update_experience 整篇写回才会丢）。只在尾部追加、不会缩短原文，不触发截断保护。一次传一段；要多段就多次调用。成功回执带 `writeback_fingerprint`（64 位）＝ 写完之后的新 hash，可直接当下一次整篇写回的 originalHash，不必再 export 重读（与 export_document 的 content_sha256 同值）。")
    public String appendExperience(
            @ToolParam(description = "经验ID") Long id,
            @ToolParam(description = "要追加的 Markdown 片段（不要带原正文）。建议以小标题开头，如「### 2026-10-25 补充：xxx」，便于日后区分批次") String text,
            @ToolParam(description = "可选。署名（记入版本快照）。不传=用 Key 绑定助手名。多助手共用同一 Key 时必传，否则所有改动记在同一人名下", required = false) String authorName,
            @ToolParam(description = "可选。**谁改的**——与 authorName 等价，两者都传以本参数为准", required = false) String updatedName,
            @ToolParam(description = "可选。验证时间（YYYY-MM-DD 或 YYYY-MM-DD HH:mm）。"
                    + "**不传 = 本次追加视为一次验证，记当前时间**；只补一句流水、结论没重新确认过时，"
                    + "传原来的验证时间表示不刷新。", required = false) String verifiedAt) {

        try {
            AiAssistant assistant = getCaller();
            if (assistant != null && !Boolean.TRUE.equals(assistant.getCanUpdate())) {
                return "❌ 权限不足：该API Key没有编辑经验的权限";
            }
            if (text == null || text.trim().isEmpty()) {
                return "❌ 追加内容不能为空。请传入要追加的 Markdown 片段。";
            }
            // 2026-10-29 ③（B6 通路）：片段入口只剥终端残留，不做全量 strip ——
            // 全量 strip 会把以 `**状态**:` 开头的正常片段当渲染头部元信息吃掉。
            int residueLines = terminalResidueLines(text);
            if (residueLines > 0) {
                String cleaned = com.exphub.util.RenderArtifactStripper.stripTerminalResidue(text);
                if (cleaned.trim().isEmpty()) {
                    return "❌ 追加内容全是终端残留（" + residueLines + " 行），已拒绝写入（未做任何改动）。\n\n"
                            + "这些行是从终端复制命令输出时一起带进来的（job control 的 `Terminated` / curl 报错 /"
                            + " `xxx.sh: line N`），不是正文。请只贴要追加的内容。";
                }
                text = cleaned;
            }
            java.time.LocalDateTime verified;
            try {
                verified = parseVerifiedAtParam(verifiedAt);
            } catch (IllegalArgumentException bad) {
                return "❌ " + bad.getMessage() + "（未做任何改动）";
            }
            Doc existing = docService.getById(id);
            if (existing == null) {
                return "❌ 未找到ID为" + id + "的经验";
            }

            int oldVer = existing.getVersion();
            int oldLen = existing.getContent() == null ? 0 : existing.getContent().length();
            Doc updated = docService.appendContent(id, text,
                    updatedName != null && !updatedName.trim().isEmpty() ? updatedName.trim() : authorName,
                    verified);
            int newLen = updated.getContent() == null ? 0 : updated.getContent().length();

            StringBuilder sb = new StringBuilder();
            sb.append("✅ 已追加到正文末尾\n\n");
            sb.append("**ID**: ").append(id).append("\n");
            sb.append("**标题**: ").append(updated.getTitle()).append("\n");
            sb.append("**版本**: v").append(oldVer).append(" → v").append(updated.getVersion()).append("\n");
            sb.append("**正文字数**: ").append(oldLen).append(" → ").append(newLen)
              .append("（+").append(newLen - oldLen).append("）\n");
            sb.append(verifiedLine(updated, verified != null));
            String addPreview = text.trim();
            if (addPreview.length() > 120) addPreview = addPreview.substring(0, 120) + "…";
            sb.append("**追加内容开头**:\n> ").append(addPreview.replace("\n", "\n> ")).append("\n\n");
            if (residueLines > 0) {
                sb.append("**已剥离终端残留**: ").append(residueLines)
                  .append(" 行（终端作业控制/curl 报错，不是正文）\n\n");
            }
            sb.append("原文完整保留（服务端拼接，未经过任何截断），旧版本已存为快照 v").append(oldVer).append("。\n");
            // 2026-11-08：写回凭证（append 必然写了正文，无条件给）
            sb.append(ContentHashGuard.writebackLine(updated.getContent()));
            // 2026-10-29 P2-8：滚动日志/施工日志就是靠 append 一次次涨到 2 万字的，这里提醒最有效
            sb.append(longDocHint(newLen));
            return sb.toString();
        } catch (SensitiveContentGuard.Rejected e) {
            callLogService.incrementFailCalls();
            return e.getMessage();
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

    /**
     * 双参数署名：{@code updatedName} 优先，其次 {@code authorName}，最后助手名。
     *
     * <p><b>2026-10-28 白虎（P2-7）</b>：原先各处写的是
     * {@code resolveAuthor(updatedName != null ? updatedName : authorName, assistant)} ——
     * 判的是 {@code != null} 而不是「非空白」，所以传空串 {@code ""} 时会把 authorName 顶掉，
     * 最后落到助手名上，看起来就像「updatedName 是死参数、两个都得传」。
     * 统一收口到这里，省得下次又有一处只判 null。
     */
    static String resolveAuthor(String updatedName, String authorName, AiAssistant assistant) {
        if (updatedName != null && !updatedName.trim().isEmpty()) return updatedName.trim();
        if (authorName != null && !authorName.trim().isEmpty()) return authorName.trim();
        return resolveAuthor((String) null, assistant);
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
    @Tool(name = "replace_section",
          description = "【按节整节替换】用一个二级标题定位，把这**一节**的内容整体换掉，其余部分一字不动。"
                  + "专治滚动日志类文档的「替换最新一轮」：patch 要求 find 恰好命中一次，而整节内容每轮全变，"
                  + "锚点只能靠两个标题夹着、标题一改就断。这个工具把「定位」和「替换」分开，边界由代码算。"
                  + "范围 = 从该标题行到下一个**同级或更高级**标题之前（`##` 节遇到下一个 `##`/`#` 停；"
                  + "`###` 节遇到下一个 `###`/`##`/`#` 停，更深的子标题属于本节内容）。"
                  + "命中 0 个 → 列出正文现有二级标题；命中 ≥2 个 → 列出各命中行号，让你先改名；"
                  + "newContent 为空 → 直接拒绝（清空整节请用 patch）。**只改正文，照常存版本快照。**"
                  + "回执给出被替换的字符区间与前后各 40 字上下文，方便你确认截到哪儿。")
    public String replaceSection(
            @ToolParam(description = "必填。文档 ID") Long docId,
            @ToolParam(description = "必填。二级小节标题文本，不用带 ##（写成「## 最新一轮」也认）") String sectionHeading,
            @ToolParam(description = "必填。这一节的新正文（**不含标题行**，标题行原文自动保留）") String newContent,
            @ToolParam(description = "可选。**谁改的**——操作人署名", required = false) String updatedName,
            @ToolParam(description = "可选。验证时间（YYYY-MM-DD 或 YYYY-MM-DD HH:mm）。"
                    + "**不传 = 本次替换视为一次验证，记当前时间**；只是重排/修错字时传原来的验证时间表示不刷新。",
                    required = false) String verifiedAt) {
        try {
            AiAssistant assistant = getCaller();
            if (assistant != null && !Boolean.TRUE.equals(assistant.getCanUpdate())) {
                return "❌ 权限不足：该API Key没有编辑经验的权限";
            }
            if (docId == null) {
                return "❌ 必须传 docId";
            }
            java.time.LocalDateTime verified;
            try {
                verified = parseVerifiedAtParam(verifiedAt);
            } catch (IllegalArgumentException bad) {
                return "❌ " + bad.getMessage() + "（未做任何改动）";
            }
            Doc existing = docService.getById(docId);
            if (existing == null) {
                return "❌ 未找到ID为" + docId + "的经验";
            }
            String by = resolveAuthor(updatedName, assistant);
            String body = existing.getContent() == null ? "" : existing.getContent();

            SectionSplicer.Result r = SectionSplicer.splice(body, sectionHeading, newContent);
            if (!r.ok()) {
                StringBuilder sb = new StringBuilder("❌ ").append(r.message());
                sb.append("（未做任何修改，仍是 v").append(existing.getVersion()).append("）\n");
                if (!r.candidates().isEmpty()) {
                    sb.append("\n**并列候选**：\n");
                    for (String c : r.candidates()) sb.append("- ").append(c).append("\n");
                }
                return sb.toString();
            }

            int oldVer = existing.getVersion();
            int oldLen = body.length();
            Doc patch = new Doc();
            patch.setContent(r.newContent());
            patch.setLastVerifiedAt(verified);
            Doc updated = docService.update(docId, patch, java.util.Set.of(), by, WriteOrigin.LOCAL_EDIT);
            if (updated == null) {
                return "❌ 更新失败：文档可能已被删除（# " + docId + "）";
            }

            StringBuilder sb = new StringBuilder();
            sb.append("✅ 已按节替换：**").append(existing.getTitle()).append("**（#").append(docId).append("）\n\n");
            sb.append("**命中节**: ").append(r.heading()).append("\n");
            sb.append("**替换区间**: 第 ").append(r.start()).append(" ~ ").append(r.end())
              .append(" 字符（原区间 ").append(r.end() - r.start()).append(" 字符）\n");
            sb.append("**正文长度**: ").append(oldLen).append(" → ").append(r.newContent().length())
              .append("（").append(r.newContent().length() - oldLen >= 0 ? "+" : "")
              .append(r.newContent().length() - oldLen).append("）\n");
            sb.append("**版本**: v").append(oldVer).append(" → v").append(updated.getVersion()).append("\n");
            if (r.before() != null) sb.append("**区间前 40 字**: ").append(r.before()).append("\n");
            if (r.after() != null) sb.append("**区间后 40 字**: ").append(r.after()).append("\n");
            sb.append("\n**只改了这一节**，其余正文与你传的内容之外的部分一字未动；"
                    + "旧版本已存快照 v").append(oldVer).append("（要用 get_experience_history 才能看到）。\n");
            sb.append("**操作人**: ").append(by).append("\n");
            sb.append(verifiedLine(updated, verified != null));
            // 2026-10-29 P2-8：按节替换是最容易被用来「把一大段塞进长文」的入口，这里一并提示
            sb.append(longDocHint(r.newContent().length()));
            return sb.toString();
        } catch (Exception e) {
            log.error("ExpHubTools.replaceSection: FAILED", e);
            callLogService.incrementFailCalls();
            return "按节替换失败: " + e.getClass().getName() + " - " + e.getMessage();
        }
    }

    @Tool(name = "patch_experience", description = "在正文里做局部替换（find→replace）。改错字、更新一行结论、换一个失效命令时用它，不要整篇写回。⚠️ find 必须在正文里**恰好出现一次**（0 次或多次都报错，可加长锚点），至少 4 字符。replace 传空串=删除该段。成功回执带 `writeback_fingerprint`（64 位）＝ 写完之后的新 hash，可直接当下一次整篇写回的 originalHash，不必再 export 重读（与 export_document 的 content_sha256 同值）。")
    public String patchExperience(
            @ToolParam(description = "经验ID") Long id,
            @ToolParam(description = "要被替换的原文片段，必须在正文中恰好出现一次，至少 4 个字符。建议包含前后文让锚点唯一") String find,
            @ToolParam(description = "替换成的新内容。传空字符串表示删除这段") String replace,
            @ToolParam(description = "可选。署名（也记入版本快照）", required = false) String authorName,
            @ToolParam(description = "可选。**谁改的**——与 authorName 等价", required = false) String updatedName,
            @ToolParam(description = "可选。验证时间（YYYY-MM-DD 或 YYYY-MM-DD HH:mm）。"
                    + "**不传 = 本次修改视为一次验证，记当前时间**；"
                    + "**改错字/润色这类不重新确认结论的修改，请传原来的验证时间**（get_experience_detail 回执里有），"
                    + "否则一次错字修复就会把 90 天前的验证刷成今天，过期判定从此失效。", required = false) String verifiedAt) {

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
            java.time.LocalDateTime verified;
            try {
                verified = parseVerifiedAtParam(verifiedAt);
            } catch (IllegalArgumentException bad) {
                return "❌ " + bad.getMessage() + "（未做任何改动）";
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
            // 2026-10-29 ③（B6 通路）：replace 里夹带终端残留同样会写进正文，片段入口只剥这一类
            int residueLines = terminalResidueLines(replace);
            if (residueLines > 0) {
                String cleaned = com.exphub.util.RenderArtifactStripper.stripTerminalResidue(replace);
                if (cleaned.trim().isEmpty()) {
                    return "❌ replace 全是终端残留（" + residueLines + " 行），已拒绝执行（仍是 v"
                            + existing.getVersion() + "）。\n\n"
                            + "要**删除**这段请明确传空字符串；这里传的是终端里复制出来的报错行，不是要写入的正文。";
                }
                replace = cleaned;
            }
            Doc updated = docService.patchContent(id, find, replace,
                    updatedName != null && !updatedName.trim().isEmpty() ? updatedName.trim() : authorName,
                    verified);
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
            sb.append("旧版本已存为快照 v").append(oldVer).append("。\n");
            if (residueLines > 0) {
                sb.append("**已剥离终端残留**: ").append(residueLines)
                  .append(" 行（终端作业控制/curl 报错，不是正文）\n");
            }
            sb.append(verifiedLine(updated, verified != null));
            // 2026-11-08：写回凭证（patch 必然写了正文，无条件给）
            sb.append(ContentHashGuard.writebackLine(newBody));
            return sb.toString();
        } catch (SensitiveContentGuard.Rejected e) {
            callLogService.incrementFailCalls();
            return e.getMessage();
        } catch (Exception e) {
            log.error("ExpHubTools.patchExperience: FAILED", e);
            callLogService.incrementFailCalls();
            return "局部替换失败: " + e.getClass().getName() + " - " + e.getMessage();
        }
    }

    /**
     * 逐行比较两篇经验（2026-10-26 13:0x 阁主第 2 条）。
     *
     * <p>他的原话：「要知道两条文档差在哪，只能读两篇全文 —— 这是**判断重复最底层的操作**，
     * 却没有专门工具」。扫描清单只给「独有 8 行」和前 3 行片段，要落到「判 duplicate 还是 distinct」
     * 还差一步；两篇上千字时读全文直接占满上下文。
     */
    @Tool(name = "diff_experiences", description = "逐行比较两篇经验：给共同行 / A 独有 / B 独有 行数 + 独有行前 N 行原文。⚠️ 与扫描口径不同：**不剔模板行**，行数会和 scan 的「独有行」略有差异。")
    public String diffExperiences(
            @ToolParam(description = "第一篇经验 ID") Long idA,
            @ToolParam(description = "第二篇经验 ID") Long idB,
            @ToolParam(description = "可选。每侧最多列几行独有原文，默认 10，上限 50", required = false) Integer maxLines) {
        try {
            if (idA == null || idB == null) {
                return "❌ 需要两个 ID：diff_experiences(idA=?, idB=?)";
            }
            if (idA.equals(idB)) {
                return "❌ idA 与 idB 是同一个 ID（" + idA + "），自己和自己比较没有意义";
            }
            Doc a = docService.getById(idA);
            Doc b = docService.getById(idB);
            if (a == null) return "❌ 未找到 ID 为 " + idA + " 的经验";
            if (b == null) return "❌ 未找到 ID 为 " + idB + " 的经验";

            int cap = (maxLines == null || maxLines < 1) ? 10 : Math.min(maxLines, 50);
            SimilarityScorer.Diff d = SimilarityScorer.diffLines(a.getContent(), b.getContent());

            StringBuilder sb = new StringBuilder();
            sb.append("📑 **#").append(idA).append("「").append(abbreviate(a.getTitle(), 40)).append("」 ↔ #")
              .append(idB).append("「").append(abbreviate(b.getTitle(), 40)).append("」**\n\n");
            sb.append("**行统计**: A ").append(d.linesA()).append(" 行 / B ").append(d.linesB())
              .append(" 行｜**共同 ").append(d.shared()).append(" 行**")
              .append("｜A 独有 ").append(d.onlyA().size()).append(" 行")
              .append("｜B 独有 ").append(d.onlyB().size()).append(" 行\n");
            sb.append("（口径：**未剔模板行**，标题/分隔行也算内容。与扫描回执的「独有行」不同源，"
                    + "扫描为了不被骨架骗会剔模板，所以这里的独有行会更多）\n");
            if (!d.lineMode()) {
                sb.append("⚠️ 有一侧有效行少于 ").append(SimilarityScorer.MIN_LINES_FOR_LINE_MODE)
                  .append(" 行 —— **逐行比较不可靠**，请当作粗看\n");
            }

            appendDiffSide(sb, "A", d.onlyA(), cap);
            appendDiffSide(sb, "B", d.onlyB(), cap);

            sb.append("\n**怎么读**\n");
            sb.append("· 一侧独有很少、另一侧很多 → 前者大概率是后者的子页（但**先看独有的是不是新增内容**，"
                    + "别把「母档补了新章节」当成「子页被包住」）\n");
            sb.append("· 共同 ").append(d.shared()).append(" 行 + 两侧独有都很多 → 同一套模板写出的两个实例，判 distinct\n");
            if (d.shared() == 0) {
                sb.append("· **共同 0 行 = 没有任何内容证据** → 不许判 duplicate / superseded\n");
            }
            return sb.toString();
        } catch (Exception e) {
            log.error("ExpHubTools.diffExperiences: FAILED", e);
            callLogService.incrementFailCalls();
            return "比较失败: " + e.getClass().getName() + " - " + e.getMessage();
        }
    }

    private void appendDiffSide(StringBuilder sb, String side, java.util.List<String> lines, int cap) {
        if (lines.isEmpty()) {
            sb.append("\n**").append(side).append(" 独有**：无（这一份的行全部出现在另一份里）\n");
            return;
        }
        sb.append("\n**").append(side).append(" 独有（前 ").append(Math.min(cap, lines.size()))
          .append(" 行 / 共 ").append(lines.size()).append(" 行）**\n");
        int n = 0;
        for (String ln : lines) {
            if (n++ >= cap) break;
            sb.append("- ").append(abbreviate(ln, 160)).append("\n");
        }
    }

    /**
     * 工具使用统计（**只读**）—— 2026-10-26 13:2x 阁主 / #671：调用埋点有数据，但 MCP 侧没有读入口，
     * 于是「要不要合并 detail / export / history」「描述太长要不要压」全靠猜。
     *
     * <p>关键一项是 <b>从未被调用的已注册工具</b>：注册表 + 埋点两边一减就知道哪些工具从来没被用过 ——
     * 这是精简的第一手依据，比「我觉得这个没人用」可靠。
     */
    @Tool(name = "get_tool_stats", description = "【只读】工具调用统计：总览、使用榜、每日趋势、**从未被调用的工具清单**。数据来自 call_logs，只统计不修改。")
    public String getToolStats(
            @ToolParam(description = "可选。统计最近几天，默认 7，上限 90", required = false) Integer days,
            @ToolParam(description = "可选。使用榜最多列几个工具，默认 20，上限 50", required = false) Integer limit) {
        try {
            int d = (days == null || days < 1) ? 7 : Math.min(days, 90);
            int n = (limit == null || limit < 1) ? 20 : Math.min(limit, 50);

            Map<String, Object> overview = callLogService.getToolOverview();
            List<Map<String, Object>> ranking = callLogService.getToolRanking(d);
            List<Map<String, Object>> trend = callLogService.getToolDailyTrend(d);
            Map<String, String> registered = mcpToolRegistry.getTools();

            StringBuilder sb = new StringBuilder();
            sb.append("📊 **工具调用统计**（近 ").append(d).append(" 天｜数据源 call_logs.action='TOOL'）\n\n");
            sb.append("**总览**: 总调用 ").append(val(overview, "total"))
              .append("｜今日 ").append(val(overview, "today"))
              .append("｜成功 ").append(val(overview, "successCalls"))
              .append("｜平均耗时 ").append(val(overview, "avgCostMs")).append(" ms")
              .append("｜**有调用的工具 ").append(val(overview, "activeTools"))
              .append(" / 已注册 ").append(registered.size()).append("**\n");

            java.util.Set<String> called = new java.util.LinkedHashSet<>();
            for (Map<String, Object> m : ranking) {
                Object t = firstNonNull(m, "toolName", "tool_name");
                if (t != null) called.add(String.valueOf(t));
            }
            java.util.List<String> neverCalled = new java.util.ArrayList<>();
            for (String t : registered.keySet()) {
                if (!called.contains(t)) neverCalled.add(t);
            }
            sb.append("\n**从未被调用（近 ").append(d).append(" 天，共 ")
              .append(neverCalled.size()).append(" 个）**");
            if (neverCalled.isEmpty()) {
                sb.append("：无\n");
            } else {
                sb.append("：").append(String.join("、", neverCalled)).append("\n");
                sb.append("（**这是精简的第一手依据**：先看这些是不是与别的工具能力重叠；"
                        + "但「近 N 天没调用」不等于「该删」—— 低频但关键的运维工具也会落在这里，"
                        + "所以至少看满 7 天再决定）\n");
            }

            sb.append("\n**使用榜（前 ").append(n).append("）**\n");
            if (ranking.isEmpty()) {
                sb.append("（近 ").append(d).append(" 天没有任何工具调用记录）\n");
            } else {
                sb.append("| # | 工具 | 调用 | 成功率 | 平均耗时 | 最大 | 最后调用 | 调用者 |\n");
                sb.append("|---|---|---|---|---|---|---|---|\n");
                int i = 1;
                for (Map<String, Object> m : ranking) {
                    if (i > n) break;
                    Object calls = firstNonNull(m, "calls", "CALLS");
                    Object succ = firstNonNull(m, "successCalls", "SUCCESSCALLS");
                    long c = num(calls), s = num(succ);
                    String rate = c > 0 ? Math.round(s * 100.0 / c) + "%" : "-";
                    sb.append("| ").append(i++).append(" | ").append(str(firstNonNull(m, "toolName", "TOOLNAME")))
                      .append(" | ").append(c).append(" | ").append(rate)
                      .append(" | ").append(str(firstNonNull(m, "avgCostMs", "AVGCOSTMS"))).append(" ms")
                      .append(" | ").append(str(firstNonNull(m, "maxCostMs", "MAXCOSTMS"))).append(" ms")
                      .append(" | ").append(str(firstNonNull(m, "lastCallAt", "LASTCALLAT")))
                      .append(" | ").append(str(firstNonNull(m, "callerCount", "CALLERCOUNT"))).append(" |\n");
                }
            }

            sb.append("\n**每日趋势**\n");
            if (trend.isEmpty()) {
                sb.append("（无数据）\n");
            } else {
                java.util.List<String> parts = new java.util.ArrayList<>();
                for (Map<String, Object> m : trend) {
                    parts.add(str(firstNonNull(m, "date", "DATE")) + " " + num(firstNonNull(m, "calls", "CALLS"))
                            + " 次/" + num(firstNonNull(m, "tools", "TOOLS")) + " 工具");
                }
                sb.append(String.join("｜", parts)).append("\n");
            }

            sb.append("\n⚠️ 口径：`action='TOOL'` 由工具调用切面写入；**没有经过 MCP 的调用不会出现在这里**。"
                    + "「有调用的工具数」按近 ").append(d).append(" 天算，总数按注册表算 —— 两个数不同源，别相减去推别的结论。\n");
            return sb.toString();
        } catch (Exception e) {
            log.error("ExpHubTools.getToolStats: FAILED", e);
            callLogService.incrementFailCalls();
            return "查询工具统计失败: " + e.getClass().getName() + " - " + e.getMessage();
        }
    }

    /** 取第一个非空键（MyBatis 返回的 map 键大小写随数据库/驱动变化，不能硬编码一种） */
    private static Object firstNonNull(Map<String, Object> m, String... keys) {
        if (m == null) return null;
        for (String k : keys) {
            if (m.containsKey(k) && m.get(k) != null) return m.get(k);
            for (Map.Entry<String, Object> e : m.entrySet()) {
                if (e.getKey() != null && e.getKey().equalsIgnoreCase(k) && e.getValue() != null) {
                    return e.getValue();
                }
            }
        }
        return null;
    }

    private static String val(Map<String, Object> m, String k) {
        Object v = firstNonNull(m, k);
        return v == null ? "-" : String.valueOf(v);
    }

    private static long num(Object o) {
        if (o instanceof Number nb) return nb.longValue();
        if (o == null) return 0L;
        try {
            return Long.parseLong(String.valueOf(o).replaceAll("[^0-9-]", ""));
        } catch (NumberFormatException e) {
            return 0L;
        }
    }

    private static String str(Object o) {
        return o == null ? "-" : String.valueOf(o);
    }

    @Tool(name = "set_flags", description = "给经验打机器可读标记（待复核/疑似重复/已被取代/可能失效/质量存疑）。**不要用 update_experience 改 tags 代替**：本工具不递增版本、不写快照、不动更新时间。flags 传空串=清空。标记会在检索与详情回显。")
    public String setFlags(
            @ToolParam(description = "经验ID。单个用这个；批量用 ids（两者可只传一个，同传会合并去重）", required = false) Long id,
            @ToolParam(description = "标记，逗号分隔：needs_review/duplicate/superseded/stale/low_quality（也认中文）。传空串=清空。白名单外的词会被忽略并列出") String flags,
            @ToolParam(description = "可选。标记说明：为什么打、和哪条重复", required = false) String note,
            @ToolParam(description = "可选。**批量**：多个 ID 逗号分隔", required = false) String ids,
            @ToolParam(description = "可选。署名（谁打的标记）", required = false) String authorName,
            @ToolParam(description = "可选。**谁改的**——与 authorName 等价", required = false) String updatedName) {

        try {
            AiAssistant assistant = getCaller();
            if (assistant != null && !Boolean.TRUE.equals(assistant.getCanUpdate())) {
                return "❌ 权限不足：该API Key没有编辑经验的权限";
            }
            // 2026-10-26 12:0x 阁主第 7 条：给 20 条打同一标记要调 20 次 → 支持 ids 列表
            List<Long> targets = new ArrayList<>();
            if (id != null) targets.add(id);
            if (ids != null && !ids.isBlank()) {
                for (String t : ids.split("[,，\\s]+")) {
                    if (t.isBlank()) continue;
                    try {
                        Long v = Long.parseLong(t.trim());
                        if (!targets.contains(v)) targets.add(v);
                    } catch (NumberFormatException nfe) {
                        return "❌ ids 里有非数字项：「" + t + "」。格式：逗号分隔的 ID，如 470,477,478";
                    }
                }
            }
            if (targets.isEmpty()) {
                return "❌ 没给要改的经验：单个传 id，批量传 ids（逗号分隔）";
            }

            List<String> parsed = DocService.parseFlags(flags);
            List<String> rejected = DocService.rejectedFlagTokens(flags);
            // 署名优先用调用方自报的（同一 Key 只能绑一个 assistant_name，多助手共用时会打架）。
            // ⚠️ 2026-10-25 修：这里原来只认 authorName，updatedName 声明了却零使用，
            // 而参数说明写着「两者都传时以本参数为准」→ 传了 updatedName 署名就丢。
            // 与 mark_superseded / mark_for_deletion 统一走同一行。
            String by = resolveAuthor(updatedName, authorName, assistant);

            if (targets.size() > 1) {
                return setFlagsBatch(targets, parsed, rejected, note, by);
            }
            Long id1 = targets.get(0);
            Doc existing = docService.getById(id1);
            if (existing == null) {
                return "❌ 未找到ID为" + id1 + "的经验";
            }

            Doc updated = docService.setFlags(id1, parsed, note, by);

            StringBuilder sb = new StringBuilder();
            if (parsed.isEmpty()) {
                sb.append("✅ 已清空标记\n\n");
            } else {
                sb.append("✅ 已设置标记\n\n");
            }
            sb.append("**ID**: ").append(id1).append("\n");
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

    /**
     * 批量打标记（2026-10-26 12:0x 阁主第 7 条：给 20 条打标记不该调 20 次）。
     *
     * <p>逐条回报：成功写「✅ vN（未变）」，不存在的写「❌ 未找到」。**一条失败不影响其它条** ——
     * 批量接口最容易犯的错是「部分失败却回一句成功」。
     */
    private String setFlagsBatch(List<Long> targets, List<String> parsed, List<String> rejected,
                                 String note, String by) {
        // ⚠️ 2026-10-26 12:1x 阁主：全失败时开头还印 ✅ ——
        //    「✅ 已批量设置标记（2 条）」+「成功 0 条」并排，开头给人的是成功。
        //    规则：**✅/❌ 由结果决定，不能由「我打算做什么」决定**。
        //    先把逐条结果算出来，再回头决定标题，标题里也不许写「设置（N 条）」这种未兑现的承诺。
        StringBuilder body = new StringBuilder();
        int ok = 0, failed = 0;
        for (Long t : targets) {
            try {
                Doc d = docService.getById(t);
                if (d == null) {
                    body.append("- #").append(t).append(" ❌ 未找到\n");
                    failed++;
                    continue;
                }
                Doc u = docService.setFlags(t, parsed, note, by);
                body.append("- #").append(t).append(" ✅ v").append(u.getVersion())
                    .append("（未变）「").append(abbreviate(u.getTitle(), 28)).append("」\n");
                ok++;
            } catch (Exception e) {
                body.append("- #").append(t).append(" ❌ 失败：").append(e.getClass().getSimpleName()).append("\n");
                failed++;
            }
        }

        StringBuilder sb = new StringBuilder();
        String verb = parsed.isEmpty() ? "清空标记" : "设置标记";
        if (ok == 0) {
            sb.append("❌ **全部失败**（").append(targets.size()).append(" 条）：没有一条被修改\n");
        } else if (failed == 0) {
            sb.append("✅ 已批量").append(verb).append("（").append(targets.size()).append(" 条）\n");
        } else {
            sb.append("⚠️ **部分成功**：").append(targets.size()).append(" 条里成功 ").append(ok)
              .append(" 条、失败 ").append(failed).append(" 条\n");
        }
        if (!parsed.isEmpty()) {
            List<String> human = new ArrayList<>();
            for (String f : parsed) human.add(DocService.flagLabel(f) + "(" + f + ")");
            sb.append("**标记**: ").append(String.join("、", human)).append("\n");
            if (note != null && !note.trim().isEmpty()) {
                sb.append("**说明**: ").append(note.trim()).append("\n");
            }
            sb.append("**署名**: ").append(by).append("\n");
        }
        sb.append("\n").append(body);
        sb.append("\n**结果**: 成功 ").append(ok).append(" 条");
        if (failed > 0) sb.append("｜失败/不存在 ").append(failed).append(" 条");
        sb.append("\n");
        if (!rejected.isEmpty()) {
            sb.append("\n⚠️ 以下词不在白名单、已忽略：").append(String.join("、", rejected))
              .append("\n可用值：needs_review / duplicate / superseded / stale / low_quality\n");
        }
        sb.append("\n说明：标记不改变正文、不产生历史版本；不需要时再传空串即可清空。");
        return sb.toString();
    }

    /**
     * 「另有 M 对未展示」的逐项归因文本。
     *
     * <p><b>2026-10-26 12:1x 阁主实测抓到的半成品</b>：头行写
     * 「另有 11 对未展示，逐项归因：③层抽样省 5 条」，而 11 = 5 + 6 —— 那 6 条
     * 「同话题跳过」的说明明明就在下一段③层标题里，**数据是有的，只是没拼进头行**。
     * 「逐项归因」四个字等于承诺把所有项都列出来，只列一项就把剩下 6 条变成未知数。
     *
     * <p>所以这里不只拼文本：**加总必须等于未展示数**，对不上就把差额也写出来并标「回执自相矛盾」。
     * 宁可输出一句难看的告警，也不许让读者自己去凑数。
     */
    static String causesText(int notShown, int bySample, int byPerDoc, int byOmitted,
                             int byLimit, int bySameTopic, int byJudged, int maxPerDoc, int sampleCap) {
        return causesText(notShown, bySample, byPerDoc, byOmitted, byLimit, bySameTopic, byJudged,
                0, maxPerDoc, sampleCap);
    }

    /**
     * @param byGated 被「正文行重合不足 minSharedLines」这道入队门槛拦下的对数。
     *                <b>必须进求和</b>：这批对仍然算在「超阈值」里（阈值是超没超过，门槛是够不够入队），
     *                不计入归因就会出现「未展示 180 对 = 各项之和 0」的假缺口（2026-11-11 派单 A）。
     */
    static String causesText(int notShown, int bySample, int byPerDoc, int byOmitted,
                             int byLimit, int bySameTopic, int byJudged, int byGated,
                             int maxPerDoc, int sampleCap) {
        int sum = bySample + byPerDoc + byOmitted + byLimit + bySameTopic + byJudged + byGated;
        if (sum == 0) return "各项计数都为 0 —— 回执自相矛盾，请报缺陷";
        java.util.List<String> causes = new java.util.ArrayList<>();
        if (byGated > 0) causes.add("正文行重合不足门槛 **未入队** " + byGated + " 条（minSharedLines 生效，传 0 关闭）");
        if (bySample > 0) causes.add("③层进度/日志类只抽样 " + sampleCap + " 条，省 " + bySample + " 条");
        if (bySameTopic > 0) causes.add("③层**同话题跳过** " + bySameTopic + " 条（同一话题只留分数最高的那条）");
        if (byJudged > 0) causes.add("**已判过的对不占名额** " + byJudged + " 条（判过就不该再花名额）");
        if (byPerDoc > 0) causes.add("「单篇最多 " + maxPerDoc + " 条候选」挤掉 " + byPerDoc + " 条");
        if (byOmitted > 0) causes.add("同族交叉对已略去 " + byOmitted + " 条");
        if (byLimit > 0) causes.add("被 limit 截断 " + byLimit + " 条");
        String s = String.join("；", causes);
        if (sum != notShown) {
            s += (notShown > sum
                    ? "；**另有 " + (notShown - sum) + " 条未归因**"
                    : "；**各项之和 " + sum + " 条超过未展示数 " + notShown + " 条**")
                    + " —— 回执自相矛盾，请报缺陷";
        }
        return s;
    }

    /**
     * 长文软阈值（2026-10-29 越哥 P2-8）：**只提示，不阻断**。
     *
     * <p><b>为什么要提示</b>：文档变长的代价不是「占空间」，是两件同时变贵的事——
     * ① 整篇导出可能读不全（超长会被接口截断，读的人拿到的是残篇）；
     * ② 每次改动都得整篇写回或小心翼翼找锚点，改一处事实赔一版快照。
     * 这两个代价都在长文上叠加，而写的人当时感觉不到。
     *
     * <p><b>口径</b>：字符数（{@code String.length()}，与 #713 的「字」一致），不是字节、不是行数；
     * <b>含</b>正文里所有内容（含历史小节），不做「扣除归档导读」这类特殊处理 ——
     * 口径越简单越不会被下一个人理解错。
     *
     * <p>阈值取 8000：现网 #748（日常入口，3.8k）远低于它，#714（拆分前 21k）远高于它，
     * 落在中间的正是「该拆还没拆」的那批。
     */
    static final int LONG_DOC_SOFT_LIMIT = 8000;

    static String longDocHint(int chars) {
        if (chars <= LONG_DOC_SOFT_LIMIT) return "";
        return "\n📏 **长度提示（不阻断，操作已生效）**：正文现在 " + chars + " 字（字符数口径），"
                + "超过 " + LONG_DOC_SOFT_LIMIT + " 字的软阈值。\n"
                + "　长文会让「整篇导出可能读不全」和「改一处要赔一版快照」同时变贵 —— "
                + "建议按主题拆章，或把可归档的施工日志挪进独立文档（只留一句「历史见 X」）。\n";
    }

    /**
     * 解析 {@code verifiedAt} 参数（#752）。
     *
     * <p>三态语义：
     * <ul>
     *   <li>{@code null}/空 → 返回 null，由服务层填当前时间（本次写入即一次验证）</li>
     *   <li>合法日期 → 返回该时间（调用方把旧值传回来 = 本次不算重新验证）</li>
     *   <li>格式不认识 → <b>抛异常</b>，由调用方转成明确报错</li>
     * </ul>
     *
     * <p>为什么格式错必须抛而不是静默忽略：静默忽略会按「未传」处理 → 刷新成今天，
     * 调用方以为自己保住了旧值，实际把有效期重置了 —— 这正是这一列要防的事，且完全无感。
     */
    static java.time.LocalDateTime parseVerifiedAtParam(String s) {
        if (s == null || s.trim().isEmpty()) return null;
        String t = s.trim().replace('T', ' ');
        // 只带日期时按当天 00:00 记 —— 用户说的是「10 月 20 日验证过」，不是「10 月 20 日 23:59」
        if (t.matches("\\d{4}-\\d{2}-\\d{2}")) {
            try {
                return java.time.LocalDate.parse(t).atStartOfDay();
            } catch (java.time.format.DateTimeParseException bad) {
                throw new IllegalArgumentException("verifiedAt 不是有效日期：" + s);
            }
        }
        for (String p : new String[]{"yyyy-MM-dd HH:mm:ss", "yyyy-MM-dd HH:mm"}) {
            try {
                return java.time.LocalDateTime.parse(t, java.time.format.DateTimeFormatter.ofPattern(p));
            } catch (java.time.format.DateTimeParseException ignored) {
                // 换下一个格式
            }
        }
        throw new IllegalArgumentException("verifiedAt 格式不认识：「" + s
                + "」。可用 " + java.time.LocalDate.now() + " 或 " + java.time.LocalDate.now() + " 15:30");
    }

    /**
     * 验证时间来源的展示标签（2026-10-29 阁主第 ① 条）。
     *
     * <p>三态必须一眼分开，尤其 {@code backfill}：那批值是从 {@code updated_at} 推出来的，
     * 只代表「最后一次被改过」，<b>不是</b>「有人确认过结论还成立」。
     * 回执里不加这个标签，下一个 AI 就会拿它当验证证据 —— 而这个误会不会有任何报错提醒。
     */
    static String verifiedSourceLabel(String source) {
        if (source == null || source.trim().isEmpty()) return "来源未记录";
        return switch (source.trim()) {
            case DocService.VERIFIED_BACKFILL -> "**回填值，非验证证据**";
            case DocService.VERIFIED_MANUAL -> "人工声明";
            case DocService.VERIFIED_AUTO -> "写入时自动记录";
            default -> "来源未记录（" + source.trim() + "）";
        };
    }

    /**
     * 写回执里的验证时间行（自证值：读库里真实结果，不复述入参）。
     *
     * <p>为什么必须回显：调用方传了旧值到底有没有生效，从别处看不出来 ——
     * 只有把落库后的值印在回执上，「我传了旧值」才不是一句自述。
     *
     * @param explicit 本次是否显式传了 verifiedAt
     */
    static String verifiedLine(Doc after, boolean explicit) {
        if (after == null) return "";
        java.time.LocalDateTime v = after.getLastVerifiedAt();
        if (v == null) {
            return "**验证时间**: （未记录）"
                    + (explicit ? "　⚠️ 本次传了值却没落库，请报缺陷" : "") + "\n";
        }
        String day = v.toLocalDate().toString();
        long days = java.time.temporal.ChronoUnit.DAYS.between(v.toLocalDate(), java.time.LocalDate.now());
        String when = days <= 0 ? "今天" : days + " 天前";
        // 「沿用你传的值」这句不能省：调用方传了旧值到底有没有生效，只能从这里看出来（#752 验收项）。
        String how = explicit ? "沿用你传的值，本次未刷新" : "本次刷新";
        // 回填值不参与「刷新/未刷新」的判断：它本来就不是一次写入行为产生的
        if (DocService.VERIFIED_BACKFILL.equals(after.getLastVerifiedSource())) {
            how = "历史回填，非验证证据";
        }
        return "**验证时间**: " + day + "（" + when + "｜"
                + verifiedSourceLabel(after.getLastVerifiedSource()) + "，" + how + "）\n";
    }

    /** 超过这个天数未验证，就在搜索回执顶部提示一行（只读计算，不改排序、不隐藏） */
    static final int STALE_VERIFY_DAYS = 90;

    /**
     * 搜索回执顶部的时效提示（#752 第 2 条）。
     *
     * <p>口径边界，写死在这里免得以后被「顺手优化」掉：
     * <ul>
     *   <li>只统计<b>本次返回的这几条</b>（「以下 N 条」说的就是列出来的这批），不看全库、不改排序</li>
     *   <li>只读计算：不改正文、不隐藏、不降权。要「按新旧排序」是另一个决定，不在这里做</li>
     *   <li>{@code lastVerifiedAt} 为 null 视为超期（从未记录验证时间），不当作「刚验证过」</li>
     *   <li>0 条超期时返回空串 —— 每次搜索都印一行「全部新鲜」只是噪音</li>
     * </ul>
     */
    static String staleVerifyLine(java.util.List<Doc> docs, java.time.LocalDate today) {
        if (docs == null || docs.isEmpty()) return "";
        java.time.LocalDate cutoff = today.minusDays(STALE_VERIFY_DAYS);
        int stale = 0;
        int backfill = 0;           // 超期条目里有多少是 updated_at 回填出来的
        Doc oldest = null;          // 最久未验证的那条（未记录的优先，因为它连起点都没有）
        boolean oldestUnknown = false;
        long oldestDays = 0;
        for (Doc d : docs) {
            java.time.LocalDateTime v = d.getLastVerifiedAt();
            boolean isBackfill = DocService.VERIFIED_BACKFILL.equals(d.getLastVerifiedSource());
            if (v == null) {
                stale++;
                if (isBackfill) backfill++;
                if (!oldestUnknown) { oldest = d; oldestUnknown = true; }
                continue;
            }
            java.time.LocalDate day = v.toLocalDate();
            if (day.isBefore(cutoff)) {
                stale++;
                if (isBackfill) backfill++;
                long days = java.time.temporal.ChronoUnit.DAYS.between(day, today);
                if (!oldestUnknown && (oldest == null || days > oldestDays)) {
                    oldest = d;
                    oldestDays = days;
                }
            }
        }
        if (stale == 0) return "";
        StringBuilder sb = new StringBuilder("⏳ **时效**: 以下 ").append(docs.size()).append(" 条中 ")
                .append(stale).append(" 条超过 ").append(STALE_VERIFY_DAYS).append(" 天未验证");
        if (oldest != null) {
            sb.append("（最久 #").append(oldest.getId())
              .append(oldestUnknown
                      ? " 未记录验证时间"
                      : "，" + oldest.getLastVerifiedAt().toLocalDate() + "，" + oldestDays + " 天")
              .append("）");
        }
        sb.append("\n");
        if (backfill > 0) {
            // 回填值必须点出来：它是 updated_at 推出来的，真实「最后一次确认」只会更早
            sb.append("　⚠️ 其中 ").append(backfill).append(" 条是 `updated_at` **回填值**、不是验证证据"
                    + "（真实确认时间只会更早，别把它当「最近核过」）。\n");
        }
        sb.append("　只提示、不过滤：这些条目仍可能是对的，但**引用前先核一遍**，别把过期结论当现状。\n");
        return sb.toString();
    }

    /** 层名短称（一行清单用；组头那句长描述不适合挤进表格） */
    public static String tierShortName(SimilarityScorer.Kind k) {
        if (k == null) return "其他";
        return switch (k) {
            case DUPLICATE -> "① 疑似字面重复";
            case SERIES -> "② 同话题演进链";
            case PROGRESS -> "③ 进度/日志记录";
            case UNKNOWN -> "④ 信息不足";
        };
    }

    /**
     * 单层限流归因：**一行说清这一层为什么没全展示**（2026-10-29 越哥 P1-5）。
     *
     * <p><b>要解决的具体症状</b>：同一份数据「只展示 5 条」，可能是③层抽样上限、可能是
     * 「单篇最多 2 条」的 per-doc 限流、也可能是 limit 截断 —— 三种原因调参方向完全不同
     * （一个传 progressSample=0，一个放宽 maxPerDoc，一个加大 limit），但回执不说是哪个。
     *
     * <p><b>为什么原来那段不够</b>：每层的自证括号写在 {@code for (Row row : r.rows())} 里，
     * 只有该层**至少有一条进了清单**时才渲染。整层被挤掉时（limit 小、③层一条没进）
     * 一个字都不输出，读者只看到「超阈值 17 对」却拿不到任何原因。
     *
     * <p>所以这里遍历 {@code tierDetail} 而不是展示行，与「展示了几条」完全解耦；
     * 且**加总必须等于「超阈值 − 展示」**，对不上就把差额印出来 —— 不替读者凑数。
     */
    /**
     * 渲染「限流归因」整块（2026-10-29 越哥 #751 第 3 条：渲染抽成纯函数才测得了）。
     *
     * <p><b>为什么这一段必须与展示列表解耦</b>：整层一条都没进清单时，展示循环里根本没有它，
     * 而读者最需要的恰恰是那一层「为什么一条都没有」。最初这段写在
     * {@code for (Row row : r.rows())} 里，整层被挤空就一个字都不出（已经踩过一次）。
     * 抽出来后，「三层、名额只够第一层、第三层展示 0 但原因行仍在」这条能直接断言。
     *
     * @param detail    每层的自证数据（键为层）
     * @param maxPerDoc 回显给读者看的 per-doc 阈值（必须是调用方实际生效的值，不是默认值）
     */
    public static String tierLimitBlock(java.util.Map<SimilarityScorer.Kind, SimilarityService.TierDetail> detail,
                                        int maxPerDoc) {
        if (detail == null || detail.isEmpty()) return "";
        boolean anyDrop = false;
        for (SimilarityService.TierDetail d : detail.values()) {
            if (d != null && d.hasDrop()) { anyDrop = true; break; }
        }
        StringBuilder sb = new StringBuilder();
        if (!anyDrop) {
            sb.append("**限流归因**：各层全部展示，无截断\n");
            return sb.toString();
        }
        sb.append("**限流归因**（每层独立算；调 limit 只改变其中「limit 截断」那一项）：\n");
        for (SimilarityScorer.Kind k : new SimilarityScorer.Kind[]{
                SimilarityScorer.Kind.DUPLICATE, SimilarityScorer.Kind.SERIES,
                SimilarityScorer.Kind.PROGRESS, SimilarityScorer.Kind.UNKNOWN}) {
            SimilarityService.TierDetail d = detail.get(k);
            if (d == null || d.above() == 0) continue;   // 该层一对都没有，不必占一行
            sb.append(tierLimitLine(tierShortName(k), d, maxPerDoc)).append("\n");
        }
        return sb.toString();
    }

    public static String tierLimitLine(String layerName, SimilarityService.TierDetail d, int maxPerDoc) {
        if (d == null) return "";
        int shown = d.shown();
        int accounted = d.droppedJudged() + d.droppedPerDoc() + d.droppedBySample()
                + d.droppedSameTopic() + d.droppedRedundant() + d.droppedByLimit();

        java.util.List<String> causes = new java.util.ArrayList<>();
        // 顺序按「先绑的闸先说」：已判过 → 抽样上限 → 同话题 → per-doc → 同族 → limit
        if (d.droppedJudged() > 0) causes.add("已判过不占名额 " + d.droppedJudged());
        if (d.droppedBySample() > 0) causes.add("③层抽样上限省 " + d.droppedBySample() + "（要全看传 progressSample=0）");
        if (d.droppedSameTopic() > 0) causes.add("同话题跳过 " + d.droppedSameTopic());
        if (d.droppedPerDoc() > 0) causes.add("「单篇最多 " + maxPerDoc + " 条」挤掉 " + d.droppedPerDoc());
        if (d.droppedRedundant() > 0) causes.add("同族交叉对略去 " + d.droppedRedundant());
        if (d.droppedByLimit() > 0) causes.add("limit 截断 " + d.droppedByLimit());

        StringBuilder sb = new StringBuilder("· ").append(layerName)
                .append("：超阈值 ").append(d.above()).append(" 对 = 展示 ").append(shown).append(" 条");
        if (!causes.isEmpty()) sb.append(" + ").append(String.join(" + ", causes));
        int expect = d.above() - shown;
        if (accounted != expect) {
            int diff = expect - accounted;
            sb.append(diff > 0 ? " + **未归因 " + diff + " 条**" : " + **多算 " + (-diff) + " 条**")
              .append("（加总不等于「超阈值 − 展示」，回执自相矛盾，请报缺陷）");
        } else if (causes.isEmpty()) {
            sb.append("（全部展示，无截断）");
        }
        return sb.toString();
    }

    /** 满员那种口径（`未展示 145 对 = 139 + 6`），同样要求加总吻合 */
    static String shortCausesText(int notShown, int bySample, int byPerDoc, int byOmitted,
                                  int byLimit, int bySameTopic, int byJudged) {
        return shortCausesText(notShown, bySample, byPerDoc, byOmitted, byLimit, bySameTopic, byJudged, 0);
    }

    /** @param byGated 被「正文行重合不足门槛」拦下的对数，必须进求和（同 {@link #causesText}） */
    static String shortCausesText(int notShown, int bySample, int byPerDoc, int byOmitted,
                                  int byLimit, int bySameTopic, int byJudged, int byGated) {
        int sum = bySample + byPerDoc + byOmitted + byLimit + bySameTopic + byJudged + byGated;
        if (sum == 0) return "各项计数都为 0 —— 回执自相矛盾，请报缺陷";
        java.util.List<String> parts = new java.util.ArrayList<>();
        if (byGated > 0) parts.add("行重合不足门槛未入队 " + byGated);
        if (bySample > 0) parts.add("③层抽样省 " + bySample);
        if (bySameTopic > 0) parts.add("③层同话题跳过 " + bySameTopic);
        if (byJudged > 0) parts.add("已判过不占名额 " + byJudged);
        if (byPerDoc > 0) parts.add("per-doc 挤掉 " + byPerDoc);
        if (byOmitted > 0) parts.add("同族交叉对略去 " + byOmitted);
        if (byLimit > 0) parts.add("limit 截断 " + byLimit);
        String s = String.join(" + ", parts);
        if (sum != notShown) {
            s += " + **未归因 " + (notShown - sum) + " 条 —— 回执自相矛盾，请报缺陷**";
        }
        return s;
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
     * detail 的头部元信息块（标题 + 分类/作者/版本/标签/状态）。
     *
     * <p>抽成静态方法是为了让单测能直接断言渲染结果 ——
     * {@code getExperienceDetail} 依赖注入的 docService，测试里 new 不出来，
     * 而这块恰恰是本次要锁的（「状态行在不在」「待删说不说清」）。
     *
     * <p>⚠️ 2026-10-26 16:5x 阁主实跑标记链路发现：头部原本只有「分类/作者/版本/标签」，
     * <b>一个字不提「这条已标记待删」</b>。于是下一个 AI 读到会当正常文档用，
     * 而它已在待删队列等着人确认 —— 比列表那条更危险。
     * 状态行放在头部而不是只在横幅里：横幅是「附加说明」，头部是「这份文档是什么」，
     * 读的人第一眼扫头部就能看到，不读横幅也不会漏。
     */
    /** batch_get 单篇默认返回多少字符；超了给 offset 续读 */
    private static final int BATCH_GET_DOC_CHARS = 3000;
    /** batch_get 一次最多读几篇：再多就该想想「是不是搜索没搜对」了 */
    static final int BATCH_GET_MAX_DOCS = 20;

    /**
     * 解析逗号分隔的 ID 列表（去重保序）。两处批量入口共用同一套规则，
     * 免得「批量判定认 #51、批量读不认」这种同一个参数两套脾气。
     *
     * @throws IllegalArgumentException 有非数字项或解析不出任何 ID
     */
    static java.util.List<Long> parseIds(String raw, String paramName) {
        java.util.List<Long> parsed = new java.util.ArrayList<>();
        if (raw == null) {
            throw new IllegalArgumentException(paramName + " 不能为空");
        }
        for (String part : raw.split("[,，;；、\\s]+")) {
            String t = part.replace("#", "").trim();
            if (t.isEmpty()) continue;
            try {
                parsed.add(Long.parseLong(t));
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException(paramName + " 里有非数字项：「" + part + "」。格式如 \"51,52,53\"");
            }
        }
        if (parsed.isEmpty()) {
            throw new IllegalArgumentException(paramName + " 解析不出任何 ID。格式如 \"51,52,53\"");
        }
        return new java.util.ArrayList<>(new java.util.LinkedHashSet<>(parsed));
    }

    /**
     * 批量读（2026-11-05 · #1220 第②条）。
     *
     * <p>为什么需要它：近 7 天 {@code get_experience_detail} 被调了 2173 次、{@code search_experience} 只有 1228 次 ——
     * 读是搜的 1.77 倍。其中很大一块是「一次要读 5~8 篇，发 8 次请求」。
     * 这个工具解决的就是连读，不解决「搜不准」。
     *
     * <p>两条边界写死：
     * <ul>
     *   <li><b>每篇默认只给前 3000 字符</b>，并给出下一段 offset —— 20 篇 × 2 万字塞进一条回执，
     *       等于把「省下的 8 次请求」变成一次读不完的巨型响应；</li>
     *   <li><b>不要用 export_document 代替</b>：它只接受单个 id、不分段。</li>
     * </ul>
     */
    @Tool(name = "batch_get",
          description = "一次读多篇经验的正文开头（最多 20 篇）。每篇默认只给前 3000 字符，回执带「下一段 offset」，"
                  + "要看全某篇再用 get_experience_detail(id, offset=)。brief=true 只给标题+摘要+首段，适合先扫一遍。"
                  + "替代「一次要读 5~8 篇就发 8 次请求」。")
    public String batchGet(
            @ToolParam(description = "经验 ID 列表，逗号分隔，如 \"748,714,1220\"（上限 20 篇）") String ids,
            @ToolParam(description = "可选。每篇最多返回多少字符，默认 3000，上限 8000", required = false) Integer maxCharsPerDoc,
            @ToolParam(description = "可选。true=只给标题+摘要+首段（约 400 字），适合先扫一遍挑目标；默认 false 给正文开头", required = false) Boolean brief) {
        try {
            AiAssistant assistant = getCaller();
            if (assistant != null && !Boolean.TRUE.equals(assistant.getCanSearch())) {
                return "❌ 权限不足：该API Key没有查询经验的权限";
            }
            java.util.List<Long> idList;
            try {
                idList = parseIds(ids, "ids");
            } catch (IllegalArgumentException e) {
                return "❌ " + e.getMessage();
            }
            if (idList.size() > BATCH_GET_MAX_DOCS) {
                return "❌ 一次最多 " + BATCH_GET_MAX_DOCS + " 篇（本次 " + idList.size()
                        + " 篇）。分两次读 —— 一次给几十篇，等于把回执变成读不完的长文。";
            }
            boolean briefMode = Boolean.TRUE.equals(brief);
            int cap = briefMode ? 400
                    : ((maxCharsPerDoc == null || maxCharsPerDoc <= 0)
                        ? BATCH_GET_DOC_CHARS : Math.min(maxCharsPerDoc, 8000));

            StringBuilder sb = new StringBuilder();
            sb.append("📚 **批量读 ").append(idList.size()).append(" 篇**")
              .append(briefMode ? "（精简：标题+摘要+首 " + cap + " 字）" : "（每篇最多 " + cap + " 字符）")
              .append("\n\n");

            int got = 0, miss = 0;
            for (int i = 0; i < idList.size(); i++) {
                Long id = idList.get(i);
                Doc doc = docService.getById(id);
                if (doc == null) {
                    miss++;
                    sb.append("## ").append(i + 1).append(". 【ID:").append(id)
                      .append("】❌ 未找到（可能已删除，或不在此 Key 的权限范围）\n\n");
                    continue;
                }
                got++;
                sb.append("## ").append(i + 1).append(".\n");
                // 复用详情页头部与存疑横幅 —— 这两块是 RenderArtifactStripper 按字面前缀白名单认的，
                // 这里另写一套措辞，迟早与剥离规则对不上（那是把提示语固化进正文的经典路径）
                sb.append(renderDetailHeader(doc));
                sb.append(doubtBanner(doc, id));
                sb.append("\n");

                String full = doc.getContent();
                if (full == null || full.isEmpty()) {
                    sb.append("> 该文档没有正文内容。\n\n");
                    continue;
                }
                int total = full.length();
                int to = Math.min(cap, total);
                // 别把表情等代理对从中间切开
                if (to < total && Character.isLowSurrogate(full.charAt(to))) to++;
                String slice = full.substring(0, to).replaceAll("\\s+", " ").trim();
                if (!briefMode) {
                    sb.append("**正文（第 0 – ").append(to).append(" / 共 ").append(total).append(" 字符）**\n\n");
                    sb.append(slice).append("\n");
                } else {
                    sb.append("**摘要**: ").append(previewSummary(doc.getSummary())).append("\n");
                }
                if (to < total) {
                    sb.append("\n> 还有 ").append(total - to).append(" 字符没读：`get_experience_detail(id=")
                      .append(id).append(", offset=").append(to).append(")`\n");
                } else {
                    sb.append("\n");
                }
                sb.append("\n");
            }

            sb.append("---\n");
            sb.append("📊 共请求 ").append(idList.size()).append(" 篇：读到 ").append(got).append("｜未找到 ").append(miss)
              .append("｜本次回执 ").append(sb.length()).append(" 字符\n");
            if (got > 0 && miss == 0) {
                sb.append("💡 已读到的都是开头。要读全文或某篇的后续段落，用 get_experience_detail(id, offset=)。\n");
            }
            return sb.toString();
        } catch (Exception e) {
            log.error("ExpHubTools.batchGet: FAILED", e);
            callLogService.incrementFailCalls();
            return "批量读取失败: " + e.getClass().getName() + " - " + e.getMessage();
        }
    }

    static String renderDetailHeader(Doc doc) {
        StringBuilder sb = new StringBuilder();
        sb.append("# ").append(doc.getTitle()).append("\n\n");
        sb.append("**分类**: ").append(doc.getCategory()).append("\n");
        sb.append("**作者**: ").append(doc.getAuthorName()).append("\n");
        sb.append("**版本**: v").append(doc.getVersion()).append("\n");
        sb.append("**标签**: ").append(doc.getTags() != null ? doc.getTags() : "无").append("\n");
        sb.append("**状态**: ").append(getStatusLabel(doc.getStatus())).append("\n");
        // #752：验证时间必须读得到 —— 否则「改错字时传旧值」根本无从下手（模型不知道旧值是多少）。
        // ⚠️ 这一行同时登记进 RenderArtifactStripper 的头部元信息白名单，否则「读出来→改一点→整篇写回」
        //    会把它固化进正文，和之前「状态」「⚑ 标记」踩的是同一个坑。
        java.time.LocalDateTime verified = doc.getLastVerifiedAt();
        if (verified == null) {
            sb.append("**验证时间**: （未记录）\n");
        } else {
            long days = java.time.temporal.ChronoUnit.DAYS.between(
                    verified.toLocalDate(), java.time.LocalDate.now());
            sb.append("**验证时间**: ").append(verified.toLocalDate())
              .append("（").append(days <= 0 ? "今天" : days + " 天前").append("｜")
              .append(verifiedSourceLabel(doc.getLastVerifiedSource())).append("）");
            if (days > STALE_VERIFY_DAYS) {
                sb.append("　⚠️ 超过 ").append(STALE_VERIFY_DAYS).append(" 天未验证，引用前先核");
            }
            sb.append("\n");
        }
        return sb.toString();
    }

    /**
     * 存疑横幅：待删状态 + 取代关系 + 标记。detail 与 export 共用。
     *
     * <p>为什么必须共用一份文案：RenderArtifactStripper 是**按字面前缀**剥的
     * （`**⛔ 已被取代**:` / `> 结论以新条为准` / `**⚑ 标记**:` / `> ⚠️ 本条被标记为`）。
     * 如果 detail 和 export 各写一套措辞，剥离规则必然只能覆盖其中一边，
     * 另一边就会在「读出来 → 改一点 → 整篇写回」时被固化进正文 —— 而且那行提示
     * 不会随后续清标记而消失，等于给文档刻了个假碑。
     */
    private String doubtBanner(Doc doc, Long id) {
        StringBuilder sb = new StringBuilder();
        sb.append(renderPendingDeleteBlock(doc));
        sb.append(renderSupersedeBlock(doc, id));
        sb.append(renderFlagBlock(doc));
        return sb.toString();
    }

    /**
     * 待删状态块（status=PENDING_DELETE）。
     *
     * <p>⚠️ 2026-10-26 16:5x 阁主实跑标记链路发现：detail 回执原本只有「分类/作者/版本/标签」，
     * **一个字不提「这条已标记待删」**。后果比列表那条更危险 —— 下一个 AI 读到会当正常文档用，
     * 而它已在待删队列等着人确认。
     *
     * <p>为什么按 ID 读要显示而不是隐藏：隐藏等于让文档「凭空消失」，
     * 用户查 ID 会以为系统坏了、也会让 AI 误以为这条不存在。
     * 正确做法是**读得到、但一眼看出它正等着被删**。
     *
     * <p>措辞与剥离规则绑定：前三行一律以 {@code 🗑} 开头，第四行复用
     * 「&gt; ⚠️ 本条被标记为…」这个既有前缀（RenderArtifactStripper 已认）。
     * 用独占 emoji 是为了让剥离规则**只吃渲染产物、不误伤正文**——
     * 正文里出现「**状态**: ...」这类行太常见，用它当锚点会吃掉真内容。
     */
    static String renderPendingDeleteBlock(Doc doc) {
        if (!DocService.PENDING_DELETE.equals(doc.getStatus())) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        sb.append("**🗑 待删**: 本条（#").append(doc.getId())
          .append("）已标记为待删（**尚未删除**，等用户确认后才会真正删除）\n");
        if (doc.getDeleteReason() != null && !doc.getDeleteReason().isBlank()) {
            sb.append("**🗑 待删原因**: ").append(abbreviate(doc.getDeleteReason(), 300)).append("\n");
        }
        sb.append("**🗑 待删标记人**: ")
          .append(doc.getDeleteMarkedBy() != null && !doc.getDeleteMarkedBy().isBlank()
                  ? doc.getDeleteMarkedBy() : "未知");
        if (doc.getDeleteMarkedAt() != null) {
            sb.append(" | ").append(doc.getDeleteMarkedAt());
        }
        sb.append("\n");
        sb.append("> ⚠️ 本条被标记为「待删」，引用前请先核实；如标记有误，可调用 cancel_deletion(id=")
          .append(doc.getId()).append(") 撤回。\n\n");
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
    @Tool(name = "mark_superseded", description = "标记「旧经验已被新经验取代」。只写一条指针：不合并正文、不递增版本、不写快照，旧条原文完整保留。⚠️ 只标关系不删内容；关系错了用 clear_superseded 撤销。")
    public String markSuperseded(
            @ToolParam(description = "被取代的旧经验 ID") Long id,
            @ToolParam(description = "取代它的新经验 ID（必须真实存在）。通常是内容更准确、时间更新的那一条") Long byId,
            @ToolParam(description = "可选.操作人署名（标记取代关系，署名只进日志）", required = false) String authorName,
            @ToolParam(description = "可选。**谁改的**——与 authorName 等价", required = false) String updatedName) {

        try {
            AiAssistant assistant = getCaller();
            if (assistant != null && !Boolean.TRUE.equals(assistant.getCanUpdate())) {
                return "❌ 权限不足：该API Key没有编辑经验的权限";
            }
            if (id == null || byId == null) {
                return "❌ id 与 byId 都必填。id=被取代的旧条，byId=取代它的新条。";
            }
            String by = resolveAuthor(updatedName, authorName, assistant);
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
              .append("**版本快照**: 未产生（本工具只写关系，不写版本表）\n");
            // ⚠️ 2026-10-26：clear_superseded 一直显示操作人、mark_superseded 不显示 ——
            // 阁主实测指出这个不对称。现在两边都输出，且 by 已落库到 superseded_marked_by
            sb.append("**操作人**: ").append(by).append("\n\n");
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
    @Tool(name = "clear_superseded", description = "撤销「已被取代」标记，恢复成独立条目（不影响正文/状态/标记）。")
    public String clearSuperseded(
            @ToolParam(description = "要撤销取代关系的经验 ID（原本被标记为「已被取代」的那条）") Long id,
            @ToolParam(description = "可选。操作人署名（撤销取代关系，署名只进日志）", required = false) String authorName,
            @ToolParam(description = "可选。**谁改的**——与 authorName 等价", required = false) String updatedName) {
        try {
            AiAssistant assistant = getCaller();
            // ⚠️ 2026-10-25 修：`authorName` / `updatedName` 原来是两个死参数
            // （参数说明写「两者等价，仅写日志」，实际一行日志都没打）
            String operator = resolveAuthor(updatedName, authorName, assistant);
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
            log.info("ExpHubTools.clearSuperseded: #{} 撤销被 #{} 取代, operator={}", id, was, operator);
            return "✅ 已撤销取代关系\n\n**ID**: " + id
                    + "\n**原来被 #" + was + " 取代**，现已恢复为独立条目"
                    + "\n**操作人**: " + operator
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
    @Tool(name = "add_review_candidate", description = "把一对「可能重复/互相取代」的经验放进候选队列。⚠️ 入队**不改任何数据**；同一对不重复入队，已判「不同」的不入队。")
    public String addReviewCandidate(
            @ToolParam(description = "候选对的第一条经验 ID") Long docA,
            @ToolParam(description = "候选对的第二条经验 ID") Long docB,
            @ToolParam(description = "可选。相似度得分，取值 (**0, 1]**，只用于排序，不是判决依据。没算出来就别传（传 0 会被当「未提供」存 NULL，因为相似度 0 的语义是「完全不像」；传大于 1 的值会被拒，若是百分制请除以 100）", required = false) java.math.BigDecimal score,
            @ToolParam(description = "可选但强烈建议。为什么判为相似，例如「标签交集:3（nginx,https,证书）；标题重叠 0.62；正文共享 2-gram 41%」。写清理由，人才判得快", required = false) String reasons,
            @ToolParam(description = "可选。来源标记：L0_CREATE / L1_DAILY / L2_WEEKLY / MANUAL。默认 MANUAL", required = false) String source,
            @ToolParam(description = "可选。**谁改的**——与 authorName 等价", required = false) String updatedName) {
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
            // 2026-10-26：operator 原来只进日志，回执不说 —— 出问题时分不清是谁入的队。
            // 与 clearSuperseded 的「**操作人**: 」写法保持一致（白泽指出这一批三个都这样）
            sb.append("- 操作人: ").append(operator).append("\n");
            sb.append("\n用 list_review_candidates 查看队列，用 resolve_candidate 做判断。");
            return sb.toString();
        } catch (Exception e) {
            log.error("ExpHubTools.addReviewCandidate: FAILED", e);
            callLogService.incrementFailCalls();
            return "入队失败: " + e.getClass().getName() + " - " + e.getMessage();
        }
    }

    /** 列出候选队列（默认只看待判断的） */
    @Tool(name = "list_review_candidates", description = "查看整理候选队列（哪些经验对可能重复/互相取代、相似度多少）。默认只看 PENDING，status=ALL 看全部。⚠️ 队列长用 **brief=true**：每条一行，避免被截断。")
    public String listReviewCandidates(
            @ToolParam(description = "可选。状态：PENDING（默认，待判断）/ DUPLICATE / SUPERSEDED / DISTINCT / SKIPPED；**ALL = 不限状态**（与 check_my_todos 的 ALL 语义一致）", required = false) String status,
            @ToolParam(description = "可选。最多返回几条，默认 20，上限 50", required = false) Integer limit,
            @ToolParam(description = "可选。**精简模式**：每条只留一行（候选号/状态/得分/来源/A↔B），不印理由与判定人。"
                    + "队列一长（>20 条）或回执被截断时用这个。默认 false", required = false) Boolean brief) {
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
            // 全队列按状态分组计数（#1220 第⑥条）：limit 只截列表，不能连总数一起吃掉
            java.util.Map<String, Long> groupCounts = reviewCandidateService.countByStatusGroup();
            String distribution = formatStatusCounts(groupCounts);
            String scope = (st == null) ? "ALL（不限状态）" : st;
            if (list.isEmpty()) {
                return "📭 队列为空（状态 " + scope + "）。\n\n"
                        + (st == null
                            ? "全部状态都没有任何候选记录。\n"
                            : "整理新候选：发现两条内容高度重合时用 add_review_candidate 入队。\n")
                        + "想看所有状态用 status=ALL。\n\n"
                        + "📊 全队列分布: " + distribution + "\n";
            }
            // 批量取标题，避免逐条查
            java.util.Set<Long> ids = new java.util.LinkedHashSet<>();
            for (ReviewCandidate c : list) { ids.add(c.getDocA()); ids.add(c.getDocB()); }
            Map<Long, Doc> docMap = new HashMap<>();
            for (Doc d : docService.listByIds(ids)) docMap.put(d.getId(), d);

            StringBuilder sb = new StringBuilder();
            sb.append("🗂 整理候选队列 · ").append(scope)
              .append("（本页 ").append(list.size()).append(" 条 / 传入 limit=").append(size).append("）\n");
            // ⚠️ 2026-11-05：原来只写「N 条」，而 N 是本页条数（被 LIMIT 截过）。
            //    用 limit=1 查 PENDING 时队列压着 34 条、回执却写「1 条」—— 积压量被分页吃掉了。
            sb.append("📊 全队列分布: ").append(distribution).append("\n");
            // ⚠️ 2026-10-26 11:5x 阁主：「项目 #8 扫出 1 篇，队列里却有 2 条」——
            //    两个数各自都对，差值来自**候选行不随文档删除失效**：行还在，它引用的文档没了。
            //    以前只有逐行写「已不存在」，头行不给总数，读的人会以为「扫出 1 篇」和「2 条」矛盾。
            int orphanPending = 0, orphanJudged = 0;
            for (ReviewCandidate c : list) {
                if (docMap.get(c.getDocA()) == null || docMap.get(c.getDocB()) == null) {
                    if (ReviewCandidate.PENDING.equals(c.getStatus())) orphanPending++;
                    else orphanJudged++;
                }
            }
            if (orphanPending + orphanJudged > 0) {
                // ⚠️ 2026-10-26 12:1x 阁主：原来写「其中 2 条…已被删除…其中 2 条已判过」——
                //    两个「2 条」是**同一批**（#26 DUPLICATE、#23 SKIPPED），措辞会让人读成 4 条。
                //    规则：同一批就明说「这 N 条均已判过」，不同批才分列。
                sb.append("⚠️ 共 **").append(orphanPending + orphanJudged)
                  .append(" 条引用的文档已被删除**（候选行不会随文档删除消失，保留是为了复盘）");
                if (orphanPending > 0) {
                    sb.append("：其中 **").append(orphanPending)
                      .append(" 条待判断**判不了任何东西，可用 resolve_candidate(..., action=skip) 清空");
                }
                if (orphanJudged > 0) {
                    // ⚠️ 2026-10-26 12:0x 阁主第 3 条：已判的悬空行**不许**建议 skip ——
                    //    那等于用「忽略」改写「判为重复」。这里只说明判定保留。
                    if (orphanPending == 0) {
                        sb.append("：**这 ").append(orphanJudged)
                          .append(" 条均已判过**，判定保留、无需处理（悬空行只能清空，不能改判）");
                    } else {
                        sb.append("；另 **").append(orphanJudged)
                          .append(" 条已判过**，判定保留、无需处理（悬空行只能清空，不能改判）");
                    }
                }
                sb.append("\n");
            }
            sb.append("\n");
            int i = 1;
            boolean compact = Boolean.TRUE.equals(brief);
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
                // 否则「没记分」和「记了 0 分」在列表里看起来一样。
                // ⚠️ 2026-10-26 13:0x 阁主抓到拼接 bug：`formatScore(null)` 本身已返回「未记录」，
                //    外面又拼一次 → 「未记录（未记录）」。**兜底文案只能在源头出现一次。**
                sb.append("｜相似度 ").append(ReviewCandidateService.formatScore(c.getScore()));
                sb.append("｜来源 ").append(c.getSource());
                if (compact) {
                    // brief 模式：一行到底（2026-10-26 13:0x 阁主：12 条就 4140 字符，第 12 条只剩半行）
                    sb.append("｜#").append(c.getDocA()).append(" ↔ #").append(c.getDocB());
                    if (a == null || b == null) sb.append("（**已不存在**）");
                    sb.append("\n");
                    continue;
                }
                sb.append("\n");
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
            if (compact) {
                // 2026-10-26 13:0x 阁主：12 条完整回执 4140 字符，第 12 条被客户端截成半行。
                sb.append("---\n（**brief 模式**：只列 候选号/状态/得分/来源/A↔B，共 ")
                  .append(sb.length()).append(" 字符。要看理由与判定人：去掉 brief 重查，或用 limit 分页）\n");
                return sb.toString();
            }
            sb.append("---\n处理方式：resolve_candidate(candidateId=..., action=duplicate|superseded|distinct|skip, note=...)\n")
              .append("· duplicate = 两条都打重复标记（**不合并**）\n")
              .append("· superseded = 较旧那条标为被较新那条取代（**只写指针**）\n")
              .append("· distinct = 判为不同，以后不再重新入队\n")
              .append("· skip = 本次忽略，下次还会提出来\n")
              .append("⚠️ 别凭「重合多」直接判 duplicate：两侧独有行都很少才是真重复；"
                      + SimilarityScorer.Score.SUBSET_RULE_SHORT + "；"
                      + "两侧各约一半是同模板实例（判 distinct）。"
                      + "正文 0 行重合时也没有内容证据，不要判 superseded —— 那等于用其中一份取代另一份");
            // 回执自己报体积：超过 6000 字符很可能已被客户端截断（实测 4140 就断在第 12 条半行）。
            // 不许静默——读的人以为看全了，其实少了几条。
            if (sb.length() > 6000) {
                sb.append("\n\n⚠️ 本条回执 ").append(sb.length())
                  .append(" 字符，**可能已被客户端截断**（后面几条会只剩半行）。")
                  .append("要一次看全：`brief=true`（每条一行），或用 limit 分页。");
            }
            return sb.toString();
        } catch (Exception e) {
            log.error("ExpHubTools.listReviewCandidates: FAILED", e);
            callLogService.incrementFailCalls();
            return "查询候选队列失败: " + e.getClass().getName() + " - " + e.getMessage();
        }
    }

    /** 处理一条候选 */
    @Tool(name = "reopen_candidate",
          description = "【撤回已判候选】把已判（duplicate / distinct / superseded / skip）的候选改回 PENDING，让它重新参与扫描与入队。"
                  + "**支持批量**：传 candidateIds=\"51,52,53\"（上限 50 条）—— 判错一次要救几十条时，逐条撤等于不撤。"
                  + "⚠️ **只回退候选状态**，当初打在文档上的标记 / 取代指针不会撤销（那用 set_flags(ids=\"...\", flags=\"\") 、clear_superseded）。"
                  + "（语义变更 2026-11-05：新增批量；2026-10-29：撤回前的判定整段存入判定流水）")
    public String reopenCandidate(
            @ToolParam(description = "候选 ID（「候选 #N」里的 N）。单条用；批量改用 candidateIds", required = false) Long candidateId,
            @ToolParam(description = "可选。**批量**撤回：候选 ID 列表，逗号分隔，如 \"51,52,53\"（上限 50）。与 candidateId 二选一", required = false) String candidateIds,
            @ToolParam(description = "可选。撤回原因：为什么当初判错了。会与原判定说明一起并入 note，原判定不丢", required = false) String note,
            @ToolParam(description = "可选。**谁改的**——操作人署名", required = false) String updatedName) {
        try {
            AiAssistant assistant = getCaller();
            if (assistant != null && !Boolean.TRUE.equals(assistant.getCanUpdate())) {
                return "❌ 权限不足：该API Key没有编辑经验的权限";
            }
            String by = resolveAuthor(updatedName, assistant);

            // ===== 批量撤回（2026-11-05）=====
            if (candidateIds != null && !candidateIds.trim().isEmpty()) {
                java.util.List<Long> ids;
                try {
                    ids = parseIds(candidateIds, "candidateIds");
                } catch (IllegalArgumentException e) {
                    return "❌ " + e.getMessage();
                }
                if (ids.size() > BATCH_REOPEN_MAX) {
                    return "❌ 一次最多 " + BATCH_REOPEN_MAX + " 条（本次 " + ids.size()
                            + " 条）。分两次撤 —— 上限放宽是因为撤销是救火动作，但也要留个体量上限。";
                }
                int ok = 0, skipped = 0, failed = 0;
                StringBuilder sb = new StringBuilder();
                sb.append("↩️ **批量撤回 ").append(ids.size()).append(" 条**\n\n");
                for (Long cid : ids) {
                    ReviewCandidateService.ResolveResult r = reviewCandidateService.reopen(cid, note, by);
                    if (r.ok()) {
                        ok++;
                        sb.append("- ✅ #").append(cid).append(" 已退回 PENDING\n");
                    } else {
                        // 「本来就是 PENDING」不算失败，是跳过 —— 否则批量救火时报一堆红，人以为没救成
                        ReviewCandidate cur = reviewCandidateService.get(cid);
                        if (cur != null && ReviewCandidate.PENDING.equals(cur.getStatus())) {
                            skipped++;
                            sb.append("- ⏭ #").append(cid).append(" 本来就是 PENDING，跳过\n");
                        } else {
                            failed++;
                            sb.append("- ❌ #").append(cid).append(" ").append(r.message()).append("\n");
                        }
                    }
                }
                sb.append("\n**合计**：成功 ").append(ok).append("｜跳过 ").append(skipped).append("｜失败 ").append(failed)
                  .append("\n**操作人**: ").append(by)
                  .append("\n⚠️ 文档上的标记 / 取代指针**没有撤销**：")
                  .append("清标记用 `set_flags(ids=\"...\", flags=\"\")`，撤取代用 `clear_superseded`。");
                return sb.toString();
            }

            if (candidateId == null) {
                return "❌ 必须传 candidateId（单条）或 candidateIds（批量，逗号分隔）。"
                        + "不知道 ID 就先 list_review_candidates(status=ALL)。";
            }
            ReviewCandidateService.ResolveResult r = reviewCandidateService.reopen(candidateId, note, by);
            if (!r.ok()) {
                callLogService.incrementFailCalls();
                return "❌ " + r.message();
            }
            // 守卫 DeadParamGuardTest 要求：算了 resolveAuthor 就必须在回执里说出来
            // —— 一个 Key 多助手共用时，不写操作人就分不清是谁改的状态
            return r.message() + "\n**操作人**: " + by;
        } catch (Exception e) {
            log.error("ExpHubTools.reopenCandidate: FAILED", e);
            callLogService.incrementFailCalls();
            return "撤回失败: " + e.getClass().getName() + " - " + e.getMessage();
        }
    }

    /** 批量撤回的上限。比批量判定的 20 宽 —— 撤销是救火动作，但也不能无限。 */
    static final int BATCH_REOPEN_MAX = 50;

    @Tool(name = "list_candidate_history",
          description = "【查一条候选的判定流水】谁在什么时候判成什么、说了什么。**只读**。"
                  + "2026-10-29 起判定历史不再挤在 note 里：note 只存「当前判定理由」，"
                  + "每一次动作（duplicate/superseded/distinct/skip/reopen）都进独立的流水表，且不截断。"
                  + "想知道「这条当初凭什么判」「某次撤回是否真发生过」「同一条判过几轮」，用它。")
    public String listCandidateHistory(
            @ToolParam(description = "必填。候选 ID（list_review_candidates 返回的「候选 #N」里的 N）") Long candidateId,
            @ToolParam(description = "可选。最多返回多少条，默认 50，上限 500", required = false) Integer limit) {
        try {
            if (candidateId == null) {
                return "❌ 必须传 candidateId（候选 #N 里的 N）。不知道 ID 就先 list_review_candidates(status=ALL)。";
            }
            int cap = (limit == null || limit < 1) ? 50 : Math.min(limit, 500);
            ReviewCandidate c = reviewCandidateService.get(candidateId);
            java.util.List<com.exphub.entity.CandidateResolutionLog> rows =
                    reviewCandidateService.history(candidateId, cap);

            StringBuilder sb = new StringBuilder();
            sb.append("📜 **候选 #").append(candidateId).append(" 判定流水**\n\n");
            if (c != null) {
                sb.append("**涉及文档**: #").append(c.getDocA()).append(" ↔ #").append(c.getDocB())
                  .append("｜相似度 ").append(c.getScore() == null ? "-" : c.getScore().toPlainString())
                  .append("\n**当前状态**: ").append(c.getStatus());
                if (c.getResolvedBy() != null && !c.getResolvedBy().trim().isEmpty()) {
                    sb.append("（").append(c.getResolvedBy()).append("）");
                }
                String cur = (c.getNote() == null || c.getNote().trim().isEmpty()) ? "(无，当前无判定理由)" : c.getNote();
                sb.append("\n**当前理由**: ").append(abbreviate(cur, 300)).append("\n\n");
            } else {
                sb.append("⚠️ 候选表里没有 #").append(candidateId)
                  .append("（可能已被清理），以下仅有流水记录。\n\n");
            }

            if (rows.isEmpty()) {
                sb.append("📭 没有任何流水记录");
                if (c != null && ReviewCandidate.PENDING.equals(c.getStatus())) sb.append("（它还在待判断）");
                sb.append("。\n2026-10-29 之前做的判定没有流水，只能看候选表上的当时结果。\n");
                return sb.toString();
            }

            sb.append("| 流水# | 时间 | 动作 | 状态变化 | 操作人 |\n|---|---|---|---|---|\n");
            int shown = 0;
            for (com.exphub.entity.CandidateResolutionLog l : rows) {
                if (shown++ >= 60) {
                    sb.append("| … | 还有 ").append(rows.size() - 60).append(" 条 | | | |\n");
                    break;
                }
                sb.append("| ").append(l.getId())
                  .append(" | ").append(l.getCreatedAt() == null ? "-"
                          : l.getCreatedAt().toString().replace('T', ' '))
                  .append(" | ").append(l.getAction())
                  .append(" | ").append(l.getPrevStatus() == null ? "-" : l.getPrevStatus())
                  .append(" → ").append(l.getNewStatus() == null ? "-" : l.getNewStatus())
                  .append(" | ").append(l.getOperator()).append(" |\n");
            }

            // 说明正文单独列出：撤回那条会带着「撤回前的判定」全文，放表格里会撑爆
            sb.append("\n**各次说明**\n");
            for (com.exphub.entity.CandidateResolutionLog l : rows) {
                if (l.getNote() == null || l.getNote().trim().isEmpty()) continue;
                sb.append("\n- **#").append(l.getId()).append(" ").append(l.getAction()).append("**（")
                  .append(l.getOperator()).append("）：").append(abbreviate(l.getNote().trim(), 400));
            }
            sb.append("\n");
            if (rows.size() >= cap) {
                sb.append("\n⚠️ 已到返回上限 ").append(cap).append(" 条，可能还有更早的记录，调大 limit 再看。\n");
            }
            return sb.toString();
        } catch (Exception e) {
            log.error("ExpHubTools.listCandidateHistory: FAILED", e);
            callLogService.incrementFailCalls();
            return "查询判定流水失败: " + e.getClass().getName() + " - " + e.getMessage();
        }
    }

    @Tool(name = "list_archive_batches",
          description = "【按批次查归档】archive_old_versions 超时后，用它确认「这批到底搬完没有」。"
                  + "不传 batchId = 列出最近各批次（批次号 / 条数 / 涉及文档数 / 时间范围）；"
                  + "传 batchId = 列出该批次搬走的每一版。**只读**。批次号来自 archive_old_versions 回执。")
    public String listArchiveBatches(
            @ToolParam(description = "可选。批次号，形如 AR20261028-230401。不传 = 列出最近批次", required = false) String batchId,
            @ToolParam(description = "可选。列出多少个批次，默认 10，上限 50", required = false) Integer limit) {
        try {
            if (batchId == null || batchId.trim().isEmpty()) {
                int cap = (limit == null || limit < 1) ? 10 : Math.min(limit, 50);
                java.util.List<VersionArchiveService.BatchRow> bs = versionArchiveService.listBatches(cap);
                StringBuilder sb = new StringBuilder("📦 **最近归档批次**\n\n");
                if (bs.isEmpty()) {
                    sb.append("没有任何带批次号的归档记录 —— 2026-10-28 之后写入的归档行才带 `batch=` 后缀。\n");
                    return sb.toString();
                }
                sb.append("| 批次号 | 归档条数 | 涉及文档 | 时间范围 |\n|---|---|---|---|\n");
                for (VersionArchiveService.BatchRow b : bs) {
                    sb.append("| `").append(b.batchId()).append("` | ").append(b.rows())
                      .append(" | ").append(b.docs()).append(" 篇 | ")
                      .append(b.firstAt()).append(" ~ ").append(b.lastAt()).append(" |\n");
                }
                sb.append("\n查某一批的明细：`list_archive_batches(batchId=\"AR...\")`\n");
                return sb.toString();
            }
            java.util.List<DocVersionArchive> rows = versionArchiveService.listByBatch(batchId);
            if (rows.isEmpty()) {
                return "❌ 没找到批次 `" + batchId.trim() + "` 的归档记录。\n"
                        + "两种可能：① 批次号写错（形如 `AR20261028-230401`）；"
                        + "② 这批发生在 2026-10-28 之前 —— 那时 archive_reason 里还没有 batch 后缀，"
                        + "只能用 `list_archived_versions(id=文档ID)` 按时间核对。";
            }
            java.util.Map<Long, java.util.List<DocVersionArchive>> byDoc = new java.util.LinkedHashMap<>();
            for (DocVersionArchive a : rows) {
                byDoc.computeIfAbsent(a.getDocId(), k -> new java.util.ArrayList<>()).add(a);
            }
            StringBuilder sb = new StringBuilder();
            sb.append("📦 批次 `").append(batchId.trim()).append("`：**").append(rows.size())
              .append(" 条**，涉及 **").append(byDoc.size()).append(" 篇**\n\n");
            sb.append("| 文档 | 标题 | 归档版本 | 归档时间 |\n|---|---|---|---|\n");
            int i = 0;
            for (java.util.Map.Entry<Long, java.util.List<DocVersionArchive>> e : byDoc.entrySet()) {
                if (i++ >= 40) {
                    sb.append("| … | 还有 ").append(byDoc.size() - 40).append(" 篇 | | |\n");
                    break;
                }
                java.util.List<DocVersionArchive> list = e.getValue();
                java.util.List<Integer> vs = new java.util.ArrayList<>(list.size());
                for (DocVersionArchive a : list) vs.add(a.getVersion());
                DocVersionArchive first = list.get(0);
                sb.append("| #").append(e.getKey()).append(" | ")
                  .append(abbreviate(first.getDocTitle() == null ? "(无标题)" : first.getDocTitle(), 30))
                  .append(" | ").append(archiveVersionsCell(vs)).append(" | ")
                  .append(first.getArchivedAt() == null ? "-" : first.getArchivedAt().toString().replace('T', ' '))
                  .append(" |\n");
            }
            sb.append("\n✅ 这批**已落地**（能查出来就说明写进库了）。逐篇核对用 `list_archived_versions(id=...)`。\n");
            return sb.toString();
        } catch (Exception e) {
            log.error("ExpHubTools.listArchiveBatches: FAILED", e);
            callLogService.incrementFailCalls();
            return "查询失败: " + e.getClass().getName() + " - " + e.getMessage();
        }
    }

    @Tool(name = "resolve_candidate", description = "处理整理候选：duplicate（只打标记）/ superseded（只写指针）/ distinct / skip。都不删任何内容。"
            + "**支持批量**：传 candidateIds=\"51,52,53\"（上限 20 条，必须带 note 作统一理由），不传则按 candidateId 单条处理。"
            + "distinct 后不再重新入队；skip 只是「这次先不看」，得分明显变高会重新提出，也可 reopen_candidate 撤回。"
            + "（语义变更 2026-11-05：新增批量与 skip 可复活）")
    public String resolveCandidate(
            @ToolParam(description = "候选 ID（list_review_candidates 返回的「候选 #N」里的 N）。单条处理用；批量改用 candidateIds", required = false) Long candidateId,
            @ToolParam(description = "可选。**批量**处理：候选 ID 列表，逗号分隔，如 \"51,52,53\"（上限 20）。与 candidateId 二选一", required = false) String candidateIds,
            @ToolParam(description = "处理动作：duplicate（确为重复）/ superseded（旧被新取代）/ distinct（判为不同）/ skip（本次忽略，可复活）") String action,
            @ToolParam(description = "可选。判断依据：为什么判为重复或取代。写下来，日后回看才知道当时为什么这么判。**批量时必填**", required = false) String note,
            @ToolParam(description = "可选。处理人署名", required = false) String authorName,
            @ToolParam(description = "可选。**谁改的**——与 authorName 等价", required = false) String updatedName) {
        try {
            AiAssistant assistant = getCaller();
            if (assistant != null && !Boolean.TRUE.equals(assistant.getCanUpdate())) {
                return "❌ 权限不足：该API Key没有编辑经验的权限";
            }
            String by = resolveAuthor(updatedName, authorName, assistant);

            // ===== 批量分支（2026-11-05 · #1220 第⑥条）=====
            if (candidateIds != null && !candidateIds.trim().isEmpty()) {
                return resolveCandidatesInBatch(candidateIds, action, note, by);
            }
            if (candidateId == null) {
                return "❌ 必须传 candidateId（单条）或 candidateIds（批量，逗号分隔）。"
                        + "不知道 ID 就先 list_review_candidates(status=ALL)。";
            }

            // ⚠️ 2026-10-26 12:0x 阁主第 3 条：**「忽略」不许能改写「判为重复」**。
            //    背景：他删掉两条临时样本后，候选 #26 成了悬空行（引用的文档已删），
            //    而头行建议「用 skip 收掉」；但 #26 已经是 DUPLICATE 终态 ——
            //    skip 若覆盖它，等于用「忽略」改写了「判为重复」这个结论。
            //    规则：**悬空行只能清空（PENDING → SKIPPED），不能改判**。
            String dangling = danglingRejection(reviewCandidateService.get(candidateId), action);
            if (dangling != null) {
                return dangling;
            }
            ReviewCandidateService.ResolveResult r =
                    reviewCandidateService.resolve(candidateId, action, note, by);
            if (!r.ok()) {
                return "❌ " + r.message();
            }
            String extra = ReviewCandidate.DUPLICATE.equals(r.candidate().getStatus())
                    || ReviewCandidate.SUPERSEDED.equals(r.candidate().getStatus())
                    ? "\n\n记住：没有删除、没有合并正文。要彻底清理仍需人用 mark_for_deletion 标记后由用户在后台确认。"
                    : "";
            // 2026-10-26：by 已传给 service.resolve，但回执没说 —— 补上，与 clearSuperseded 对齐
            return r.message() + "\n\n**候选 #" + candidateId + " 状态** → " + r.candidate().getStatus()
                    + "\n**操作人**: " + by + extra
                    + "\n**历史**: 本次动作已记入判定流水（note 只存当前理由）→ `list_candidate_history(candidateId="
                    + candidateId + ")`";
        } catch (Exception e) {
            log.error("ExpHubTools.resolveCandidate: FAILED", e);
            callLogService.incrementFailCalls();
            return "处理候选失败: " + e.getClass().getName() + " - " + e.getMessage();
        }
    }

    /** 批量判定一次最多处理多少条（#1220 第⑥条）：一次刷太多，判错的代价就没人认真看了 */
    static final int BATCH_RESOLVE_MAX = 20;

    /**
     * 悬空行护栏（抽成方法是因为批量也要用同一套判据）。
     *
     * <p>规则没变：**悬空行只能清空（PENDING → SKIPPED），不能改判**。
     * 抽出来之前这段只服务单条路径 —— 批量一旦漏了它，就等于给「忽略改写判为重复」开了后门。
     *
     * @return null 表示放行；否则是要原样返回给调用方的拒绝文案
     */
    private String danglingRejection(ReviewCandidate pre, String action) {
        if (pre == null) return null;
        boolean aGone = docService.getById(pre.getDocA()) == null;
        boolean bGone = docService.getById(pre.getDocB()) == null;
        if (!aGone && !bGone) return null;
        boolean isSkip = "skip".equalsIgnoreCase(action == null ? "" : action.trim());
        boolean isPending = ReviewCandidate.PENDING.equals(pre.getStatus());
        if (isSkip && isPending) return null;   // 清空是唯一允许的动作
        String gone = (aGone ? "#" + pre.getDocA() : "") + (aGone && bGone ? "、" : "")
                + (bGone ? "#" + pre.getDocB() : "");
        return "❌ 候选 #" + pre.getId() + " 引用的文档已删除（" + gone
                + "），这是一条**悬空行**。\n\n"
                + "**悬空行只能清空，不能改判**：" + (isPending
                    ? "它还在待判断，要清掉请传 action=skip（只会标 SKIPPED，不动任何文档）。"
                    : "它已经是 **" + pre.getStatus() + "** 终态，判定保留、无需处理 —— "
                      + "`skip` 不会覆盖已有结论，正是为了防止「忽略」改写成「判为重复」。");
    }

    /**
     * 批量判定（2026-11-05 · #1220 第⑥条：`list_review_candidates` 里压着 43 条 PENDING，
     * 一条一条判 43 次调用 —— 理性的选择是不判，队列只会越来越长）。
     *
     * <p>三条硬约束，都是「批量最容易出事」的地方：
     * <ol>
     *   <li><b>上限 20 条</b>：不够可以分两次，一口气刷 43 条没人会认真看理由；</li>
     *   <li><b>必须带 note</b>：一次判 N 条，没有统一理由事后无法复盘（每条都记进判定流水）；</li>
     *   <li><b>逐条过悬空行护栏</b>：批量不许绕过「悬空行只能清空」这条规矩。</li>
     * </ol>
     *
     * <p>回执逐条给结果：「已处理过」单独算**跳过**不叫失败 —— 把早就判过的当错误报，
     * 会让人以为批量坏了。
     */
    private String resolveCandidatesInBatch(String rawIds, String action, String note, String by) {
        java.util.List<Long> ids;
        try {
            ids = parseIds(rawIds, "candidateIds");
        } catch (IllegalArgumentException e) {
            return "❌ " + e.getMessage() + "（可取 list_review_candidates 回执里的「候选 #N」）";
        }
        if (ids.size() > BATCH_RESOLVE_MAX) {
            return "❌ 一次最多 " + BATCH_RESOLVE_MAX + " 条（本次 " + ids.size() + " 条）。分几次判，别一口气刷 —— 刷过去的没人会认真看理由。";
        }
        if (note == null || note.trim().isEmpty()) {
            return "❌ 批量判定必须传 note：一次判 " + ids.size()
                    + " 条，没有统一理由事后无法复盘。每条都会记进判定流水，理由只写一次、逐条复用。";
        }

        int ok = 0, skipped = 0, failed = 0;
        StringBuilder sb = new StringBuilder();
        sb.append("🗂 批量判定 · action=").append(action == null ? "(空)" : action)
          .append("｜共 ").append(ids.size()).append(" 条\n\n");
        for (Long id : ids) {
            String dang = danglingRejection(reviewCandidateService.get(id), action);
            if (dang != null) {
                failed++;
                sb.append("- ❌ #").append(id).append(" 悬空行（引用的文档已删），只能 skip 清空\n");
                continue;
            }
            ReviewCandidateService.ResolveResult r = reviewCandidateService.resolve(id, action, note, by);
            if (r.ok()) {
                ok++;
                sb.append("- ✅ #").append(id).append(" → ").append(r.candidate().getStatus()).append("\n");
            } else if (r.candidate() != null && !ReviewCandidate.PENDING.equals(r.candidate().getStatus())) {
                skipped++;
                sb.append("- ⏭ #").append(id).append(" 跳过（").append(r.candidate().getStatus()).append("）：")
                  .append(r.message()).append("\n");
            } else {
                failed++;
                sb.append("- ❌ #").append(id).append(" ").append(r.message()).append("\n");
            }
        }
        sb.append("\n**合计**：成功 ").append(ok).append("｜跳过 ").append(skipped).append("｜失败 ").append(failed)
          .append("\n**操作人**: ").append(by)
          .append("\n**理由**（已逐条写入判定流水）: ").append(abbreviate(note.trim(), 200));
        if (ok > 0 && ("duplicate".equalsIgnoreCase(action) || "superseded".equalsIgnoreCase(action))) {
            sb.append("\n\n记住：没有删除、没有合并正文。要彻底清理仍需用 mark_for_deletion 标记后由用户在后台确认。");
        }
        return sb.toString();
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
     *
     * <p>⚠️ <b>2026-10-26 01:35 越哥实测：这里原来有一份自己的文案，写着
     * 「演进链 → 大概率该判 superseded」，而文档那边的判法已经改成「先排除母集-子集」了。</b>
     * 实际命中是 #77 灵狐 ↔ #83 神龙（0 行重合，两只不同的宠物）——
     * 照那句建议判下去，等于用一只宠物取代另一只。
     * <b>一条建议写两份，必然有一份是旧的。</b>现在这里只做转发：
     * 文案全部来自 {@link SimilarityScorer#advice}，本方法只负责「没有文案时给个兜底」。
     */
    private String kindLabel(SimilarityScorer.Kind kind, int sharedLines, double coverage, String advice) {
        if (advice != null && !advice.isBlank()) return advice;
        // 兜底：老数据（队列里存的历史行）没有 advice 时，只描述已知证据 + **给缺省动作**。
        // ⚠️ 2026-10-26 02:51 越哥定：缺省是 distinct，其余动作要正面证据才给。
        // 这里拿不到两侧行数与标题，算不出形态 —— 那就**不许猜**，直接渲染缺省动作。
        if (kind == null) return "";
        String cov = SimilarityScorer.Score.pct(coverage);
        String evidence = switch (kind) {
            case DUPLICATE -> "🏷 疑似**字面重复**（正文 " + sharedLines + " 行完全一致，覆盖较短那篇 " + cov
                    + "）—— 这是老队列行，没存两侧独有行数，形态判不了";
            case SERIES -> sharedLines > 0
                    ? "🔗 **共用骨架**（只重 " + sharedLines + " 行、覆盖仅 " + cov + "）"
                    : "🔗 **只有标签/标题像**（正文 0 行重合 —— 没有任何内容证据）";
            case UNKNOWN -> "❔ 信息不足（行太少，走 2-gram 兜底）";
            case PROGRESS -> "📅 **两侧都是进度/日志记录**";
        };
        SimilarityScorer.Action act = kind == SimilarityScorer.Kind.PROGRESS
                ? SimilarityScorer.Action.NONE : SimilarityScorer.Action.DISTINCT;
        return evidence + "。\n　　" + SimilarityScorer.actionSentence(act, 0);
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
            sb.append("  ").append(kindLabel(h.kind(), h.sharedLines(), h.coverage(), h.advice())).append("\n");
            sb.append("  ").append(abbreviate(h.reasons(), 200)).append("\n");
        }
        if (!r.hits().isEmpty()) {
            sb.append("判断用 resolve_candidate(candidateId=..., action=duplicate|superseded|distinct)：\n")
              .append("· 两侧独有行都很少（整篇复制）→ duplicate（只打标记不合并）\n")
              .append("· 确认后者是前者同一件事的新阶段（**先排除母集-子集与同模板实例**）→ superseded（只写指针）\n")
              .append("· ").append(SimilarityScorer.Score.SUBSET_RULE_SHORT).append("\n")
              .append("· 是两个不同实体/不同角色，或拿不准 → distinct（此后这一对不再打扰）\n")
              .append("⚠️ 0 行正文重合时不要判 superseded：那只是标签/标题像，判了等于用其中一份取代另一份\n");
        }
        return sb.toString();
    }

    /**
     * 头行的「需要你判几条」。
     *
     * <p><b>2026-10-26 13:2x 阁主实测</b>：dryRun 下印「本次需要你判 5 对」是**不成立**的 ——
     * dryRun 不入队，就没有「待判」这回事（同一份回执里还写着「未入队（dryRun）」）。
     * 两种情境分开写，别用一个词同时表达「扫出来了」和「已进队列等判」。
     */
    static String triageLine(boolean dry, int need, int judged, int progress) {
        StringBuilder sb = new StringBuilder();
        if (dry) {
            sb.append("**dryRun：没有入队，所以不存在「待判」** —— 本次扫出 ").append(need)
              .append(" 对值得人看一眼的候选（要真判请先 `dryRun=false` 入队）");
        } else {
            sb.append("**需要你判**: ").append(need).append(" 对（已进队列）");
        }
        java.util.List<String> why = new java.util.ArrayList<>();
        if (judged > 0) why.add("已判过 " + judged + " 对（只是重新扫出，已标注）");
        if (progress > 0) why.add("进度类 " + progress + " 条（不用判）");
        if (!why.isEmpty()) sb.append("（其余 ").append(String.join("、", why)).append("）");
        return sb.toString();
    }

    /** 这一对是否已经判过（scan 行尾状态里写的是「**已判 XXX**（候选 #N…）」） */
    static boolean isJudged(SimilarityService.Row r) {
        return r != null && r.queueState() != null && r.queueState().startsWith("**已判");
    }

    /**
     * 分层分组标题 —— **不带编号**。
     *
     * <p>⚠️ 2026-10-26 02:1x 越哥实测发现：原来编号是写死的（①DUPLICATE ②SERIES ③UNKNOWN ④PROGRESS），
     * 而某一层可能一对都没有（例如 ③ 信息不足常常 0 对），于是回执出现「①②④」——
     * 读的人会以为漏了一层。**编号必须按「这次实际出现的层」顺排**，见 {@link #circle}。
     */
    private String kindGroupTitle(SimilarityScorer.Kind kind) {
        if (kind == null) return "其他";
        // ⚠️ 2026-10-26 03:01 越哥实测：组头原来写「…别当重复判」，里面却有一条给 ✅ 合并动作 ——
        //    动作枚举上线后，**组头不能再预设动作**（分层说的是「像什么」，动作说的是「做什么」，
        //    同一层可能同时出现 distinct 和 merge）。组头只描述分层，动作由每条的「动作：」行给出。
        return switch (kind) {
            case DUPLICATE -> "疑似字面重复 —— 正文有重合"
                    + "（这一层混着真重复 / 母集-子集 / 同模板实例三种，**别预设动作**，看每对形态与动作）";
            case SERIES -> "同话题演进链 / 共用骨架 / 补充页 —— 正文重合不够判重复（**别预设动作**）";
            case UNKNOWN -> "信息不足 —— 行太少，走 2-gram 兜底";
            case PROGRESS -> "进度/日志记录 —— 天然是分阶段记录（默认只抽样几条）";
        };
    }

    /**
     * 从已渲染的 advice 里读回动作标签（用于组头统计）。
     *
     * <p>为什么读回而不是重算：动作由 {@code SimilarityScorer.decide()} 决定并渲染进正文，
     * 组头若另算一遍，两处迟早漂移 —— 今晚已经因为「同一事实两个出口」踩过多次。
     */
    private static String actionTag(String advice) {
        if (advice == null) return "";
        if (advice.contains("动作：判 duplicate")) return "duplicate";
        if (advice.contains("动作：判 superseded")) return "superseded";
        if (advice.contains("动作：先把这")) return "merge";
        if (advice.contains("动作：判 distinct")) return "distinct";
        if (advice.contains("动作：不用判")) return "none";
        return "";
    }

    /** 动态层号：按本次实际出现的顺序给 ① ② ③…，不再跟枚举序号绑定 */
    private static String circle(int n) {
        String[] c = {"①", "②", "③", "④", "⑤", "⑥", "⑦", "⑧", "⑨", "⑩"};
        return n >= 1 && n <= c.length ? c[n - 1] : "(" + n + ")";
    }

    /**
     * 同一母档的多个「并回母档再清子页」—— <b>串行依赖，不是多个独立操作</b>（2026-10-26 10:4x 阁主指出的唯一
     * 「会让人按错数据动手」的问题）。
     *
     * <p>现场：①层 4 对 merge 全部指向母档 #63（子页 #65/#66/#67/#68）。回执把 4 对并列呈现，
     * 看起来像 4 个独立操作；但**每并完一对，#63 的行数就变**，下一对的「独有 8 行」「覆盖 83%」
     * 立刻过期。照着旧数字并第二对，并进去的可能已经不是那 8 行了。
     *
     * <p>顺序规则：子页独有行从少到多（改动最小的先做；每并完一对都要回头重读母档再核下一对）。
     * 这只是**执行建议**，真正要紧的是「一次只并一对 + 并完重读」——不做成批量操作。
     */
    static String mergeFamilyPlan(Long motherId, java.util.List<SimilarityService.Row> pairs) {
        java.util.List<SimilarityService.Row> ordered = new java.util.ArrayList<>(pairs);
        ordered.sort(java.util.Comparator
                .comparingInt(SimilarityService.Row::kidLines)
                .thenComparing(SimilarityService.Row::kidId,
                        java.util.Comparator.nullsLast(java.util.Comparator.naturalOrder())));
        StringBuilder sb = new StringBuilder();
        sb.append("   ⚠️ **同族串行依赖：母档 #").append(motherId).append(" 有 ").append(ordered.size())
          .append(" 对「并回母档再清子页」—— 这 ").append(ordered.size()).append(" 对不是 ")
          .append(ordered.size()).append(" 个独立操作**：每并完一对，#").append(motherId)
          .append(" 的行数就会变，后几对的「独有 N 行 / 覆盖 X%」是**并之前**算出来的数字，会过期。\n");
        sb.append("   建议顺序（子页独有行从少到多）：");
        for (int k = 0; k < ordered.size(); k++) {
            SimilarityService.Row r = ordered.get(k);
            if (k > 0) sb.append(" → ");
            sb.append("第").append(k + 1).append("步 #").append(r.kidId())
              .append("（独有 ").append(r.kidLines()).append(" 行）");
        }
        sb.append("\n   做法：**一次只并一对** → 并完打开 #").append(motherId)
          .append(" 读一遍确认真的并进去了 → 再核下一对的独有行还对不对得上 → 对不上就停下来重判，别照旧数字并。\n");
        sb.append("   要回头核「这几份原本是不是并列记录」，看被略去的同族交叉对 ID（列在各层头），按 ID 查原文。\n");
        return sb.toString();
    }

    @Tool(name = "scan_similar_docs", description = "全库两两打分，把可能重复/互相取代的经验对入队（**只入队、不判定、不改正文**，判定用 resolve_candidate）。只比同作用域：全局↔全局、项目内↔项目内，跨项目不比。按性质分层、**层内才按分数排**；分层统计基于全部超阈值对，**不受 limit 影响**。⚠️ ③层（进度/日志类）**默认不入队**：它们是同一件事的分阶段记录，按设计不该判重，只打分、只展示；要它们入队传 enqueueProgress=true。⚠️ **正文行重合门槛默认 1 行**：按行比较时 0 行完全重合的对不进候选（行数太少回退 2-gram 的不受此限）；传 minSharedLines=0 关闭。⚠️ **已判过（DISTINCT/SUPERSEDED/SKIPPED）的对不再进清单、不占名额**，回执只报「另有 N 对已判过、略过」。"
            + "（语义变更 2026-10-29：③层从「照旧入队」改为「默认不入队」；回执按「超阈值 = 已判过 + 被截断 + 本次拦下」拆开列）"
            + "（语义变更 2026-11-11：新增 minSharedLines 入队门槛；已判过的对不再占名额）")
    public String scanSimilarDocs(
            @ToolParam(description = "可选。扫描范围：不传 = 全局库 + 我名下所有项目；0 = 仅全局库；传项目ID = 只看该项目", required = false) Long projectId,
            @ToolParam(description = "可选。默认 0.50（0.45 更宽松 / 0.55 更严）。区间 [0.30,0.95]，**超区间直接拒绝并回显收到的值**", required = false) Double threshold,
            @ToolParam(description = "可选。最多返回/入队几条，默认 20，上限 50", required = false) Integer limit,
            @ToolParam(description = "可选。单篇最多几条候选，默认 2（防模板族刷屏）。⚠️ ①层（疑似字面重复）豁免，②层及以下生效", required = false) Integer maxPerDoc,
            @ToolParam(description = "可选。进度/日志层最多展示几条，默认 5，传 0=不限", required = false) Integer progressSample,
            @ToolParam(description = "可选。传 true 才让③层（进度/日志类）一起入队。"
                    + "③层是同一件事的分阶段记录，按设计不该判重 —— 入队只会每轮多出几条必须用 distinct 清掉的候选，"
                    + "而 distinct 是永久封口的不可逆动作。不传时③层只打分、只展示、不落队列", required = false) Boolean enqueueProgress,
            @ToolParam(description = "可选。**正文行重合门槛**：按行比较时至少几条行完全重合才进候选，默认 1；"
                    + "**传 0 = 关闭这道门（回退旧行为，0 行重合也照旧入队）**。"
                    + "⚠️ 行数太少回退 2-gram 的对不受此门约束（它们的行重合恒为 0，但正文有重合）。"
                    + "负数直接拒绝并回显收到的值", required = false) Integer minSharedLines,
            @ToolParam(description = "可选。true = 只算不入队，用来先看清单。默认 false", required = false) Boolean dryRun,
            @ToolParam(description = "可选。**谁改的**——与 authorName 等价", required = false) String updatedName) {
        try {
            AiAssistant assistant = getCaller();
            if (assistant != null && !Boolean.TRUE.equals(assistant.getCanUpdate())) {
                return "❌ 权限不足：该API Key没有编辑经验的权限";
            }
            boolean dry = Boolean.TRUE.equals(dryRun);
            boolean enqProgress = Boolean.TRUE.equals(enqueueProgress);
            String operator = resolveAuthor(updatedName, assistant);
            SimilarityService.ScanResult r = similarityService.scan(projectId, threshold, limit, maxPerDoc,
                    progressSample, dry, operator, enqProgress, minSharedLines);
            if (!r.ok()) {
                return "❌ " + r.summary();
            }
            StringBuilder sb = new StringBuilder();
            sb.append(dry ? "🔍 扫描完成（dryRun：**没有入队任何东西**）\n\n" : "🗂 扫描完成并已入队\n\n");
            sb.append("**范围**: ").append(r.summary()).append("\n");
            // 2026-10-26：原来 operator 只传给 service，回执不显示 —— 谁扫的说不清
            sb.append("**操作人**: ").append(dry ? operator + "（dryRun 不入队）" : operator).append("\n");
            // ⚠️ 2026-10-26 12:0x 阁主：**没有时间戳的计数，分不清「数据没变」还是「缓存过期」**。
            //    他今天为此做了两次错误判断 —— 索引有 3 分钟 TTL，而回执从来不说这个数是什么时候的。
            //    规则：凡是来自索引的计数，必须把索引生成时刻与 TTL 一起印出来。
            if (r.indexBuiltAt() != null && !r.indexBuiltAt().isBlank()) {
                sb.append("**数据时间**: 索引生成于 ").append(r.indexBuiltAt())
                  .append("（距今 ").append(r.indexAgeSeconds()).append(" 秒；索引 TTL ")
                  .append(SimilarityService.INDEX_TTL_SECONDS)
                  .append(" 秒 —— 刚改过文档时，这里最多可能差 ")
                  .append(SimilarityService.INDEX_TTL_SECONDS).append(" 秒）\n");
            }
            // ⚠️ 2026-10-26 13:0x 阁主第 4 条：他**逐条读完 12 条才发现是 0 条需要处理**。
            //    头行给出「需要你判几条」，就能省掉整次通读。
            int need = 0, alreadyJudged = 0, progressOnly = 0;
            for (SimilarityService.Row row : r.rows()) {
                if (row.kind() == SimilarityScorer.Kind.PROGRESS) { progressOnly++; continue; }
                if (row.queueState() != null && row.queueState().startsWith("**已判")) { alreadyJudged++; continue; }
                need++;
            }
            // ⚠️ 2026-10-26 13:2x 阁主：dryRun 下印「本次需要你判 5 对」是**不成立**的 ——
            //    dryRun 不入队，就没有「待判」这回事（同一份回执里还写着「未入队（dryRun）」）。
            //    两种情境分开写：入队了才有「待判」，没入队只能说「扫出几条值得看」。
            sb.append(triageLine(dry, need, alreadyJudged, progressOnly)).append("\n");
            // ⚠️ 2026-11-11 阁主派单 B：判过的对不再进清单。它们的「消失」必须有一句交代，
            //    否则「超阈值 368、清单 20 条」会被读成「大部分被 limit 截断」，而真实原因常常是存量已判。
            int judgedTotal = 0;
            for (SimilarityService.TierDetail d : r.tierDetail().values()) judgedTotal += d.droppedJudged();
            if (judgedTotal > 0) {
                sb.append("**已判过**: 本轮另有 ").append(judgedTotal)
                  .append(" 对已判过（DISTINCT / SUPERSEDED / SKIPPED），**不进清单、不占名额**")
                  .append("（分层明细见下方「限流归因」）\n");
            }
            int minSharedEcho = (minSharedLines == null)
                    ? SimilarityService.DEFAULT_MIN_SHARED_LINES : minSharedLines;
            sb.append("**入队门槛**: ")
              .append(minSharedEcho <= 0
                      ? "**已关闭**（minSharedLines=0 = 旧行为，正文 0 行重合也照旧入队）"
                      : "正文按行比较时至少 " + minSharedEcho + " 行完全重合才进候选（minSharedLines="
                        + minSharedEcho + "；传 0 关闭）")
              .append("｜本次拦下 ").append(r.gatedBySharedLines())
              .append(" 对（行数太少回退 2-gram 的对不受此门约束）\n");
            sb.append("**阈值**: ").append(SimilarityScorer.Score.fmt(r.threshold()))
              .append("｜**比较对数**: ").append(r.pairsCompared())
              .append("｜**超阈值**: ").append(r.aboveThreshold());
            // 2026-10-26 13:2x 阁主：同一件事两个名字（头行「限流丢掉」/ 归因「limit 截断」），
                // 而「限流」在别处专指 per-doc 限流 → 统一成「limit 截断」。
                if (r.droppedByCap() > 0) sb.append("｜**limit 截断**: ").append(r.droppedByCap());
            sb.append("\n");
            if (!dry) {
                sb.append("**入队结果**: 新建 ").append(r.enqueued())
                  .append(" 对｜已在队列(待判断) ").append(r.alreadyPending())
                  .append(" 对｜已判过(不重复入队) ").append(r.alreadyResolved()).append(" 对\n");
            }
            if (r.rows().isEmpty()) {
                if (r.gatedBySharedLines() > 0) {
                    // 门槛刚上线时这是常见形态：超阈值不少，但全是正文 0 行重合的
                    sb.append("\n📭 超阈值 ").append(r.aboveThreshold()).append(" 对，但全部被入队门槛拦下（正文行重合不足 ")
                      .append(minSharedEcho).append(" 行，没有真的内容重合）。要看它们传 minSharedLines=0 重跑一次。\n");
                } else {
                    sb.append("\n📭 没有超过阈值的候选对。阈值可以调到 0.45 再看一次，或用 add_review_candidate 手工入队。\n");
                }
            } else {
                // 分层统计必须用**全量**（r.tierStats，限流前的全部超阈值对），不能用 r.rows()。
                // 阁主实测发现：按 rows 数个数时，limit=12 下进度层 36 对整层消失，
                // 同一阈值改个 limit 结论就变 → 下个人会以为降级失效。
                int dup = r.tierStats().getOrDefault(SimilarityScorer.Kind.DUPLICATE, 0);
                int ser = r.tierStats().getOrDefault(SimilarityScorer.Kind.SERIES, 0);
                int unk = r.tierStats().getOrDefault(SimilarityScorer.Kind.UNKNOWN, 0);
                int prog = r.tierStats().getOrDefault(SimilarityScorer.Kind.PROGRESS, 0);

                sb.append("\n**按性质分层**（统计的是**全部超阈值对**，与下面展示几条无关）：")
                  .append("疑似字面重复 ").append(dup).append(" 对")
                  .append("｜同话题演进链 ").append(ser).append(" 对");
                if (prog > 0) {
                    sb.append("｜进度/日志记录 ").append(prog).append(" 对（一般不用判）");
                }
                if (unk > 0) sb.append("｜信息不足 ").append(unk).append(" 对");

                // 2026-10-29 越哥 P1-5：限流原因透明 —— TierDetail 五个数拼一行，**每层独立**。
                // 上面那句「按性质分层」只说各层有多少对，不说为什么没全展示；
                // 而「展示 5 条」的成因可能是抽样上限、per-doc、limit 三者之一，调参方向完全不同。
                // 整块渲染已抽成 tierLimitBlock()，与展示行完全解耦 —— 这样「整层被挤空时
                // 原因行仍在」这件事才测得了（#751 第 3 条）。
                sb.append("\n").append(tierLimitBlock(r.tierDetail(), r.maxPerDoc()));

                // 2026-10-29 越哥 P1-4：③层默认不入队，必须单列说清楚。
                //
                // ⚠️ 口径坑（越哥 2026-10-29 10:xx 指出，同一类错我犯过第二次）：
                //    原来写「③层进度类未入队 17 对」—— 17 是**超阈值总数**，其中 15 对本来就因
                //    判过 distinct 永久出队，跟这个开关毫无关系。真正被拦下的只有展示出来的那 2 对。
                //    把 17 写成「未入队」会让人以为 P1-4 挡了 17 对，实际挡了 2 对。
                //    现在按「总数 = 已判过 + 被截断 + 本次拦下」拆开，每项都标明它与开关的关系。
                if (prog > 0) {
                    SimilarityService.TierDetail pd = r.tierDetail().get(SimilarityScorer.Kind.PROGRESS);
                    int pJudged = pd == null ? 0 : pd.droppedJudged();
                    int pShown = pd == null ? 0 : pd.shown();
                    int pUndecided = Math.max(0, prog - pJudged);          // 客观事实：③层里没判过的对数
                    int pNotReached = Math.max(0, pUndecided - pShown);    // 没判过、但本次没进清单
                    sb.append("\n**③层进度类**: 超阈值 ").append(prog).append(" 对 = 已判过 ")
                      .append(pJudged).append(" 对（distinct 永久出队，**与开关无关**）")
                      .append(" + 未判过 ").append(pUndecided).append(" 对");
                    if (enqProgress) {
                        sb.append("\n　本次 ").append(pShown).append(" 对按 `enqueueProgress=true` 入队（与旧行为一致）");
                    } else if (pShown > 0) {
                        sb.append("\n　本次 **拦下 ").append(pShown).append(" 对**")
                          .append("（`enqueueProgress=false`，这 ").append(pShown)
                          .append(" 对才是本开关挡下的）");
                    } else {
                        sb.append("\n　本次 **0 对走到入队判定**")
                          .append("（③层一条都没进清单，`enqueueProgress=false` 在这里没起作用）");
                    }
                    if (pNotReached > 0) {
                        sb.append("；另有 ").append(pNotReached)
                          .append(" 对未判的③层没进清单（被 limit / 抽样截断，没到入队这一步）");
                    }
                    sb.append("\n　③层是同一件事的分阶段记录，按设计不该判重；要恢复旧行为传 `enqueueProgress=true`\n");
                }

                // 本次只展示前 N 条：说清「分层统计」和「展示清单」是两个口径，
                // 否则改 limit 会让人以为分层结论变了
                // ⚠️ 2026-10-26 03:01 越哥实测：这里原来写「本次展示前 22 条（limit=22）」——
                //    **把展示数当成了 limit**。他传 50，回执说 22，无法判断是参数被改写还是显示错了。
                //    红线「回执必须自证值」：摆真实数值，不要改成另一个数值。
                sb.append("\n　**本次展示 ").append(r.rows().size()).append(" 条 / 你传的 limit=")
                  .append(r.limit()).append("**");
                boolean filled = r.filledPerDoc() > 0;
                int gap = Math.max(0, r.limit() - r.rows().size());
                // ⚠️ 2026-10-26 03:1x 阁主实测：差额为 0 时这句仍然印「（差额 0 条 …）」
                //    —— 没差额还让人去找差额，等于凭空造一个不存在的东西。
                //    规则：**只在真有差额时渲染解释**；有差额时也不许含糊其辞。
                int bySample = 0, byPerDoc = 0, byOmitted = 0, byLimit = 0, bySameTopic = 0, byJudged = 0;
                // 门槛拦下的那批仍然算「超阈值」，必须进归因求和，否则未展示数永远对不上
                int byGated = r.gatedBySharedLines();
                for (SimilarityService.TierDetail d : r.tierDetail().values()) {
                    bySample += d.droppedBySample();
                    byPerDoc += d.droppedPerDoc();
                    byOmitted += d.droppedRedundant();
                    byLimit += d.droppedByLimit();
                    bySameTopic += d.droppedSameTopic();
                    byJudged += d.droppedJudged();
                }
                int notShown = Math.max(0, r.aboveThreshold() - r.rows().size());
                // ⚠️ 2026-10-26 12:1x 阁主：「逐项归因」只写了一半 ——
                //    印「另有 11 对未展示，逐项归因：③层抽样省 5 条」，而 11 = 5 + 6，
                //    那 6 条同话题跳过的说明就在下一段③层标题里，**数据是有的，只是没拼进头行**。
                //    「逐项归因」四个字等于承诺把所有项都列出来，只列一项就把剩下 6 条变成未知数。
                //    → 归因文本抽成纯函数 causesText()：**加总必须等于未展示数**，对不上就自己报缺陷。
                if (gap > 0) {
                    // ⚠️ 2026-10-26 10:5x 阁主第 4 条（与「编解释」同源）：这里原来不管实际发生什么，
                    //    都并列写「③层抽样 / per-doc 限流」。这轮差额 2 条**全来自③层抽样上限**，
                    //    per-doc 一次都没挤掉（回执自己还写着「已补回」），却仍被列成原因 ——
                    //    等于把「可能的因」写成「已经发生的因」。
                    //    规则：**只摆按真实计数非零的因**，逐项写出条数。
                    //
                    // ⚠️ 2026-10-26 10:4x 自查又发现同一处还有第二个病：「差额」和「省略条数」是两个数。
                    //    实测 limit=22、超阈值 23 对、展示 12 对 → 差额 10 格，而③层抽样省了 11 条，
                    //    印成「差额 10 条 = 抽样省 11 条」自相矛盾（超阈值 23 > limit 22，有一条本来也放不下）。
                    //    → **两个数分开摆、各自归因**：差额 N 格（limit 没被用满）／另有 M 对未展示（逐项给因）。
                    sb.append("（差额 ").append(gap).append(" 格没被用满");
                    if (notShown == 0) {
                        sb.append("：超阈值对只有 ").append(r.aboveThreshold()).append(" 对，比 limit 少 ")
                          .append(gap).append(" 条 —— **不是限流，是本来就没那么多**");
                    } else {
                        sb.append("；**另有 ").append(notShown).append(" 对未展示**，逐项归因：")
                          .append(causesText(notShown, bySample, byPerDoc, byOmitted, byLimit, bySameTopic,
                                  byJudged, byGated, r.maxPerDoc(), SimilarityService.DEFAULT_PROGRESS_SAMPLE));
                    }
                    sb.append("）");
                }
                if (filled) {
                    // ⚠️ 2026-10-26 11:0x 阁主实测：范围行说「limit=22，已用满」，紧接着这句又写
                    //    「因**名额有余**已补回清单」—— 同一个回执里一边说满了、一边说还有余。
                    //    两句字面都对（补位发生在填满**之前**），但并排读就是自相矛盾。
                    //    规则：满员时说清「补回发生在填满之前」，不许再用「名额有余」。
                    if (gap > 0) {
                        sb.append("；另有 ").append(r.filledPerDoc())
                          .append(" 条原本被 per-doc 挤掉，因名额有余**已补回清单**");
                    } else {
                        sb.append("；其中 ").append(r.filledPerDoc())
                          .append(" 条是**补位补回的**（原被 per-doc 挤掉，在名额填满之前补回）");
                    }
                }
                // ⚠️ 2026-10-26 11:3x 阁主：头行算术不闭合 ——「165 = 展示 20 + limit 截断 139 + 同族略去 6」，
                //    但头行只报了 droppedByCap（它**不含「已略去」**），那 6 条没交代。
                //    规则：**满员时也要把未展示总数与各项之和摆出来，且必须能对上**。
                if (gap == 0 && notShown > 0) {
                    sb.append("；**未展示 ").append(notShown).append(" 对 = ")
                      .append(shortCausesText(notShown, bySample, byPerDoc, byOmitted, byLimit, bySameTopic,
                              byJudged, byGated))
                      .append("**（分层数字不受 limit 影响，调大 limit 只是让更多条进清单）");
                }
                sb.append("\n\n");
                int i = 1;
                int groupNo = 0;
                SimilarityScorer.Kind last = null;
                // 同一母档的多个「并回母档再清子页」按母档归组：它们是串行依赖，不是多个独立操作。
                // 先按**全部展示行**归组（家族可能跨层），组头只渲染一次，避免同一家族说四遍。
                java.util.Map<Long, java.util.List<SimilarityService.Row>> mergeFamilies =
                        new java.util.LinkedHashMap<>();
                for (SimilarityService.Row r2 : r.rows()) {
                    if (r2.motherId() == null) continue;
                    mergeFamilies.computeIfAbsent(r2.motherId(), x -> new java.util.ArrayList<>()).add(r2);
                }
                java.util.Set<Long> planShown = new java.util.HashSet<>();
                for (SimilarityService.Row row : r.rows()) {
                    // 层与层之间插一条分隔，让「分数低的反而更重要」这件事无法被忽略
                    if (row.kind() != last) {
                        if (last != null) sb.append("---\n");
                        groupNo++;
                        sb.append("**【").append(circle(groupNo)).append(" ")
                          .append(kindGroupTitle(row.kind())).append("】**");
                        // 本层动作分布：直接从已渲染的 advice 里读回标签，
                        // 这样组头与条目不可能对不上（另算一遍迟早漂移）
                        java.util.Map<String, Integer> actTally = new java.util.LinkedHashMap<>();
                        int judgedShown = 0;
                        for (SimilarityService.Row r2 : r.rows()) {
                            if (r2.kind() != row.kind()) continue;
                            // 已判过的对不再给动作（2026-10-26 13:2x 阁主：上一句「不必再判」、
                            // 下一句又给「动作：判 distinct」，自相矛盾），也不计入动作分布
                            if (isJudged(r2)) { judgedShown++; continue; }
                            String tag = actionTag(r2.advice());
                            if (!tag.isEmpty()) actTally.merge(tag, 1, Integer::sum);
                        }
                        if (!actTally.isEmpty() || judgedShown > 0) {
                            sb.append("（本层动作：");
                            int ai = 0;
                            for (var ae : actTally.entrySet()) {
                                if (ai++ > 0) sb.append("、");
                                sb.append(ae.getKey()).append(" ×").append(ae.getValue());
                            }
                            if (actTally.isEmpty()) sb.append("无");
                            if (judgedShown > 0) {
                                sb.append("；另 ").append(judgedShown).append(" 条**已判过、不再给动作**");
                            }
                            sb.append("）");
                        }
                        // 每层自证：全库 X 对 / 本次展示 Y 条 / 挤掉哪些（2026-10-26 02:1x 越哥要求）
                        SimilarityScorer.Kind kk = row.kind() == null
                                ? SimilarityScorer.Kind.UNKNOWN : row.kind();
                        SimilarityService.TierDetail d = r.tierDetail().get(kk);
                        if (d != null) {
                            sb.append("（全库 ").append(d.above()).append(" 对｜本次展示 ")
                              .append(d.shown()).append(" 条");
                            if (d.droppedPerDoc() > 0) {
                                sb.append("｜另有 ").append(d.droppedPerDoc())
                                  .append(" 对被「单篇最多 ").append(r.maxPerDoc()).append(" 条候选」的限流挤掉");
                                if (!d.droppedSamples().isEmpty()) {
                                    sb.append("：").append(String.join("、", d.droppedSamples()));
                                }
                            }
                            if (d.droppedRedundant() > 0) {
                                sb.append("｜另有 ").append(d.droppedRedundant())
                                  .append(" 条是**同族文档之间的交叉对，已略去**");
                                // ⚠️ 2026-10-26 10:4x 阁主：光报「已略去 6 条」，动手前就无从回头核对
                                //    「这几份原本是不是并列记录」。**列出 ID 备查（一行字，不占名额）**。
                                if (!d.omittedSamples().isEmpty()) {
                                    sb.append("：").append(String.join("、", d.omittedSamples()));
                                }
                                // ⚠️ 2026-10-26 11:1x 自查：原来写「再列一遍只会和①层的『清掉这一份』打架」——
                                //    ①层加上编号排除后那 4 对全是 distinct，**这句话描述的因已经不存在了**
                                //    （留着一句解释旧行为的理由，比没有理由更容易骗过下一个人）。
                                //    现在只说事实：①层已逐对判过 → 这里重复没新信息；要核对就按 ID 查原文。
                                sb.append("（这几份与母档的关系已在①层逐对给过结论，这里再列一遍没有新信息；")
                                  .append("要回头核它们原本是不是并列记录，按上面 ID 查原文）");
                            }
                            if (d.droppedBySample() > 0) {
                                sb.append("｜另有 ").append(d.droppedBySample())
                                  .append(" 条进度类默认抽样省略（要全看传 progressSample=0）");
                            }
                            // ⚠️ 2026-10-26 12:0x 阁主：③层 5 个名额被同一个话题占满
                            //    （#470/#477/#478/#464/#471/#479 全是 list_recent_memories 的同族记录）。
                            //    抽样已改成**按话题去重**，这里必须把跳过的条数写出来 —— 否则
                            //    「全库 16 对、只展示 4 条」又会被读成「名额不够」。
                            if (d.droppedSameTopic() > 0) {
                                sb.append("｜另有 ").append(d.droppedSameTopic())
                                  .append(" 条因**与已抽中的是同话题**跳过（同一话题只留分数最高的那条，")
                                  .append("腾出的名额让给别的话题）");
                            }
                            // ⚠️ 2026-10-26 13:0x 阁主第 3 条：已判过的对占着抽样名额
                            //    （#470↔#477 是候选 #13，已判 DISTINCT 却还占 1 个名额）。
                            //    现在这类对不占名额，这里必须报出来 —— 否则「全库 16 对、展示 5 条」的差额对不上。
                            if (d.droppedJudged() > 0) {
                                sb.append("｜另有 ").append(d.droppedJudged())
                                  .append(" 条**已判过、不占名额**（判过的结论已落库，再排一遍不产生新信息）");
                            }
                            if (d.droppedByLimit() > 0) {
                                sb.append("｜另有 ").append(d.droppedByLimit()).append(" 对被 limit 截断");
                            }
                            sb.append("）");
                        }
                        sb.append("\n");
                        // 组头把「同族串行依赖」摊开（2026-10-26 10:4x 阁主第 1 条）
                        for (SimilarityService.Row r2 : r.rows()) {
                            if (r2.kind() != row.kind() || r2.motherId() == null) continue;
                            java.util.List<SimilarityService.Row> fam = mergeFamilies.get(r2.motherId());
                            if (fam == null || fam.size() < 2) continue;
                            if (!planShown.add(r2.motherId())) continue;   // 同一个母档只说一次
                            sb.append(mergeFamilyPlan(r2.motherId(), fam));
                        }
                        last = row.kind();
                    }
                    sb.append(i++).append(". ").append(SimilarityScorer.Score.fmt(row.score()))
                      .append("｜#").append(row.docA()).append("「").append(abbreviate(row.titleA(), 34))
                      .append("」 ↔ #").append(row.docB()).append("「").append(abbreviate(row.titleB(), 34)).append("」");
                    if (row.queueState() != null) sb.append("　→ ").append(row.queueState());
                    String advice = isJudged(row)
                            // 已判过的对：动作已经执行过，再渲染一遍等于让他重复劳动
                            ? SimilarityScorer.withoutAction(row.advice())
                            : row.advice();
                    sb.append("\n   ").append(kindLabel(row.kind(), row.sharedLines(), row.coverage(), advice)).append("\n");
                    // 独有行样例（2026-10-26 12:0x 阁主）：只写「独有 8 行」等于逼他读两篇全文。
                    // 给前 3 行片段，他就能先判断「是多了一段环境说明」还是「真写了别的东西」。
                    if (row.aOnlySample() != null && !row.aOnlySample().isBlank()) {
                        sb.append("　　A 侧独有行样例（前 3 行）：").append(row.aOnlySample()).append("\n");
                    }
                    if (row.bOnlySample() != null && !row.bOnlySample().isBlank()) {
                        sb.append("　　B 侧独有行样例（前 3 行）：").append(row.bOnlySample()).append("\n");
                    }
                    sb.append("   ").append(row.reason()).append("\n");
                }
            }
            sb.append("\n---\n");
            sb.append("· **分层优先于 limit**：清单按「层先、分数后」排，截断只可能发生在最后一层；")
              .append("①层（对数最少的那层）**不受「单篇最多 N 条候选」的限流约束** —— ")
              .append("否则一篇母文档命中多个子页的族会被砍到只剩 2 条，看起来像「分层没生效」")
              .append("（2026-10-26 02:1x 越哥实测：①层统计 6 对、清单只有 4 条，根因就是这个）\n");
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
        } catch (LinkageError le) {
            // ⚠️ 2026-10-27 白虎：NoClassDefFoundError / ExceptionInInitializerError 都属 Error，
            //    不会被 catch(Exception) 捕到 —— 因此 #712 有「日志里查不到 FAILED、回执只有裸类名」两个现象。
            //    这里单独兜住 LinkageError 族，把 Caused by 落进 app.log，回执也带首层原因。
            //    LinkageError 不做重试：类加载失败是确定性的，重试只会重复报错。
            log.error("ExpHubTools.scanSimilarDocs: LINKAGE FAILED", le);
            callLogService.incrementFailCalls();
            Throwable root = le;
            while (root.getCause() != null && root.getCause() != root) root = root.getCause();
            return "扫描失败（类加载/链接错误）: " + le.getClass().getName() + " - " + le.getMessage()
                    + (root == le ? "" : "｜首层原因: " + root.getClass().getName() + " - " + root.getMessage())
                    + "｜完整堆栈已落 app.log（关键字 LINKAGE FAILED）";
        } catch (Exception e) {
            log.error("ExpHubTools.scanSimilarDocs: FAILED", e);
            callLogService.incrementFailCalls();
            return "扫描失败: " + e.getClass().getName() + " - " + e.getMessage();
        }
    }

    // ==================== 项目空间工具（2026-09-20 新增）====================

    @Tool(name = "create_project", description = "创建一个项目空间。项目归集该项目的 AGENTS.md、进度与专属经验，默认隔离、不进全局搜索。")
    public String createProject(
            @ToolParam(description = "项目名称") String name,
            @ToolParam(description = "项目编码，英文唯一标识") String projectCode,
            @ToolParam(description = "项目别名，逗号分隔，用于模糊搜索。可留空", required = false) String aliases,
            @ToolParam(description = "项目描述，可留空", required = false) String description,
            @ToolParam(description = "默认测试命令，工作流模板用。可留空", required = false) String defaultTestCmd,
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

    /**
     * 项目概览聚合读（2026-11-05 · #1220 第④条）。
     *
     * <p>原来回答「这个项目现在什么样」要连调 5 次：get_project（2ms）+ get_project_agents（6ms）
     * + list_project_experiences（85ms）+ get_project_progress（4ms）+ check_my_todos（18ms）。
     * 前四个加起来不到 100ms，纯粹是被拆成 5 个端点 —— 近 7 天这组调用合计 586 次。
     *
     * <p>这里只做「聚合读」：一次返回概要，<b>不复制细节</b>。
     * 章节只给标题清单（不给正文）、经验只给最近 3 条标题、进度只给最近 1 条。
     * 要正文仍然去调各自的工具 —— 否则这个接口自己就会长成一篇读不完的长文。
     */
    @Tool(name = "get_project_brief", description = "一次拿全项目概览：元信息 + AGENTS.md 章节清单 + 经验条数与最近 3 条 + 最近 1 条进度 + 未完成待办数。"
            + "要正文细节再分别调 get_project_agents / list_project_experiences / get_project_progress / check_my_todos。")
    public String getProjectBrief(
            @ToolParam(description = "项目ID（用 list_projects 查）") Long projectId) {
        try {
            if (projectId == null) {
                return "❌ 必须传 projectId。不知道 ID 就先 list_projects。";
            }
            Project p = projectService.getProject(projectId);
            if (p == null) {
                return "❌ 未找到项目ID " + projectId + "，或你没有访问权限。可用 list_projects 查看你有权访问的项目。";
            }

            StringBuilder sb = new StringBuilder();
            sb.append("# ").append(p.getName()).append("（#").append(p.getId()).append("）\n\n");
            sb.append("**编码**: ").append(p.getProjectCode())
              .append(" ｜ **状态**: ").append(p.getStatus()).append("\n");
            if (p.getAliases() != null && !p.getAliases().isBlank()) {
                sb.append("**别名**: ").append(abbreviate(p.getAliases(), 100)).append("\n");
            }
            if (p.getDescription() != null && !p.getDescription().isBlank()) {
                sb.append("**描述**: ").append(abbreviate(p.getDescription(), 120)).append("\n");
            }
            if (p.getDefaultTestCmd() != null && !p.getDefaultTestCmd().isBlank()) {
                sb.append("**默认测试命令**: ").append(abbreviate(p.getDefaultTestCmd(), 100)).append("\n");
            }
            sb.append("\n");

            // 一次查询拿项目内全部「读端可见」文档（已排除待删），再在内存里按角色分类。
            // 分类而不是多次查询：这些文档本来就在同一张表、同一个 project_id 下。
            List<Doc> all = projectService.listVisibleProjectDocs(projectId, null);
            int mainDoc = 0;
            java.util.List<Doc> chapters = new ArrayList<>();
            java.util.List<Doc> experiences = new ArrayList<>();
            Doc lastProgress = null;
            for (Doc d : all) {
                String role = d.getDocRole();
                if ("MAIN".equals(role)) {
                    mainDoc++;
                } else if ("CHAPTER".equals(role)) {
                    chapters.add(d);
                } else if ("project_progress".equals(d.getTemplateType())) {
                    // all 已按 updated_at 倒序，第一条即最新
                    if (lastProgress == null) lastProgress = d;
                } else if ("todo_list".equals(d.getTemplateType())) {
                    // 待办单独计数，不混进「经验」
                } else {
                    experiences.add(d);
                }
            }
            // ⚠️ 2026-11-05 实测踩到：all 是按 updated_at 倒序的，直接拿来列章节会出现
            //    「15 高频踩坑速查｜1 项目概述｜2 …」—— 最近改过的那章跳到第一位。
            //    章节清单必须按 sort_order + 章号排，这跟「最近更新」是两个维度。
            // 2026-11-05 二次修正：章号为空的脏章节（#1164/#1184 那种）要**排到最后**。
            //    只按 sort_order 排的话，它们 sort_order 往往也是 0，会和正常章节混在中间，
            //    显示成「null xxx」，看着像系统坏了。
            chapters.sort(java.util.Comparator
                    .comparingInt((Doc c) -> chapterNoValue(c.getChapterNo()) == Integer.MAX_VALUE ? 1 : 0)
                    .thenComparingInt(c -> c.getSortOrder() == null ? Integer.MAX_VALUE : c.getSortOrder())
                    .thenComparingInt(c -> chapterNoValue(c.getChapterNo())));

            // ===== AGENTS.md =====
            sb.append("## AGENTS.md\n");
            if (mainDoc == 0 && chapters.isEmpty()) {
                sb.append("未建立（可用 update_project_agents_main / update_project_agents_chapter 建立）\n");
            } else {
                long always = 0;
                for (Doc c : chapters) {
                    if ("ALWAYS".equalsIgnoreCase(c.getLoadStrategy())) always++;
                }
                sb.append("MAIN 索引: ").append(mainDoc > 0 ? "有" : "**缺失**")
                  .append(" ｜ 章节 ").append(chapters.size()).append(" 章");
                if (!chapters.isEmpty()) {
                    sb.append("（ALWAYS ").append(always).append(" / ON_DEMAND ")
                      .append(chapters.size() - always).append("）");
                }
                sb.append("\n");
                if (!chapters.isEmpty()) {
                    sb.append("章节: ");
                    int shown = 0;
                    for (Doc c : chapters) {
                        if (shown >= 12) { sb.append(" …等 ").append(chapters.size()).append(" 章"); break; }
                        if (shown > 0) sb.append("｜");
                        sb.append(chapterNoLabel(c.getChapterNo())).append(" ").append(c.getChapterTitle());
                        shown++;
                    }
                    sb.append("\n");
                }
            }
            sb.append("\n");

            // ===== 经验 =====
            long pendingDelete = projectService.countPendingDeleteInProject(projectId, null);
            sb.append("## 经验（不含 AGENTS 与进度）\n");
            sb.append("共 ").append(experiences.size()).append(" 条");
            if (pendingDelete > 0) {
                sb.append("｜另有 ").append(pendingDelete).append(" 条已标待删（读端不计入）");
            }
            sb.append("\n");
            if (!experiences.isEmpty()) {
                sb.append("最近 3 条:\n");
                int shown = 0;
                for (Doc d : experiences) {
                    if (shown >= 3) break;
                    sb.append("- #").append(d.getId()).append(" ").append(abbreviate(d.getTitle(), 60))
                      .append("（").append(d.getUpdatedAt() != null ? d.getUpdatedAt().toLocalDate() : "-").append("）\n");
                    shown++;
                }
            }
            sb.append("\n");

            // ===== 最近进度 =====
            sb.append("## 最近进度\n");
            if (lastProgress == null) {
                sb.append("无（用 append_project_progress 记录第一条）\n");
            } else {
                sb.append("- #").append(lastProgress.getId()).append(" ")
                  .append(abbreviate(lastProgress.getTitle(), 60))
                  .append("（").append(lastProgress.getUpdatedAt() != null
                        ? lastProgress.getUpdatedAt().toLocalDate() : "-").append("）\n");
            }
            sb.append("\n");

            // ===== 待办 =====
            sb.append("## 待办\n");
            int todoCount = 0;
            try {
                todoCount = docService.listByTemplateType("todo_list", projectId, "ACTIVE").size();
            } catch (Exception e) {
                log.debug("getProjectBrief: 待办计数失败 project={}, {}", projectId, e.getMessage());
            }
            sb.append("未完成 ").append(todoCount).append(" 条\n");

            sb.append("\n---\n");
            sb.append("细节按需取：get_project_agents(projectId=").append(projectId).append(")")
              .append(" / list_project_experiences(projectId=").append(projectId).append(")")
              .append(" / get_project_progress(projectId=").append(projectId).append(")")
              .append(" / check_my_todos(projectId=").append(projectId).append(")");
            return sb.toString();
        } catch (Exception e) {
            log.error("ExpHubTools.getProjectBrief: FAILED", e);
            return "查询失败: " + e.getMessage();
        }
    }

    @Tool(name = "update_project", description = "更新项目信息（名称/别名/描述/默认测试命令/默认约束），不改的字段留空。")
    public String updateProject(
            @ToolParam(description = "项目ID") Long projectId,
            @ToolParam(description = "新名称", required = false) String name,
            @ToolParam(description = "新别名", required = false) String aliases,
            @ToolParam(description = "新描述", required = false) String description,
            @ToolParam(description = "默认测试命令", required = false) String defaultTestCmd,
            @ToolParam(description = "默认约束", required = false) String defaultConstraints) {
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

    /**
     * 删除项目（2026-10-26 13:0x 阁主第 7 条：MCP 有 list/create/update，**能创建不能删除**）。
     *
     * <p>两条硬约束，都是「删错了没法撤」的对策：
     * <ol>
     *   <li><b>必须回填项目名</b> —— 只给 ID 的误删无法发现（ID 是数字，没人记得住几号叫什么）。</li>
     *   <li><b>项目里还有文档就不删</b> —— 不给「连文档一起删」的开关。要删先把文档移走
     *       （`update_experience(projectId=0)` 移回全局 / `promote_to_global`），删文档走
     *       `mark_for_deletion` + 网页端确认，这两步都是有意为之的独立动作。</li>
     * </ol>
     */
    @Tool(name = "delete_project", description = "删除一个项目（**只删项目本身，不删任何文档**）。"
            + "两道保险：①必须用 confirmName 回填项目名（只传 ID 不给删）；②项目里还有文档时拒绝删除，"
            + "要先把文档移走（update_experience(projectId=0)）再删。删完会回读项目列表自证。")
    public String deleteProject(
            @ToolParam(description = "项目ID") Long projectId,
            @ToolParam(description = "回填项目名（必须与现有名称完全一致）—— 防「只给 ID 时误删」") String confirmName,
            @ToolParam(description = "可选。**谁改的**——与 authorName 等价", required = false) String updatedName) {
        try {
            AiAssistant assistant = getCaller();
            if (assistant != null && !Boolean.TRUE.equals(assistant.getCanUpdate())) {
                return "❌ 权限不足：该API Key没有编辑经验的权限";
            }
            Project p = projectService.getProject(projectId);
            if (p == null) {
                return "❌ 未找到项目ID " + projectId + "，或你没有访问权限。可用 list_projects 查看你的项目。";
            }
            String must = p.getName() == null ? "" : p.getName().trim();
            if (confirmName == null || !must.equals(confirmName.trim())) {
                return "❌ 未删除：confirmName 与项目名不一致。\n\n"
                     + "该项目实际名称：**「" + must + "」**\n"
                     + "请原样回填：delete_project(projectId=" + projectId + ", confirmName=\""
                     + must + "\")\n\n"
                     + "（要求回填名字而不是只给 ID，是因为 ID 是数字，误删一次无法发现更无法撤回）";
            }
            int docs = projectService.listProjectDocs(projectId, null).size();
            if (docs > 0) {
                return "❌ 未删除：项目「" + must + "」下还有 **" + docs + " 篇文档**。\n\n"
                     + "删除项目**不会**连文档一起删，也不会把它们移走 —— 所以这里直接不放行。\n"
                     + "先把文档移出去（任选）：\n"
                     + "· `update_experience(id=?, projectId=0)` → 移回全局\n"
                     + "· `promote_to_global(id=?)` → 提升为全局经验\n"
                     + "全部移走后重新调用本工具。";
            }
            String operator = resolveAuthor(updatedName, assistant);
            int deleted = projectService.deleteProject(projectId);
            if (deleted == 0) {
                return "❌ 删除失败：服务层返回 0（可能不是你名下的项目）。未做任何改动。";
            }
            // 自证：回读项目列表，确认它真的不在了
            boolean still = false;
            for (Project x : projectService.listProjects()) {
                if (projectId.equals(x.getId())) { still = true; break; }
            }
            StringBuilder sb = new StringBuilder();
            sb.append(still ? "⚠️ 回读仍有该项目，删除可能未生效" : "✅ 项目已删除").append("\n\n");
            sb.append("**ID**: ").append(projectId).append("\n");
            sb.append("**名称**: ").append(must).append("\n");
            sb.append("**其下文档**: 0 篇（删除前已确认，没有文档被删或改归属）\n");
            sb.append("**回读自证**: list_projects ").append(still ? "仍包含 #" + projectId : "已不含 #" + projectId).append("\n");
            sb.append("**操作人**: ").append(operator).append("\n");
            log.info("ExpHubTools.deleteProject: #{}「{}」 by={}", projectId, must, operator);
            return sb.toString();
        } catch (Exception e) {
            log.error("ExpHubTools.deleteProject: FAILED", e);
            callLogService.incrementFailCalls();
            return "删除项目失败: " + e.getClass().getName() + " - " + e.getMessage();
        }
    }

    @Tool(name = "get_project_agents", description = "读取项目 AGENTS.md。不传 chapterNo 返回 MAIN 索引（含章节目录与红线）；传 chapterNo 返回该章全文。")
    public String getProjectAgents(
            @ToolParam(description = "项目ID") Long projectId,
            @ToolParam(description = "可选。章节号。不传则返回 MAIN 索引", required = false) String chapterNo) {
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

    @Tool(name = "update_project_agents_main", description = "写入或更新项目 AGENTS.md 的 MAIN 索引**正文**。⚠️ 不要写标题行（「# 项目名 · AGENTS.md 索引」）、版本行（「> 版本: vN」）和章节目录——这三样由系统自动生成并在返回时自动追加，写进 content 会导致内容重复且与系统版本号脱节。你只负责：项目一句话定位、当前状态、3-5 条绝对红线（内联，确保AI每次都能看到）、关键配置。建议 ≤1500 字。")
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

    @Tool(name = "update_project_agents_chapter", description = "写入或更新项目 AGENTS.md 的某一章正文。按章独立更新，不触发长文档覆盖保护，每章独立保留版本快照。⚠️ 不要写「# 第 N 章 · 标题」和「> 加载策略: ...」这两行——由系统自动生成并拼接，写进 content 会导致重复且版本号脱节。建议 ≤3000 字。")
    public String updateProjectAgentsChapter(
            @ToolParam(description = "项目ID") Long projectId,
            @ToolParam(description = "章节号") String chapterNo,
            @ToolParam(description = "章节标题") String chapterTitle,
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

    @Tool(name = "export_project_agents", description = "导出项目完整 AGENTS.md（MAIN 索引 + 所有章节按序拼接）。全量查看或备份时用。")
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

    @Tool(name = "append_project_progress", description = "追加一条项目进度/交接记录。一次一条，按时间倒序，每条独立快照。")
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

    @Tool(name = "get_project_progress", description = "读取项目最近的进度/交接记录（按时间倒序）。")
    public String getProjectProgress(
            @ToolParam(description = "项目ID") Long projectId,
            @ToolParam(description = "读取条数，默认3，最多50", required = false) Integer limit) {
        try {
            Project proj = projectService.getProject(projectId);
            if (proj == null) {
                return "❌ 未找到项目ID " + projectId + "，或你没有访问权限。";
            }
            int n = (limit == null || limit <= 0) ? 3 : Math.min(limit, 50);
            // ⚠️ 2026-10-26 16:5x：同族漏网 —— 进度读端原来用 listProgress（不过滤待删），
            //    与列表/检索口径不一致。统一用「可见」查询。
            var list = projectService.listVisibleProgress(projectId, n);
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

    @Tool(name = "list_recent_memories", description = "按更新时间倒序列出本 Key 的记忆（全局 + 自有项目），游标分页。要按关键词找用 search_experience。整理记忆用 since 只看最近一段。")
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

    @Tool(name = "list_project_experiences", description = "列出项目专属经验。项目经验默认隔离，不会出现在全局 search_experience 结果中。默认返回 10 条，摘要压缩到 60 字便于扫读。")
    public String listProjectExperiences(
            @ToolParam(description = "项目ID") Long projectId,
            @ToolParam(description = "可选。按模板类型过滤", required = false) String templateType,
            @ToolParam(description = "可选。每页条数，默认 10，上限 50", required = false) Integer pageSize,
            @ToolParam(description = "可选。跳过前 N 条（从 0 开始），配合 pageSize 翻页。默认 0", required = false) Integer offset) {
        try {
            Project proj = projectService.getProject(projectId);
            if (proj == null) {
                return "❌ 未找到项目ID " + projectId + "，或你没有访问权限。";
            }
            // ⚠️ 2026-10-26 16:5x 阁主实跑发现：这里原来用 listProjectDocs（不过滤待删），
            //    标记待删后该文档照样排在列表第一位 —— 而 search_experience 立刻搜不到它。
            //    **同一个状态字段两个出口两套口径**，AI 会把它当正常文档用。
            //    口径：读端一律用「可见」查询（排除 PENDING_DELETE），与检索一致。
            var list = projectService.listVisibleProjectDocs(projectId, templateType);
            long pendingDelete = projectService.countPendingDeleteInProject(projectId, templateType);
            if (list.isEmpty()) {
                if (pendingDelete > 0) {
                    return "项目「" + proj.getName() + "」没有可见的专属经验（"
                          + pendingDelete + " 条已标记待删，未显示）。";
                }
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
              .append(" 条专属经验，本次返回第 ").append(from + 1).append("-").append(to).append(" 条：\n");
            // 2026-10-26 12:0x 阁主：所有计数型回执都要带数据时间。
            // 本工具**直读主表、没有索引缓存** —— 所以这里的数字不会过期，这一点也要写出来，
            // 否则他没法区分「这个数是实时的」和「这个数可能是 3 分钟前的」。
            sb.append("**数据时间**: 直读主表 ")
              .append(java.time.LocalDateTime.now().format(java.time.format.DateTimeFormatter.ofPattern("HH:mm:ss")))
              .append("（无索引缓存，不存在 TTL 偏差）\n");
            // ⚠️ 2026-10-26 16:5x：被排除的待删项要**说出来**。
            //    否则「共 N 条」与后台待删队列对不上，数字又不闭合了（阁主的老要求）。
            if (pendingDelete > 0) {
                sb.append("（另有 ").append(pendingDelete).append(" 条已标记待删，未显示）\n");
            }
            sb.append("\n");
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

    @Tool(name = "generate_workflow_prompt", description = "生成填空完成的开发任务提示词：工作流模板 + 项目默认变量 + 本次任务变量。默认约束与测试命令自动带入。")
    public String generateWorkflowPrompt(
            @ToolParam(description = "项目ID") Long projectId,
            @ToolParam(description = "本次任务，一句话") String task,
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

    // ==================== 版本快照归档（2026-10-26） ====================
    //
    // 为什么需要：doc_versions 只增不减，而「验证一个改动」本身就要写多次 patch ——
    // 阁主为验证写 7 次，#654 一夜从 v25 涨到 v42。整理动作正在喂大它要整理的东西。
    // 实测 598 条 / 3.87 MB，Top5 文档占 42%。

    @Tool(name = "archive_old_versions",
          description = "【瘦身，不删数据】把超出保留窗口的历史版本搬进冷存档 doc_versions_archive。"
                  + "⚠️ **默认 dryRun=true 只算不写**，确认后传 dryRun=false。"
                  + "安全性：只搬历史副本（docs.content 不动）；先写归档再删主表（同事务）；最新版永不归档。"
                  + "用 list_archived_versions 回查。")
    public String archiveOldVersions(
            @ToolParam(description = "可选。只处理某篇文档（传文档 ID）；不传 = 全库扫描", required = false) Long id,
            @ToolParam(description = "可选。每篇保留最近几版，默认 10；允许区间 [3, 200]，**超出区间会被直接拒绝**（不静默改成边界值）", required = false) Integer keepRecent,
            @ToolParam(description = "可选。true = 只算不写（**默认 true**）。确认清单没问题后再传 false 才真正搬", required = false) Boolean dryRun,
            @ToolParam(description = "可选。**谁改的**——记进存档的 archived_by 字段", required = false) String updatedName) {
        try {
            AiAssistant assistant = getCaller();
            if (assistant != null && !Boolean.TRUE.equals(assistant.getCanUpdate())) {
                return "❌ 权限不足：该API Key没有编辑经验的权限";
            }
            // 非法参数明拒，不静默修正（红线：错误输入宁可失败得刺眼）
            if (!VersionArchiveService.isKeepValid(keepRecent)) {
                return "❌ keepRecent 只接受 [" + VersionArchiveService.MIN_KEEP_RECENT
                        + ", " + VersionArchiveService.MAX_KEEP_RECENT + "]，**ExpHub 实际收到的是 "
                        + keepRecent + "**。未做任何改动。"
                        + "（如果你的传的是小数，说明链路上有取整，请查调用侧）";
            }
            // 默认 dryRun=true —— 归档虽然可回查，但仍是会动数据的操作，第一次必须先看清单
            boolean dry = dryRun == null || Boolean.TRUE.equals(dryRun);
            String operator = resolveAuthor(updatedName, assistant);

            VersionArchiveService.ArchiveResult r =
                    versionArchiveService.archive(id, keepRecent, dry, operator);

            StringBuilder sb = new StringBuilder();
            sb.append(dry ? "🔍 归档预演（dryRun：**没有搬任何数据**）\n\n" : "🗂 归档已执行\n\n");
            sb.append("**保留窗口**: 每篇最近 ").append(r.keepRecent()).append(" 版（按 version 倒序）\n");
            // 批次号：客户端超时后拿不到回执，但每条归档行的 archive_reason 里都有它 ——
            // 这是「这次到底搬完没有」唯一能从服务端查回来的抓手（#714 第 2 项）
            sb.append("**批次号**: `").append(r.batchId())
              .append("`（已写进每条归档行的 archive_reason，超时后用它核对）\n");
            sb.append("**操作人**: ").append(operator);
            if (dry) sb.append("（dryRun 不写入）");
            sb.append("\n");
            sb.append("**扫描范围**: ").append(id == null ? "全库" : ("#" + id)).append("\n");
            sb.append("**版本数超窗口的文档**: ").append(r.docsScanned()).append(" 篇\n\n");

            if (r.details().isEmpty()) {
                sb.append("📭 没有需要归档的文档。\n");
            } else {
                sb.append("| 文档 | 标题 | 原版本数 | ").append(dry ? "将归档" : "已归档")
                  .append(" | 归档版本 | 归档后剩 | ").append(dry ? "将减少" : "主表减少")
                  .append(" |\n|---|---|---|---|---|---|---|\n");
                int i = 0;
                for (VersionArchiveService.DocArchiveResult d : r.details()) {
                    if (i++ >= 30) { sb.append("| … | 还有 ").append(r.details().size() - 30).append(" 篇 | | | | | |\n"); break; }
                    sb.append("| #").append(d.docId()).append(" | ")
                      .append(abbreviate(d.title() == null ? "(无标题)" : d.title(), 30)).append(" | ")
                      .append(d.keptBefore()).append(" | ").append(d.archived()).append(" | ")
                      .append(archiveVersionsCell(d.versions())).append(" | ")
                      .append(d.keptAfter()).append(" | ")
                      // #746：这一列是**事务内回读**的真实减少量，不是 deleteBatchIds 的返回值。
                      // 原来那句「删除 0 行 / 期望 2 行」就是因为照抄返回值，数据全对却报错，
                      // 而执行者看到「没删」就会重试 —— 重试正是重复副本的来源。
                      .append(dry ? "-" : ("-" + d.removed())).append(" |\n");
                }
                int removedTotal = 0;
                for (VersionArchiveService.DocArchiveResult d : r.details()) removedTotal += d.removed();
                sb.append("\n**合计**: ").append(dry ? "将归档 " : "已归档 ")
                  .append(r.archivedRows()).append(" 条");
                if (r.skippedRows() > 0) {
                    sb.append("（另有 ").append(r.skippedRows())
                      .append(" 条**已在冷存档**、本次未重复写入）");
                }
                sb.append("，约 ")
                  .append(String.format(java.util.Locale.ROOT, "%.2f", r.archivedBytes() / 1024.0 / 1024.0))
                  .append(" MB｜主表现有 ").append(r.remainingRows()).append(" 条");
                if (!dry) {
                    // 回读值：本次事务真的从主表删掉多少行。#746 之前这里只有一个不可信的返回值
                    sb.append("｜主表本次减少 ").append(removedTotal).append(" 行（事务回读）");
                }
                sb.append("\n");
            }

            if (dry) {
                sb.append("\n⚠️ 以上只是预演。确认无误后，用**相同参数 + dryRun=false** 再调一次才会真正搬。\n");
            } else {
                sb.append("\n✅ 归档完成。搬运的行都在 `doc_versions_archive` 里，可原样取回：\n")
                  .append("· 看某篇被搬走了哪几版 → `list_archived_versions(id=文档ID)`\n")
                  .append("· 当前正文一个字都没动（本工具只碰历史副本）\n");
                // #714 教训：客户端超时后直接重试，两个请求并发搬同一批 → 归档表成对重复。
                // 现在写入幂等 + 唯一索引兜住了，但「先核对再重试」仍然更省事
                sb.append("\n⚠️ **若客户端报 request timed out，不要直接重试**：\n")
                  .append("· 先 `list_archived_versions(id=文档ID)` 看归档时间是否已是刚才那一刻 —— 超时不等于没执行\n")
                  .append("· 重试现在是安全的（同 (doc_id, version) 不会重复写入），但先核对能省一次全库扫描\n");
            }
            for (String n : r.notes()) sb.append("· ").append(n).append("\n");
            return sb.toString();
        } catch (Exception e) {
            log.error("ExpHubTools.archiveOldVersions: FAILED", e);
            callLogService.incrementFailCalls();
            return "归档失败: " + e.getClass().getName() + " - " + e.getMessage();
        }
    }

    /** 归档版本号压成一格：连续区间写 v60–v84（25 版），零散的列前 8 个（#714 第 3 项：逐篇结果要能核对） */
    static String archiveVersionsCell(java.util.List<Integer> vs) {
        if (vs == null || vs.isEmpty()) return "-";
        if (vs.size() == 1) return "v" + vs.get(0);
        int first = vs.get(0);
        int last = vs.get(vs.size() - 1);
        if ((last - first + 1) == vs.size()) return "v" + first + "–v" + last + "（" + vs.size() + " 版）";
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < vs.size() && i < 8; i++) {
            if (i > 0) b.append("、");
            b.append("v").append(vs.get(i));
        }
        if (vs.size() > 8) b.append(" 等 ").append(vs.size()).append(" 版");
        return b.toString();
    }

    @Tool(name = "list_archived_versions",
          description = "查看某篇文档被归档（搬进冷存档）的历史版本清单。**只读**。归档不是删除，每条都能原样取回。")
    public String listArchivedVersions(
            @ToolParam(description = "必填。文档 ID") Long id,
            @ToolParam(description = "可选。是否输出正文片段（默认 false 只列版本号与时间，避免刷屏）", required = false) Boolean withPreview,
            @ToolParam(description = "可选。从第几条开始列（默认 0）。版本很多时用回执里的「下一段 offset」续读。", required = false) Integer offset) {
        try {
            List<DocVersionArchive> list = versionArchiveService.listArchived(id);
            StringBuilder sb = new StringBuilder();
            sb.append("📦 #").append(id).append(" 的归档版本\n\n");
            if (list.isEmpty()) {
                sb.append("该文档没有归档版本（要么从没归档过，要么版本数没超过保留窗口）。\n");
                return sb.toString();
            }
            // 分页：默认每段 40 条。
            //
            // ⚠️ 2026-10-26 阁主实测：55 版输出 4194 字符，被**忆阁**的通用截断器按 3500 切掉，
            // 而它的兜底文案是「需要完整内容请改用 export_document 按 ID 单独读取」——
            // **对归档清单是死路**：export_document 只导当前正文，一条归档记录都不列。
            // 这跟修 v44 单版本读取时那处错误一模一样，**上次只修了单版本，列表这条漏了**。
            // 所以这里自己分段：既不会撞上转发层的上限，也不会把指引指向做不到的工具。
            int total = list.size();
            int from = (offset == null || offset < 0) ? 0 : Math.min(offset, total);
            int to = Math.min(from + ARCHIVE_LIST_PAGE, total);
            boolean hasMore = to < total;

            sb.append("**共 ").append(total).append(" 版**（按版本号升序");
            if (from > 0 || hasMore) sb.append("，本段第 ").append(from + 1).append("–").append(to).append(" 条");
            sb.append("）\n\n");
            sb.append("| 版本 | 原创建时间 | 归档时间 | 归档人 | 变更类型 | 正文字数 |\n");
            sb.append("|---|---|---|---|---|---|\n");
            boolean preview = Boolean.TRUE.equals(withPreview);
            for (DocVersionArchive a : list.subList(from, to)) {
                sb.append("| v").append(a.getVersion()).append(" | ")
                  .append(a.getCreatedAt() == null ? "-" : a.getCreatedAt().toString().replace('T', ' ')).append(" | ")
                  .append(a.getArchivedAt() == null ? "-" : a.getArchivedAt().toString().replace('T', ' ')).append(" | ")
                  .append(a.getArchivedBy() == null ? "-" : a.getArchivedBy()).append(" | ")
                  .append(a.getChangeType() == null ? "-" : a.getChangeType()).append(" | ")
                  .append(a.getContent() == null ? "0（仅元数据）" : a.getContent().length()).append(" |\n");
            }
            if (preview) {
                sb.append("\n---\n**正文片段**（前 200 字）\n");
                for (DocVersionArchive a : list.subList(from, to)) {
                    sb.append("\n**v").append(a.getVersion()).append("**");
                    if (a.getContent() == null) {
                        sb.append("（仅元数据变更，原文未存）\n");
                    } else {
                        sb.append("：").append(abbreviate(a.getContent().replaceAll("\\s+", " "), 200)).append("\n");
                    }
                }
            }
            if (hasMore) {
                sb.append("\n> 📖 还有 ").append(total - to).append(" 版没列。继续读：`list_archived_versions(id=")
                  .append(id).append(", offset=").append(to).append(")`\n");
                sb.append("> ⚠️ 不要用 `export_document` 代替 —— 它只导当前正文，**不列归档清单**。\n");
            }
            sb.append("\n> 要看某一版的**完整正文**：`get_experience_history(id=").append(id)
              .append(", version=版本号)`（支持 offset 分段，归档版本可原样读回）。\n");
            return sb.toString();
        } catch (Exception e) {
            log.error("ExpHubTools.listArchivedVersions: FAILED", e);
            callLogService.incrementFailCalls();
            return "查询失败: " + e.getClass().getName() + " - " + e.getMessage();
        }
    }

    // ================================================================
    // 协作信箱（2026-11-06；设计定稿见 #1324 C114–C121）
    //
    // 只碰 mail_messages / mail_cursors，不进 docs 索引、不影响搜索排名。
    // 身份 = 共享 Key + 客户端配置里写死的请求头，属「委托信任」，不是鉴权。
    // ================================================================

    /**
     * 当前角色：优先客户端配置注入的请求头，其次参数兜底（只有带不了 header 的客户端才用）。
     *
     * <p><b>2026-11-09 阁主 T261109054938-679</b>：正名已是汉字，客户端配置里可能仍写着拼音
     * （{@code x-exphub-role: zhulong}）。这里统一归一到汉字（{@code 烛龙}）——
     * 兼容期两种写法都收，但出口、日志、审计一律汉字，不允许「库里汉字、日志拼音」这种半套。
     */
    private String mailRole(String fallback) {
        String r = MailIdentity.role();
        if (r == null || r.isBlank()) r = fallback;
        if (r == null) return null;
        String trimmed = r.trim();
        String canon = mailboxService == null ? null : mailboxService.canonicalRole(trimmed);
        return (canon == null || canon.isBlank()) ? trimmed : canon;
    }

    @Tool(name = "mail_start",
          description = "【开工先调这个】取「你是谁 + 未读信封」。身份来自客户端配置注入的 x-exphub-role 头（委托信任，不是鉴权）；"
                      + "**角色正名是汉字（如「烛龙」），拼音（zhulong）仍兼容并会自动归一成汉字**；回执里的角色一律汉字。"
                      + "没配角色就只回提示、不给任何私信。信封按线程聚合，每根一行：主题/发件人/新消息数/正文首 100 字。"
                      + "要展开某根用 mail_thread；q 只搜主题/发件人/类型，不搜正文。"
                      + "sent=true 切到**发件视角**（看你发出去的、含发给谁），默认 false=收件视角。"
                      + "roles=true 只列**当前可投递角色名单**（汉字正名，停用/待复核不出现），不读信封、不推游标；"
                      + "名单只用于展示，**调用方不得依据名单自动改写收件人并重发**。"
                      + "includeRead=true 含已读线程（面板做「全部来信」用它）；每条线程带 `最新 #id`，这是稳定排序键。"
                      + "lastId 只要更新于该消息的线程（增量刷新）；beforeId 只要更早的线程（翻页），"
                      + "取上一页最后一条的 `最新 #id` 原样回传即可，**翻到底会明确说「已到最早」，窗口没扫完则说「不代表没有更早的信」**。")
    public String mailStart(
            @ToolParam(description = "可选。搜信封的关键词（只匹配主题/发件人/类型，不搜正文）", required = false) String q,
            @ToolParam(description = "可选。最多列几根线程，默认 20，上限 50", required = false) Integer limit,
            @ToolParam(description = "可选。角色兜底（汉字正名或拼音别名都行，服务端会归一成汉字）。优先用客户端配置的 x-exphub-role；只有客户端带不了 header 时才传", required = false) String role,
            @ToolParam(description = "可选。true=发件视角（我发出去的，列出收件人）；**默认 false**，不传就是收件视角。"
                    + "发信方自查「到底发给了谁」用它 —— 收件视角永远看不到自己发的信", required = false) Boolean sent,
            @ToolParam(description = "可选。true=**只读未读摘要**：只给线程数/消息数/每根的主题类型，"
                    + "**不含正文摘要**，不推游标、不改任何状态（调多次结果一样）。外部系统做未读角标用它，"
                    + "默认 false 行为不变", required = false) Boolean unreadOnly,
            @ToolParam(description = "可选。true=**按需查全量角色名单**：只列当前 status=ACTIVE 的角色（汉字正名），"
                    + "停用与待复核不出现；不读信封、不推游标。拒绝响应本身已带全量名单，"
                    + "这个入口用于主动查询；**名单只用于展示，不得据此自动改写收件人并重发**", required = false) Boolean roles,
            @ToolParam(description = "可选。true=连**已读线程**一起列（游标之后没有新消息的也列）。"
                    + "面板要「全部来信、最新在上」时传它；默认 false = 只列有待看线程，与旧行为逐字节一致。"
                    + "不推游标，调它不会吃掉未读角标", required = false) Boolean includeRead,
            @ToolParam(description = "可选。增量游标：只列「线程最新消息 id **大于**它」的线程，填上次看到的 `最新 #id`。"
                    + "传 0 或负数直接拒，不静默归零；与 beforeId 方向相反，两个一起传会明拒", required = false) Long lastId,
            @ToolParam(description = "可选。翻页游标：只列「线程最新消息 id **小于**它」的线程，"
                    + "填上一页最后一条的 `最新 #id`。小于 1 的值直接拒并回显收到的实际值", required = false) Long beforeId) {
        try {
            if (Boolean.TRUE.equals(roles)) {
                // 阁主 T261109054529-699：全量名单单独一个查询入口按需给，不塞进每次错误响应。
                return mailboxService.roleDirectoryMessage();
            }
            String me = mailRole(role);
            String cfg = MailIdentity.configSource();
            StringBuilder sb = new StringBuilder();

            if (me == null) {
                sb.append("⚠️ 未声明角色：客户端配置里没有 `").append(MailIdentity.HEADER_ROLE).append("` 头。\n");
                sb.append("本机制是**委托信任、不是鉴权** —— 服务端不认人，只按配置给信件贴收件人标签。\n");
                sb.append("配好之前**不给任何私信**，免得串信。\n");
                sb.append("配法：MCP 客户端配置加请求头 `").append(MailIdentity.HEADER_ROLE)
                  .append(": 你的角色（汉字正名，拼音也兼容）`、`").append(MailIdentity.HEADER_CONFIG_SOURCE)
                  .append(": 唯一配置标识`（九份配置别从模板复制，实例 ID 必须各不相同）。\n");
                return sb.toString();
            }

            int lim = (limit == null || limit <= 0) ? 20 : Math.min(limit, 50);
            boolean searchMode = q != null && !q.isBlank();

            // 2026-11-10 阁主 #625：新参数的非法组合一律明拒，不许静默挑一个（AGENTS 9c）。
            boolean wantsPage = includeRead != null || lastId != null || beforeId != null;
            if (lastId != null && lastId < 1) {
                return "❌ lastId 必须 ≥ 1（ExpHub 实际收到的是 " + lastId
                        + "）。它是「线程最新消息 id」游标，不是数组下标。";
            }
            if (beforeId != null && beforeId < 1) {
                return "❌ beforeId 必须 ≥ 1（ExpHub 实际收到的是 " + beforeId
                        + "）。它是上一页最后一条的「最新 #id」，不是页码。";
            }
            if (lastId != null && beforeId != null) {
                return "❌ lastId 与 beforeId 方向相反（一个只看更新、一个只看更早），不能同时传。"
                        + "增量刷新用 lastId；加载更早用 beforeId。";
            }
            if (Boolean.TRUE.equals(sent) && wantsPage) {
                return "❌ includeRead / lastId / beforeId 只作用于收件视角，与 sent=true 冲突。"
                        + "发件视角没有「已读/未读」之分，也不支持这两类游标。";
            }
            if (Boolean.TRUE.equals(unreadOnly) && wantsPage) {
                return "❌ unreadOnly=true 与 includeRead / lastId / beforeId 语义冲突："
                        + "前者是只看未读的角标摘要，后者要读全部/分页。分开调。";
            }

            int unread = mailboxService.unreadCount(me);

            sb.append("你是【").append(me).append("】");
            if (cfg != null) sb.append("，配置来源 ").append(cfg);
            sb.append("；未读 ").append(unread).append(" 封\n");

            // 2026-11-08（#1313 第 2 条）：发件视角。默认 false，现有调用方行为不变。
            if (Boolean.TRUE.equals(sent)) {
                List<com.exphub.service.MailboxService.Envelope> sentList = mailboxService.outbox(me, q, lim);
                sb.append("\n📤 发件视角");
                if (searchMode) sb.append("（按关键词过滤）");
                sb.append("：").append(sentList.size()).append(" 根线程\n");
                if (sentList.isEmpty()) {
                    sb.append(searchMode ? "📭 没搜到你发过的匹配信件。\n"
                                         : "📭 你还没发过信。\n");
                    return sb.toString();
                }
                for (com.exphub.service.MailboxService.Envelope e : sentList) {
                    sb.append("- [").append(e.threadId).append("] ").append(e.msgType)
                      .append(" · 发给【").append(e.toRole).append("】")
                      .append(" · ").append(com.exphub.service.MailboxService.time(e.latestAt)).append("\n");
                    sb.append("  「").append(e.subject).append("」").append(e.latestSnippet).append("\n");
                }
                sb.append("\n> 展开某根：`mail_thread(threadId=\"").append(sentList.get(0).threadId).append("\")`\n");
                sb.append("> 发件视角只列「我发出去的」，收件箱的信不在这里（默认模式才是收件视角）。\n");
                return sb.toString();
            }

            // 2026-11-08（越哥批准走「改现有工具」而不是加第 51 个）：只读未读摘要，供忆阁做角标。
            // 幂等的根本原因：只走 inbox（内部只调 cursorOf 读游标），全程没有 advanceCursor。
            if (Boolean.TRUE.equals(unreadOnly)) {
                List<com.exphub.service.MailboxService.Envelope> un =
                        mailboxService.inbox(me, null, lim, false);
                // 2026-11-10 阁主 #634：续写必须在首行那句话里，不许另起一行让标点顶到行首。
                continueIdentityLine(sb);
                sb.append("；只读模式\n");
                if (un.isEmpty()) {
                    sb.append("📭 没有未读。\n");
                    sb.append("{\"threads\":0,\"messages\":0,\"items\":[]}\n");
                    return sb.toString();
                }
                sb.append("\n📬 未读：").append(un.size()).append(" 根线程 / ").append(unread).append(" 封消息\n");
                StringBuilder json = new StringBuilder("{\"threads\":").append(un.size())
                        .append(",\"messages\":").append(unread).append(",\"items\":[");
                for (int i = 0; i < un.size(); i++) {
                    com.exphub.service.MailboxService.Envelope e = un.get(i);
                    if (i > 0) json.append(",");
                    json.append("{\"threadId\":\"").append(jsonEscape(e.threadId))
                        .append("\",\"subject\":\"").append(jsonEscape(e.subject))
                        .append("\",\"from\":\"").append(jsonEscape(e.fromRole))
                        .append("\",\"type\":\"").append(jsonEscape(e.msgType))
                        .append("\",\"unread\":").append(e.freshCount)
                        .append(",\"lastAt\":\"")
                        .append(jsonEscape(com.exphub.service.MailboxService.time(e.latestAt)))
                        .append("\"}");
                    sb.append("- [").append(e.threadId).append("] ").append(e.msgType)
                      .append(" · ").append(e.fromRole).append(" · ").append(e.freshCount).append(" 封新")
                      .append(" · ").append(com.exphub.service.MailboxService.time(e.latestAt)).append("\n");
                    sb.append("  「").append(e.subject).append("」\n");
                }
                json.append("]}");
                sb.append("\n> 只读：不推游标、不改任何状态，重复调用结果逐字相同。不含正文摘要。\n");
                sb.append(json).append("\n");
                return sb.toString();
            }

            // 2026-11-10 阁主 #625：含已读 / 稳定排序键 / 真分页。
            // 不传这三个参数时，下面的默认分支逐字不变（老调用方零感知）。
            if (wantsPage) {
                boolean withRead = searchMode || Boolean.TRUE.equals(includeRead);
                com.exphub.service.MailboxService.InboxPage page =
                        mailboxService.inboxPage(me, q, lim, withRead, lastId, beforeId);
                // 2026-11-10 阁主 #634：这段是首行那句的续写（「…；未读 4 封；含已读｜你传的 limit=2」）。
                // 原来另起一行，分号落在行首，被读成两句互不相干的断言。先去掉首行末尾换行再续写。
                // limit 仍在同一位置、仍是调用方传的原值（lim），默认分支一个字没动。
                continueIdentityLine(sb);
                sb.append("；").append(withRead ? "含已读" : "只看有待看的")
                  .append(searchMode ? "（按关键词过滤）" : "")
                  .append("｜你传的 limit=").append(lim).append("\n");

                if (page.items.isEmpty()) {
                    if (lastId != null) {
                        sb.append("📭 没有比 #").append(lastId).append(" 更新的线程（本次扫了 ")
                          .append(page.scannedMessages).append(" 条消息）。\n");
                    } else if (page.windowFull) {
                        sb.append("📭 本窗口内没有符合条件的线程（本次扫了 ").append(page.scannedMessages)
                          .append(" 条消息，窗口上限 ").append(page.scanWindow).append(" 已满）—— ")
                          .append("**这不代表没有更早的信**。\n");
                        if (page.nextBeforeId != null) {
                            sb.append("➡️ 继续翻：mail_start(includeRead=").append(withRead)
                              .append(", beforeId=").append(page.nextBeforeId).append(", limit=").append(lim).append(")\n");
                        }
                    } else {
                        sb.append("📭 本窗口内没有符合条件的线程（已扫 ").append(page.scannedMessages)
                          .append(" 条消息").append(beforeId != null ? "，已到最早" : "").append("）。\n");
                    }
                    appendMailPageJson(sb, page, lim, withRead);
                    return sb.toString();
                }

                sb.append("\n📬 ").append(page.items.size()).append(" 根线程")
                  .append(withRead ? "（含已读）" : "有待看").append("\n");
                for (com.exphub.service.MailboxService.Envelope e : page.items) {
                    sb.append("- [").append(e.threadId).append("] ").append(e.msgType)
                      .append(" · ").append(e.fromRole)
                      .append(" · ").append(e.freshCount).append(" 封新")
                      .append(" · ").append(com.exphub.service.MailboxService.time(e.latestAt))
                      .append(" · 最新 #").append(e.latestId);
                    if (e.claimed) sb.append(" · 已认领(").append(e.claimedBy).append(")");
                    sb.append("\n  「").append(e.subject).append("」").append(e.latestSnippet).append("\n");
                }
                sb.append("\n🔎 本页 ").append(page.items.size()).append(" 根 / 你传的 limit=").append(lim)
                  .append("｜最新 #").append(page.items.get(0).latestId)
                  .append("、最旧 #").append(page.items.get(page.items.size() - 1).latestId).append("\n");
                if (page.matched > page.items.size()) {
                    sb.append("另有 ").append(page.matched - page.items.size())
                      .append(" 根在本窗口内没展示（limit 截断，不是没有）。\n");
                }
                if (lastId != null) {
                    if (page.windowFull) {
                        sb.append("⚠️ 扫描窗口已满：本次扫了 ").append(page.scannedMessages)
                          .append(" 条消息（上限 ").append(page.scanWindow)
                          .append("）—— 增量拉新够用；要看更早的历史请改用 includeRead + beforeId 分页。\n");
                    } else {
                        sb.append("✅ 已扫完更新于 #").append(lastId).append(" 的线程，没有更新的了。\n");
                    }
                } else {
                    if (page.windowFull) {
                        sb.append("⚠️ 扫描窗口已满：本次扫了 ").append(page.scannedMessages)
                          .append(" 条消息（上限 ").append(page.scanWindow)
                          .append("），更早的线程还没进窗口 —— **不代表没有更早的信**，按下面的 beforeId 继续翻即可越过窗口。\n");
                    }
                    if (page.endOfInbox) {
                        sb.append("✅ 已到最早：本次扫了 ").append(page.scannedMessages)
                          .append(" 条消息，没有更早的线程了。\n");
                    } else if (page.nextBeforeId != null) {
                        sb.append("➡️ 加载更早：mail_start(includeRead=").append(withRead)
                          .append(", beforeId=").append(page.nextBeforeId).append(", limit=").append(lim).append(")\n");
                    }
                }
                sb.append("> 未读只是计数；读列表不推游标（含本次——不会吃掉角标）。展开某根用 mail_thread。\n");
                appendMailPageJson(sb, page, lim, withRead);
                return sb.toString();
            }

            List<com.exphub.service.MailboxService.Envelope> list =
                    mailboxService.inbox(me, q, lim, searchMode);
            if (list.isEmpty()) {
                sb.append(searchMode ? "📭 没搜到匹配的信封。换个词，或直接报 threadId 用 mail_thread 展开。\n"
                                     : "📭 没有新信。要翻旧信传 q（搜主题/发件人/类型）。\n");
                return sb.toString();
            }

            sb.append("\n📬 ").append(list.size()).append(" 根线程").append(searchMode ? "（含已读）" : "有待看").append("\n");
            for (com.exphub.service.MailboxService.Envelope e : list) {
                sb.append("- [").append(e.threadId).append("] ").append(e.msgType)
                  .append(" · ").append(e.fromRole)
                  .append(" · ").append(e.freshCount).append(" 封新")
                  .append(" · ").append(com.exphub.service.MailboxService.time(e.latestAt));
                if (e.claimed) sb.append(" · 已认领(").append(e.claimedBy).append(")");
                sb.append("\n  「").append(e.subject).append("」").append(e.latestSnippet).append("\n");
            }
            sb.append("\n> 展开某根：`mail_thread(threadId=\"").append(list.get(0).threadId).append("\")`\n");
            sb.append("> 未读只是计数；推信只看游标（服务端记，别自己记）。标处理过用 `mail_thread(..., ackIds=\"消息ID\")`。\n");
            return sb.toString();
        } catch (Exception e) {
            log.error("ExpHubTools.mailStart: FAILED", e);
            return "读信箱失败: " + e.getClass().getName() + " - " + e.getMessage();
        }
    }

    /**
     * 把「你是【X】，配置来源 Y；未读 N 封」这句续写下去（2026-11-10 阁主 #634）。
     *
     * <p>问题原样：首行以换行收尾，后面的分支用「；」开头续写，分号落到行首，
     * 「未读 4 封」和「；含已读｜limit=2」被读成两句互不相干的断言 —— 正是
     * 这单刚修掉的「半句话」那类毛病的翻版。
     *
     * <p>做法：只在本分支里去掉首行末尾那一个换行，让续写仍是同一句。
     * <b>默认分支完全不经过这里</b>，不传新参数时回执逐字不变。
     */
    private static void continueIdentityLine(StringBuilder sb) {
        if (sb.length() > 0 && sb.charAt(sb.length() - 1) == '\n') {
            sb.setLength(sb.length() - 1);
        }
    }

    /** 拼只读摘要里的 JSON 用：正文摘要不带，但主题里可能有引号和换行，不转义会拼出坏 JSON。 */
    private static String jsonEscape(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\").replace("\"", "\\\"")
                .replace("\n", " ").replace("\r", " ").replace("\t", " ");
    }

    /**
     * 分页信封的末行 JSON（2026-11-10 阁主 #625 硬要求二）。
     *
     * <p>忆阁的解析器只认「最后一行能 parse 的对象」。新分支若只给人读文本，
     * 切过去会解析失败、面板直接变空态 —— 又一个静默失败。所以人读文本之后
     * <b>永远</b>跟一行 JSON，且它是最后一行的唯一内容。
     */
    private static void appendMailPageJson(StringBuilder sb,
                                           com.exphub.service.MailboxService.InboxPage page,
                                           int lim, boolean includeRead) {
        StringBuilder json = new StringBuilder("{\"limit\":").append(lim)
                .append(",\"includeRead\":").append(includeRead)
                .append(",\"scannedMessages\":").append(page.scannedMessages)
                .append(",\"scanWindow\":").append(page.scanWindow)
                .append(",\"windowFull\":").append(page.windowFull)
                .append(",\"matched\":").append(page.matched)
                .append(",\"hasMore\":").append(page.hasMore)
                .append(",\"endOfInbox\":").append(page.endOfInbox)
                .append(",\"nextBeforeId\":").append(page.nextBeforeId == null ? "null" : page.nextBeforeId)
                .append(",\"items\":[");
        for (int i = 0; i < page.items.size(); i++) {
            com.exphub.service.MailboxService.Envelope e = page.items.get(i);
            if (i > 0) json.append(",");
            json.append("{\"threadId\":\"").append(jsonEscape(e.threadId))
                .append("\",\"subject\":\"").append(jsonEscape(e.subject))
                .append("\",\"from\":\"").append(jsonEscape(e.fromRole))
                .append("\",\"type\":\"").append(jsonEscape(e.msgType))
                .append("\",\"unread\":").append(e.freshCount)
                .append(",\"lastId\":").append(e.latestId)
                .append(",\"lastAt\":\"").append(jsonEscape(com.exphub.service.MailboxService.time(e.latestAt)))
                .append("\"}");
        }
        json.append("]}");
        sb.append(json).append("\n");
    }

    @Tool(name = "mail_send",
          description = "给另一个助手发信。**from 由服务端按当前角色填，不接受传入**；回信只传 inReplyTo，线程自动推导（没有 threadId 参数）。"
                      + "消息只追加不可改，要纠错发一条 type=fix 引用原信。clientMsgId 做幂等，工具超时重试不会发两封；"
                      + "**同键二次发送不会新建消息**，回执首行是「⚠️ 幂等命中」并报出原消息 id（这次请求与库里那封不一致时会逐项点出差异）。"
                      + "键全局唯一且永久有效，同一键只对应它首次入库的那封信 —— 换内容、换收件人、换线程都必须换键。"
                      + "类型：dispatch 派单 / receipt 回执 / accept 验收 / note 通知 / fix 更正。"
                      + "**收件人正名是汉字（如「烛龙」）；拼音（zhulong）和错拼（zhuolong）仍兼容，会归一到汉字并在回执里说明**。"
                      + "**回信（带 inReplyTo）的收件人强制取父消息发件人**，不接受自选。"
                      + "收件人被拒时返回结构化的 rejectedRole / candidates / roleList / hint；"
                      + "**候选和名单只用于展示，调用方不得据此自动改写收件人并重发**（roleList 已是全量 ACTIVE 名单；也可用 mail_start(roles=true) 主动查）。"
                      + "可选的授权链元数据 chainId/auto/depth **只记录、不推导**：服务端分不出自动回合与本人生成的信，只能由调用方自报。")
    public String mailSend(
            @ToolParam(description = "收件人：正名优先写汉字（如「烛龙」「重明鸟」）；拼音（zhulong）也收，"
                    + "会归一到汉字后入库。开新线程必填；回信（带 inReplyTo）可留空 —— "
                    + "收件人取父消息的发件人，传了且与父消息发件人不一致会被明拒", required = false) String to,
            @ToolParam(description = "主题，一句话说清这件事") String subject,
            @ToolParam(description = "正文。建议不超过 8000 字，超了拆成多条；禁止写账号密码密钥") String body,
            @ToolParam(description = "可选。回信填父消息ID；不填=开新线程", required = false) Long inReplyTo,
            @ToolParam(description = "可选。类型：dispatch/receipt/accept/note/fix，默认 note", required = false) String type,
            @ToolParam(description = "可选。幂等键。工具超时重试时带同一个值，不会重复发；"
                    + "**同键二次调用不会新建消息**，回执会写明「幂等命中」并报原消息 id。键全局唯一、永久有效，"
                    + "换内容/收件人/线程请换新键（沿用旧键只会回放旧信）", required = false) String clientMsgId,
            @ToolParam(description = "可选。授权链根标识（≤64 字符），如 T261109035247-364 或调用方自定义链号。"
                    + "不传=这封信不属于任何授权链。传空白或超长会明拒，不会截断", required = false) String chainId,
            @ToolParam(description = "可选。true=这封是代理人自动回合发出的；false 或不传=人工发出（默认 false）", required = false) Boolean auto,
            @ToolParam(description = "可选。授权链第几跳，非负整数，0=链根（默认 0）。传负数直接拒，不会静默归零", required = false) Integer depth) {
        try {
            String me = mailRole(null);
            if (me == null) {
                return "❌ 发不了：客户端配置里没有 `" + MailIdentity.HEADER_ROLE
                        + "` 头，服务端不知道该署谁的名。先配好角色再发。";
            }
            com.exphub.service.MailboxService.SendResult sent = mailboxService.sendDetailed(me, to, subject, body,
                    inReplyTo, type, clientMsgId, MailIdentity.configSource(), chainId, auto, depth);
            return renderSendReceipt(me, to, subject, body, sent, chainId, auto, depth);
        } catch (SensitiveContentGuard.Rejected e) {
            return e.getMessage();
        } catch (IllegalArgumentException iae) {
            return "❌ " + iae.getMessage();
        } catch (Exception e) {
            log.error("ExpHubTools.mailSend: FAILED", e);
            return "发信失败: " + e.getClass().getName() + " - " + e.getMessage();
        }
    }

    /**
     * mail_send 的回执渲染。**真新建与幂等回放必须长得不一样** ——
     * 2026-11-14 阁主 T261114082605-521 的现场就是两者逐字相同：调用方拿到「✅ 已发给」，
     * 把委托单推进到 running，收件人信箱里却没有任何新信（#1986 被 dispatch:W-20261114-02 回放）。
     */
    private String renderSendReceipt(String me, String to, String subject, String body,
                                     com.exphub.service.MailboxService.SendResult sent,
                                     String chainId, Boolean auto, Integer depth) {
        MailMessage m = sent.message();
        StringBuilder sb = new StringBuilder();
        if (sent.replayed()) {
            appendIdempotentReplayReceipt(sb, m, subject, body);
        } else {
            sb.append("✅ 已以【").append(me).append("】");
            String cfg = MailIdentity.configSource();
            if (cfg != null) sb.append("（配置来源 ").append(cfg).append("）");
            sb.append("发给【").append(m.getToRole()).append("】\n");
            // 2026-11-08：别名归一定要说出来。静默修正和静默投丢是同一种病 —— 发件方看不出区别。
            String canonTo = mailboxService.canonicalRole(to);
            if (to != null && canonTo != null && !canonTo.equalsIgnoreCase(to.trim())) {
                sb.append("⚠️ 收件人「").append(to.trim()).append("」是别名，已归一为【")
                  .append(canonTo).append("】后投递（历史老信的 to_role 未改动）。\n");
            }
            sb.append("消息 #").append(m.getId()).append("，线程 ").append(m.getThreadId())
              .append("，类型 ").append(m.getMsgType());
            if (m.getInReplyTo() != null) sb.append("，回复 #").append(m.getInReplyTo());
            sb.append("\n");
            // 2026-11-09 阁主 P9 第 3 条：回信的收件人**取父消息发件人**（服务端强制），
            // 不再按入参入库 —— 旧行为下 to 写错的回信照样入库、线程里看得见，
            // 对方收件箱里却没有，发件方还以为送到了。这里回读库里的 to_role 自证。
            if (m.getInReplyTo() != null) {
                MailMessage storedMsg = mailboxService.byId(m.getId());
                String storedTo = storedMsg == null ? m.getToRole() : storedMsg.getToRole();
                sb.append("收件人取自父消息 #").append(m.getInReplyTo()).append(" 的发件人【")
                  .append(storedTo).append("】（回信不许自选收件人，避免 to 写错却照样入库）\n");
            }
        }
        // 传了授权链元数据 → 回读库里的实际值再报，不按入参复述（AGENTS 第 10 章）
        if (chainId != null || auto != null || depth != null) {
            appendChainMetadataReceipt(sb, m.getId(), chainId, auto, depth);
        }
        return sb.toString();
    }

    /**
     * 幂等回放的回执：**首句就说「本次没有新建消息」**，并给出旧信的 id/线程/原时间，
     * 让调用方能当场判断「这是重试成功」还是「键被复用了、这封根本没发出去」。
     */
    private void appendIdempotentReplayReceipt(StringBuilder sb, MailMessage old, String subject, String body) {
        String at = old.getCreatedAt() == null ? "（库里未记录时间）"
                : com.exphub.service.MailboxService.time(old.getCreatedAt());
        sb.append("⚠️ 幂等命中：本次**没有新建消息**，一封新信也没有发出。\n");
        sb.append("消息 #").append(old.getId()).append("，线程 ").append(old.getThreadId())
          .append("，类型 ").append(old.getMsgType())
          .append("，原发送时间 ").append(at).append("\n");
        sb.append("库里那封：【").append(old.getFromRole()).append("】→【").append(old.getToRole())
          .append("】，主题「").append(brief(old.getSubject(), 60)).append("」")
          .append("，正文 ").append(old.getBody() == null ? 0 : old.getBody().length()).append(" 字");
        if (old.getInReplyTo() != null) sb.append("，回复 #").append(old.getInReplyTo());
        sb.append("\n");

        // 同一键对应的旧信与本次请求不一样 → 这不是重试，是键被复用（这正是本次事故的形态）。
        boolean subjectDiff = !java.util.Objects.equals(trimToNull(subject), trimToNull(old.getSubject()));
        String reqBody = body == null ? "" : body;
        String oldBody = old.getBody() == null ? "" : old.getBody();
        boolean bodyDiff = !reqBody.equals(oldBody);
        if (subjectDiff || bodyDiff) {
            sb.append("⚠️ 本次请求与库里那封**不是同一封信**：");
            if (subjectDiff) {
                sb.append("主题不同（库里「").append(brief(old.getSubject(), 40))
                  .append("」，本次「").append(brief(subject, 40)).append("」）");
            }
            if (bodyDiff) {
                if (subjectDiff) sb.append("；");
                sb.append("正文不同（库里 ").append(oldBody.length()).append(" 字，本次 ")
                  .append(reqBody.length()).append(" 字）");
            }
            sb.append("。clientMsgId 只对应它首次入库的那封，换了内容沿用旧键永远不会发出去。\n");
            sb.append("**要发这封新信，请换一个没被用过的 clientMsgId 重发。**\n");
        } else {
            sb.append("这封信与库里那封内容一致，是同一封的重试 —— 首次那次已经成功，不必再发。\n");
        }
        sb.append("**别把它当成「刚发出去」**：如果你要的是新消息（例如派单信），现在是没发出去的。\n");
    }

    private static String trimToNull(String s) {
        if (s == null) return null;
        String v = s.trim();
        return v.isEmpty() ? null : v;
    }

    private static String brief(String s, int max) {
        if (s == null) return "";
        String v = s.trim().replace("\n", " ");
        return v.length() <= max ? v : v.substring(0, max) + "…";
    }
    /**
     * 授权链元数据的发信回执：<b>回读库里的值</b>再报，并把「默认值」与「幂等未覆盖」说清。
     *
     * <p>两条纪律：① 不把入参复述当成入库结果（AGENTS 第 10 章）；
     * ② 不一致时必须给出可执行的解释（同一 clientMsgId 重试以首次为准），不许只说一句「已保存」。
     */
    private void appendChainMetadataReceipt(StringBuilder sb, Long messageId,
                                            String reqChain, Boolean reqAuto, Integer reqDepth) {
        sb.append("\n**授权链元数据（入库后回读）**\n");
        MailMessage stored = mailboxService.byId(messageId);
        if (stored == null) {
            sb.append("- ⚠️ 回读失败：消息 #").append(messageId).append(" 查不到，无法自证入库值。\n");
            return;
        }
        String storedChain = stored.getChainId();
        boolean storedAuto = Boolean.TRUE.equals(stored.getAutoFlag());
        int storedDepth = stored.getChainDepth() == null ? 0 : stored.getChainDepth();
        sb.append("- chainId: ").append(storedChain == null ? "（未记录，不属于任何授权链）" : storedChain).append("\n");
        sb.append("- auto: ").append(storedAuto).append("\n");
        sb.append("- depth: ").append(storedDepth).append("\n");

        String reqChainTrimmed = reqChain == null ? null : reqChain.trim();
        boolean reqAutoVal = Boolean.TRUE.equals(reqAuto);
        int reqDepthVal = reqDepth == null ? 0 : reqDepth;
        boolean mismatch = !java.util.Objects.equals(reqChainTrimmed, storedChain)
                || reqAutoVal != storedAuto || reqDepthVal != storedDepth;
        if (mismatch) {
            sb.append("⚠️ 与本次传入不一致（传入 chainId=")
              .append(reqChainTrimmed == null ? "未传" : reqChainTrimmed)
              .append(" / auto=").append(reqAutoVal).append(" / depth=").append(reqDepthVal)
              .append("）。若是同一 clientMsgId 重试，元数据以首次入库为准、不会被覆盖；")
              .append("否则请把这条回执报给维护者。\n");
        }
        if (reqDepth == null) sb.append("- depth 未传，入库用默认 0（链根）。\n");
        if (reqAuto == null) sb.append("- auto 未传，入库用默认 false（人工发出）。\n");
        if (storedChain == null && storedDepth > 0) {
            sb.append("⚠️ depth>0 但 chainId 为空：跳数离开授权链没有可追溯的根，已按原样入库（没有替你补 chainId）。\n");
        }
    }

    @Tool(name = "mail_thread",
          description = "展开一根线程。默认只给**游标之后的新消息**（最多 10 条），full=true 才从头分页回溯。"
                      + "读完服务端自动推游标 —— 同样的参数不用再调一次，不会重复给同一批。"
                      + "peek=true 则只读：推游标由调用方用 advanceTo 显式确认，适合「读成功才算读过」的自动唤醒。"
                      + "claim_id 认领派单（数据库原子抢锁，已被别人认领会告诉你被谁认了）；ackIds 只记「处理过」，不参与去重。"
                      + "带授权链元数据的消息会在头部回显 链/第几跳/自动或人工（没有元数据的历史消息不多印一个字）。")
    public String mailThread(
            @ToolParam(description = "线程号，从 mail_start 的信封列表里抄") String threadId,
            @ToolParam(description = "可选。true=从线程头开始（分页回溯）；默认 false 只给游标之后的新消息", required = false) Boolean full,
            @ToolParam(description = "可选。条数上限：默认 10，回溯模式默认 30，都封顶 50", required = false) Integer limit,
            @ToolParam(description = "可选。逗号分隔的消息ID，标记「我处理过了」。只做展示，不推游标、不影响去重", required = false) String ackIds,
            @ToolParam(description = "可选。要认领的消息ID（派单用）。抢锁失败会回是谁认领的", required = false) Long claimId,
            @ToolParam(description = "可选。true=**只读**：正常返回正文，但游标原地不动，可反复调同一批。"
                    + "给「模型调用/进程被杀就丢掉这封信」的场景用；确认消费成功后必须再调一次 advanceTo 才算读过。"
                    + "与 advanceTo 互斥。默认 false 行为不变", required = false) Boolean peek,
            @ToolParam(description = "可选。把游标显式推进到这条消息ID，**不返回正文**（peek 之后的确认步）。"
                    + "只接受本线程里真实存在的消息ID，且只前进不后退；跨线程的ID会被拒。"
                    + "目标之后还有几封会写在回执里", required = false) Long advanceTo) {
        try {
            // 参数自身的错误先判：这些分支不需要读名单（mailRole 会归一角色，可能触发一次清单读取），
            // 「缺 threadId」「peek 与 advanceTo 互斥」应当在碰任何信箱数据之前就拒掉。
            if (threadId == null || threadId.isBlank()) {
                return "❌ 要 threadId。从 mail_start 的信封列表里抄，别自己编。";
            }
            boolean peekMode = Boolean.TRUE.equals(peek);
            boolean backfill = Boolean.TRUE.equals(full);
            if (advanceTo != null && (peekMode || backfill)) {
                return "❌ advanceTo 不能和 " + (peekMode ? "peek" : "full") + " 一起传："
                        + "advanceTo 只推游标、不返回正文，peek 是只读，full 是回溯读正文 —— 三者语义冲突。"
                        + "要推进请单独调一次 `mail_thread(threadId=…, advanceTo=消息ID)`。";
            }
            String me = mailRole(null);
            if (me == null) {
                return "❌ 读不了：客户端配置里没有 `" + MailIdentity.HEADER_ROLE + "` 头。";
            }
            int def = backfill ? 30 : 10;
            int lim = (limit == null || limit <= 0) ? def : Math.min(limit, 50);

            // 认领先做：失败要说清是谁认的，别让调用方以为读到了就算拿到了
            StringBuilder head = new StringBuilder();
            if (claimId != null) {
                int ok = mailboxService.claim(claimId, me);
                if (ok == 1) {
                    head.append("🔒 已认领 #").append(claimId).append("\n");
                } else {
                    MailMessage c = mailboxService.byId(claimId);
                    head.append("⚠️ 认领失败：#").append(claimId);
                    if (c != null && c.getClaimedBy() != null) {
                        head.append(" 已被【").append(c.getClaimedBy()).append("】认领于 ")
                            .append(com.exphub.service.MailboxService.time(c.getClaimedAt()));
                    } else {
                        head.append(" 不存在");
                    }
                    head.append("。别重复接这单，先跟对方确认。\n");
                }
            }

            // 显式推进：只动游标，不返回正文（peek 之后的确认步）
            if (advanceTo != null) {
                long cursorBefore = mailboxService.cursorOf(me, threadId.trim());
                int moved = mailboxService.advanceCursorTo(me, threadId.trim(), advanceTo);
                long cursorAfter = mailboxService.cursorOf(me, threadId.trim());
                long left = mailboxService.countAfterCursor(me, threadId.trim());
                StringBuilder sb = new StringBuilder(head);
                if (moved == 1) {
                    sb.append("✅ 游标已推进：#").append(cursorBefore).append(" → #").append(cursorAfter);
                    if (cursorAfter > advanceTo) {
                        sb.append("（目标 #").append(advanceTo).append(" 已被更靠后的推进覆盖）");
                    }
                    sb.append("\n");
                } else {
                    sb.append("ℹ️ 游标未变：当前已读到 #").append(cursorAfter)
                      .append("，目标 #").append(advanceTo).append(" 不比它新（只前进不后退）。\n");
                }
                sb.append("该线程游标之后还有 ").append(left).append(" 封；本次没有返回正文，要读用 `mail_thread(threadId=\"")
                  .append(threadId.trim()).append("\")`。\n");
                appendAckReceipt(sb, ackIds, me);
                return sb.toString();
            }

            List<MailMessage> list = mailboxService.thread(me, threadId.trim(), backfill, lim, peekMode);
            long total = mailboxService.countThread(threadId.trim());

            if (list.isEmpty()) {
                return head + "📭 这根线程没有新消息（游标已到头，共 " + total + " 封）。要重看历史传 `full=true`。";
            }

            StringBuilder sb = new StringBuilder(head);
            sb.append("📬 线程 ").append(threadId.trim())
              .append("「").append(mailboxService.threadSubject(threadId.trim())).append("」")
              .append("　本批 ").append(list.size()).append(" 封 / 共 ").append(total).append(" 封\n\n");
            for (MailMessage m : list) {
                sb.append("---\n#").append(m.getId()).append(" [").append(m.getMsgType()).append("] ")
                  .append(m.getFromRole()).append(" → ").append(m.getToRole())
                  .append(" · ").append(com.exphub.service.MailboxService.time(m.getCreatedAt()));
                if (m.getConfigSourceId() != null) sb.append(" · 配置 ").append(m.getConfigSourceId());
                appendChainMeta(sb, m);
                if (m.getClaimedBy() != null) sb.append(" · 已认领(").append(m.getClaimedBy()).append(")");
                if (m.getProcessedAt() != null) sb.append(" · 已处理");
                sb.append("\n**").append(m.getSubject()).append("**\n")
                  .append(m.getBody() == null ? "" : m.getBody()).append("\n\n");
            }

            long tail = list.get(list.size() - 1).getId();
            if (peekMode) {
                long cursorNow = mailboxService.cursorOf(me, threadId.trim());
                sb.append("> 👀 peek 只读：游标**未推进**，仍停在 #").append(cursorNow)
                  .append("。确认这批已处理成功后调 `mail_thread(threadId=\"").append(threadId.trim())
                  .append("\", advanceTo=").append(tail).append(")` 才算读过；中途失败直接重来，这封信还在。\n");
            } else {
                sb.append("> 已读到 #").append(tail).append("，服务端游标已推进。**同样参数别重复调**，没有新信时会直接告诉你到头了。\n");
            }

            // ack：只记「处理过」，不推游标
            appendAckReceipt(sb, ackIds, me);
            return sb.toString();
        } catch (IllegalArgumentException iae) {
            return "❌ " + iae.getMessage();
        } catch (Exception e) {
            log.error("ExpHubTools.mailThread: FAILED", e);
            return "读线程失败: " + e.getClass().getName() + " - " + e.getMessage();
        }
    }

    /**
     * 线程回显授权链元数据。
     *
     * <p><b>只在有非默认值时才渲染</b>：chainId 为空、auto=false、depth=0 的消息（全部历史消息 +
     * 不传元数据的新消息）一个字符都不多印 —— 这是本单「不传时行为完全一致」的一部分
     * （老线程的展开结果必须逐字不变，否则等于修一个字段顺手改了所有人的回执）。
     */
    private static void appendChainMeta(StringBuilder sb, MailMessage m) {
        boolean hasChain = m.getChainId() != null && !m.getChainId().isBlank();
        boolean auto = Boolean.TRUE.equals(m.getAutoFlag());
        int depth = m.getChainDepth() == null ? 0 : m.getChainDepth();
        if (!hasChain && !auto && depth == 0) return;
        sb.append(" · 链 ").append(hasChain ? m.getChainId() : "（未记录）")
          .append(" · 第 ").append(depth).append(" 跳")
          .append(auto ? " · 自动回合" : " · 人工");
    }

    /** ack：只记「处理过」，不推游标。回执写实际成功几条，避免「传了≠标上了」。 */
    private void appendAckReceipt(StringBuilder sb, String ackIds, String me) {        if (ackIds == null || ackIds.isBlank()) return;
        int acked = 0;
        for (String t : ackIds.split("[,，\\s]+")) {
            if (t.isBlank()) continue;
            try {
                Long id = Long.parseLong(t.trim());
                int n = mailboxService.markProcessed(id, me);
                if (n > 0) acked++;
            } catch (NumberFormatException ignore) {
                // 非数字项跳过，不因为一个脏参数整封失败
            }
        }
        sb.append("> 已标记 ").append(acked).append(" 封为处理过（只是记录，不影响游标）。\n");
    }
}
