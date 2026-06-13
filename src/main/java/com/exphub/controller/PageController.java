package com.exphub.controller;

import com.exphub.entity.User;
import com.exphub.entity.Doc;
import com.exphub.entity.AiAssistant;
import com.exphub.mapper.DocMapper;
import com.exphub.service.AiAssistantService;
import com.exphub.service.CallLogService;
import com.exphub.service.DocService;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import java.util.List;
import java.util.Map;

@Controller
@RequestMapping("")
public class PageController extends BaseController {

    @Autowired
    private DocMapper docMapper;

    @Autowired
    private DocService docService;

    @Autowired
    private CallLogService callLogService;

    @Autowired
    private AiAssistantService assistantService;

    // 登录页
    @GetMapping("/login")
    public String loginPage(HttpSession session, Model model) {
        User user = (User) session.getAttribute("user");
        if (user != null) {
            return "redirect:/dashboard";
        }
        model.addAttribute("active", "login");
        return "login";
    }

    // Dashboard 仪表盘
    @GetMapping("/dashboard")
    public String dashboard(HttpSession session, Model model, HttpServletRequest request) {
        User user = (User) session.getAttribute("user");
        if (user == null) return "redirect:/login";

        addContextPath(model, request);
        model.addAttribute("active", "dashboard");
        model.addAttribute("pageTitle", "仪表盘");

        // 统计数据
        Map<String, Object> stats = callLogService.getOverview();
        long totalDocs = docMapper.selectCount(null);
        long problemDocs = docService.countProblemDocs();

        model.addAttribute("totalDocs", totalDocs);
        model.addAttribute("totalCalls", stats.get("totalCalls"));
        model.addAttribute("todayCalls", stats.get("todayCalls"));
        model.addAttribute("successRate", String.format("%.1f", ((Double) stats.get("successRate")) * 100));
        model.addAttribute("problemDocs", problemDocs);
        model.addAttribute("totalAssistants", assistantService.count());

        // 热门文档
        List<Doc> hotDocs = docMapper.selectList(
            new com.baomidou.mybatisplus.core.conditions.query.QueryWrapper<Doc>()
                .orderByDesc("call_count")
                .last("LIMIT 10")
        );
        model.addAttribute("hotDocs", hotDocs);

        return "dashboard";
    }

    // 经验列表
    @GetMapping("/docs")
    public String docs(HttpSession session,
                       @RequestParam(required = false) String keyword,
                       @RequestParam(required = false) String templateType,
                       @RequestParam(defaultValue = "1") int page,
                       Model model, HttpServletRequest request) {
        User user = (User) session.getAttribute("user");
        if (user == null) return "redirect:/login";

        addContextPath(model, request);
        model.addAttribute("active", "docs");
        model.addAttribute("pageTitle", "经验管理");
        model.addAttribute("keyword", keyword != null ? keyword : "");
        model.addAttribute("templateType", templateType != null ? templateType : "");

        Page<Doc> result;
        if (keyword != null && !keyword.trim().isEmpty()) {
            String kw = keyword.trim();
            // 如果输入的是纯数字，先尝试按 ID 精确查找
            try {
                Long id = Long.valueOf(kw);
                Doc doc = docMapper.selectById(id);
                if (doc != null) {
                    result = new Page<>(1, 20, 1);
                    result.setRecords(java.util.Collections.singletonList(doc));
                } else {
                    // ID 不存在，返回空结果
                    result = new Page<>(1, 20, 0);
                }
            } catch (NumberFormatException e) {
                // 不是纯数字，走模糊搜索
                String tt = (templateType != null && !templateType.trim().isEmpty()) ? templateType.trim() : null;
                result = docService.search(keyword, tt, page, 20);
            }
        } else {
            com.baomidou.mybatisplus.core.conditions.query.QueryWrapper<Doc> wrapper =
                new com.baomidou.mybatisplus.core.conditions.query.QueryWrapper<Doc>()
                    .orderByDesc("updated_at");
            if (templateType != null && !templateType.trim().isEmpty()) {
                wrapper.eq("template_type", templateType.trim());
            }
            result = docMapper.selectPage(new Page<>(page, 20), wrapper);
        }
        model.addAttribute("docs", result.getRecords());
        model.addAttribute("currentPage", result.getCurrent());
        model.addAttribute("totalPages", result.getPages());
        model.addAttribute("totalRows", result.getTotal());

        return "docs/list";
    }

    // 新建/编辑经验
    @GetMapping("/docs/edit")
    public String docEdit(HttpSession session, 
                          @RequestParam(required = false) Long id,
                          Model model, HttpServletRequest request) {
        User user = (User) session.getAttribute("user");
        if (user == null) return "redirect:/login";

        addContextPath(model, request);
        model.addAttribute("active", "docs");
        if (id != null) {
            model.addAttribute("pageTitle", "编辑经验");
            model.addAttribute("doc", docMapper.selectById(id));
        } else {
            model.addAttribute("pageTitle", "新建经验");
            model.addAttribute("doc", new Doc());
        }
        return "docs/edit";
    }

    // 经验详情
    @GetMapping("/docs/{id}")
    public String docView(HttpSession session, @PathVariable Long id, Model model, HttpServletRequest request) {
        User user = (User) session.getAttribute("user");
        if (user == null) return "redirect:/login";

        addContextPath(model, request);
        model.addAttribute("active", "docs");
        model.addAttribute("pageTitle", "经验详情");
        model.addAttribute("doc", docMapper.selectById(id));
        model.addAttribute("versions", docService.getVersions(id));
        return "docs/view";
    }

    // 秘钥管理
    @GetMapping("/assistants")
    public String assistants(HttpSession session,
                             @RequestParam(defaultValue = "1") int page,
                             Model model, HttpServletRequest request) {
        User user = (User) session.getAttribute("user");
        if (user == null) return "redirect:/login";

        addContextPath(model, request);
        model.addAttribute("active", "assistants");
        model.addAttribute("pageTitle", "秘钥管理");

        Page<AiAssistant> result = assistantService.list(page, 20);
        model.addAttribute("assistants", result.getRecords());
        model.addAttribute("currentPage", result.getCurrent());
        model.addAttribute("totalPages", result.getPages());
        model.addAttribute("totalRows", result.getTotal());

        return "assistants/list";
    }

    // 新建秘钥
    @GetMapping("/assistants/add")
    public String assistantAdd(HttpSession session, Model model, HttpServletRequest request) {
        User user = (User) session.getAttribute("user");
        if (user == null) return "redirect:/login";

        addContextPath(model, request);
        model.addAttribute("active", "assistants");
        model.addAttribute("pageTitle", "新建秘钥");
        return "assistants/add";
    }

    // 秘钥创建成功结果页
    @GetMapping("/assistants/created")
    public String assistantCreated(HttpSession session,
                                   @RequestParam String apiKey,
                                   @RequestParam String apiKeySecret,
                                   Model model) {
        User user = (User) session.getAttribute("user");
        if (user == null) return "redirect:/login";

        model.addAttribute("active", "assistants");
        model.addAttribute("pageTitle", "创建成功");
        model.addAttribute("apiKey", apiKey);
        model.addAttribute("apiKeySecret", apiKeySecret);
        return "assistants/created";
    }

    // 调用日志
    @GetMapping("/logs")
    public String logs(HttpSession session, Model model, HttpServletRequest request) {
        User user = (User) session.getAttribute("user");
        if (user == null) return "redirect:/login";

        addContextPath(model, request);
        model.addAttribute("active", "logs");
        model.addAttribute("pageTitle", "调用日志");
        return "logs/list";
    }

    // 待办看板
    @GetMapping("/kanban")
    public String kanban(HttpSession session, Model model, HttpServletRequest request) {
        User user = (User) session.getAttribute("user");
        if (user == null) return "redirect:/login";

        addContextPath(model, request);
        model.addAttribute("active", "kanban");
        model.addAttribute("pageTitle", "待办看板");
        return "kanban/index";
    }

    // 模板管理
    @GetMapping("/templates")
    public String templates(HttpSession session, Model model, HttpServletRequest request) {
        User user = (User) session.getAttribute("user");
        if (user == null) return "redirect:/login";

        addContextPath(model, request);
        model.addAttribute("active", "templates");
        model.addAttribute("pageTitle", "模板管理");
        return "templates/list";
    }

    // 修改密码页面
    @GetMapping("/profile/password")
    public String changePasswordPage(HttpSession session, Model model, HttpServletRequest request) {
        User user = (User) session.getAttribute("user");
        if (user == null) return "redirect:/login";

        addContextPath(model, request);
        model.addAttribute("active", "profile");
        model.addAttribute("pageTitle", "修改密码");
        return "profile/password";
    }

    // 退出登录
    @GetMapping("/logout")
    public String logout(HttpSession session) {
        session.invalidate();
        return "redirect:/login";
    }

    // 删除经验（后台管理）
    @PostMapping("/docs/{id}/delete")
    public String deleteDoc(HttpSession session, @PathVariable Long id) {
        User user = (User) session.getAttribute("user");
        if (user == null) return "redirect:/login";

        docService.delete(id);
        return "redirect:/docs";
    }
}
