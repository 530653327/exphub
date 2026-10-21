package com.exphub.controller;

import com.exphub.entity.Doc;
import com.exphub.entity.Project;
import com.exphub.service.DocService;
import com.exphub.service.ProjectService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 后台：项目管理
 * 列表、详情、新建/编辑、AGENTS.md 分片维护、进度记录
 */
@Controller
@RequestMapping("/projects")
public class ProjectController extends BaseController {

    @Autowired
    private ProjectService projectService;

    @Autowired
    private DocService docService;

    /** 项目列表 */
    @GetMapping
    public String list(Model model) {
        List<Project> projects = projectService.listProjects();
        model.addAttribute("projects", projects);
        model.addAttribute("active", "projects");
        return "projects/list";
    }

    /** 项目详情 */
    @GetMapping("/{id}")
    public String detail(@PathVariable Long id, Model model, RedirectAttributes ra) {
        Project project = projectService.getProject(id);
        if (project == null) {
            ra.addFlashAttribute("error", "项目不存在");
            return "redirect:/projects";
        }
        model.addAttribute("project", project);
        model.addAttribute("main", projectService.getAgentsMain(id));
        model.addAttribute("chapters", projectService.listAgentsChapters(id));
        model.addAttribute("progress", projectService.listProgress(id, 10));
        model.addAttribute("docs", projectService.listProjectDocs(id, null));
        model.addAttribute("active", "projects");
        return "projects/detail";
    }

    /** 新建或更新项目（表单提交） */
    @PostMapping("/save")
    public String save(@RequestParam(required = false) Long id,
                       @RequestParam String name,
                       @RequestParam String projectCode,
                       @RequestParam(required = false) String aliases,
                       @RequestParam(required = false) String description,
                       @RequestParam(required = false) String defaultTestCmd,
                       @RequestParam(required = false) String defaultConstraints,
                       RedirectAttributes ra) {
        try {
            if (id == null) {
                Project p = new Project();
                p.setName(name);
                p.setProjectCode(projectCode);
                p.setAliases(aliases);
                p.setDescription(description);
                p.setDefaultTestCmd(defaultTestCmd);
                p.setDefaultConstraints(defaultConstraints);
                projectService.createProject(p);
                ra.addFlashAttribute("msg", "项目创建成功");
                return "redirect:/projects/" + p.getId();
            } else {
                Project patch = new Project();
                patch.setName(name);
                patch.setAliases(aliases);
                patch.setDescription(description);
                patch.setDefaultTestCmd(defaultTestCmd);
                patch.setDefaultConstraints(defaultConstraints);
                projectService.updateProject(id, patch);
                ra.addFlashAttribute("msg", "项目已更新");
                return "redirect:/projects/" + id;
            }
        } catch (Exception e) {
            ra.addFlashAttribute("error", "保存失败: " + e.getMessage());
            return "redirect:/projects";
        }
    }

    /** 保存 AGENTS.md MAIN 索引 */
    @PostMapping("/{id}/agents/main")
    public String saveMain(@PathVariable Long id, @RequestParam String content, RedirectAttributes ra) {
        projectService.updateAgentsMain(id, content);
        ra.addFlashAttribute("msg", "MAIN 索引已保存");
        return "redirect:/projects/" + id;
    }

    /** 保存章节（新增或更新） */
    @PostMapping("/{id}/agents/chapter")
    public String saveChapter(@PathVariable Long id,
                              @RequestParam String chapterNo,
                              @RequestParam String chapterTitle,
                              @RequestParam String content,
                              @RequestParam(required = false, defaultValue = "ON_DEMAND") String loadStrategy,
                              @RequestParam(required = false) Integer sortOrder,
                              @RequestParam(required = false) String summary,
                              RedirectAttributes ra) {
        projectService.updateAgentsChapter(id, chapterNo, chapterTitle, content, loadStrategy, sortOrder, summary);
        ra.addFlashAttribute("msg", "第 " + chapterNo + " 章已保存");
        return "redirect:/projects/" + id;
    }

    /** 删除章节 */
    @PostMapping("/{id}/agents/chapter/delete")
    public String deleteChapter(@PathVariable Long id, @RequestParam Long docId, RedirectAttributes ra) {
        docService.delete(docId);
        ra.addFlashAttribute("msg", "章节已删除");
        return "redirect:/projects/" + id;
    }

    /** 追加进度 */
    @PostMapping("/{id}/progress")
    public String addProgress(@PathVariable Long id,
                              @RequestParam String content,
                              @RequestParam(required = false) String title,
                              RedirectAttributes ra) {
        projectService.appendProgress(id, content, title);
        ra.addFlashAttribute("msg", "进度已记录");
        return "redirect:/projects/" + id;
    }

    /** 归档项目 */
    @PostMapping("/{id}/archive")
    public String archive(@PathVariable Long id, RedirectAttributes ra) {
        Project patch = new Project();
        patch.setStatus("ARCHIVED");
        projectService.updateProject(id, patch);
        ra.addFlashAttribute("msg", "项目已归档");
        return "redirect:/projects";
    }

    /** 删除项目（同时删除其下文档） */
    @PostMapping("/{id}/delete")
    public String delete(@PathVariable Long id, RedirectAttributes ra) {
        try {
            for (Doc d : projectService.listProjectDocs(id, null)) {
                docService.delete(d.getId());
            }
            Doc main = projectService.getAgentsMain(id);
            if (main != null) docService.delete(main.getId());
            for (Doc c : projectService.listAgentsChapters(id)) {
                docService.delete(c.getId());
            }
            for (Doc p : projectService.listProgress(id, 50)) {
                docService.delete(p.getId());
            }
            projectService.deleteProject(id);
            ra.addFlashAttribute("msg", "项目已删除");
        } catch (Exception e) {
            ra.addFlashAttribute("error", "删除失败: " + e.getMessage());
        }
        return "redirect:/projects";
    }

    /** 生成工作流提示词（后台预览，返回文本） */
    @PostMapping("/{id}/workflow")
    @ResponseBody
    public Map<String, Object> workflow(@PathVariable Long id,
                                        @RequestParam String task,
                                        @RequestParam(required = false) String files,
                                        @RequestParam(required = false) String criteria,
                                        @RequestParam(required = false, defaultValue = "M") String level,
                                        @RequestParam(required = false) String constraints) {
        Map<String, Object> r = new HashMap<>();
        Project p = projectService.getProject(id);
        if (p == null) {
            r.put("success", false);
            r.put("message", "项目不存在");
            return r;
        }
        String body = projectService.getWorkflowTemplate();
        if (body == null || body.trim().isEmpty()) {
            body = "(未配置系统模板，需在 exphub 中创建 template_type=workflow_template 的经验)";
        }
        StringBuilder sb = new StringBuilder();
        sb.append("【本次任务参数】\n");
        sb.append("- 项目：").append(p.getName()).append("\n");
        sb.append("- 任务：").append(task).append("\n");
        sb.append("- 涉及文件：\n").append(files == null ? "（待补充）" : files).append("\n");
        sb.append("- 验收标准：\n").append(criteria == null ? "（待补充）" : criteria).append("\n");
        sb.append("- 约束：\n").append(p.getDefaultConstraints() == null ? "（见 AGENTS.md）" : p.getDefaultConstraints());
        if (constraints != null && !constraints.isEmpty()) sb.append("\n").append(constraints);
        sb.append("\n- 任务级别：").append(level).append("\n");
        sb.append("- 测试命令：").append(p.getDefaultTestCmd() == null ? "（见 AGENTS.md）" : p.getDefaultTestCmd()).append("\n\n");
        sb.append(body);
        r.put("success", true);
        r.put("prompt", sb.toString());
        return r;
    }
}
