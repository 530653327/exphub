package com.exphub.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.exphub.entity.AiAssistant;
import com.exphub.entity.Doc;
import com.exphub.entity.Project;
import com.exphub.interceptor.ApiKeyInterceptor;
import com.exphub.mapper.AiAssistantMapper;
import com.exphub.mapper.DocMapper;
import com.exphub.mapper.ProjectMapper;
import com.exphub.util.RenderArtifactStripper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 项目空间服务：项目 CRUD、AGENTS.md 分片维护、进度条目化
 * 数据隔离：所有查询均按当前 API Key 过滤
 */
@Service
public class ProjectService {

    private static final Logger log = LoggerFactory.getLogger(ProjectService.class);

    /**
     * L0-1：别名项参与项目判定的最小长度（归一化后）。
     * <p>api / ai 这类通用短词作为别名时会把整片项目域带进全局搜索，一律不参与判定。</p>
     */
    private static final int MIN_ALIAS_LEN = 3;

    @Autowired
    private ProjectMapper projectMapper;

    @Autowired
    private DocMapper docMapper;

    @Autowired
    private DocService docService;

    @Autowired
    private AiAssistantMapper assistantMapper;

    /**
     * E13：统一剥离渲染痕迹（历史导读 / 关联经验段 / 文档结尾标记 / 终端残留）。
     *
     * <p>剥离规则与 MCP 工具层共用 {@link RenderArtifactStripper}，避免两套实现漂移。
     * null / 空串原样返回——空内容可能是合法的「清空」意图，不能在这里悄悄改判。</p>
     */
    private static String sanitize(String content) {
        if (content == null || content.isEmpty()) {
            return content;
        }
        String cleaned = RenderArtifactStripper.strip(content);
        if (!cleaned.equals(content)) {
            // 用英文：服务器 nohup/systemd 下 LANG 未设置，中文日志会被写成 '?'，排查时看不到
            log.info("ProjectService.sanitize: stripped render artifacts {} -> {} chars",
                    content.length(), cleaned.length());
        }
        return cleaned;
    }

    private String currentKey() {
        AiAssistant a = ApiKeyInterceptor.getCurrentAssistant();
        if (a == null) {
            // 身份缺失会跳过隔离校验，属安全相关，保留告警
            log.warn("currentKey: CURRENT_ASSISTANT is NULL（隔离校验将被跳过）, thread={}",
                    Thread.currentThread().getName());
        }
        return a != null ? a.getApiKey() : null;
    }

    /**
     * 项目可见性判定（全服务唯一口径）：
     * <ul>
     *   <li>无 Key 上下文（后台 Web 会话）→ 放行；</li>
     *   <li>有 Key 上下文 → 必须与项目 api_key 严格相等；</li>
     *   <li>项目 api_key 为空（孤儿项目）→ 对任何 Key 都不可见，避免“人人可读”。</li>
     * </ul>
     */
    static boolean canAccess(Project project, String key) {
        if (project == null) return false;
        if (key == null) return true;
        return key.equals(project.getApiKey());
    }

    // ==================== 项目 CRUD ====================

    public Project createProject(Project project) {
        if (project.getStatus() == null || project.getStatus().isEmpty()) {
            project.setStatus("ACTIVE");
        }
        String key = currentKey();
        if (key == null) {
            // 后台（管理员）创建项目时没有 API Key 上下文，留空会导致该项目
            // 在 MCP list_projects 中永远不返回（api_key = 当前key 不匹配 NULL），
            // 成为「孤儿项目」。因此回退归属到主 Key。
            key = primaryKey();
        }
        if (key != null) {
            project.setApiKey(key);
        } else {
            log.warn("createProject: 没有可用的 API Key 归属，项目「{}」将以孤儿形式创建（MCP 侧不可见）",
                    project.getName());
        }
        projectMapper.insert(project);
        return project;
    }

    /**
     * 主 Key：第一个启用状态的助手 API Key。
     * 用于「后台创建项目」等无 API Key 上下文的场景，保证项目有明确归属、可被 MCP 列出。
     */
    private String primaryKey() {
        try {
            AiAssistant a = assistantMapper.selectOne(
                    new QueryWrapper<AiAssistant>().eq("enabled", 1).orderByAsc("id").last("LIMIT 1"));
            return a != null ? a.getApiKey() : null;
        } catch (Exception e) {
            return null;
        }
    }

    public List<Project> listProjects() {
        QueryWrapper<Project> w = new QueryWrapper<>();
        String key = currentKey();
        if (key != null) {
            w.eq("api_key", key);
        }
        w.eq("status", "ACTIVE").orderByDesc("updated_at");
        return projectMapper.selectList(w);
    }

    public Project getProject(Long id) {
        Project p = projectMapper.selectById(id);
        if (p == null) return null;
        if (!canAccess(p, currentKey())) {
            return null;
        }
        return p;
    }

    public Project updateProject(Long id, Project patch) {
        Project old = projectMapper.selectById(id);
        if (old == null) return null;
        if (!canAccess(old, currentKey())) {
            return null;
        }
        if (patch.getName() != null) old.setName(patch.getName());
        if (patch.getAliases() != null) old.setAliases(patch.getAliases());
        if (patch.getDescription() != null) old.setDescription(patch.getDescription());
        if (patch.getStatus() != null) old.setStatus(patch.getStatus());
        if (patch.getDefaultTestCmd() != null) old.setDefaultTestCmd(patch.getDefaultTestCmd());
        if (patch.getDefaultConstraints() != null) old.setDefaultConstraints(patch.getDefaultConstraints());
        projectMapper.updateById(old);
        return old;
    }

    /**
     * 按项目名称 / 别名 / 编码解析项目，返回候选列表（可能多个）。
     *
     * <p>L0-1：别名不再走 {@code aliases LIKE '%q%'}——整串 LIKE 会让通用词作为某个别名的子串命中
     * （{@code api} 命中别名 {@code xy-api}），把整个项目域带进全局搜索。现在按逗号切项、逐项精确比对。
     * name / project_code 两条路径保持原样。</p>
     */
    public List<Project> resolveProject(String nameOrAlias) {
        QueryWrapper<Project> w = new QueryWrapper<>();
        String key = currentKey();
        if (key != null) {
            w.eq("api_key", key);
        }
        String q = nameOrAlias.trim();
        w.and(x -> x.eq("name", q)
                .or().eq("project_code", q)
                .or().like("name", q));
        w.eq("status", "ACTIVE");
        List<Project> hits = new ArrayList<>(projectMapper.selectList(w));
        for (Project p : hits) {
            log.info("resolveProject: hit field={} project={}({}) item=\"{}\"",
                    q.equals(p.getName()) ? "name"
                            : q.equals(p.getProjectCode()) ? "project_code" : "name-like",
                    p.getName(), p.getId(), q);
        }
        Set<Long> seen = hits.stream().map(Project::getId).collect(Collectors.toSet());
        for (Project p : matchAlias(q, key)) {
            if (seen.add(p.getId())) {
                hits.add(p);
            }
        }
        return hits;
    }

    /**
     * 别名按项精确比对：逗号（含全角/顿号/分号）切项 → 归一 → 相等。
     * 单个项目可能命中多项，这里按项目去重；长度不足 {@link #MIN_ALIAS_LEN} 的别名项直接不参与。
     */
    private List<Project> matchAlias(String q, String key) {
        List<Project> out = new ArrayList<>();
        String needle = normalizeAlias(q);
        if (needle.length() < MIN_ALIAS_LEN) {
            return out;
        }
        QueryWrapper<Project> w = new QueryWrapper<>();
        if (key != null) {
            w.eq("api_key", key);
        }
        w.eq("status", "ACTIVE");
        for (Project p : projectMapper.selectList(w)) {
            String item = matchedAliasItem(p.getAliases(), q);
            if (item != null) {
                // 用英文：服务器 nohup/systemd 下 LANG 未设置，中文日志会写成 '?'
                log.info("resolveProject: hit field=aliases project={}({}) item=\"{}\"",
                        p.getName(), p.getId(), item);
                out.add(p);
            }
        }
        return out;
    }

    /**
     * 别名是否命中这个查询词。命中返回**别名项原文**（日志要能看出命中的是哪一项），不命中返回 null。
     *
     * <p>整串 LIKE 的坑：{@code api} 是 {@code xy-api} 的子串 → 一条通用词就能把整个项目域带进来。
     * 这里按项切分后做**等值**比对，子串关系一律不算命中；长度不足 {@link #MIN_ALIAS_LEN} 的别名项不参与。</p>
     */
    static String matchedAliasItem(String aliases, String query) {
        String needle = normalizeAlias(query);
        if (needle.length() < MIN_ALIAS_LEN) {
            return null;
        }
        for (String item : splitAliases(aliases)) {
            String n = normalizeAlias(item);
            if (n.length() < MIN_ALIAS_LEN) {
                continue;
            }
            if (n.equals(needle)) {
                return item;
            }
        }
        return null;
    }

    /**
     * #1272 C9：中文长 query 不带空格时，项目名/别名项常常整段**嵌在** token 中间，
     * 例如「工业大学校友会小程序架构说明」含有别名项「校友会小程序」。
     *
     * <p>原 {@code detectProjectInQuery} 按空白切词后只做「token 整串 == 名称 / name LIKE %token% /
     * 别名项等值」三种比对，方向全是「token 是名称的子串」；长句反过来（名称是 token 的子串）一律落空。
     * 这里补反向内嵌匹配，并保留三道防误召：① 只认含 CJK 的片段，纯 ASCII 结构性拒绝
     * （#678 §5.1：{@code api} 不得命中 {@code xy-api}）；② 片段两侧不能是 ASCII 词字符；
     * ③ 多个项目并列最长时宁可不报，也不猜。</p>
     *
     * <p><b>边界</b>：这是 C9「识别行可观测」的最小修复，不等同 #678 §5 的 L0-1a 三分支 ——
     * 本方法尚未接 df 常量表，L0-1a 落地时会补 df 门槛与 ②/③ 分支判据。</p>
     */
    public Project detectEmbeddedProject(String token) {
        return pickEmbeddedProject(listProjects(), token);
    }

    /** 纯函数版本：不查库、不依赖 API Key 上下文，便于离线单测直接构造项目列表。 */
    static Project pickEmbeddedProject(List<Project> projects, String token) {
        if (projects == null || token == null) return null;
        String needle = normalizeAlias(token);
        if (!containsCjk(needle) || needle.length() < MIN_ALIAS_LEN) return null;
        Project best = null;
        String bestItem = "";
        boolean tie = false;
        for (Project p : projects) {
            for (String item : candidateItems(p)) {
                String n = normalizeAlias(item);
                if (n.length() < MIN_ALIAS_LEN || !containsCjk(n)) continue;
                if (!embeddedHit(needle, n)) continue;
                if (n.length() > bestItem.length()) {
                    best = p;
                    bestItem = n;
                    tie = false;
                } else if (n.length() == bestItem.length() && best != null
                        && !java.util.Objects.equals(best.getId(), p.getId())) {
                    tie = true;         // 跨项目并列最长：不猜，返回 null
                }
            }
        }
        return tie ? null : best;
    }

    /** 内嵌匹配候选片：项目名 + 编码 + 别名逐项。 */
    static List<String> candidateItems(Project p) {
        List<String> out = new ArrayList<>();
        if (p == null) return out;
        if (p.getName() != null && !p.getName().isBlank()) out.add(p.getName());
        if (p.getProjectCode() != null && !p.getProjectCode().isBlank()) out.add(p.getProjectCode());
        out.addAll(splitAliases(p.getAliases()));
        return out;
    }

    /** 片段是否作为独立成分出现：两侧不能是 ASCII 字母/数字/下划线/连字符。 */
    static boolean embeddedHit(String token, String item) {
        if (token == null || item == null || item.isEmpty()) return false;
        int from = 0;
        while (true) {
            int p = token.indexOf(item, from);
            if (p < 0) return false;
            boolean leftOk = p == 0 || !isAsciiWord(token.charAt(p - 1));
            int end = p + item.length();
            boolean rightOk = end >= token.length() || !isAsciiWord(token.charAt(end));
            if (leftOk && rightOk) return true;
            from = p + 1;
        }
    }

    private static boolean isAsciiWord(char c) {
        return c < 128 && (Character.isLetterOrDigit(c) || c == '_' || c == '-');
    }

    private static boolean containsCjk(String s) {
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c >= '\u4e00' && c <= '\u9fff') return true;
        }
        return false;
    }

    /** 别名归一：全角转半角（NFKC）+ 去首尾空白 + 小写。 */
    private static String normalizeAlias(String s) {
        if (s == null) {
            return "";
        }
        return Normalizer.normalize(s, Normalizer.Form.NFKC).trim().toLowerCase(Locale.ROOT);
    }

    /** 别名拆项：半角/全角逗号、顿号、分号都当分隔符，逐项 trim 后丢弃空项。 */
    private static List<String> splitAliases(String aliases) {
        List<String> out = new ArrayList<>();
        if (aliases == null || aliases.isEmpty()) {
            return out;
        }
        for (String raw : aliases.split("[,，、;；]")) {
            String item = raw.trim();
            if (!item.isEmpty()) {
                out.add(item);
            }
        }
        return out;
    }

    // ==================== AGENTS.md 分片维护 ====================

    /**
     * 读取项目 AGENTS.md 的 MAIN 索引
     */
    public Doc getAgentsMain(Long projectId) {
        QueryWrapper<Doc> w = new QueryWrapper<>();
        w.eq("project_id", projectId).eq("doc_role", "MAIN")
         .orderByAsc("id").last("LIMIT 1");
        List<Doc> list = docMapper.selectList(w);
        return list.isEmpty() ? null : list.get(0);
    }

    /**
     * 读取指定章节
     */
    public Doc getAgentsChapter(Long projectId, String chapterNo) {
        QueryWrapper<Doc> w = new QueryWrapper<>();
        w.eq("project_id", projectId).eq("doc_role", "CHAPTER").eq("chapter_no", chapterNo)
         .orderByAsc("id").last("LIMIT 1");
        List<Doc> list = docMapper.selectList(w);
        return list.isEmpty() ? null : list.get(0);
    }

    /**
     * 列出项目 AGENTS.md 的所有章节（按章号排序）
     */
    public List<Doc> listAgentsChapters(Long projectId) {
        QueryWrapper<Doc> w = new QueryWrapper<>();
        w.eq("project_id", projectId).eq("doc_role", "CHAPTER")
         .orderByAsc("sort_order").orderByAsc("chapter_no");
        return docMapper.selectList(w);
    }

    /**
     * 章节号必填校验（抽成纯函数便于单测）。
     *
     * <p>为什么必须挡（2026-11-05 实测踩到，一次产生了 3 条脏数据）：
     * {@code chapterNo} 为空时，标题会被拼成「<b>第 null 章</b>」；更糟的是
     * {@link #getAgentsChapter} 按章号查不到已有记录，于是<b>每次调用都再新建一条</b>，
     * 同一章在库里躺好几份。实测 #1163 / #1164 / #1184 都是这么来的
     * （#1184 后来被人手工改成「（重复章节，内容已合并至第 1 章）」，但脏数据本身还在）。
     */
    static String requireChapterNo(String chapterNo) {
        if (chapterNo == null || chapterNo.trim().isEmpty()) {
            throw new IllegalArgumentException(
                    "章节号不能为空：为空会建出「第 null 章」，而且因为按章号查不到已有章节，"
                            + "每次调用都会再新建一条重复章节。请传 chapterNo（如 \"1\"）。");
        }
        return chapterNo.trim();
    }

    /**
     * 更新（或创建）章节。已存在则走 DocService.update 触发版本快照
     */
    public Doc updateAgentsChapter(Long projectId, String chapterNo, String chapterTitle,
                                   String content, String loadStrategy, Integer sortOrder, String summary) {
        // 空章号是「静默建重复章节」的入口，必须在最前面挡住，不能等到拼标题时才发现
        chapterNo = requireChapterNo(chapterNo);
        // E13：写入端剥离渲染痕迹。原先只有 ExpHubTools 的经验增删改/导入调用了剥离，
        // service 层的项目路径（章节 / MAIN / 进度）是漏网的，实测已踩脏 4 篇（252/293/515/585）
        content = sanitize(content);
        Doc exist = getAgentsChapter(projectId, chapterNo);
        if (exist != null) {
            Doc patch = new Doc();
            patch.setId(exist.getId());
            patch.setContent(content);
            if (chapterTitle != null) patch.setChapterTitle(chapterTitle);
            if (loadStrategy != null) patch.setLoadStrategy(loadStrategy);
            if (sortOrder != null) patch.setSortOrder(sortOrder);
            // 章节描述（自动生成的 AGENTS.md 目录用它作为一行说明），空则保持原值
            if (summary != null && !summary.trim().isEmpty()) patch.setSummary(summary.trim());
            return docService.update(exist.getId(), patch);
        }
        Doc doc = new Doc();
        doc.setProjectId(projectId);
        doc.setDocRole("CHAPTER");
        doc.setChapterNo(chapterNo);
        doc.setChapterTitle(chapterTitle != null ? chapterTitle : ("第 " + chapterNo + " 章"));
        doc.setLoadStrategy(loadStrategy != null ? loadStrategy : "ON_DEMAND");
        doc.setSortOrder(sortOrder != null ? sortOrder : 0);
        doc.setContent(content);
        doc.setTitle(chapterTitle != null ? chapterTitle : ("第 " + chapterNo + " 章"));
        doc.setTemplateType("project_agents");
        doc.setCategory("项目文档");
        doc.setTags("AGENTS.md,项目文档,章节");
        doc.setAliases(chapterNo + "章," + (chapterTitle != null ? chapterTitle : ""));
        doc.setSummary(summary != null && !summary.trim().isEmpty()
                ? summary.trim() : ("项目 AGENTS.md 第 " + chapterNo + " 章"));
        doc.setVisibility("PRIVATE");
        return docService.create(doc);
    }

    /**
     * 更新（或创建）MAIN 索引
     */
    public Doc updateAgentsMain(Long projectId, String content) {
        content = sanitize(content);   // E13：MAIN 与章节同属 AI 写入路径，同样要剥
        Doc exist = getAgentsMain(projectId);
        if (exist != null) {
            Doc patch = new Doc();
            patch.setId(exist.getId());
            patch.setContent(content);
            return docService.update(exist.getId(), patch);
        }
        Doc doc = new Doc();
        doc.setProjectId(projectId);
        doc.setDocRole("MAIN");
        doc.setLoadStrategy("ALWAYS");
        doc.setSortOrder(0);
        doc.setContent(content);
        doc.setTitle("AGENTS.md 索引");
        doc.setTemplateType("project_agents");
        doc.setCategory("项目文档");
        doc.setTags("AGENTS.md,索引,项目文档");
        doc.setAliases("MAIN,索引,项目手册");
        doc.setSummary("项目 AGENTS.md 索引，含章节目录与绝对红线");
        doc.setVisibility("PRIVATE");
        return docService.create(doc);
    }

    /**
     * 导出完整 AGENTS.md（MAIN + 所有章节按序拼接）
     */
    public String exportAgents(Long projectId) {
        StringBuilder sb = new StringBuilder();
        Doc main = getAgentsMain(projectId);
        if (main != null && main.getContent() != null) {
            sb.append(main.getContent()).append("\n\n---\n\n");
        }
        for (Doc ch : listAgentsChapters(projectId)) {
            sb.append("## ").append(ch.getChapterNo()).append(". ")
              .append(ch.getChapterTitle() != null ? ch.getChapterTitle() : ch.getTitle()).append("\n\n");
            if (ch.getContent() != null) {
                sb.append(ch.getContent()).append("\n\n");
            }
        }
        return sb.toString();
    }

    // ==================== 进度条目化 ====================

    /**
     * 追加一条进度 / 交接记录
     */
    public Doc appendProgress(Long projectId, String content, String title) {
        content = sanitize(content);   // E13：进度记录最常被「整段复制渲染视图」污染
        Doc doc = new Doc();
        doc.setProjectId(projectId);
        doc.setDocRole("NORMAL");
        doc.setTemplateType("project_progress");
        doc.setCategory("项目进度");
        doc.setTitle(title != null ? title : "进度记录");
        doc.setContent(content);
        doc.setTags("进度,交接,项目进度");
        doc.setAliases("进度记录,交接笔记");
        String summary = content.length() > 60 ? content.substring(0, 60) + "..." : content;
        doc.setSummary(summary);
        doc.setVisibility("PRIVATE");
        doc.setApiKey(currentKey());
        return docService.create(doc);
    }

    /**
     * 读取最近 N 条进度（按时间倒序）
     *
     * <p>⚠️ 这个方法**不过滤待删**，两个调用方语义不同，不要混用：
     * <ul>
     *   <li>后台删项目（ProjectController）—— 必须包含待删项，否则待删文档会变成孤儿</li>
     *   <li>MCP 读端 —— 必须用 {@link #listVisibleProgress(Long, int)}</li>
     * </ul>
     */
    public List<Doc> listProgress(Long projectId, int limit) {
        QueryWrapper<Doc> w = new QueryWrapper<>();
        w.eq("project_id", projectId).eq("template_type", "project_progress")
         .orderByDesc("created_at").last("LIMIT " + Math.max(1, Math.min(limit, 50)));
        return docMapper.selectList(w);
    }

    /**
     * 列出项目专属经验
     *
     * <p>⚠️ 这个方法**不过滤待删**，两个调用方语义不同，不要混用：
     * <ul>
     *   <li>后台删项目（ProjectController）—— 必须包含待删项，否则待删文档会变成孤儿</li>
     *   <li>MCP 的 delete_project 计数 —— 必须包含，否则项目只剩待删文档时会被误判为「已清空」而放行删除</li>
     *   <li>MCP 读端 list_project_experiences —— 必须用 {@link #listVisibleProjectDocs(Long, String)}</li>
     * </ul>
     */
    public List<Doc> listProjectDocs(Long projectId, String templateType) {
        QueryWrapper<Doc> w = new QueryWrapper<>();
        w.eq("project_id", projectId);
        if (templateType != null && !templateType.trim().isEmpty()) {
            w.eq("template_type", templateType.trim());
        }
        w.orderByDesc("updated_at");
        // ⚠️ 2026-10-26 12:1x 自查（阁主要求「计数必须对得上」时顺带发现）：
        //    这里原来硬编码 `LIMIT 50`，而 `list_project_experiences` 的回执写「共 N 条」用的是
        //    这个 list 的 size —— 项目 #1 实际 52 条，回执却说「共 50 条专属经验」，
        //    而且 offset 翻到 50 以后永远拿不到东西（分页被悄悄截断）。
        //    上限只用于防爆内存，量级要远大于真实项目文档数。
        w.last("LIMIT 2000");
        return docMapper.selectList(w);
    }

    // ==================== 读端口径：待删项对读端不可见 ====================
    //
    // ⚠️ 2026-10-26 16:5x 阁主实跑标记链路发现（#674 期间）：
    //    标记待删后，search_experience 立刻搜不到（检索只查 ACTIVE），
    //    但 list_project_experiences 照样把它排在项目列表第一位 ——
    //    **同一个状态字段，两个出口两套口径**。搜索认它、列表不认。
    //    后果不是「多显示一条」，而是下一个 AI 读到它会当正常文档用，
    //    而它已经在待删队列里等着人确认。
    //
    //    口径定死：**凡「MCP 读端」列出文档，一律排除 PENDING_DELETE。**
    //    例外只有一个：按 ID 定向读取（get_experience_detail / export_document）——
    //    那里不能藏（否则用户会以为文档凭空消失），但**必须显示待删状态与原因**
    //    （见 ExpHubTools.renderStatusBlock）。

    /**
     * 项目专属经验里「读端可见」的部分（排除已标记待删）。
     *
     * <p>SQL 条件写在 {@link #visibleProjectDocsQuery(Long, String)} 里，
     * 单测直接断言那段 SQL —— 断言字符串常量等于什么都没验。
     */
    public List<Doc> listVisibleProjectDocs(Long projectId, String templateType) {
        return docMapper.selectList(visibleProjectDocsQuery(projectId, templateType));
    }

    /**
     * 项目进度里「读端可见」的部分（排除已标记待删）。
     */
    public List<Doc> listVisibleProgress(Long projectId, int limit) {
        return docMapper.selectList(visibleProgressQuery(projectId, limit));
    }

    /**
     * 项目下已标记待删的文档数（用于回执把口径说全）。
     *
     * <p>为什么要单独数：读端列表排除了待删项，若回执只写「共 N 条」，
     * 而后台待删队列里还有 M 条，数字就对不上（阁主反复强调「计数必须对得上」）。
     */
    public long countPendingDeleteInProject(Long projectId, String templateType) {
        QueryWrapper<Doc> w = new QueryWrapper<>();
        w.eq("project_id", projectId).eq("status", DocService.PENDING_DELETE);
        if (templateType != null && !templateType.trim().isEmpty()) {
            w.eq("template_type", templateType.trim());
        }
        return docMapper.selectCount(w);
    }

    /**
     * 可见项目文档的查询条件（排除待删）。
     *
     * <p>抽成包级静态是为了让单测能断言**生成的 SQL**，
     * 而不是断言「代码里写了 ne(...)」这种自证式检查。
     */
    static QueryWrapper<Doc> visibleProjectDocsQuery(Long projectId, String templateType) {
        QueryWrapper<Doc> w = new QueryWrapper<>();
        w.eq("project_id", projectId);
        if (templateType != null && !templateType.trim().isEmpty()) {
            w.eq("template_type", templateType.trim());
        }
        w.ne("status", DocService.PENDING_DELETE);
        w.orderByDesc("updated_at");
        w.last("LIMIT 2000");
        return w;
    }

    /** 可见项目进度的查询条件（排除待删） */
    static QueryWrapper<Doc> visibleProgressQuery(Long projectId, int limit) {
        QueryWrapper<Doc> w = new QueryWrapper<>();
        w.eq("project_id", projectId).eq("template_type", "project_progress")
         .ne("status", DocService.PENDING_DELETE)
         .orderByDesc("created_at").last("LIMIT " + Math.max(1, Math.min(limit, 50)));
        return w;
    }

    /**
     * 获取系统工作流模板正文（template_type = workflow_template 的全局经验）
     * 未配置时返回 null，由调用方用内置模板兜底
     */
    public String getWorkflowTemplate() {
        QueryWrapper<Doc> w = new QueryWrapper<>();
        w.eq("template_type", "workflow_template").isNull("project_id")
         .orderByDesc("updated_at").last("LIMIT 1");
        List<Doc> list = docMapper.selectList(w);
        return list.isEmpty() ? null : list.get(0).getContent();
    }

    /**
     * 把项目经验提升为全局经验（清除项目归属）
     */
    public Doc promoteToGlobal(Long docId) {
        Doc doc = docMapper.selectById(docId);
        if (doc == null) return null;
        String key = currentKey();
        if (doc.getProjectId() != null) {
            // 项目文档以项目归属为隔离边界；项目 api_key 为空时对任何 Key 都不可见，
            // 避免任意 Key 把别人的项目文档“提升”为全局经验。
            if (getProject(doc.getProjectId()) == null) {
                return null;
            }
        } else if (key != null && !key.equals(doc.getApiKey())) {
            return null;
        }
        // 先用 DocService.update 触发版本快照（内容不变，仅 version+1）
        Doc patch = new Doc();
        patch.setId(docId);
        patch.setSummary(doc.getSummary());
        docService.update(docId, patch);
        // MyBatis-Plus 默认忽略 null 字段，必须用 UpdateWrapper 显式置空才能解除项目归属
        // GUARD:ALLOW-ONUPDATE-REFRESH 本方法上一句 docService.update() 已经刷新过 updated_at，
        // 这里只是把章节字段置空的补充 UPDATE，同一次操作内再刷一次不影响语义。
        docMapper.update(null, new com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper<Doc>()
                .eq("id", docId)
                .set("project_id", null)
                .set("doc_role", "NORMAL")
                .set("chapter_no", null)
                .set("chapter_title", null));
        return docMapper.selectById(docId);
    }

    /**
     * 删除项目（其下文档由调用方先清理）
     */
    public int deleteProject(Long id) {
        Project p = projectMapper.selectById(id);
        if (p == null) return 0;
        if (!canAccess(p, currentKey())) {
            return 0; // 非同 Key 不可删
        }
        return projectMapper.deleteById(id);
    }
}
