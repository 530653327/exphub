package com.exphub.controller;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.exphub.entity.MailMessage;
import com.exphub.entity.User;
import com.exphub.mapper.MailMessageMapper;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 后台「协作信箱」查看页（2026-11-06 越哥要的）。
 *
 * <p><b>只读。</b>后台不提供发信、改信、删信：消息表的设计就是「只追加、不回改」，
 * 在这里开一个编辑入口等于把这个前提废掉。要纠错就让助手发一条更正消息。
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

    @GetMapping
    public String list(HttpSession session,
                       @RequestParam(required = false) String fromRole,
                       @RequestParam(required = false) String toRole,
                       @RequestParam(required = false) String type,
                       @RequestParam(required = false) String keyword,
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

        QueryWrapper<MailMessage> w = new QueryWrapper<>();
        if (fr != null) w.eq("from_role", fr);
        if (tr != null) w.eq("to_role", tr);
        if (tp != null) w.eq("msg_type", tp);
        if (kw != null) {
            w.and(x -> x.like("subject", kw).or().like("body", kw).or().like("thread_id", kw));
        }
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

        // 下拉选项：角色来自表里真实出现过的值，别写死九个代号 ——
        // 写死了以后新增角色，后台会「看不到这个人的筛选」
        List<String> roles = mailMapper.distinctRoles();
        model.addAttribute("roles", roles);

        // 顶部计数：总消息 /  threads / 今日 / 未处理
        long total = mailMapper.selectCount(null);
        long threads = mailMapper.countThreads();
        long today = mailMapper.selectCount(new QueryWrapper<MailMessage>()
                .ge("created_at", LocalDate.now().atStartOfDay()));
        long unprocessed = mailMapper.selectCount(new QueryWrapper<MailMessage>()
                .isNull("processed_at"));
        model.addAttribute("totalMessages", total);
        model.addAttribute("totalThreads", threads);
        model.addAttribute("todayMessages", today);
        model.addAttribute("unprocessedMessages", unprocessed);

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
        return "mail/detail";
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
