package com.exphub.web;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.mock.web.MockServletContext;
import org.thymeleaf.context.WebContext;
import org.thymeleaf.spring6.SpringTemplateEngine;
import org.thymeleaf.templatemode.TemplateMode;
import org.thymeleaf.templateresolver.ClassLoaderTemplateResolver;
import org.thymeleaf.web.servlet.JakartaServletWebApplication;

import java.util.Locale;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * PC 页面「能渲染」守卫（2026-11-08 加）。
 *
 * <p>起因：给经验列表加「问题文档」视图时改了 {@code docs/list.html}，
 * 而这个页面<b>没有任何测试覆盖</b> —— 模板语法错一个字符，线上就是 500。
 * 移动端已经有 {@code MobileTemplateRenderTest} 兜住了，PC 侧一直是空的。
 *
 * <p>这里只渲染，不启动 Spring、不连库：变量手工喂成「空列表」即可走进空态分支，
 * 足以让模板里所有表达式被求值一遍 —— 这正是要防的东西。
 */
class PageTemplateRenderTest {

    private static final String CTX_PATH = "/exphub";

    private static SpringTemplateEngine engine() {
        ClassLoaderTemplateResolver resolver = new ClassLoaderTemplateResolver();
        resolver.setPrefix("templates/");
        resolver.setSuffix(".html");
        resolver.setTemplateMode(TemplateMode.HTML);
        resolver.setCharacterEncoding("UTF-8");
        resolver.setCacheable(false);

        SpringTemplateEngine engine = new SpringTemplateEngine();
        engine.setTemplateResolver(resolver);
        return engine;
    }

    private static WebContext context(String problem) {
        MockServletContext servletContext = new MockServletContext();
        MockHttpServletRequest request = new MockHttpServletRequest(servletContext);
        request.setContextPath(CTX_PATH);
        request.setRequestURI(CTX_PATH + "/docs");
        MockHttpServletResponse response = new MockHttpServletResponse();

        JakartaServletWebApplication webApp = JakartaServletWebApplication.buildApplication(servletContext);
        WebContext ctx = new WebContext(webApp.buildExchange(request, response), Locale.SIMPLIFIED_CHINESE);

        // 与 PageController.docs 往 Model 里放的字段保持一致
        ctx.setVariable("contextPath", CTX_PATH);
        ctx.setVariable("active", "docs");
        ctx.setVariable("pageTitle", "经验管理");
        ctx.setVariable("keyword", "");
        ctx.setVariable("templateType", "");
        ctx.setVariable("status", "");
        ctx.setVariable("flag", "");
        ctx.setVariable("view", "");
        ctx.setVariable("problem", problem);
        ctx.setVariable("docs", List.of());
        ctx.setVariable("currentPage", 1L);
        ctx.setVariable("totalPages", 0L);
        ctx.setVariable("totalRows", 0L);
        ctx.setVariable("pageNumbers", List.of(1));
        ctx.setVariable("pendingDeleteCount", 0L);
        ctx.setVariable("needsReviewCount", 0L);
        ctx.setVariable("candidateCount", 0L);
        ctx.setVariable("problemCounts", Map.of(
                "STALE", 105L, "SUPERSEDED", 0L, "FLAGGED", 0L,
                "PENDING_DELETE", 0L, "BROKEN", 0L));
        ctx.setVariable("problemTotal", 105L);
        ctx.setVariable("staleDays", 90);
        // versionStats 必须是可变 Map 且带齐 4 个 key：
        // SpEL 对 Map 的属性访问走 MapAccessor，遇到不可变 Map（Map.of）会报
        // "Property 'snapshots' cannot be found"，导致这条测试假红。
        Map<String, Object> versionStats = new java.util.HashMap<>();
        versionStats.put("snapshots", 0L);
        versionStats.put("kb", 0L);
        versionStats.put("metaCount", 0L);
        versionStats.put("avgChars", 0L);
        ctx.setVariable("versionStats", versionStats);
        ctx.setVariable("_csrf", null);
        return ctx;
    }

    @Test
    @DisplayName("经验列表：普通视图与问题文档视图都必须能渲染完成")
    void docsListRendersInBothModes() {
        SpringTemplateEngine engine = engine();

        for (String problem : List.of("", "stale")) {
            String html;
            try {
                html = engine.process("docs/list", context(problem));
            } catch (RuntimeException e) {
                throw new AssertionError("docs/list 在 problem=\"" + problem + "\" 下渲染失败，线上会是一个 500 页。"
                        + "原始异常：" + e.getMessage(), e);
            }
            assertTrue(html.contains("<!DOCTYPE html>"), "输出不是完整 HTML");
            // problem 为空时也不能丢入口，否则「问题文档」这个 Tab 永远回不去
            assertTrue(html.contains("problem='stale'") || html.contains("problem=stale"),
                    "页面里没有指向问题文档视图的链接");
        }
    }

    @Test
    @DisplayName("问题文档视图要给出 5 个分类，且数字来自 problemCounts")
    void problemViewShowsAllFiveTypes() {
        String html = engine().process("docs/list", context("stale"));

        // 渲染后 @{/docs(problem='stale')} 会变成 /exphub/docs?problem=stale，所以断言 URL 形态而非模板原文
        for (String t : List.of("stale", "superseded", "flagged", "pending_delete", "broken")) {
            assertTrue(html.contains("problem=" + t),
                    "问题文档视图缺分类入口：" + t);
        }
        assertTrue(html.contains("超期未验证"), "缺「超期未验证」文案");
        assertTrue(html.contains("105"), "STALE 的计数没有渲染出来（problemCounts 没被读到）");
    }

    // ── 协作信箱（2026-11-14 阁主 W-20261114-12：线程封存 / 人工解锁）────────────────

    /** 信箱列表页的模型（与 MailAdminController.list 保持一致）。 */
    private WebContext mailContext() {
        MockServletContext servletContext = new MockServletContext();
        MockHttpServletRequest request = new MockHttpServletRequest(servletContext);
        request.setContextPath(CTX_PATH);
        request.setRequestURI(CTX_PATH + "/mail");
        MockHttpServletResponse response = new MockHttpServletResponse();
        JakartaServletWebApplication webApp = JakartaServletWebApplication.buildApplication(servletContext);
        WebContext ctx = new WebContext(webApp.buildExchange(request, response), Locale.SIMPLIFIED_CHINESE);

        com.exphub.entity.MailMessage m = new com.exphub.entity.MailMessage();
        m.setId(2092L);
        m.setThreadId("T261114142452-739");
        m.setFromRole("阁主");
        m.setToRole("烛龙");
        m.setMsgType("dispatch");
        m.setSubject("派单 W-20261114-12");
        m.setBody("单号: W-20261114-12\n正文");
        m.setOrderNo("W-20261114-12");
        m.setCreatedAt(java.time.LocalDateTime.now());

        com.exphub.entity.MailThreadState st = new com.exphub.entity.MailThreadState();
        st.setThreadId("T261114142452-739");
        st.setOrderNo("W-20261114-12");
        st.setState("SEALED");
        st.setSealedAt(java.time.LocalDateTime.now());
        st.setSealedBy("阁主");
        st.setSealReason("单已转待验收");

        java.util.Map<String, com.exphub.entity.MailThreadState> states = new java.util.HashMap<>();
        states.put(st.getThreadId(), st);

        ctx.setVariable("contextPath", CTX_PATH);
        ctx.setVariable("active", "mail");
        ctx.setVariable("pageTitle", "协作信箱");
        ctx.setVariable("messages", List.of(m));
        ctx.setVariable("currentPage", 1L);
        ctx.setVariable("totalPages", 1L);
        ctx.setVariable("totalRows", 1L);
        ctx.setVariable("fromRole", "");
        ctx.setVariable("toRole", "");
        ctx.setVariable("type", "");
        ctx.setVariable("keyword", "");
        ctx.setVariable("orderNo", "W-20261114-12");
        ctx.setVariable("sealMsg", "已解锁 T261114142452-739");
        ctx.setVariable("roles", List.of("阁主", "烛龙"));
        ctx.setVariable("sealStates", states);
        ctx.setVariable("sealedRecent", List.of(st));
        ctx.setVariable("readState", java.util.Map.of(2092L, Boolean.TRUE));
        ctx.setVariable("totalMessages", 1L);
        ctx.setVariable("totalThreads", 1L);
        ctx.setVariable("todayMessages", 1L);
        ctx.setVariable("unreadMessages", 1L);
        ctx.setVariable("_csrf", null);
        return ctx;
    }

    @Test
    @DisplayName("信箱列表：封存块、🔒 标记、解锁表单、单号筛选都必须渲染出来")
    void mailListRendersSealSection() {
        String html = engine().process("mail/list", mailContext());

        assertTrue(html.contains("线程封存"), "缺封存块 —— 越哥就没有人工解锁入口了");
        assertTrue(html.contains("已封存（不唤醒）"), "行内 🔒 标记没渲染（读者会以为封存失败=链路坏了）");
        assertTrue(html.contains("/mail/thread/T261114142452-739/unseal"), "解锁表单 action 不对");
        assertTrue(html.contains("委托单号"), "缺少按单号筛选/封存的入口");
        assertTrue(html.contains("已解锁 T261114142452-739"), "sealMsg 没渲染出来");
    }

    @Test
    @DisplayName("信箱详情：封存状态 + 留痕 + 解除封存按钮必须渲染出来")
    void mailDetailRendersSealState() {
        MockServletContext servletContext = new MockServletContext();
        MockHttpServletRequest request = new MockHttpServletRequest(servletContext);
        request.setContextPath(CTX_PATH);
        request.setRequestURI(CTX_PATH + "/mail/2092");
        MockHttpServletResponse response = new MockHttpServletResponse();
        JakartaServletWebApplication webApp = JakartaServletWebApplication.buildApplication(servletContext);
        WebContext ctx = new WebContext(webApp.buildExchange(request, response), Locale.SIMPLIFIED_CHINESE);

        com.exphub.entity.MailMessage m = new com.exphub.entity.MailMessage();
        m.setId(2092L);
        m.setThreadId("T261114142452-739");
        m.setFromRole("阁主");
        m.setToRole("烛龙");
        m.setMsgType("dispatch");
        m.setSubject("派单 W-20261114-12");
        m.setBody("正文");
        m.setCreatedAt(java.time.LocalDateTime.now());

        com.exphub.entity.MailThreadState st = new com.exphub.entity.MailThreadState();
        st.setThreadId("T261114142452-739");
        st.setOrderNo("W-20261114-12");
        st.setState("SEALED");
        st.setSealedAt(java.time.LocalDateTime.now());
        st.setSealedBy("阁主");

        com.exphub.entity.MailThreadStateLog lg = new com.exphub.entity.MailThreadStateLog();
        lg.setAction("SEAL");
        lg.setActor("阁主");
        lg.setCreatedAt(java.time.LocalDateTime.now());

        ctx.setVariable("contextPath", CTX_PATH);
        ctx.setVariable("active", "mail");
        ctx.setVariable("pageTitle", "信件详情");
        ctx.setVariable("current", m);
        ctx.setVariable("thread", List.of(m));
        ctx.setVariable("threadSize", 1);
        ctx.setVariable("threadId", "T261114142452-739");
        ctx.setVariable("readState", java.util.Map.of(2092L, Boolean.FALSE));
        ctx.setVariable("sealState", st);
        ctx.setVariable("sealLog", List.of(lg));
        ctx.setVariable("_csrf", null);

        String html = engine().process("mail/detail", ctx);

        assertTrue(html.contains("已封存（不唤醒）"), "详情页没显示封存状态");
        assertTrue(html.contains("解除封存"), "详情页缺解锁按钮");
        assertTrue(html.contains("SEAL by 阁主"), "留痕没渲染（这就是「谁解的」的证据）");
    }
}
