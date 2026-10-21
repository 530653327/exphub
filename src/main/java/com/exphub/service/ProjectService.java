package com.exphub.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.exphub.entity.AiAssistant;
import com.exphub.entity.Doc;
import com.exphub.entity.Project;
import com.exphub.interceptor.ApiKeyInterceptor;
import com.exphub.mapper.AiAssistantMapper;
import com.exphub.mapper.DocMapper;
import com.exphub.mapper.ProjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 项目空间服务：项目 CRUD、AGENTS.md 分片维护、进度条目化
 * 数据隔离：所有查询均按当前 API Key 过滤
 */
@Service
public class ProjectService {

    private static final Logger log = LoggerFactory.getLogger(ProjectService.class);

    @Autowired
    private ProjectMapper projectMapper;

    @Autowired
    private DocMapper docMapper;

    @Autowired
    private DocService docService;

    @Autowired
    private AiAssistantMapper assistantMapper;

    private String currentKey() {
        AiAssistant a = ApiKeyInterceptor.getCurrentAssistant();
        if (a == null) {
            // 身份缺失会跳过隔离校验，属安全相关，保留告警
            log.warn("currentKey: CURRENT_ASSISTANT is NULL（隔离校验将被跳过）, thread={}",
                    Thread.currentThread().getName());
        }
        return a != null ? a.getApiKey() : null;
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
        String key = currentKey();
        // 隔离校验：非同 Key 且非公开项目不可见
        if (key != null && p.getApiKey() != null && !key.equals(p.getApiKey())) {
            return null;
        }
        return p;
    }

    public Project updateProject(Long id, Project patch) {
        Project old = projectMapper.selectById(id);
        if (old == null) return null;
        String key = currentKey();
        if (key != null && old.getApiKey() != null && !key.equals(old.getApiKey())) {
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
     * 按项目名称 / 别名 / 编码解析项目，返回候选列表（可能多个）
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
                .or().like("name", q)
                .or().like("aliases", q));
        w.eq("status", "ACTIVE");
        return projectMapper.selectList(w);
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
     * 更新（或创建）章节。已存在则走 DocService.update 触发版本快照
     */
    public Doc updateAgentsChapter(Long projectId, String chapterNo, String chapterTitle,
                                   String content, String loadStrategy, Integer sortOrder, String summary) {
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
     */
    public List<Doc> listProgress(Long projectId, int limit) {
        QueryWrapper<Doc> w = new QueryWrapper<>();
        w.eq("project_id", projectId).eq("template_type", "project_progress")
         .orderByDesc("created_at").last("LIMIT " + Math.max(1, Math.min(limit, 50)));
        return docMapper.selectList(w);
    }

    /**
     * 列出项目专属经验
     */
    public List<Doc> listProjectDocs(Long projectId, String templateType) {
        QueryWrapper<Doc> w = new QueryWrapper<>();
        w.eq("project_id", projectId);
        if (templateType != null && !templateType.trim().isEmpty()) {
            w.eq("template_type", templateType.trim());
        }
        w.orderByDesc("updated_at").last("LIMIT 50");
        return docMapper.selectList(w);
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
        if (key != null && doc.getApiKey() != null && !key.equals(doc.getApiKey())) {
            return null;
        }
        // 先用 DocService.update 触发版本快照（内容不变，仅 version+1）
        Doc patch = new Doc();
        patch.setId(docId);
        patch.setSummary(doc.getSummary());
        docService.update(docId, patch);
        // MyBatis-Plus 默认忽略 null 字段，必须用 UpdateWrapper 显式置空才能解除项目归属
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
        String key = currentKey();
        if (key != null && p.getApiKey() != null && !key.equals(p.getApiKey())) {
            return 0; // 非同 Key 不可删
        }
        return projectMapper.deleteById(id);
    }
}
