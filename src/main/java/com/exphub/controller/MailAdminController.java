package com.exphub.controller;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.exphub.entity.MailCursor;
import com.exphub.entity.MailMessage;
import com.exphub.entity.User;
import com.exphub.mapper.MailCursorMapper;
import com.exphub.mapper.MailMessageMapper;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 后台「协作信箱」查看页（2026-11-06 越哥要的）。
 *
 * <p><b>消息只读，封存状态可写。</b>后台不提供发信、改信、删信：消息表的设计就是「只追加、不回改」，
 * 在这里开一个编辑入口等于把这个前提废掉。要纠错就让助手发一条更正消息。
 *
 * <p>2026-11-14 阁主 W-20261114-12 加了<b>线程封存 / 人工解锁</b> —— 它动的是
 * {@code mail_thread_states}（线程级状态 + 留痕），**不碰任何一封信**，
 * 所以不违反上面那条。这也是「越哥不在 MCP 里也能解锁」的人工入口。
 *
 * <p>回答三个问题：谁发给谁、什么时候、说了什么。列表给前三项的索引，
 * 详情页把同一根线程的全部消息按时间摊开——判断「这事完没完」必须看上下文，
 * 单封信看不出回执有没有到。
 */
@Controller
@RequestMapping("/mail")
public class MailAdminController extends BaseController {

    private static final int PAGE_SIZE = 20;

    @Autowired
    private MailMessageMapper mailMapper;

    @Autowired
    private MailCursorMapper cursorMapper;

    @Autowired
    private com.exphub.service.MailThreadSealService sealService;

    @GetMapping
    public String list(HttpSession session,
                       @RequestParam(required = false) String fromRole,
                       @RequestParam(required = false) String toRole,
                       @RequestParam(required = false) String type,
                       @RequestParam(required = false) String keyword,
                       @RequestParam(required = false) String orderNo,
                       @RequestParam(required = false) String sealMsg,
                       @RequestParam(defaultValue = "1") int page,
                       Model model, HttpServletRequest request) {
        User user = (User) session.getAttribute("user");
        if (user == null) return "redirect:/login";

        addContextPath(model, request);
        model.addAttribute("active", "mail");
        model.addAttribute("pageTitle", "协作信箱");

        String fr = trimToNull(fromRole);
        String tr = trimToNull(toRole);
        String tp = trimToNull(type);
        String kw = trimToNull(keyword);
        String on = trimToNull(orderNo);

        QueryWrapper<MailMessage> w = new QueryWrapper<>();
        if (fr != null) w.eq("from_role", fr);
        if (tr != null) w.eq("to_role", tr);
        if (tp != null) w.eq("msg_type", tp);
        if (kw != null) {
            w.and(x -> x.like("subject", kw).or().like("body", kw).or().like("thread_id", kw));
        }
        // 2026-11-14 W-20261114-12：按单号查（「封存状态需落库、能按单号查出」）
        if (on != null) w.eq("order_no", on);
        w.orderByDesc("id");

        int p = Math.max(page, 1);
        Page<MailMessage> result = mailMapper.selectPage(new Page<>(p, PAGE_SIZE), w);

        model.addAttribute("messages", result.getRecords());
        model.addAttribute("currentPage", result.getCurrent());
        model.addAttribute("totalPages", result.getPages());
        model.addAttribute("totalRows", result.getTotal());

        model.addAttribute("fromRole", fr == null ? "" : fr);
        model.addAttribute("toRole", tr == null ? "" : tr);
        model.addAttribute("type", tp == null ? "" : tp);
        model.addAttribute("keyword", kw == null ? "" : kw);
        model.addAttribute("orderNo", on == null ? "" : on);
        model.addAttribute("sealMsg", sealMsg == null ? "" : sealMsg);

        // 2026-11-14 W-20261114-12：封存状态 + 最近被封存的线程（人工解锁入口就在这一块）
        Map<String, com.exphub.entity.MailThreadState> sealStates = new HashMap<>();
        for (com.exphub.entity.MailThreadState s : sealService.recent(50)) {
            sealStates.put(s.getThreadId(), s);
        }
        model.addAttribute("sealStates", sealStates);
        model.addAttribute("sealedRecent", sealStates.values().stream()
                .filter(s -> "SEALED".equals(s.getState())).limit(20)
                .collect(java.util.stream.Collectors.toList()));

        // 下拉选项：角色来自表里真实出现过的值，别写死九个代号 ——
        // 写死了以后新增角色，后台会「看不到这个人的筛选」
        List<String> roles = mailMapper.distinctRoles();
        model.addAttribute("roles", roles);

        // 已读状态按「收件人游标」判，不按 ack。
        // ack 是模型手工调的，它不调就永远显示「未处理」—— 那不是状态，是噪声。
        // 游标是服务端在读线程时自动推进的，才是「对方到底看没看」的真实信号。
        Map<String, Long> cursorMap = cursorMap();
        Map<Long, Boolean> readState = new HashMap<>();
        for (MailMessage m : result.getRecords()) {
            long last = cursorMap.getOrDefault(m.getToRole() + "|" + m.getThreadId(), 0L);
            readState.put(m.getId(), last >= m.getId());
        }
        model.addAttribute("readState", readState);

        // 顶部计数：总消息 /  threads / 今日 / 未读（按游标）
        long total = mailMapper.selectCount(null);
        long threads = mailMapper.countThreads();
        long today = mailMapper.selectCount(new QueryWrapper<MailMessage>()
                .ge("created_at", LocalDate.now().atStartOfDay()));
        long unread = unreadCount(cursorMap);
        model.addAttribute("totalMessages", total);
        model.addAttribute("totalThreads", threads);
        model.addAttribute("todayMessages", today);
        model.addAttribute("unreadMessages", unread);

        return "mail/list";
    }

    /**
     * 单封详情：把同一根线程全部摊开，当前这封高亮。
     * 单看一封信判断不了「回执到了没」，所以这里不按游标截断，全给。
     */
    @GetMapping("/{id}")
    public String detail(HttpSession session, @PathVariable Long id,
                         Model model, HttpServletRequest request) {
        User user = (User) session.getAttribute("user");
        if (user == null) return "redirect:/login";

        MailMessage current = mailMapper.selectById(id);
        if (current == null) {
            return "redirect:/mail";
        }

        addContextPath(model, request);
        model.addAttribute("active", "mail");
        model.addAttribute("pageTitle", "信件详情");

        List<MailMessage> thread = mailMapper.selectList(new QueryWrapper<MailMessage>()
                .eq("thread_id", current.getThreadId())
                .orderByAsc("id"));

        model.addAttribute("current", current);
        model.addAttribute("thread", thread);
        model.addAttribute("threadSize", thread.size());
        model.addAttribute("threadId", current.getThreadId());
        // 2026-11-14 W-20261114-12：该线程的封存状态与留痕（人工解锁入口在模板上）
        model.addAttribute("sealState", sealService.stateOf(current.getThreadId()));
        model.addAttribute("sealLog", sealService.logOf(current.getThreadId(), 10));

        // 每封信「收件人读没读」，同样看游标而不是 ack
        Map<String, Long> cursorMap = cursorMap();
        Map<Long, Boolean> readState = new HashMap<>();
        for (MailMessage m : thread) {
            long last = cursorMap.getOrDefault(m.getToRole() + "|" + m.getThreadId(), 0L);
            readState.put(m.getId(), last >= m.getId());
        }
        model.addAttribute("readState", readState);
        return "mail/detail";
    }

    /**
     * 人工解锁一根线程（2026-11-14 阁主 W-20261114-12 第 3 条必做项）。
     *
     * <p>「没有解锁入口，封存之后真需要追加说明时对方看不到，比现在的多回话更难查」——
     * 所以这个是必做项，不是可选。越哥在后台点，阁主/助手走 MCP 的
     * {@code mail_thread(threadId=…, unseal=true)}，两条路写的是同一张留痕表。
     *
     * <p>只动封存状态，<b>不动任何一封信</b>（消息只追加的纪律不变）。
     */
    @PostMapping("/thread/{threadId}/unseal")
    public String unseal(@PathVariable String threadId,
                         @RequestParam(required = false) String note,
                         @RequestParam(required = false) String back,
                         HttpSession session) {
        User user = (User) session.getAttribute("user");
        if (user == null) return "redirect:/login";
        String actor = "web:" + (user.getUsername() == null ? String.valueOf(user.getId()) : user.getUsername());
        com.exphub.service.MailThreadSealService.UnsealOutcome r =
                sealService.unseal(threadId, actor, trimToNull(note));
        String msg = r.changed() ? ("已解锁 " + threadId) : ("未变更：" + r.detail());
        String target = (back == null || back.isBlank()) ? "/mail" : back;
        return "redirect:" + target + (target.contains("?") ? "&" : "?") + "sealMsg=" + enc(msg);
    }

    /**
     * 按单号手动封存（后台入口，2026-11-14 W-20261114-12）。
     *
     * <p>为什么要手动入口：自动封存只在「收到 delivered 回执」时触发，
     * 而 W-06 这类历史线程的信没有 order_no 列值（本单禁止回填历史数据），
     * 只能用显式线程号补录 —— 这条路径就是给补录用的。
     */
    @PostMapping("/seal")
    public String seal(@RequestParam String orderNo,
                       @RequestParam(required = false) String threadIds,
                       @RequestParam(required = false) String note,
                       HttpSession session) {
        User user = (User) session.getAttribute("user");
        if (user == null) return "redirect:/login";
        String on = trimToNull(orderNo);
        if (on == null) return "redirect:/mail?sealMsg=" + enc("单号不能为空，未做任何改动");
        String actor = "web:" + (user.getUsername() == null ? String.valueOf(user.getId()) : user.getUsername());
        java.util.List<String> extra = new java.util.ArrayList<>();
        if (threadIds != null && !threadIds.isBlank()) {
            for (String t : threadIds.split("[,，\\s]+")) {
                if (!t.isBlank()) extra.add(t.trim());
            }
        }
        com.exphub.service.MailThreadSealService.SealOutcome r =
                sealService.sealThreads(extra, null, on.toUpperCase(), actor, trimToNull(note), false);
        StringBuilder msg = new StringBuilder("单 ").append(on).append("：新封 ")
                .append(r.sealed().size()).append(" 根、原已封 ").append(r.alreadySealed().size()).append(" 根");
        if (!r.notFound().isEmpty()) msg.append("、线程不存在 ").append(r.notFound().size()).append(" 根");
        if (!r.skippedHumanOpen().isEmpty()) msg.append("、人工解锁跳过 ").append(r.skippedHumanOpen().size()).append(" 根");
        return "redirect:/mail?sealMsg=" + enc(msg.toString());
    }

    private static String enc(String s) {
        return java.net.URLEncoder.encode(s == null ? "" : s, java.nio.charset.StandardCharsets.UTF_8);
    }

    /** role|threadId -> 已读到的消息 ID。 */
    private Map<String, Long> cursorMap() {
        Map<String, Long> map = new HashMap<>();
        for (MailCursor c : cursorMapper.selectList(null)) {
            if (c.getRole() != null && c.getThreadId() != null && c.getLastReadId() != null) {
                map.put(c.getRole() + "|" + c.getThreadId(), c.getLastReadId());
            }
        }
        return map;
    }

    /** 未被收件人读到的消息数（按游标，不按 ack）。 */
    private long unreadCount(Map<String, Long> cursorMap) {
        List<MailMessage> all = mailMapper.selectList(new QueryWrapper<MailMessage>()
                .select("id", "to_role", "thread_id"));
        long n = 0;
        for (MailMessage m : all) {
            long last = cursorMap.getOrDefault(m.getToRole() + "|" + m.getThreadId(), 0L);
            if (last < m.getId()) n++;
        }
        return n;
    }

    private String trimToNull(String s) {
        if (s == null) return null;
        String t = s.trim();
        return t.isEmpty() ? null : t;
    }

    /** 供模板显示：把 LocalDateTime 格式化成「MM-dd HH:mm」。 */
    public static String fmt(LocalDateTime t) {
        return t == null ? "-" : t.format(java.time.format.DateTimeFormatter.ofPattern("MM-dd HH:mm"));
    }
}
