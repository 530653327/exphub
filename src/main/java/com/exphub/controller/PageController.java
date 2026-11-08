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
import com.exphub.service.ProjectService;
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

    @Autowired
    private ProjectService projectService;

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
        model.addAttribute("currentUserName", user.getDisplayName() != null && !user.getDisplayName().isEmpty()
                ? user.getDisplayName() : user.getUsername());

        // 统计数据（2026-11-06 第一批口径修正）
        Map<String, Object> stats = callLogService.getOverview();
        long totalDocs = docService.countKnowledgeDocs();   // 真实知识文档，排除过程流水/废弃
        long problemDocs = docService.countProblemDocs(90); // WHOLE 真问题：超期/取代/flag/待删/BROKEN

        model.addAttribute("totalDocs", totalDocs);
        model.addAttribute("totalCalls", stats.get("totalCalls"));        // 兼容旧字段名（现实中几乎没人用）
        model.addAttribute("totalBiz", stats.get("totalBiz"));
        model.addAttribute("totalTool", stats.get("totalTool"));
        model.addAttribute("todayCalls", stats.get("todayCalls"));
        model.addAttribute("todayBiz", stats.get("todayBiz"));
        model.addAttribute("todayTool", stats.get("todayTool"));
        model.addAttribute("successRate", String.format("%.1f", ((Double) stats.get("successRate")) * 100));
        model.addAttribute("successSampled", stats.get("successSampled"));
        model.addAttribute("toolSuccessRate", String.format("%.1f", ((Double) stats.get("toolSuccessRate")) * 100));
        model.addAttribute("problemDocs", problemDocs);
        model.addAttribute("problemDocsByType", docService.countProblemDocsByType(90));
        model.addAttribute("totalAssistants", assistantService.countActive());

        // 第三批看板卡片：项目 / 时效 / 候选队列 / 搜索零命中
        List<com.exphub.entity.Project> projects = projectService.listProjects();
        model.addAttribute("totalProjects", projects == null ? 0 : projects.size());
        model.addAttribute("projectDocs", docService.countProjectDocs());
        model.addAttribute("globalDocs", docService.countGlobalRegularDocs());
        Map<String, Long> pbt = docService.countProblemDocsByType(90);
        model.addAttribute("staleDocs", pbt.getOrDefault("STALE", 0L));
        model.addAttribute("candidatePending", reviewCandidateService.pendingCount());
        Map<String, Long> candGroup = reviewCandidateService.countByStatusGroup();
        long candTotal = candGroup.values().stream().mapToLong(Long::longValue).sum();
        model.addAttribute("candidateTotal", candTotal);
        Map<String, Object> searchHit = callLogService.getSearchHitStats(30);
        model.addAttribute("searchTotal", searchHit.get("totalSearch"));
        model.addAttribute("searchZeroHit", searchHit.get("zeroHit"));
        model.addAttribute("searchZeroRate", String.format("%.1f", ((Number) searchHit.get("zeroRate")).doubleValue() * 100));

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
                       @RequestParam(required = false) String problem,
                       @RequestParam(defaultValue = "1") int page,
                       Model model, HttpServletRequest request) {
        User user = (User) session.getAttribute("user");
        if (user == null) return "redirect:/login";

        // 问题文档视图（2026-11-08）：stale / superseded / flagged / pending_delete / broken
        // 只认白名单值，非法值当没传 —— 否则拼错一个词会静默返回全部文档，看着像"筛出来了"
        String problemType = (problem != null && DocService.isProblemType(problem.trim().toLowerCase()))
                ? problem.trim().toLowerCase() : "";

        addContextPath(model, request);
        model.addAttribute("active", "docs");
        model.addAttribute("pageTitle", "经验管理");
        model.addAttribute("keyword", keyword != null ? keyword : "");
        model.addAttribute("templateType", templateType != null ? templateType : "");
        model.addAttribute("status", status != null ? status : "");
        model.addAttribute("flag", flag != null ? flag : "");
        model.addAttribute("view", view != null ? view : "");
        model.addAttribute("problem", problemType);

        Page<Doc> result;
        if (!problemType.isEmpty()) {
            // 问题视图优先：它的判据（如超期、被取代）用 status/flag 参数表达不出来
            result = docService.listProblems(problemType, page, 20);
        } else if (keyword != null && !keyword.trim().isEmpty()) {
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
        long curPage = result.getCurrent();
        long totalPages = result.getPages();
        model.addAttribute("currentPage", curPage);
        model.addAttribute("totalPages", totalPages);
        model.addAttribute("totalRows", result.getTotal());
        // 压缩页码窗口：首尾各1页 + 当前页前后各2页，中间用省略号分隔（0 作占位）
        model.addAttribute("pageNumbers", paginationWindow(curPage, totalPages));

        // 问题文档 5 类计数：既做 Tab 角标，也做子分类的数字。
        // 注意口径 —— 同一篇可能命中多类（既超期又被 flag），所以各项之和 != 去重后的文档数。
        java.util.Map<String, Long> problemCounts = docService.countProblemDocsByType(DocService.STALE_DAYS);
        model.addAttribute("problemCounts", problemCounts);
        model.addAttribute("problemTotal",
                problemCounts.values().stream().mapToLong(Long::longValue).sum());
        model.addAttribute("staleDays", DocService.STALE_DAYS);

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
    // ⚠️ 2026-11-07：删除后必须回到「原来的视图」而不是 /docs 首页。
    // 用户在待删 Tab 里删一条，旧代码 redirect:/docs 直接跳回全部，得重新点待删——
    // 6 条就得重复 6 次。现在把当前 Tab/筛选条件带回来（参数由前端表单 query 传入）。
    @PostMapping("/docs/{id}/delete")
    public String deleteDoc(HttpSession session, @PathVariable Long id,
                            @RequestParam(required = false) String status,
                            @RequestParam(required = false) String flag,
                            @RequestParam(required = false) String keyword,
                            @RequestParam(required = false) String templateType) {
        User user = (User) session.getAttribute("user");
        if (user == null) return "redirect:/login";

        docService.delete(id);

        // 移动壳（2026-11-08）：手机上的删除请求要回移动列表，不能被甩到 PC 的 /docs。
        // 判据用会话标记而不是 UA —— UA 判移动端在平板/微信里会判错，而且 PC 用户横屏也可能带 Mobile UA。
        if (Boolean.TRUE.equals(session.getAttribute(com.exphub.controller.MobileController.UI_SESSION_KEY))) {
            return "redirect:/m/pending?deleted=1";
        }

        // 回到原视图第 1 页：删掉一条后当前页可能越界，且待删队列本来就没几页
        return redirectToDocs(status, flag, keyword, templateType, true);
    }

    // M10：撤销待删标记（把 AI 标记的东西放回来）
    @PostMapping("/docs/{id}/restore")
    public String restoreDoc(HttpSession session, @PathVariable Long id,
                             @RequestParam(required = false) String status,
                             @RequestParam(required = false) String flag,
                             @RequestParam(required = false) String keyword,
                             @RequestParam(required = false) String templateType) {
        User user = (User) session.getAttribute("user");
        if (user == null) return "redirect:/login";

        docService.cancelDeletion(id);
        // 恢复后仍留在待删队列（方便连续处理剩下的），但保留用户的其它筛选条件
        return redirectToDocs(
                (status == null || status.isEmpty()) ? DocService.PENDING_DELETE : status,
                flag, keyword, templateType, true);
    }

    /**
     * 拼「回到经验列表」的重定向地址，保留当前 Tab 与筛选条件。
     *
     * <p>只接受已知的几个参数键并逐个编码后拼装，<b>不接收完整 URL</b> ——
     * 直接 {@code redirect(userInput)} 会有开放重定向风险。
     *
     * @param resetPage true = 回到第 1 页（删/恢复后当前页可能已越界）
     */
    private String redirectToDocs(String status, String flag, String keyword,
                                  String templateType, boolean resetPage) {
        return buildDocsRedirect(status, flag, keyword, templateType);
    }
    /**
     * 纯函数版重定向拼装（包级可见，供单测直接断言输出，不必起 Spring）。
     * 只认这 4 个已知键，逐个编码后拼装；<b>不接收完整 URL</b>，避免开放重定向。
     */
    static String buildDocsRedirect(String status, String flag, String keyword, String templateType) {
        List<String> qs = new java.util.ArrayList<>();
        if (status != null && !status.trim().isEmpty()) {
            qs.add("status=" + org.springframework.web.util.UriUtils.encodeQueryParam(status, java.nio.charset.StandardCharsets.UTF_8));
        }
        if (flag != null && !flag.trim().isEmpty()) {
            qs.add("flag=" + org.springframework.web.util.UriUtils.encodeQueryParam(flag, java.nio.charset.StandardCharsets.UTF_8));
        }
        if (keyword != null && !keyword.trim().isEmpty()) {
            qs.add("keyword=" + org.springframework.web.util.UriUtils.encodeQueryParam(keyword, java.nio.charset.StandardCharsets.UTF_8));
        }
        if (templateType != null && !templateType.trim().isEmpty()) {
            qs.add("templateType=" + org.springframework.web.util.UriUtils.encodeQueryParam(templateType, java.nio.charset.StandardCharsets.UTF_8));
        }
        return qs.isEmpty() ? "redirect:/docs" : "redirect:/docs?" + String.join("&", qs);
    }

    /**
     * 生成压缩页码窗口（2026-11-07）。全库 1200+ 篇 / 20 每页 = 60+ 页，
     * 直接 {@code sequence(1, totalPages)} 会渲染 60 个页码按钮。改成：
     * 首 1 + 尾 1 + 当前页前后各 2，中间用省略号（占位 0）隔开。
     * 例（共 62 页，当前第 30 页）：[1] … [28,29,30,31,32] … [62]
     */
    private List<Integer> paginationWindow(long current, long totalPages) {
        List<Integer> pages = new java.util.ArrayList<>();
        if (totalPages <= 7) {          // 页数少直接全列
            for (long i = 1; i <= totalPages; i++) pages.add((int) i);
            return pages;
        }
        long c = Math.max(1, Math.min(current, totalPages));
        // 候选集：[1, c-2 .. c+2, totalPages]
        java.util.TreeSet<Long> set = new java.util.TreeSet<>();
        set.add(1L);
        set.add(totalPages);
        for (long i = c - 2; i <= c + 2; i++) {
            if (i >= 1 && i <= totalPages) set.add(i);
        }
        long prev = -1;
        for (long p : set) {
            if (prev >= 1 && p - prev > 1) {
                pages.add(0);            // 省略号占位
            }
            pages.add((int) p);
            prev = p;
        }
        return pages;
    }
}
