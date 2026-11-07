package com.exphub.controller;

import com.exphub.entity.User;
import jakarta.servlet.http.HttpSession;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;

/**
 * 移动端后台（H5 + PWA）。
 *
 * <p><b>只做四件事</b>（越哥 2026-11-08 定范围）：看板 / 待删队列 / 工具埋点 / 用户。
 * 手机端**不提供**新增、编辑能力 —— 长文编辑在手机上体验差，而且整篇写回会踩 A5 的截断坑。
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

    @GetMapping("/tools")
    public String tools(HttpSession session, Model model) {
        markMobile(session);
        model.addAttribute("title", "工具埋点");
        model.addAttribute("activeTab", "tools");
        return "m/tools";
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
}
