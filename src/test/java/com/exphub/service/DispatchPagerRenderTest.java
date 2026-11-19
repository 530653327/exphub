package com.exphub.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.thymeleaf.TemplateSpec;
import org.thymeleaf.spring6.SpringTemplateEngine;
import org.thymeleaf.context.Context;
import org.thymeleaf.context.IExpressionContext;
import org.thymeleaf.linkbuilder.ILinkBuilder;
import org.thymeleaf.templatemode.TemplateMode;
import org.thymeleaf.templateresolver.ClassLoaderTemplateResolver;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 派单翻页条的**真渲染**守卫（2026-11-19）。
 *
 * <p><b>为什么不能只做源码字符串断言</b>：上一版（{@link DispatchPaginationTest}）检查的是
 * 「模板里有没有 ${pageSizes} 这段字」，源码里当然有 —— 而真实的失败模式是
 * ①整块被 {@code th:if="${totalPages > 1}"} 藏在库里 19 张单的情况下，
 * ②以及 {@code th:text} 里把 {@code ${}} 和字面量混着拼导致 Thymeleaf 解析失败。
 * 这两种都能让字符串断言全绿、页面却什么都没有或者直接 500。**只有真渲染一遍才算验证过。**
 *
 * <p>这里用 Thymeleaf 引擎 + 类路径模板渲染 {@code fragments/dispatch-pager}
 * 的真实片段（不是测试里另抄一份 markup —— 抄一份就等于验证假对象），
 * 断言渲染结果里有页码、张数范围、每页条数选择器，且链接带着 size / status / keyword。
 *
 * <p>链接表达式（{@code @{...}}）在没有 Web 上下文时算不出来，所以挂一个最小
 * {@link ILinkBuilder}：它的行为与 Spring 的实现一致（**值为 null 的参数不拼进 query**，
 * 否则每页条数链接会带一个空的 {@code status=}）。这里验证的是「表达式有没有把参数写进去」，
 * 不验证 Spring 的 URL 编码 —— 那部分由框架自己保证。
 */
class DispatchPagerRenderTest {

    private static final List<Integer> PAGE_SIZES = List.of(10, 20, 50);

    /** 真实片段名：两个端共用同一个模板文件。 */
    private static SpringTemplateEngine engine() {
        ClassLoaderTemplateResolver resolver = new ClassLoaderTemplateResolver();
        resolver.setPrefix("templates/");
        resolver.setSuffix(".html");
        resolver.setTemplateMode(TemplateMode.HTML);
        resolver.setCharacterEncoding("UTF-8");
        resolver.setCacheable(false);

        SpringTemplateEngine engine = new SpringTemplateEngine();
        engine.setTemplateResolver(resolver);
        engine.setLinkBuilder(new QueryLinkBuilder());
        return engine;
    }

    /** 渲染片段文件里的某一个片段（真实模板，不复制 markup）。 */
    private static String render(String fragment, Context ctx) {
        // 链接里的 & 会被转义成 &amp;（HTML 正确），断言里想读的是 query 本身，先还原
        return engine().process(new TemplateSpec("fragments/dispatch-pager", java.util.Set.of(fragment), (org.thymeleaf.templatemode.TemplateMode) null, null), ctx)
                .replace("&amp;", "&");
    }

    private static Context model(int page, int totalPages, int total, int from, int to, int size) {
        Context ctx = new Context();
        ctx.setVariable("page", page);
        ctx.setVariable("totalPages", totalPages);
        ctx.setVariable("total", total);
        ctx.setVariable("from", from);
        ctx.setVariable("to", to);
        ctx.setVariable("size", size);
        ctx.setVariable("pageSizes", PAGE_SIZES);
        ctx.setVariable("status", null);
        ctx.setVariable("keyword", null);
        ctx.setVariable("orders", List.of());
        return ctx;
    }

    /** 去掉标签、压平空白：断言「人眼看到的文字」而不是 markup 的换行与缩进。 */
    private static String text(String html) {
        return html.replaceAll("<[^>]+>", "").replaceAll("\\s+", " ").trim();
    }

    @Test
    @DisplayName("19 张单 / 每页 20 张：PC 翻页条也必须渲染出「1 / 1」「第 1–19 张」与每页条数")
    void pcPagerRendersWhenEverythingFitsOnOnePage() {
        String html = render("pc", model(1, 1, 19, 1, 19, 20));
        String text = text(html);

        assertTrue(text.contains("1 / 1"), "没有页码「1 / 1」—— 页面上看不出有分页：" + text);
        assertTrue(text.contains("第 1–19 张 / 共 19 张"), "张数范围不对：" + text);
        assertTrue(text.contains("一页装得下（每页 20 张），把每页调小就能翻页"),
                "一页装得下时必须明说「把每页调小就能翻页」，否则用户只能猜为什么没有下一页：" + text);
        // 每页条数选择器：三档都在，且当前档（20）被标记为 active
        assertTrue(text.contains("10 张/页") && text.contains("20 张/页") && text.contains("50 张/页"),
                "缺每页条数选择器：" + text);
        assertTrue(html.contains("btn-outline-secondary active"),
                "当前每页条数没有 active 标记，看不出现在每页几张：" + html);
        // 链接必须带 size / status / keyword —— 少一个，翻页就等于把筛选重置
        assertTrue(html.contains("page=2&size=20"), "下一页链接没带 size：点上就回到默认每页条数");
        assertTrue(html.contains("page=1&size=10"), "每页条数链接没有 shape 成 page=1&size=10");
    }

    @Test
    @DisplayName("41 张 / 每页 20 / 第 2 页：范围是 21–40，上一页回 1、下一页到 3，且不再印「一页装得下」")
    void pcPagerComputesRangeAndLinks() {
        String html = render("pc", model(2, 3, 41, 21, 40, 20));
        String text = text(html);

        assertTrue(text.contains("2 / 3"), "页码不对：" + text);
        assertTrue(text.contains("第 21–40 张 / 共 41 张"), "张数范围不对：" + text);
        assertFalse(text.contains("一页装得下"), "多页时不许再印「一页装得下」—— 自相矛盾");
        assertTrue(html.contains("page=1&size=20"), "上一页没回到第 1 页：" + html);
        assertTrue(html.contains("page=3&size=20"), "下一页没到第 3 页：" + html);
    }

    @Test
    @DisplayName("手机端同一份片段：页码与张数正常渲染，链接打到 /m/dispatch 并带 size")
    void mobilePagerRenders() {
        String html = render("m", model(1, 1, 19, 1, 19, 10));
        String text = text(html);

        assertTrue(text.contains("1 / 1 页"), "手机端页码没渲染：" + text);
        assertTrue(text.contains("第 1–19 张 / 共 19 张"), "手机端张数不对：" + text);
        assertTrue(html.contains("/m/dispatch?page=2&size=10"),
                "手机端下一页链接没带 size：" + html);
        assertTrue(text.contains("每页"), "手机端没有每页条数入口：" + text);
    }

    /**
     * 最小链接构造器：{@code @{/x(a=${a})}} → {@code /x?a=...}，**值为 null 的参数不拼**。
     *
     * <p>无 Web 上下文时 Thymeleaf 自己没有 link builder，不加这个连表达式都算不出来。
     */
    private static final class QueryLinkBuilder implements ILinkBuilder {

        @Override
        public String getName() {
            return "test-query";
        }

        @Override
        public Integer getOrder() {
            return 0;
        }

        @Override
        public String buildLink(IExpressionContext context, String base, Map<String, Object> parameters) {
            StringBuilder sb = new StringBuilder(base == null ? "" : base);
            Map<String, Object> params = new LinkedHashMap<>();
            if (parameters != null) {
                parameters.forEach((k, v) -> {
                    if (v != null && !String.valueOf(v).isEmpty()) {
                        params.put(k, v);
                    }
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
