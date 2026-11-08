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
}
