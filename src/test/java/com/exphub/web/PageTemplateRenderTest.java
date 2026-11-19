package com.exphub.web;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import com.exphub.service.DispatchService;
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

    // ==================== 派单列表（2026-11-17 补已完成/已取消 + 关键词搜索）====================

    private static WebContext dispatchContext(String status, String keyword) {
        MockServletContext servletContext = new MockServletContext();
        MockHttpServletRequest request = new MockHttpServletRequest(servletContext);
        request.setContextPath(CTX_PATH);
        request.setRequestURI(CTX_PATH + "/dispatch");
        MockHttpServletResponse response = new MockHttpServletResponse();

        JakartaServletWebApplication webApp = JakartaServletWebApplication.buildApplication(servletContext);
        WebContext ctx = new WebContext(webApp.buildExchange(request, response), Locale.SIMPLIFIED_CHINESE);

        // 与 DispatchController.list 往 Model 里放的字段逐一对齐；少一个就是线上 500。
        // orders 给空列表：走进空态分支，模板里所有条件表达式都会被求值一遍。
        Map<String, Long> counts = new java.util.LinkedHashMap<>();
        for (String st : List.of("REVIEWING", "READY", "RUNNING", "CONFIRMING", "VERIFYING", "DONE", "CANCELLED")) {
            counts.put(st, 0L);
        }
        Map<String, String> labels = new java.util.LinkedHashMap<>();
        labels.put("REVIEWING", "待审核");
        labels.put("READY", "待接单");
        labels.put("RUNNING", "进行中");
        labels.put("CONFIRMING", "待确认");
        labels.put("VERIFYING", "待验收");
        labels.put("DONE", "已完成");
        labels.put("CANCELLED", "已取消");

        ctx.setVariable("active", "dispatch");
        ctx.setVariable("orders", List.of());
        ctx.setVariable("status", status);
        // Controller 会把 null 归一成空串，这里照做，保证测的是线上真实形态
        ctx.setVariable("keyword", keyword == null ? "" : keyword);
        ctx.setVariable("counts", counts);
        ctx.setVariable("labels", labels);
        // 2026-11-19 分页新增的字段，Controller 必然会给 —— 少一个线上就是 500
        ctx.setVariable("total", 0L);
        ctx.setVariable("page", 1);
        ctx.setVariable("totalPages", 1);
        ctx.setVariable("from", 0);
        ctx.setVariable("to", 0);
        // 每页条数：翻页条（fragments/dispatch-pager）读这两个，不给就是「没有分页」的样子
        ctx.setVariable("size", 20);
        ctx.setVariable("pageSizes", DispatchService.PAGE_SIZE_CHOICES);
        // 建单页（dispatch/new）会读这三个；list 模板不用，多给无害
        ctx.setVariable("roles", DispatchService.ASSIGNABLE_ROLES);
        ctx.setVariable("projects", List.of());
        ctx.setVariable("suggestedVersion", "1.0.1");
        ctx.setVariable("ownerKeyTail", "…9999");
        ctx.setVariable("_csrf", null);
        return ctx;
    }

    @Test
    @DisplayName("PC 建单页要渲染出「归属 Key」提示，且语法错不会变成线上 500")
    void dispatchNewShowsOwnerKey() {
        SpringTemplateEngine engine = engine();
        String html;
        try {
            html = engine.process("dispatch/new", dispatchContext(null, ""));
        } catch (RuntimeException e) {
            throw new AssertionError("dispatch/new 渲染失败，线上会是一个 500 页。原始异常：" + e.getMessage(), e);
        }
        assertTrue(html.contains("…9999"),
                "建单页没显示归属 Key —— 人工单写没写进 Key，建单的人只能靠猜");
        assertTrue(html.contains("/exphub/dispatch/create") || html.contains("/dispatch/create"),
                "建单页没有提交地址");
        for (String f : List.of("modifyCode", "commit", "push", "release", "dataWrite", "migration",
                "credential", "prodConfig", "securityGroup", "firewall", "spend")) {
            assertTrue(html.contains("name=\"" + f + "\""), "建单页缺授权位：" + f);
        }
    }

    @Test
    @DisplayName("派单列表：翻页控件要印当前页/总页数，且翻页链接不许丢 status 与 keyword")
    void dispatchPaginationKeepsFilters() {
        SpringTemplateEngine engine = engine();
        WebContext ctx = dispatchContext("DONE", "七色");
        ctx.setVariable("total", 41L);
        ctx.setVariable("page", 2);
        ctx.setVariable("totalPages", 3);
        ctx.setVariable("from", 21);
        ctx.setVariable("to", 40);

        String html = engine.process("dispatch/list", ctx);
        assertTrue(html.contains("上一页"), "第 2 页没有上一页入口");
        assertTrue(html.contains("下一页"), "第 2 页没有下一页入口");
        assertTrue(html.contains("2 / 3"), "没印「第几页 / 共几页」，用户不知道还有没有");
        assertTrue(html.contains(">41<"), "页头总数没用 count()，41 张单应当直接印出来");
        // 翻页链接带上筛选条件，否则一翻页搜索和状态就没了
        assertTrue(html.contains("page=3") && html.contains("status=DONE"),
                "下一页链接丢了 status：翻页会把筛选清空");
        assertTrue(html.contains("keyword=%E4%B8%83%E8%89%B2") || html.contains("keyword=七色"),
                "下一页链接丢了 keyword：翻页会把搜索词清空");
        // 每页条数必须跟着翻页链接走，否则翻到第 2 页会被打回默认 20 张
        assertTrue(html.contains("size=20"), "翻页链接丢了 size：翻页会重置每页条数");
        // 三个档位都要能看见 —— 数据不足一页时，靠调小每页条数才能验证翻页
        assertTrue(html.contains("10 张/页") && html.contains("50 张/页"),
                "每页条数选择器没渲染出来，数据不足一页时无法验证翻页");
    }

    @Test
    @DisplayName("派单列表：八个状态筛选 + 搜索框都要在，且带关键词时不能渲染成 500")
    void dispatchListHasAllStatusChipsAndSearch() {
        SpringTemplateEngine engine = engine();

        String plain;
        try {
            plain = engine.process("dispatch/list", dispatchContext(null, ""));
        } catch (RuntimeException e) {
            throw new AssertionError("dispatch/list 渲染失败，线上会是一个 500 页。原始异常：" + e.getMessage(), e);
        }

        // 少于八个就等于「已完成 / 已取消」翻不到，历史单一多就没法查（2026-11-17 越哥要求）
        for (String st : List.of("REVIEWING", "READY", "RUNNING", "CONFIRMING", "VERIFYING", "DONE", "CANCELLED")) {
            assertTrue(plain.contains("status=" + st), "派单列表缺状态筛选：" + st);
        }
        assertTrue(plain.contains("已完成"), "缺「已完成」筛选按钮文案");
        assertTrue(plain.contains("已取消"), "缺「已取消」筛选按钮文案");
        assertTrue(plain.contains("name=\"keyword\""), "没有关键词搜索输入框（name=keyword）");

        // 带关键词的分支也要能渲染：空态文案换了口气，还多出「清空」入口
        String searched;
        try {
            searched = engine.process("dispatch/list", dispatchContext("DONE", "七色"));
        } catch (RuntimeException e) {
            throw new AssertionError("dispatch/list 在带关键词时渲染失败（线上就是 500）。原始异常：" + e.getMessage(), e);
        }
        assertTrue(searched.contains("七色"), "搜索词没有回填到页面（keyword 没被读到）");
        assertTrue(searched.contains("清空"), "带关键词时没有清空入口，用户只能手动删 URL");
        // 状态筛选必须跟关键词一起留在链接里，否则点一下「已取消」就把搜索词丢了
        assertTrue(searched.contains("keyword=%E4%B8%83%E8%89%B2") || searched.contains("keyword=七色"),
                "状态筛选链接没有带上 keyword，切状态会丢搜索条件");
    }

}
