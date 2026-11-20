package com.exphub.controller;

import com.exphub.entity.DispatchHeader;
import com.exphub.entity.DispatchItem;
import com.exphub.service.DispatchService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;
import org.springframework.web.util.UriComponentsBuilder;

import java.util.ArrayList;
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

    /** 每页多少张。上限是 {@link DispatchService#MAX_PAGE_SIZE}，这里只决定「一屏能读多少」。 */
    private static final int PAGE_SIZE = 20;

    /** 列表：按状态筛选 + 关键词模糊搜索 + 翻页，顶部显示各状态张数。 */
    @GetMapping
    public String list(@RequestParam(required = false) String status,
                       @RequestParam(required = false) String keyword,
                       @RequestParam(required = false) Integer size,
                       @RequestParam(defaultValue = "1") int page,
                       Model model) {
        // apiKey 传 null = 后台视角，看全部（后台只有越哥一个人用）
        int pageSize = DispatchService.normalizePageSize(size, PAGE_SIZE);
        long total = dispatchService.count(null, status, null, keyword, null);
        DispatchService.PageWindow w = DispatchService.pageWindow(total, page, pageSize);
        // 页码越界就重定向到合法的 URL，而不是「URL 说第 9 页、内容其实是第 3 页」。
        // 静默截断会让地址栏和内容互相矛盾，翻页时再点上一页就像跳了一下。
        if (w.page() != page) {
            UriComponentsBuilder b = UriComponentsBuilder.fromPath("/dispatch")
                    .queryParam("page", w.page())
                    .queryParamIfPresent("status", Optional.ofNullable(blankToNull(status)))
                    .queryParamIfPresent("keyword", Optional.ofNullable(blankToNull(keyword)));
            if (pageSize != PAGE_SIZE) {
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
