package com.exphub.controller;

import com.exphub.entity.DispatchEvent;
import com.exphub.service.DispatchBurndownService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.thymeleaf.TemplateSpec;
import org.thymeleaf.spring6.SpringTemplateEngine;
import org.thymeleaf.context.Context;
import org.thymeleaf.context.IExpressionContext;
import org.thymeleaf.linkbuilder.ILinkBuilder;
import org.thymeleaf.templatemode.TemplateMode;
import org.thymeleaf.templateresolver.ClassLoaderTemplateResolver;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 后台两页（操作流水 / 燃尽图）的**真渲染**守卫（W-20261120-000028）。
 *
 * <p>字符串断言看不出模板表达式写错：{@code th:each} 变量名拼错、{@code ${}} 与字面量混拼、
 * 方言缺失，都会让源码断言全绿、页面 500。这里用真实 Thymeleaf 引擎渲染整页模板，
 * 断言角色 / 单号 / 状态三色 class、阶梯线 points、KPI、无外链 CDN 都真的渲染出来。
 *
 * <p>链接用最小 {@link ILinkBuilder}（与 DispatchPagerRenderTest 同款）：无 Web 上下文时
 * {@code @{...}} 需要它才算得出来，null 参数不拼进 query。
 */
class DispatchOpsPagesRenderTest {

    private static SpringTemplateEngine engine() {
        ClassLoaderTemplateResolver resolver = new ClassLoaderTemplateResolver();
        resolver.setPrefix("templates/");
        resolver.setSuffix(".html");
        resolver.setTemplateMode(TemplateMode.HTML);
        resolver.setCharacterEncoding("UTF-8");
        resolver.setCacheable(false);
        SpringTemplateEngine e = new SpringTemplateEngine();
        e.setTemplateResolver(resolver);
        e.setLinkBuilder(new QueryLinkBuilder());
        return e;
    }

    private static String render(String template, Context ctx) {
        return engine().process(template, ctx).replace("&amp;", "&");
    }

    private static String text(String html) {
        return html.replaceAll("<[^>]+>", "").replaceAll("\\s+", " ").trim();
    }

    private static void labels(Context ctx) {
        Map<String, String> st = new LinkedHashMap<>();
        st.put("RUNNING", "进行中");
        st.put("DONE", "已完成");
        st.put("CANCELLED", "已取消");
        ctx.setVariable("statusLabels", st);
        Map<String, String> et = new LinkedHashMap<>();
        et.put("CLAIM", "认领");
        et.put("ACCEPT_PASS", "验收通过");
        ctx.setVariable("eventTypeLabels", et);
        Map<String, String> at = new LinkedHashMap<>();
        at.put("HUMAN", "人");
        at.put("AI", "助手");
        ctx.setVariable("actorTypeLabels", at);
    }

    @Test
    @DisplayName("操作流水页：角色 / 单号 / 状态三色 class 真渲染，翻页链接带筛选参数")
    void eventsPageRendersThreeColorsAndFilters() {
        Context ctx = new Context();
        DispatchEvent e = new DispatchEvent();
        e.setOrderNo("W-20261120-000001");
        e.setEventType("CLAIM");
        e.setFromStatus("READY");
        e.setToStatus("RUNNING");
        e.setActor("烛龙");
        e.setActorType("AI");
        e.setNote("接单");
        ctx.setVariable("events", List.of(e));
        ctx.setVariable("orderNos", List.of("W-20261120-000001"));
        ctx.setVariable("orderNo", "");
        ctx.setVariable("toStatus", "");
        ctx.setVariable("actor", "");
        ctx.setVariable("total", 1);
        ctx.setVariable("page", 1);
        ctx.setVariable("totalPages", 1);
        ctx.setVariable("from", 1);
        ctx.setVariable("to", 1);
        ctx.setVariable("size", 50);
        labels(ctx);

        String html = render("dispatch/events", ctx);
        String text = text(html);

        assertTrue(text.contains("派单操作流水"), "标题没渲染：" + text);
        assertTrue(text.contains("认领"), "事件中文映射没渲染：" + text);
        assertTrue(text.contains("进行中") && text.contains("W-20261120-000001"),
                "状态 / 单号没渲染：" + text);
        assertTrue(html.contains("actor-AI"), "角色三色 class（actor-AI）没渲染：" + html);
        assertTrue(html.contains("st-RUNNING"), "状态三色 class（st-RUNNING）没渲染：" + html);
        assertTrue(html.contains("mono-no"), "单号等宽高亮 class 没渲染：" + html);
        assertTrue(html.contains("/dispatch/events?page="), "翻页链接没渲染：" + html);
        assertNoCdn(html);
    }

    @Test
    @DisplayName("操作流水空态也能渲染（没有事件时不 500）")
    void eventsPageEmptyState() {
        Context ctx = new Context();
        ctx.setVariable("events", List.of());
        ctx.setVariable("orderNos", List.of());
        ctx.setVariable("orderNo", "");
        ctx.setVariable("toStatus", "");
        ctx.setVariable("actor", "");
        ctx.setVariable("total", 0);
        ctx.setVariable("page", 1);
        ctx.setVariable("totalPages", 1);
        ctx.setVariable("from", 0);
        ctx.setVariable("to", 0);
        ctx.setVariable("size", 50);
        labels(ctx);
        String text = text(render("dispatch/events", ctx));
        assertTrue(text.contains("还没有流水记录"), "空态提示没渲染：" + text);
    }

    @Test
    @DisplayName("燃尽图页：阶梯线 points / KPI / 里程碑下拉真渲染，且无任何外链 CDN")
    void burndownPageRendersStepLineAndKpis() {
        Context ctx = new Context();
        List<DispatchBurndownService.DayPoint> series = List.of(
                new DispatchBurndownService.DayPoint("2026-11-18", 3),
                new DispatchBurndownService.DayPoint("2026-11-19", 2),
                new DispatchBurndownService.DayPoint("2026-11-20", 2));
        DispatchBurndownService.Result r = new DispatchBurndownService.Result(
                "M1", 3, 1, 2, LocalDate.of(2026, 11, 18), LocalDate.of(2026, 11, 20), series, 1);
        ctx.setVariable("result", r);
        ctx.setVariable("chart", DispatchController.buildChart(r));
        ctx.setVariable("milestones", List.of("M1"));
        ctx.setVariable("milestoneOrders", List.of());
        ctx.setVariable("statusLabels", new LinkedHashMap<>());
        ctx.setVariable("milestone", "M1");
        ctx.setVariable("from", "");
        ctx.setVariable("to", "");

        String html = render("dispatch/burndown", ctx);
        String text = text(html);

        assertTrue(text.contains("派单燃尽图"), "标题没渲染：" + text);
        assertTrue(text.contains("里程碑：M1"), "里程碑名没渲染：" + text);
        assertTrue(text.contains("缺事件时间") || text.contains("兜底"), "兜底口径说明没渲染：" + text);
        // 阶梯线：3 个点 → points 至少有 5 组坐标（含水平 + 垂直段），且 class 正确
        assertTrue(html.contains("class=\"step-line\""), "阶梯线 polyline 没渲染：" + html);
        assertTrue(html.contains("points=\"48,"), "阶梯线起点坐标不对（应从左内边距 48 开始）：" + html);
        long commas = html.chars().filter(c -> c == ',').count();
        assertTrue(commas >= 4, "阶梯线坐标点太少，可能 series 没进模板：" + html);
        // 里程碑下拉选中项
        assertTrue(html.contains("value=\"M1\" selected") || html.contains("selected=\"selected\""),
                "里程碑下拉没选中当前值：" + html);
        assertNoCdn(html);
    }

    /** 禁外链 CDN：页面里不许有任何 http(s) 的 <script src> / <link href>（含 Chart.js 之类）。 */
    private static void assertNoCdn(String html) {
        assertFalse(html.matches("(?s).*<script[^>]+src=[\"']https?://.*"),
                "出现外链脚本 CDN，违反「禁外链 CDN」：" + html);
        assertFalse(html.matches("(?s).*<link[^>]+href=[\"']https?://.*"),
                "出现外链样式 CDN，违反「禁外链 CDN」：" + html);
    }

    /** 同 DispatchPagerRenderTest：无 Web 上下文时 @{...} 的链接构造器，null 参数不拼。 */
    private static final class QueryLinkBuilder implements ILinkBuilder {
        @Override public String getName() { return "test-query"; }
        @Override public Integer getOrder() { return 0; }
        @Override
        public String buildLink(IExpressionContext context, String base, Map<String, Object> parameters) {
            if (base == null || base.isEmpty()) return "";
            StringBuilder sb = new StringBuilder(base);
            Map<String, Object> params = new LinkedHashMap<>();
            if (parameters != null) {
                parameters.forEach((k, v) -> {
                    if (v != null && !String.valueOf(v).isEmpty()) params.put(k, v);
                });
            }
            boolean first = sb.indexOf("?") < 0;
            for (Map.Entry<String, Object> e : params.entrySet()) {
                sb.append(first ? '?' : '&').append(e.getKey()).append('=').append(e.getValue());
                first = false;
            }
            return sb.toString();
        }
    }
}
