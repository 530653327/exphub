package com.exphub.controller;

import com.exphub.entity.User;
import com.exphub.entity.Doc;
import com.exphub.entity.AiAssistant;
import com.exphub.mapper.DocMapper;
import com.exphub.service.AiAssistantService;
import com.exphub.service.CallLogService;
import com.exphub.service.DocService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;

import javax.servlet.http.HttpSession;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Controller
@RequestMapping("")
public class PageController {

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
    public String dashboard(HttpSession session, Model model) {
        User user = (User) session.getAttribute("user");
        if (user == null) return "redirect:/login";

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
                       Model model) {
        User user = (User) session.getAttribute("user");
        if (user == null) return "redirect:/login";

        model.addAttribute("active", "docs");
        model.addAttribute("pageTitle", "经验管理");
        model.addAttribute("keyword", keyword != null ? keyword : "");

        List<Doc> docs;
        if (keyword != null && !keyword.trim().isEmpty()) {
            // 搜索模式
            com.baomidou.mybatisplus.extension.plugins.pagination.Page<Doc> result =
                docService.search(keyword, 1, 100);
            docs = result.getRecords();
        } else {
            // 普通列表
            docs = docMapper.selectList(
                new com.baomidou.mybatisplus.core.conditions.query.QueryWrapper<Doc>()
                    .orderByDesc("updated_at")
            );
        }
        model.addAttribute("docs", docs);

        return "docs/list";
    }

    // 新建/编辑经验
    @GetMapping("/docs/edit")
    public String docEdit(HttpSession session, 
                          @RequestParam(required = false) Long id,
                          Model model) {
        User user = (User) session.getAttribute("user");
        if (user == null) return "redirect:/login";

        model.addAttribute("active", "docs");
        if (id != null) {
            model.addAttribute("pageTitle", "编辑经验");
            model.addAttribute("doc", docMapper.selectById(id));
        } else {
            model.addAttribute("pageTitle", "新建经验");
        }
        return "docs/edit";
    }

    // 经验详情
    @GetMapping("/docs/{id}")
    public String docView(HttpSession session, @org.springframework.web.bind.annotation.PathVariable Long id, Model model) {
        User user = (User) session.getAttribute("user");
        if (user == null) return "redirect:/login";

        model.addAttribute("active", "docs");
        model.addAttribute("pageTitle", "经验详情");
        model.addAttribute("doc", docMapper.selectById(id));
        return "docs/view";
    }

    // 助手管理
    @GetMapping("/assistants")
    public String assistants(HttpSession session, Model model) {
        User user = (User) session.getAttribute("user");
        if (user == null) return "redirect:/login";

        model.addAttribute("active", "assistants");
        model.addAttribute("pageTitle", "助手管理");

        List<AiAssistant> assistants = assistantService.list(1, 100).getRecords();
        model.addAttribute("assistants", assistants);

        return "assistants/list";
    }

    // 助手新建
    @GetMapping("/assistants/add")
    public String assistantAdd(HttpSession session, Model model) {
        User user = (User) session.getAttribute("user");
        if (user == null) return "redirect:/login";

        model.addAttribute("active", "assistants");
        model.addAttribute("pageTitle", "新建助手");
        return "assistants/add";
    }

    // 助手创建成功结果页
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
    public String logs(HttpSession session, Model model) {
        User user = (User) session.getAttribute("user");
        if (user == null) return "redirect:/login";

        model.addAttribute("active", "logs");
        model.addAttribute("pageTitle", "调用日志");
        return "logs/list";
    }

    // 退出登录
    @GetMapping("/logout")
    public String logout(HttpSession session) {
        session.invalidate();
        return "redirect:/login";
    }
}