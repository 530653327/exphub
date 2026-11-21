package com.exphub.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 派单列表分页的守卫（2026-11-19 加）。
 *
 * <p><b>为什么要有这个测试</b>：后台原来一次只取 50 张、且没有 offset，页头「共 N 张」
 * 用的是 {@code orders.size()}（= 展示条数）。两个毛病叠在一起时**页面看不出任何异常**：
 * 单子过 50 张就静默丢掉更早的，页头还理直气壮写「共 50 张」。
 *
 * <p>这里只盯三件事，都不连库：
 * <ol>
 *   <li>分页片段的 offset/size 生成正确，且 size 不会被悄悄改大或改小</li>
 *   <li>列表与总数必须走<b>同一个</b> {@code buildFilter} —— 两处各拼一份 WHERE 必然漂</li>
 *   <li>页头不许再拿「展示条数」冒充总数</li>
 * </ol>
 */
class DispatchPaginationTest {

    private static final Path SERVICE =
            Path.of("src/main/java/com/exphub/service/DispatchService.java");
    private static final Path PC_LIST =
            Path.of("src/main/resources/templates/dispatch/list.html");
    private static final Path MOBILE_LIST =
            Path.of("src/main/resources/templates/m/dispatch.html");
    /** 翻页条的唯一实现（两个端共用）。 */
    private static final Path PAGER =
            Path.of("src/main/resources/templates/fragments/dispatch-pager.html");

    @Test
    @DisplayName("分页片段带 offset：第 2 页就是跳过前 20 条")
    void limitClauseCarriesOffset() {
        assertEquals("LIMIT 0,20", DispatchService.limitClause(20, 0));
        assertEquals("LIMIT 20,20", DispatchService.limitClause(20, 20));
        assertEquals("LIMIT 40,20", DispatchService.limitClause(20, 40));
    }

    @Test
    @DisplayName("size 封顶 50，但不会小于调用方要的（传 100 得到 50，而不是 20 或 100）")
    void limitIsCappedAtMaxPageSize() {
        assertEquals("LIMIT 0,50", DispatchService.limitClause(100, 0));
        assertEquals("LIMIT 0,50", DispatchService.limitClause(500, 0));
        assertEquals(50, DispatchService.MAX_PAGE_SIZE);
    }

    @Test
    @DisplayName("limit<=0 = 不限条数：不拼 LIMIT 片段（MCP 传 0 的语义）")
    void unlimitedMeansNoClause() {
        assertNull(DispatchService.limitClause(0, 0));
        assertNull(DispatchService.limitClause(-1, 100));
    }

    @Test
    @DisplayName("负 offset 当 0，不生成 MySQL 不认识的 LIMIT -5,20")
    void negativeOffsetFallsBackToZero() {
        assertEquals("LIMIT 0,20", DispatchService.limitClause(20, -5));
    }

    @Test
    @DisplayName("分页窗口：页码越界落到最后一页、返回空库给 0–0，绝不返回空页")
    void pageWindowClampsAndComputesRange() {
        DispatchService.PageWindow w = DispatchService.pageWindow(41, 2, 20);
        assertEquals(2, w.page());
        assertEquals(3, w.totalPages());
        assertEquals(20, w.offset());
        assertEquals(21, w.from());
        assertEquals(40, w.to());

        DispatchService.PageWindow first = DispatchService.pageWindow(41, 1, 20);
        assertEquals(1, first.from());
        assertEquals(20, first.to());

        DispatchService.PageWindow last = DispatchService.pageWindow(41, 3, 20);
        assertEquals(40, last.offset());
        assertEquals(41, last.from());
        assertEquals(41, last.to(), "最后一页只有 1 条时，to 必须等于总数，不能印成第 60 张");

        // 越界不是「报错」也不是「给你一个空页」——落到最后一页，Controller 再重定向到合法 URL
        assertEquals(3, DispatchService.pageWindow(41, 99, 20).page());
        assertEquals(1, DispatchService.pageWindow(41, 0, 20).page());
        assertEquals(1, DispatchService.pageWindow(41, -7, 20).page());

        DispatchService.PageWindow empty = DispatchService.pageWindow(0, 1, 20);
        assertEquals(1, empty.totalPages(), "0 张也要有 1 页，否则页码显示 1/0");
        assertEquals(0, empty.from());
        assertEquals(0, empty.to());
    }

    @Test
    @DisplayName("列表与总数共用同一套过滤条件 —— 各拼一份必然出现「页头 51 张、只有 3 页」")
    void listAndCountShareOneFilter() throws IOException {
        String src = Files.readString(SERVICE, StandardCharsets.UTF_8);

        // 6 参 buildFilter（多了 milestone）必须被 list 与 count 各调一次；多一份就说明有人又手抄了一遍 WHERE
        assertEquals(2, countOccurrences(src, "buildFilter(role, status, orderNo, keyword, milestone, apiKey)"),
                "list 与 count 必须都调同一个 6 参 buildFilter；多一份就说明有人又手抄了一遍 WHERE");
        // 反向守卫：Key 隔离与状态条件各自只允许出现在 buildFilter 里一次
        assertEquals(1, countOccurrences(src, "q.eq(\"api_key\", apiKey)"),
                "Key 条件不只出现在 buildFilter 里 —— 说明列表那边又手抄了一套过滤");
        assertEquals(1, countOccurrences(src, "q.eq(\"status\", status.trim()"),
                "状态条件不只出现在 buildFilter 里 —— 两处各拼一份就会漂");
    }

    @Test
    @DisplayName("页头不许拿展示条数冒充总数（orders.size 只能用于「本页为空」判断）")
    void headerMustNotUseDisplayedSizeAsTotal() throws IOException {
        String pc = Files.readString(PC_LIST, StandardCharsets.UTF_8);
        assertTrue(pc.contains("${total}"), "PC 页头没接总数");
        assertFalse(pc.contains("共 <strong th:text=\"${orders.size()}"),
                "PC 页头仍在用展示条数当总数 —— 单子过一页后这个数就是假的");

        String pager = Files.readString(PAGER, StandardCharsets.UTF_8);
        assertTrue(pager.contains("${total}"), "翻页条没接总数");
        assertFalse(pager.contains("${orders.size()}"),
                "翻页条在用展示条数当总数 —— 一翻页这个数就是假的");
    }

    @Test
    @DisplayName("每页条数只认白名单；?size=100000 回落默认，不许一页塞整张表")
    void pageSizeIsWhitelisted() {
        assertEquals(10, DispatchService.normalizePageSize(10, 20));
        assertEquals(50, DispatchService.normalizePageSize(50, 20));
        assertEquals(20, DispatchService.normalizePageSize(null, 20), "没传就走默认");
        assertEquals(20, DispatchService.normalizePageSize(999, 20), "白名单外必须回落，不能当成上限截断（会变成猜）");
        assertEquals(20, DispatchService.normalizePageSize(0, 20));
        assertEquals(20, DispatchService.normalizePageSize(-5, 20));
        assertEquals(10, DispatchService.normalizePageSize(null, 10), "手机端默认 10");
    }

    @Test
    @DisplayName("19 张单 / 每页 20 张时，翻页条也必须显示 —— 整块被 totalPages>1 藏起来就等于「没有分页」")
    void pagerIsAlwaysVisible() throws IOException {
        DispatchService.PageWindow onePage = DispatchService.pageWindow(19, 1, 20);
        assertEquals(1, onePage.totalPages());

        // 只检查「真的会渲染的 markup」—— 注释里引用了旧写法会把字符串断言骗成失败/通过
        String pager = Files.readString(PAGER, StandardCharsets.UTF_8)
                .replaceAll("(?s)<!--.*?-->", "");
        // 翻页条不许再挂 totalPages 条件（挂上了，单子不足一页时页面上什么都看不到）
        assertFalse(pager.contains("th:if=\"${totalPages > 1}\""),
                "翻页条又被藏起来了：单子不足一页时页面看起来就是没有分页");
        // 两个片段都必须给出「每页 N 张」的入口，否则「有没有分页」无法当场验证
        assertEquals(2, countOccurrences(pager, "${pageSizes}"), "两个端各缺一个每页条数选择器");
        assertEquals(2, countOccurrences(pager, "size=${s}"), "两个端的每页条数链接都要带 size");
        // 翻页链接必须带 size/status/keyword/milestone —— 少一个，翻页就等于重置筛选（W-20261121-000010 补 milestone）
        assertEquals(2, countOccurrences(pager,
                        "page=${page + 1}, size=${size}, status=${status}, keyword=${keyword}, milestone=${milestone}"),
                "下一页链接没带全参数（含 milestone）");

        // 两个页面都必须真的引用这个片段，否则「抽出来但没人用」= 页面还是旧的
        assertTrue(Files.readString(PC_LIST, StandardCharsets.UTF_8)
                        .contains("~{fragments/dispatch-pager :: pc}"),
                "PC 列表没引用翻页片段");
        assertTrue(Files.readString(MOBILE_LIST, StandardCharsets.UTF_8)
                        .contains("~{fragments/dispatch-pager :: m}"),
                "手机端列表没引用翻页片段");
    }

    private static int countOccurrences(String haystack, String needle) {
        int n = 0, i = 0;
        while ((i = haystack.indexOf(needle, i)) >= 0) {
            n++;
            i += needle.length();
        }
        return n;
    }
}
