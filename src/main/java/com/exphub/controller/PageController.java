package com.exphub.controller;

import com.exphub.entity.User;
import com.exphub.entity.Doc;
import com.exphub.entity.ReviewCandidate;
import com.exphub.entity.AiAssistant;
import com.exphub.mapper.DocMapper;
import com.exphub.service.AiAssistantService;
import com.exphub.service.CallLogService;
import com.exphub.service.DocService;
import com.exphub.service.ReviewCandidateService;
import com.exphub.service.SimilarityScorer;
import com.exphub.service.ToolStatsService;
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

    @Autowired
    private ToolStatsService toolStatsService;

    @Autowired
    private ReviewCandidateService reviewCandidateService;

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
                       @RequestParam(required = false) String status,
                       @RequestParam(required = false) String flag,
                       @RequestParam(required = false) String view,
                       @RequestParam(defaultValue = "1") int page,
                       Model model, HttpServletRequest request) {
        User user = (User) session.getAttribute("user");
        if (user == null) return "redirect:/login";

        addContextPath(model, request);
        model.addAttribute("active", "docs");
        model.addAttribute("pageTitle", "经验管理");
        model.addAttribute("keyword", keyword != null ? keyword : "");
        model.addAttribute("templateType", templateType != null ? templateType : "");
        model.addAttribute("status", status != null ? status : "");
        model.addAttribute("flag", flag != null ? flag : "");
        model.addAttribute("view", view != null ? view : "");

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
            // M10：status 筛选同时承担「待删队列」入口（/docs?status=PENDING_DELETE）
            if (status != null && !status.trim().isEmpty()) {
                wrapper.eq("status", status.trim());
            }
            // set_flags：按标记筛选（/docs?flag=needs_review）。flags 是逗号分隔的多值列，故用 like
            if (flag != null && !flag.trim().isEmpty()) {
                wrapper.like("flags", flag.trim());
            }
            result = docMapper.selectPage(new Page<>(page, 20), wrapper);
        }
        model.addAttribute("docs", result.getRecords());
        model.addAttribute("currentPage", result.getCurrent());
        model.addAttribute("totalPages", result.getPages());
        model.addAttribute("totalRows", result.getTotal());

        // M10：待删数量用于顶部入口的角标（AI 标记后用户能看到「有几条等确认」）
        model.addAttribute("pendingDeleteCount",
                docMapper.selectCount(new com.baomidou.mybatisplus.core.conditions.query.QueryWrapper<Doc>()
                        .eq("status", DocService.PENDING_DELETE)));

        // set_flags：待复核数量用于顶部入口角标（整理记忆时第一眼看这里）
        model.addAttribute("needsReviewCount",
                docMapper.selectCount(new com.baomidou.mybatisplus.core.conditions.query.QueryWrapper<Doc>()
                        .like("flags", "needs_review")));

        // D1 施工包 3：整理候选队列（/docs?view=candidates）
        // 角标常驻，因为候选队列的价值就在于「有人会去看」，藏在二级入口等于没有
        model.addAttribute("candidateCount", reviewCandidateService.pendingCount());
        if ("candidates".equalsIgnoreCase(view)) {
            java.util.List<ReviewCandidate> candidates = reviewCandidateService.listByStatus(ReviewCandidate.PENDING, 50);
            java.util.Set<Long> ids = new java.util.LinkedHashSet<>();
            for (ReviewCandidate c : candidates) { ids.add(c.getDocA()); ids.add(c.getDocB()); }
            java.util.Map<Long, Doc> docMap = new java.util.HashMap<>();
            for (Doc d : docService.listByIds(ids)) docMap.put(d.getId(), d);
            model.addAttribute("candidates", candidates);
            model.addAttribute("candDocMap", docMap);
        }

        // A3：版本表占用（只读展示，让「快照在膨胀」这件事可见；清理动作不在界面里，删正文不可逆）
        model.addAttribute("versionStats", docService.versionStats());

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

    // MCP 工具使用统计看板（观察期数据：哪些工具被用、哪些是僵尸工具）
    @GetMapping("/tool-stats")
    public String toolStats(HttpSession session, Model model, HttpServletRequest request,
                            @RequestParam(value = "days", defaultValue = "30") int days) {
        User user = (User) session.getAttribute("user");
        if (user == null) return "redirect:/login";

        addContextPath(model, request);
        model.addAttribute("active", "toolStats");
        model.addAttribute("pageTitle", "工具统计");

        int window = (days < 1 || days > 365) ? 30 : days;
        Map<String, Object> data = toolStatsService.getDashboard(window);
        model.addAttribute("overview", data.get("overview"));
        model.addAttribute("ranking", data.get("ranking"));
        model.addAttribute("zeroTools", data.get("zeroTools"));
        model.addAttribute("unknownCalled", data.get("unknownCalled"));
        model.addAttribute("trend", data.get("trend"));
        model.addAttribute("byAssistant", data.get("byAssistant"));
        model.addAttribute("days", window);
        model.addAttribute("lowFreqThreshold", data.get("lowFreqThreshold"));
        return "tool-stats";
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

    // D1 施工包 3：处理一条整理候选
    @PostMapping("/candidates/{id}/resolve")
    public String resolveCandidate(HttpSession session,
                                   @PathVariable Long id,
                                   @RequestParam String action,
                                   @RequestParam(required = false) String note) {
        User user = (User) session.getAttribute("user");
        if (user == null) return "redirect:/login";

        String by = user.getDisplayName() != null ? user.getDisplayName() : user.getUsername();
        reviewCandidateService.resolve(id, action, note, by);
        return "redirect:/docs?view=candidates";
    }

    /**
     * 候选对比页（2026-11-05 越哥要的）：两篇左右并排，相同行不上色、不同行标红。
     *
     * <p>为什么必须有：队列里每条只给「ID + 标题 + 相似度 + 理由」，
     * 而判重复真正要看的是「正文差在哪」。之前要么点开两个标签页自己记行，
     * 要么靠 MCP 的 diff 回执在脑子里拼 —— 判 40 多条时根本判不动。
     */
    @GetMapping("/candidates/{id}/diff")
    public String candidateDiff(HttpSession session, @PathVariable Long id, Model model) {
        User user = (User) session.getAttribute("user");
        if (user == null) return "redirect:/login";

        ReviewCandidate c = reviewCandidateService.get(id);
        if (c == null) {
            return "redirect:/docs?view=candidates";
        }
        Doc a = docService.getById(c.getDocA());
        Doc b = docService.getById(c.getDocB());
        // 悬空行（引用的文档已删）没有对比可看，回队列 —— 那里会标出「已不存在」
        if (a == null || b == null) {
            return "redirect:/docs?view=candidates";
        }

        String ca = a.getContent() == null ? "" : a.getContent();
        String cb = b.getContent() == null ? "" : b.getContent();
        List<SimilarityScorer.DiffRow> rows = SimilarityScorer.alignLines(ca, cb);
        SimilarityScorer.Diff stat = SimilarityScorer.diffLines(ca, cb);
        int diffRows = 0;
        for (SimilarityScorer.DiffRow r : rows) {
            if (!r.same()) diffRows++;
        }

        model.addAttribute("candidate", c);
        model.addAttribute("docA", a);
        model.addAttribute("docB", b);
        model.addAttribute("rows", rows);
        model.addAttribute("stat", stat);
        model.addAttribute("diffRows", diffRows);
        // 与 alignLines 的降级门槛保持一致：超了就没做最优对齐，页面上要说一声
        model.addAttribute("tooBig", (long) stat.linesA() * stat.linesB() > 4_000_000L);
        return "docs/diff";
    }

    /**
     * 对比页上「直接删掉这条」（2026-11-05 越哥要求）。
     *
     * <p>为什么允许在这里真删：这个场景是「看完左右对比、确认两条一样，删掉多余那条」。
     * 判 duplicate 只是给两条各打一个标记，两条都还在，搜索照样各出一半内容 ——
     * 对「明显重复」来说等于没解决。
     *
     * <p>两道限制，都不是替他加门槛，而是防止这个路由变成万能删除器：
     * <ul>
     *   <li>只能删当前候选的 A 或 B 侧，不接受任意 ID；</li>
     *   <li>页面按钮带原生 confirm，明确写「不可恢复、历史版本一起删」。</li>
     * </ul>
     *
     * <p>删完候选会自动消失：{@link DocService#delete} 会清掉指向该文档的 PENDING 候选
     * （已判过的记录保留，那是判断历史）。
     */
    @PostMapping("/candidates/{id}/delete/{side}")
    public String deleteCandidateSide(HttpSession session, @PathVariable Long id,
                                      @PathVariable String side) {
        User user = (User) session.getAttribute("user");
        if (user == null) return "redirect:/login";

        ReviewCandidate c = reviewCandidateService.get(id);
        if (c == null) return "redirect:/docs?view=candidates";
        Long docId;
        if ("A".equalsIgnoreCase(side)) {
            docId = c.getDocA();
        } else if ("B".equalsIgnoreCase(side)) {
            docId = c.getDocB();
        } else {
            return "redirect:/docs?view=candidates";
        }
        if (docService.getById(docId) == null) {
            return "redirect:/docs?view=candidates";
        }
        // 级联由 DocService 负责：主表 + 版本快照 + 指向它的 PENDING 候选 + 调用日志
        docService.delete(docId);
        return "redirect:/docs?view=candidates";
    }

    // 删除经验（后台管理）
    @PostMapping("/docs/{id}/delete")
    public String deleteDoc(HttpSession session, @PathVariable Long id) {
        User user = (User) session.getAttribute("user");
        if (user == null) return "redirect:/login";

        docService.delete(id);
        return "redirect:/docs";
    }

    // M10：撤销待删标记（把 AI 标记的东西放回来）
    @PostMapping("/docs/{id}/restore")
    public String restoreDoc(HttpSession session, @PathVariable Long id) {
        User user = (User) session.getAttribute("user");
        if (user == null) return "redirect:/login";

        docService.cancelDeletion(id);
        return "redirect:/docs?status=" + DocService.PENDING_DELETE;
    }
}
