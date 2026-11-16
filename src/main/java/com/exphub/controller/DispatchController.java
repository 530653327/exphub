package com.exphub.controller;

import com.exphub.entity.DispatchHeader;
import com.exphub.entity.DispatchItem;
import com.exphub.service.DispatchService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

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

    /** 列表：按状态筛选，顶部显示各状态张数。 */
    @GetMapping
    public String list(@RequestParam(required = false) String status, Model model) {
        List<DispatchHeader> orders = dispatchService.list(null, status, null, 100);
        model.addAttribute("orders", orders);
        model.addAttribute("status", status);
        model.addAttribute("counts", dispatchService.countByStatus());
        model.addAttribute("labels", statusLabels());
        model.addAttribute("active", "dispatch");
        return "dispatch/list";
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
        model.addAttribute("ballOwner", DispatchService.statusLabel(h.getStatus()));
        model.addAttribute("active", "dispatch");
        return "dispatch/detail";
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
