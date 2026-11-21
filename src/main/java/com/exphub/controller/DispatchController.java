package com.exphub.controller;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.exphub.entity.DispatchEvent;
import com.exphub.entity.DispatchHeader;
import com.exphub.entity.DispatchItem;
import com.exphub.mapper.DispatchEventMapper;
import com.exphub.mapper.DispatchHeaderMapper;
import com.exphub.service.DispatchBurndownService;
import com.exphub.service.DispatchService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;
import org.springframework.web.util.UriComponentsBuilder;

import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 后台：派单系统（2026-11-16）。
 *
 * <p><b>为什么这些动作只能在这里，不做成 MCP 工具</b>：
 * 审核、验收、取消、人工单回复都是「人按的确认键」。MCP 工具是 AI 调的，只要存在这些动作，
 * 迟早会有 AI 自己调 —— 那就等于把闸门交给了被审的一方。所以物理隔离：网页有、工具没有。
 *
 * <p>设计基线见 ExpHub #3308。
 */
@Controller
@RequestMapping("/dispatch")
public class DispatchController extends BaseController {

    @Autowired
    private DispatchService dispatchService;

    @Autowired
    private com.exphub.service.ProjectService projectService;

    /** 操作流水页直接读事件表（只读 select，运行期业务代码不调 update/delete）。 */
    @Autowired
    private DispatchEventMapper eventMapper;

    /** 里程碑下拉：从单头取 distinct milestone。 */
    @Autowired
    private DispatchHeaderMapper headerMapper;

    /** 燃尽图聚合，与 /api/dispatch/burndown 同一份口径（同一 service，不另算一遍）。 */
    @Autowired
    private DispatchBurndownService burndownService;

    /** 每页多少张。上限是 {@link DispatchService#MAX_PAGE_SIZE}，这里只决定「一屏能读多少」。 */
    private static final int PAGE_SIZE = 20;

    /** 流水页每页条数。单子要求「默认最近 200 条」，控制台日志翻页稳定靠 created_at + id。 */
    private static final int EVENT_PAGE_SIZE = 200;

    /** 列表：按状态筛选 + 关键词搜索 + 里程碑筛选 + 翻页，顶部显示各状态张数。 */
    @GetMapping
    public String list(@RequestParam(required = false) String status,
                       @RequestParam(required = false) String keyword,
                       @RequestParam(required = false) String milestone,
                       @RequestParam(required = false) String releaseFlag,
                       @RequestParam(required = false) Integer size,
                       @RequestParam(defaultValue = "1") int page,
                       Model model) {
        // apiKey 传 null = 后台视角，看全部（后台只有越哥一个人用）
        int pageSize = DispatchService.normalizePageSize(size, PAGE_SIZE);
        long total = dispatchService.count(null, status, null, keyword, milestone, releaseFlag, null);
        DispatchService.PageWindow w = DispatchService.pageWindow(total, page, pageSize);
        // 页码越界就重定向到合法的 URL，而不是「URL 说第 9 页、内容其实是第 3 页」。
        // 静默截断会让地址栏和内容互相矛盾，翻页时再点上一页就像跳了一下。
        if (w.page() != page) {
            UriComponentsBuilder b = UriComponentsBuilder.fromPath("/dispatch")
                    .queryParam("page", w.page())
                    .queryParamIfPresent("status", Optional.ofNullable(blankToNull(status)))
                    .queryParamIfPresent("keyword", Optional.ofNullable(blankToNull(keyword)))
                    .queryParamIfPresent("milestone", Optional.ofNullable(blankToNull(milestone)))
                    .queryParamIfPresent("releaseFlag", Optional.ofNullable(blankToNull(releaseFlag)));
            if (pageSize != PAGE_SIZE) {
                b.queryParam("size", pageSize);
            }
            return "redirect:" + b.build().encode().toUriString();
        }
        List<DispatchHeader> orders = dispatchService.list(null, status, null, keyword,
                milestone, releaseFlag, w.size(), w.offset(), null);
        model.addAttribute("orders", orders);
        model.addAttribute("status", status);
        model.addAttribute("keyword", keyword == null ? "" : keyword.trim());
        model.addAttribute("milestone", milestone == null ? "" : milestone.trim());
        model.addAttribute("releaseFlag", releaseFlag == null ? "" : releaseFlag.trim());
        // 下拉数据源与燃尽图页共用同一个方法（W-20261121-000010），避免两处 distinct 口径漂移
        model.addAttribute("milestones", dispatchService.distinctMilestones());
        model.addAttribute("counts", dispatchService.countByStatus());
        model.addAttribute("labels", statusLabels());
        // 页头与页码读的都是 count()，与列表同源 —— 不许用 orders.size() 顶替总数
        model.addAttribute("total", w.total());
        model.addAttribute("page", w.page());
        model.addAttribute("totalPages", w.totalPages());
        model.addAttribute("from", w.from());
        model.addAttribute("to", w.to());
        model.addAttribute("size", w.size());
        model.addAttribute("pageSizes", DispatchService.PAGE_SIZE_CHOICES);
        model.addAttribute("active", "dispatch");
        return "dispatch/list";
    }

    private static String blankToNull(String s) {
        return (s == null || s.isBlank()) ? null : s;
    }

    /** 详情：单子全貌 + item 流水 + 按当前状态显示能做的动作。 */
    @GetMapping("/{orderNo}")
    public String detail(@PathVariable String orderNo, Model model, RedirectAttributes ra) {
        DispatchHeader h = dispatchService.get(orderNo);
        if (h == null) {
            ra.addFlashAttribute("error", "单号 " + orderNo + " 不存在");
            return "redirect:/dispatch";
        }
        List<DispatchItem> items = dispatchService.items(h.getId());
        model.addAttribute("order", h);
        model.addAttribute("items", items);
        model.addAttribute("labels", statusLabels());
        model.addAttribute("granted", grantedAuths(h));
        model.addAttribute("suggestedVersion", dispatchService.suggestVersion(h.getProjectId()));
        model.addAttribute("versionHint", dispatchService.suggestVersionInfo(h.getProjectId()));
        model.addAttribute("ballOwner", DispatchService.statusLabel(h.getStatus()));
        model.addAttribute("releaseFlagLabel", DispatchService.releaseFlagLabel(h.getReleaseFlag()));
        model.addAttribute("active", "dispatch");
        return "dispatch/detail";
    }

    // ==================== 人工建单 ====================

    /** 建单页：越哥亲自下单。建完是「待审核」，还要自己点一次通过（防误操作的两步）。 */
    @GetMapping("/new")
    public String newForm(Model model) {
        model.addAttribute("roles", DispatchService.ASSIGNABLE_ROLES);
        // 建单页还没选项目 —— 版本号基准依赖项目，页面默认给空，选完项目后由前端按项目提示。
        model.addAttribute("projects", projectService.listProjects());
        model.addAttribute("suggestedVersion", "");
        // 归属 Key：人工单会自动挂到派单共享 Key 上，让建单的人先看见「哨兵靠哪把 Key 找到这张单」
        String ownerKey = dispatchService.sharedDispatchKey();
        model.addAttribute("ownerKeyTail", ownerKey.isEmpty() ? "" : DispatchService.maskKey(ownerKey));
        model.addAttribute("active", "dispatch");
        return "dispatch/new";
    }

    /** 建单页用：按项目实时算「不填版本号会得什么」，并把基准一起返回。 */
    @GetMapping("/suggest-version")
    @ResponseBody
    public Map<String, Object> suggestVersion(@RequestParam(required = false) Long projectId) {
        DispatchService.VersionBasis b = dispatchService.suggestVersionInfo(projectId);
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("ok", b.ok());
        m.put("value", b.value() == null ? "" : b.value());
        m.put("basis", b.basis() == null ? "" : b.basis());
        m.put("problem", b.problem() == null ? "" : b.problem());
        m.put("oneLine", b.oneLine());
        return m;
    }

    @PostMapping("/create")
    public String create(@RequestParam String title,
                         @RequestParam String description,
                         @RequestParam String assignee,
                         @RequestParam(required = false) Integer priority,
                         @RequestParam(required = false) String remark,
                         @RequestParam(required = false) Boolean modifyCode,
                         @RequestParam(required = false) Boolean commit,
                         @RequestParam(required = false) Boolean push,
                         @RequestParam(required = false) Boolean release,
                         @RequestParam(required = false) Boolean dataWrite,
                         @RequestParam(required = false) Boolean migration,
                         @RequestParam(required = false) Boolean credential,
                         @RequestParam(required = false) Boolean prodConfig,
                         @RequestParam(required = false) Boolean securityGroup,
                         @RequestParam(required = false) Boolean firewall,
                         @RequestParam(required = false) Boolean spend,
                         @RequestParam(required = false) Long projectId,
                         @RequestParam(required = false) String version,
                         @RequestParam(required = false) String milestone,
                         RedirectAttributes ra) {
        Map<String, Boolean> auths = new LinkedHashMap<>();
        auths.put("modifyCode", modifyCode);
        auths.put("commit", commit);
        auths.put("push", push);
        auths.put("release", release);
        auths.put("dataWrite", dataWrite);
        auths.put("migration", migration);
        auths.put("credential", credential);
        auths.put("prodConfig", prodConfig);
        auths.put("securityGroup", securityGroup);
        auths.put("firewall", firewall);
        auths.put("spend", spend);

        DispatchService.Outcome out = dispatchService.create(
                DispatchService.HUMAN_OPERATOR, DispatchHeader.ORIGIN_HUMAN,
                assignee, title, description, priority, projectId, auths, null, version, milestone);
        if (!out.ok() || out.header() == null) {
            ra.addFlashAttribute("error", out.ok() ? "建单未返回单号" : out.message());
            return "redirect:/dispatch/new";
        }
        if (remark != null && !remark.isBlank()) {
            DispatchHeader h = out.header();
            h.setRemark(remark.trim());
            // 备注不进 create 的签名（AI 建单不需要），这里补一次
            dispatchService.updateRemark(h.getOrderNo(), remark);
        }
        ra.addFlashAttribute("success", "已建单 " + out.header().getOrderNo() + "，状态待审核。确认无误后点「审核通过」才会派出去。");
        return "redirect:/dispatch/" + out.header().getOrderNo();
    }

    /**
     * 待审核时改授权位 / 版本号（越哥 2026-11-16 明确要求在审核阶段能改）。
     *
     * <p>只允许 REVIEWING。审核通过（READY）之后这里会拒绝 —— 授权位重新锁死。
     */
    @PostMapping("/auths")
    public String updateAuths(@RequestParam String orderNo,
                              @RequestParam(required = false) Boolean modifyCode,
                              @RequestParam(required = false) Boolean commit,
                              @RequestParam(required = false) Boolean push,
                              @RequestParam(required = false) Boolean release,
                              @RequestParam(required = false) Boolean dataWrite,
                              @RequestParam(required = false) Boolean migration,
                              @RequestParam(required = false) Boolean credential,
                              @RequestParam(required = false) Boolean prodConfig,
                              @RequestParam(required = false) Boolean securityGroup,
                              @RequestParam(required = false) Boolean firewall,
                              @RequestParam(required = false) Boolean spend,
                              @RequestParam(required = false) String version,
                              RedirectAttributes ra) {
        Map<String, Boolean> auths = new LinkedHashMap<>();
        auths.put("modifyCode", modifyCode);
        auths.put("commit", commit);
        auths.put("push", push);
        auths.put("release", release);
        auths.put("dataWrite", dataWrite);
        auths.put("migration", migration);
        auths.put("credential", credential);
        auths.put("prodConfig", prodConfig);
        auths.put("securityGroup", securityGroup);
        auths.put("firewall", firewall);
        auths.put("spend", spend);
        flash(ra, dispatchService.updateAuths(orderNo, auths, version));
        return "redirect:/dispatch/" + orderNo;
    }

    // ==================== 审核 ====================

    @PostMapping("/approve")
    public String approve(@RequestParam String orderNo,
                          @RequestParam(required = false) String note,
                          RedirectAttributes ra) {
        flash(ra, dispatchService.approve(orderNo, note));
        return "redirect:/dispatch/" + orderNo;
    }

    @PostMapping("/reject-review")
    public String rejectReview(@RequestParam String orderNo,
                               @RequestParam(required = false) String reason,
                               RedirectAttributes ra) {
        flash(ra, dispatchService.rejectReview(orderNo, reason));
        return "redirect:/dispatch/" + orderNo;
    }

    // ==================== 验收 ====================

    @PostMapping("/verify-pass")
    public String verifyPass(@RequestParam String orderNo,
                             @RequestParam(required = false) String note,
                             RedirectAttributes ra) {
        flash(ra, dispatchService.verifyPass(orderNo, note));
        return "redirect:/dispatch/" + orderNo;
    }

    @PostMapping("/verify-reject")
    public String verifyReject(@RequestParam String orderNo,
                               @RequestParam(required = false) String reason,
                               RedirectAttributes ra) {
        flash(ra, dispatchService.verifyReject(orderNo, reason));
        return "redirect:/dispatch/" + orderNo;
    }

    // ==================== 待确认回复 / 取消 ====================

    @PostMapping("/reply")
    public String reply(@RequestParam String orderNo,
                        @RequestParam(required = false) String text,
                        RedirectAttributes ra) {
        flash(ra, dispatchService.humanReply(orderNo, text));
        return "redirect:/dispatch/" + orderNo;
    }

    @PostMapping("/cancel")
    public String cancel(@RequestParam String orderNo,
                         @RequestParam(required = false) String reason,
                         RedirectAttributes ra) {
        flash(ra, dispatchService.cancel(orderNo, reason));
        return "redirect:/dispatch/" + orderNo;
    }

    /**
     * 标记发版（W-20261121-000014）：发版标记唯一的 RELEASED 写入口。
     *
     * <p><b>越哥后台专用</b>，没有 MCP 入口 —— AI 只能写 NEED/NONE，RELEASED 只能人按。
     * 「已发布」按钮传 RELEASED，手工救回传 NONE / 未标记（空串），都在 Service 白名单内。
     */
    @PostMapping("/release-flag")
    public String releaseFlag(@RequestParam String orderNo,
                              @RequestParam(required = false) String releaseFlag,
                              @RequestParam(required = false) String reason,
                              RedirectAttributes ra) {
        flash(ra, dispatchService.markReleaseFlagHuman(orderNo, releaseFlag, reason));
        return "redirect:/dispatch/" + orderNo;
    }

    // ==================== 操作流水 / 燃尽图（W-20261120-000028 后台页）====================

    /**
     * 操作流水页：控制台日志样式，角色 / 单号 / 状态分字段上色，可筛可翻。
     *
     * <p>数据与 {@link DispatchApiController#events} 同源（同一张 dispatch_event 表、同样的
     * created_at + id 兜底排序），只是这里走后台 Session 直接渲染，不经只读 token —— 后台只有越哥用，
     * 因此含 origin=HUMAN 的后台人工单（与 MCP 视角的隔离规则无关）。
     *
     * <p>按单子要求「时间正序、最新滚到底、默认最近 200 条」：先用倒序取最近一页（page=1 即最近 200 条），
     * 再在内存里反排成正序显示，这样最新动作在页底。翻页越往后翻越早，按 created_at + id 兜底稳定。
     */
    @GetMapping("/events")
    public String events(@RequestParam(required = false) String orderNo,
                         @RequestParam(required = false) String toStatus,
                         @RequestParam(required = false) String actor,
                         @RequestParam(defaultValue = "1") int page,
                         Model model) {
        QueryWrapper<DispatchEvent> q = new QueryWrapper<>();
        if (orderNo != null && !orderNo.isBlank()) q.eq("order_no", orderNo.trim());
        if (toStatus != null && !toStatus.isBlank()) q.eq("to_status", toStatus.trim());
        if (actor != null && !actor.isBlank()) q.like("actor", actor.trim());

        Long cnt = eventMapper.selectCount(q);
        long total = cnt == null ? 0L : cnt;
        DispatchService.PageWindow w = DispatchService.pageWindow(total, page, EVENT_PAGE_SIZE);
        if (w.page() != page) {
            UriComponentsBuilder b = UriComponentsBuilder.fromPath("/dispatch/events")
                    .queryParam("page", w.page());
            if (orderNo != null && !orderNo.isBlank()) b.queryParam("orderNo", orderNo.trim());
            if (toStatus != null && !toStatus.isBlank()) b.queryParam("toStatus", toStatus.trim());
            if (actor != null && !actor.isBlank()) b.queryParam("actor", actor.trim());
            return "redirect:" + b.build().encode().toUriString();
        }
        q.orderByDesc("created_at").orderByDesc("id").last("LIMIT " + w.offset() + "," + w.size());
        List<DispatchEvent> rows = new ArrayList<>(eventMapper.selectList(q));
        Collections.reverse(rows); // 取到的是最近一页的倒序，反排成正序显示（老的在上、最新在底）

        List<String> orderNos = headerMapper
                .selectList(new QueryWrapper<DispatchHeader>().select("order_no").orderByDesc("id"))
                .stream().map(DispatchHeader::getOrderNo).filter(java.util.Objects::nonNull).toList();

        model.addAttribute("events", rows);
        model.addAttribute("orderNos", orderNos);
        model.addAttribute("orderNo", orderNo == null ? "" : orderNo.trim());
        model.addAttribute("toStatus", toStatus == null ? "" : toStatus.trim());
        model.addAttribute("actor", actor == null ? "" : actor.trim());
        model.addAttribute("statusLabels", statusLabels());
        model.addAttribute("eventTypeLabels", eventTypeLabels());
        model.addAttribute("actorTypeLabels", actorTypeLabels());
        model.addAttribute("total", w.total());
        model.addAttribute("page", w.page());
        model.addAttribute("totalPages", w.totalPages());
        model.addAttribute("from", w.from());
        model.addAttribute("to", w.to());
        model.addAttribute("size", w.size());
        model.addAttribute("active", "dispatch-events");
        return "dispatch/events";
    }

    /**
     * 燃尽图页：里程碑下拉 + 服务端内联 SVG 阶梯线图，取消单不进分母，禁外链 CDN。
     *
     * <p>口径与 {@link DispatchApiController#burndown} 共用 {@link DispatchBurndownService}：
     * 纵轴 = 未完成单数（不搞工时）；图用已算好的 series 在服务端拼 points，前端零 JS 库、零外链。
     */
    @GetMapping("/burndown")
    public String burndown(@RequestParam(required = false) String milestone,
                           @RequestParam(required = false) String from,
                           @RequestParam(required = false) String to,
                           Model model) {
        // 下拉数据源与派单列表页共用同一个方法（W-20261121-000010），不再各自 distinct 一份
        List<String> milestones = dispatchService.distinctMilestones();
        model.addAttribute("milestones", milestones);
        model.addAttribute("statusLabels", statusLabels());
        model.addAttribute("milestoneOrders", List.of());
        model.addAttribute("milestone", milestone == null ? "" : milestone.trim());
        model.addAttribute("from", from == null ? "" : from.trim());
        model.addAttribute("to", to == null ? "" : to.trim());
        model.addAttribute("active", "dispatch-burndown");

        if (milestone == null || milestone.isBlank()) {
            model.addAttribute("chart", null);
            return "dispatch/burndown";
        }
        try {
            LocalDate fd = (from == null || from.isBlank()) ? null : LocalDate.parse(from.trim());
            LocalDate td = (to == null || to.isBlank()) ? null : LocalDate.parse(to.trim());
            DispatchBurndownService.Result r = burndownService.compute(milestone, fd, td);
            model.addAttribute("result", r);
            model.addAttribute("chart", buildChart(r));
            // 图下方列出该里程碑下的单（单号 / 标题 / 状态，可点进详情）
            model.addAttribute("milestoneOrders", dispatchService.byMilestone(milestone.trim()));
        } catch (DateTimeParseException e) {
            model.addAttribute("error", "from / to 必须是 yyyy-MM-dd（收到 from=" + from + ", to=" + to + "）");
        } catch (DispatchBurndownService.InvalidInput e) {
            model.addAttribute("error", e.getMessage());
        }
        return "dispatch/burndown";
    }

    /** 把天序列拼成纯 SVG 折线（阶梯）所需坐标；无 JS 库、无外链，模板直接输出 points。 */
    static Map<String, Object> buildChart(DispatchBurndownService.Result r) {
        List<DispatchBurndownService.DayPoint> s = r.series();
        final int W = 900, H = 320, L = 48, R = 16, T = 20, B = 42;
        final int plotW = W - L - R, plotH = H - T - B;
        long yMax = 1;
        for (DispatchBurndownService.DayPoint p : s) yMax = Math.max(yMax, p.remaining());
        final int n = s.size();
        final long max = yMax;
        java.util.function.IntUnaryOperator px = i -> n <= 1 ? L : L + (int) Math.round((double) i * plotW / (n - 1));
        java.util.function.LongUnaryOperator py = v -> T + plotH - (int) Math.round((double) v * plotH / max);

        StringBuilder pts = new StringBuilder();
        for (int i = 0; i < n; i++) {
            long cy = py.applyAsLong(s.get(i).remaining());
            if (i == 0) {
                pts.append(px.applyAsInt(i)).append(',').append(cy);
            } else {
                pts.append(' ').append(px.applyAsInt(i)).append(',').append(py.applyAsLong(s.get(i - 1).remaining()));
                pts.append(' ').append(px.applyAsInt(i)).append(',').append(cy);
            }
        }
        List<Map<String, Object>> yTicks = new ArrayList<>();
        long step = Math.max(1, (max + 4) / 5);
        for (long v = 0; v <= max; v += step) {
            Map<String, Object> t = new LinkedHashMap<>();
            t.put("y", py.applyAsLong(v));
            t.put("label", v);
            yTicks.add(t);
        }
        List<Map<String, Object>> xLabels = new ArrayList<>();
        int stride = Math.max(1, (int) Math.ceil(n / 8.0));
        for (int i = 0; i < n; i += stride) {
            Map<String, Object> t = new LinkedHashMap<>();
            t.put("x", px.applyAsInt(i));
            t.put("label", s.get(i).date().substring(5));
            xLabels.add(t);
        }
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("points", pts.toString());
        m.put("yTicks", yTicks);
        m.put("xLabels", xLabels);
        m.put("w", W);
        m.put("h", H);
        m.put("plotLeft", L);
        m.put("plotRight", W - R);
        m.put("plotBottom", T + plotH);
        m.put("yMax", max);
        return m;
    }

    // ==================== 页面辅助 ====================

    /** 状态码 → 中文，模板里直接 ${labels[o.status]} 取。 */
    private Map<String, String> statusLabels() {
        Map<String, String> m = new LinkedHashMap<>();
        for (String st : List.of(DispatchHeader.ST_DRAFT, DispatchHeader.ST_REVIEWING,
                DispatchHeader.ST_READY, DispatchHeader.ST_RUNNING, DispatchHeader.ST_CONFIRMING,
                DispatchHeader.ST_VERIFYING, DispatchHeader.ST_DONE, DispatchHeader.ST_CANCELLED)) {
            m.put(st, DispatchService.statusLabel(st));
        }
        return m;
    }

    /** 11 类事件 → 中文，流水页筛选用。 */
    private Map<String, String> eventTypeLabels() {
        Map<String, String> m = new LinkedHashMap<>();
        m.put(DispatchEvent.EV_CREATE, "建单");
        m.put(DispatchEvent.EV_REVIEW_PASS, "审核通过");
        m.put(DispatchEvent.EV_REVIEW_REJECT, "审核驳回");
        m.put(DispatchEvent.EV_CLAIM, "认领");
        m.put(DispatchEvent.EV_NEED_CONFIRM, "请求确认");
        m.put(DispatchEvent.EV_REPLY, "回复");
        m.put(DispatchEvent.EV_SUBMIT_ACCEPT, "提交验收");
        m.put(DispatchEvent.EV_ACCEPT_PASS, "验收通过");
        m.put(DispatchEvent.EV_ACCEPT_REJECT, "验收驳回");
        m.put(DispatchEvent.EV_CANCEL, "取消");
        m.put(DispatchEvent.EV_TERMINATE, "终止本轮");
        m.put(DispatchEvent.EV_RELEASE_FLAG, "发版标记");
        return m;
    }

    private Map<String, String> actorTypeLabels() {
        Map<String, String> m = new LinkedHashMap<>();
        m.put(DispatchEvent.ACTOR_HUMAN, "人");
        m.put(DispatchEvent.ACTOR_AI, "助手");
        return m;
    }

    /**
     * 已授权的动作清单。逐项判断放这里而不是模板里：
     * 模板里写 11 个 th:if 既难读又容易漏，而漏了就等于审核时看不见。
     */
    private List<String> grantedAuths(DispatchHeader h) {
        List<String> on = new ArrayList<>();
        if (Boolean.TRUE.equals(h.getAuthModifyCode())) on.add("修改代码");
        if (Boolean.TRUE.equals(h.getAuthCommit())) on.add("commit");
        if (Boolean.TRUE.equals(h.getAuthPush())) on.add("push");
        if (Boolean.TRUE.equals(h.getAuthRelease())) on.add("发版");
        if (Boolean.TRUE.equals(h.getAuthDataWrite())) on.add("数据写入");
        if (Boolean.TRUE.equals(h.getAuthMigration())) on.add("迁移");
        if (Boolean.TRUE.equals(h.getAuthCredential())) on.add("凭证生成");
        if (Boolean.TRUE.equals(h.getAuthProdConfig())) on.add("生产配置");
        if (Boolean.TRUE.equals(h.getAuthSecurityGroup())) on.add("安全组");
        if (Boolean.TRUE.equals(h.getAuthFirewall())) on.add("防火墙");
        if (Boolean.TRUE.equals(h.getAuthSpend())) on.add("花钱");
        return on;
    }

    private void flash(RedirectAttributes ra, DispatchService.Outcome out) {
        if (out == null) {
            ra.addFlashAttribute("error", "操作未返回结果，请刷新确认状态");
            return;
        }
        if (out.ok()) ra.addFlashAttribute("success", out.message());
        else ra.addFlashAttribute("error", out.message());
    }
}
