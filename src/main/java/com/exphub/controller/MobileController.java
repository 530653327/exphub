package com.exphub.controller;

import com.exphub.entity.DispatchHeader;
import com.exphub.entity.DispatchItem;
import com.exphub.entity.User;
import com.exphub.service.DispatchService;
import jakarta.servlet.http.HttpSession;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;
import org.springframework.web.util.UriComponentsBuilder;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 移动端后台（H5 + PWA）。
 *
 * <p><b>五件事</b>：看板 / 待删队列 / 派单 / 用户 / 我的。
 * 手机端**不提供**文档新增与编辑能力 —— 长文编辑在手机上体验差，而且整篇写回会踩 A5 的截断坑。
 *
 * <p><b>2026-11-17 变更</b>：原「工具埋点」tab 整个下线（越哥要求），位置换成派单。
 * 埋点的统计与日志仍在 PC 端 {@code /tool-stats}，移动端不再提供；{@code /m/tools} 路由已删除，
 * 旧书签会 404 —— 这是有意的，比留一个半残页面干净。
 *
 * <p><b>派单为什么能在手机上做</b>：审核 / 验收 / 取消 / 人工单回复在 PC 上就只存在于网页，
 * 手机端同样<b>只走网页表单</b>，没有任何对应的 MCP 工具 —— 手机端只是同一个后台的另一层皮，
 * 不改变「闸门在人手里」这条。业务规则一律调 {@link DispatchService}，本类不自己判断状态。
 *
 * <p><b>数据一律走 {@code /api/stats/**}，不在本类里自己算。</b>
 * 理由：PC 仪表盘在 {@link PageController} 里现算，如果移动端再算一遍，
 * 两边口径迟早会漂 —— 这类「同一指标两个数」的问题在 #585 已经吃过一次。
 * 为此 {@code /api/stats/overview} 在 2026-11-08 补齐了 PC 看板的全部指标。
 *
 * <p><b>与 PC 的关系</b>：两套模板（{@code templates/m/*} 与 {@code templates/*}），同一套后端。
 * 不做 UA 自动跳转 —— 手机想用 PC 版（比如横屏看大表格）不该被强行拦住；
 * 入口靠 URL 区分：{@code /exphub/m} 或桌面图标。
 */
@Controller
@RequestMapping("/m")
public class MobileController {

    /** 会话标记：本次是移动壳在用，登录成功后跳 /m/dashboard 而不是 /dashboard。 */
    public static final String UI_SESSION_KEY = "ui.mobile";

    /** 手机端派单每页几张。比 PC 少一半：卡片比表格行高，一屏翻太多反而不好找。 */
    private static final int MOBILE_PAGE_SIZE = 10;

    @Autowired
    private DispatchService dispatchService;

    @Autowired
    private com.exphub.service.ProjectService projectService;

    /**
     * 产品版本号，与 health / MCP serverInfo 同读 {@code exphub.version}。
     * 模板里不用 {@code @environment.getProperty(...)} —— 那个 Bean 表达式在纯 Thymeleaf
     * 渲染（测试、以及某些上下文）下解析不到，会抛异常，线上表现是「200 + 半截页」。
     */
    @Value("${exphub.version:2.5.6}")
    private String appVersion;

    @GetMapping({"", "/"})
    public String index(HttpSession session) {
        session.setAttribute(UI_SESSION_KEY, Boolean.TRUE);
        return "redirect:/m/dashboard";
    }

    @GetMapping("/login")
    public String login(HttpSession session, Model model) {
        // 先打标记再渲染：登录表单提交到统一的 /login，由 LoginSuccessHandler 读这个标记决定跳哪
        session.setAttribute(UI_SESSION_KEY, Boolean.TRUE);
        model.addAttribute("title", "登录");
        return "m/login";
    }

    @GetMapping("/dashboard")
    public String dashboard(HttpSession session, Model model) {
        markMobile(session);
        User user = (User) session.getAttribute("user");
        model.addAttribute("title", "看板");
        model.addAttribute("activeTab", "dashboard");
        model.addAttribute("me", displayName(user));
        return "m/dashboard";
    }

    @GetMapping("/pending")
    public String pending(HttpSession session, Model model) {
        markMobile(session);
        model.addAttribute("title", "待删队列");
        model.addAttribute("activeTab", "pending");
        return "m/pending";
    }

    // ==================== 派单（2026-11-17 顶掉原「工具埋点」tab）====================

    /**
     * 列表：状态 chips + 关键词搜索 + 卡片 + 翻页。
     *
     * <p>2026-11-19：原来一次取 100 条、被 service 截成 50 且无翻页，单子过 50 张就看不到更早的。
     * 现在与 PC 后台同一套口径（每页 20、总数与列表同源）。
     */
    @GetMapping("/dispatch")
    public String dispatchList(@RequestParam(required = false) String status,
                               @RequestParam(required = false) String keyword,
                               @RequestParam(required = false) Integer size,
                               @RequestParam(defaultValue = "1") int page,
                               HttpSession session, Model model) {
        markMobile(session);
        model.addAttribute("title", "派单");
        model.addAttribute("activeTab", "dispatch");
        // apiKey 传 null = 后台视角，看全部（后台只有越哥一个人用，与 PC 端一致）
        int pageSize = DispatchService.normalizePageSize(size, MOBILE_PAGE_SIZE);
        long total = dispatchService.count(null, status, null, keyword, null);
        DispatchService.PageWindow w = DispatchService.pageWindow(total, page, pageSize);
        if (w.page() != page) {
            // 页码越界重定向到合法页，别让地址栏说的和内容不一致
            String kw = (keyword == null || keyword.isBlank()) ? null : keyword;
            String st = (status == null || status.isBlank()) ? null : status;
            UriComponentsBuilder b = UriComponentsBuilder.fromPath("/m/dispatch")
                    .queryParam("page", w.page())
                    .queryParamIfPresent("status", Optional.ofNullable(st))
                    .queryParamIfPresent("keyword", Optional.ofNullable(kw));
            if (pageSize != MOBILE_PAGE_SIZE) {
                b.queryParam("size", pageSize);
            }
            return "redirect:" + b.build().encode().toUriString();
        }
        List<DispatchHeader> orders = dispatchService.list(null, status, null, keyword,
                w.size(), w.offset(), null);
        model.addAttribute("orders", orders);
        model.addAttribute("status", status);
        model.addAttribute("keyword", keyword == null ? "" : keyword.trim());
        model.addAttribute("counts", dispatchService.countByStatus());
        model.addAttribute("labels", statusLabels());
        model.addAttribute("total", w.total());
        model.addAttribute("page", w.page());
        model.addAttribute("totalPages", w.totalPages());
        model.addAttribute("from", w.from());
        model.addAttribute("to", w.to());
        model.addAttribute("size", w.size());
        model.addAttribute("pageSizes", DispatchService.PAGE_SIZE_CHOICES);
        return "m/dispatch";
    }

    /** 建单页：与 PC 同一套字段。建完是「待审核」，还要自己点一次通过。 */
    @GetMapping("/dispatch/new")
    public String dispatchNew(HttpSession session, Model model) {
        markMobile(session);
        model.addAttribute("title", "新建派单");
        model.addAttribute("activeTab", "dispatch");
        model.addAttribute("roles", DispatchService.ASSIGNABLE_ROLES);
        model.addAttribute("projects", projectService.listProjects());
        model.addAttribute("suggestedVersion", "");
        String ownerKey = dispatchService.sharedDispatchKey();
        model.addAttribute("ownerKeyTail", ownerKey.isEmpty() ? "" : DispatchService.maskKey(ownerKey));
        return "m/dispatch-new";
    }

    @GetMapping("/dispatch/{orderNo}")
    public String dispatchDetail(@PathVariable String orderNo, HttpSession session,
                                 Model model, RedirectAttributes ra) {
        markMobile(session);
        DispatchHeader h = dispatchService.get(orderNo);
        if (h == null) {
            ra.addFlashAttribute("error", "单号 " + orderNo + " 不存在");
            return "redirect:/m/dispatch";
        }
        List<DispatchItem> items = dispatchService.items(h.getId());
        model.addAttribute("title", "派单 " + orderNo);
        model.addAttribute("activeTab", "dispatch");
        model.addAttribute("order", h);
        model.addAttribute("items", items);
        model.addAttribute("labels", statusLabels());
        model.addAttribute("granted", grantedAuths(h));
        model.addAttribute("suggestedVersion", dispatchService.suggestVersion(h.getProjectId()));
        model.addAttribute("versionHint", dispatchService.suggestVersionInfo(h.getProjectId()));
        model.addAttribute("ballOwner", dispatchService.ballOwnerLabel(h));
        return "m/dispatch-detail";
    }

    /**
     * 所有「人按确认键」的动作集中到一个端点。
     *
     * <p>不拆成 6 个 URL 的理由：手机模板里每个按钮都是同一个表单，只改 action 取值，
     * 拆开会让模板里出现 6 段几乎一样的 form。业务判断全在 {@link DispatchService}，
     * 这里只是转发 —— 状态对不对、该不该拒，一律由 service 决。
     *
     * <p>参数：approve/verify-pass 用 text（备注），reject-review/verify-reject/cancel 用 reason，
     * reply 用 text。
     */
    @PostMapping("/dispatch/act")
    public String dispatchAct(@RequestParam String orderNo,
                              @RequestParam String action,
                              @RequestParam(required = false) String text,
                              @RequestParam(required = false) String reason,
                              RedirectAttributes ra) {
        DispatchService.Outcome out = switch (action) {
            case "approve" -> dispatchService.approve(orderNo, text);
            case "reject-review" -> dispatchService.rejectReview(orderNo, reason);
            case "verify-pass" -> dispatchService.verifyPass(orderNo, text);
            case "verify-reject" -> dispatchService.verifyReject(orderNo, reason);
            case "reply" -> dispatchService.humanReply(orderNo, text);
            case "cancel" -> dispatchService.cancel(orderNo, reason);
            default -> null;
        };
        if (out == null) {
            ra.addFlashAttribute("error", "未知动作：" + action);
        } else {
            flash(ra, out);
        }
        return "redirect:/m/dispatch/" + orderNo;
    }

    @PostMapping("/dispatch/create")
    public String dispatchCreate(@RequestParam String title,
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
        Map<String, Boolean> auths = authsMap(modifyCode, commit, push, release, dataWrite,
                migration, credential, prodConfig, securityGroup, firewall, spend);

        DispatchService.Outcome out = dispatchService.create(
                DispatchService.HUMAN_OPERATOR, DispatchHeader.ORIGIN_HUMAN,
                assignee, title, description, priority, projectId, auths, null, version, milestone);
        if (!out.ok() || out.header() == null) {
            ra.addFlashAttribute("error", out.ok() ? "建单未返回单号" : out.message());
            return "redirect:/m/dispatch/new";
        }
        if (remark != null && !remark.isBlank()) {
            dispatchService.updateRemark(out.header().getOrderNo(), remark);
        }
        ra.addFlashAttribute("success",
                "已建单 " + out.header().getOrderNo() + "，状态待审核。确认无误后点「审核通过」才会派出去。");
        return "redirect:/m/dispatch/" + out.header().getOrderNo();
    }

    /** 待审核阶段改授权位 / 版本号；审核通过后 service 会拒。 */
    @PostMapping("/dispatch/auths")
    public String dispatchAuths(@RequestParam String orderNo,
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
        Map<String, Boolean> auths = authsMap(modifyCode, commit, push, release, dataWrite,
                migration, credential, prodConfig, securityGroup, firewall, spend);
        flash(ra, dispatchService.updateAuths(orderNo, auths, version));
        return "redirect:/m/dispatch/" + orderNo;
    }

    @GetMapping("/users")
    public String users(HttpSession session, Model model) {
        markMobile(session);
        model.addAttribute("title", "用户");
        model.addAttribute("activeTab", "users");
        return "m/users";
    }

    @GetMapping("/problems")
    public String problems(HttpSession session, Model model) {
        markMobile(session);
        model.addAttribute("title", "问题文档");
        model.addAttribute("activeTab", "dashboard");
        return "m/problems";
    }

    @GetMapping("/me")
    public String me(HttpSession session, Model model) {
        markMobile(session);
        User user = (User) session.getAttribute("user");
        model.addAttribute("title", "我的");
        model.addAttribute("activeTab", "me");
        model.addAttribute("me", displayName(user));
        model.addAttribute("username", user == null ? "" : user.getUsername());
        model.addAttribute("lastLogin", user == null || user.getLastLogin() == null
                ? "—" : user.getLastLogin().toString().replace('T', ' ').substring(0, 16));
        model.addAttribute("version", appVersion);
        return "m/me";
    }

    private static void markMobile(HttpSession session) {
        // 直接访问 /m/xxx 也算移动端在用；否则从 PC 登录后切到移动页会跳回 PC 首页
        session.setAttribute(UI_SESSION_KEY, Boolean.TRUE);
    }

    private static String displayName(User user) {
        if (user == null) return "";
        return user.getDisplayName() != null && !user.getDisplayName().isEmpty()
                ? user.getDisplayName() : user.getUsername();
    }

    // ==================== 派单页辅助（与 PC 端 DispatchController 对齐，改动请两边一起看）====================

    /** 状态码 → 中文，模板里 ${labels[o.status]} 直接取。 */
    private Map<String, String> statusLabels() {
        Map<String, String> m = new LinkedHashMap<>();
        for (String st : List.of(DispatchHeader.ST_DRAFT, DispatchHeader.ST_REVIEWING,
                DispatchHeader.ST_READY, DispatchHeader.ST_RUNNING, DispatchHeader.ST_CONFIRMING,
                DispatchHeader.ST_VERIFYING, DispatchHeader.ST_DONE, DispatchHeader.ST_CANCELLED)) {
            m.put(st, DispatchService.statusLabel(st));
        }
        return m;
    }

    /** 已授权的动作清单。手机上空间只够放「已授」的，未授的不占位。 */
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

    /** 11 个授权位。键名必须与 DispatchService.updateAuths / create 认的一致。 */
    private static Map<String, Boolean> authsMap(Boolean modifyCode, Boolean commit, Boolean push,
                                                 Boolean release, Boolean dataWrite, Boolean migration,
                                                 Boolean credential, Boolean prodConfig,
                                                 Boolean securityGroup, Boolean firewall, Boolean spend) {
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
        return auths;
    }

    private static void flash(RedirectAttributes ra, DispatchService.Outcome out) {
        if (out == null) {
            ra.addFlashAttribute("error", "操作未返回结果，请刷新确认状态");
            return;
        }
        if (out.ok()) ra.addFlashAttribute("success", out.message());
        else ra.addFlashAttribute("error", out.message());
    }
}
