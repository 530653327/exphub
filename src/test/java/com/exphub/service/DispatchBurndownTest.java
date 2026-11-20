package com.exphub.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 燃尽图按天序列的纯函数守卫（W-20261120-000026）。
 *
 * <p>验收标准 1/2 的算法核心：取消单不进分母、当天完成当天不计未完成、
 * 把一张推进到 DONE 后当天点从 2 变 1。不碰 DB，直接喂 OrderSpan。
 */
class DispatchBurndownTest {

    private static DispatchBurndownService.OrderSpan span(
            String no, String created, String finished, boolean cancelled) {
        return new DispatchBurndownService.OrderSpan(no,
                LocalDate.parse(created), finished == null ? null : LocalDate.parse(finished), cancelled);
    }

    private static long at(List<DispatchBurndownService.DayPoint> s, String date) {
        return s.stream().filter(p -> p.date().equals(date)).findFirst()
                .orElseThrow(() -> new AssertionError("序列里没有 " + date)).remaining();
    }

    @Test
    @DisplayName("验收1口径：5 单（2 DONE + 1 CANCELLED + 2 未完成），图上末点未完成=2，取消单全程不占坑")
    void cancelledNeverCountsAndRemainingMatchesSummary() {
        List<DispatchBurndownService.OrderSpan> spans = List.of(
                span("A", "2026-11-18", null, false),            // 一直未完成
                span("B", "2026-11-18", null, false),            // 一直未完成
                span("C", "2026-11-18", "2026-11-20", false),    // 20 号完成
                span("D", "2026-11-18", "2026-11-20", false),    // 20 号完成
                span("E", "2026-11-18", "2026-11-19", true));    // 19 号取消，从不占坑

        var s = DispatchBurndownService.series(
                spans, LocalDate.parse("2026-11-18"), LocalDate.parse("2026-11-20"));
        assertEquals(3, s.size());
        assertEquals(4, at(s, "2026-11-18")); // A B C D，E 取消不计
        assertEquals(4, at(s, "2026-11-19")); // E 取消当天也不让未完成数变化
        assertEquals(2, at(s, "2026-11-20")); // C D 完成，剩 A B —— 与 total=4/done=2/remaining=2 自洽
    }

    @Test
    @DisplayName("验收2口径：同一天把 B 推进到 DONE，当天点从 2 变 1（阶梯线当场下落）")
    void sameDayDoneDropsPointByOne() {
        var before = List.of(
                span("A", "2026-11-18", null, false),
                span("B", "2026-11-18", null, false),
                span("C", "2026-11-18", "2026-11-20", false),
                span("D", "2026-11-18", "2026-11-20", false));
        var after = List.of(
                span("A", "2026-11-18", null, false),
                span("B", "2026-11-18", "2026-11-20", false), // 20 号推进到 DONE
                span("C", "2026-11-18", "2026-11-20", false),
                span("D", "2026-11-18", "2026-11-20", false));

        var sBefore = DispatchBurndownService.series(
                before, LocalDate.parse("2026-11-18"), LocalDate.parse("2026-11-20"));
        var sAfter = DispatchBurndownService.series(
                after, LocalDate.parse("2026-11-18"), LocalDate.parse("2026-11-20"));
        assertEquals(2, at(sBefore, "2026-11-20"));
        assertEquals(1, at(sAfter, "2026-11-20"));
    }

    @Test
    @DisplayName("当天建、当天完成：当天结束时算完成，不计入那天的未完成点")
    void createdAndDoneSameDayDoesNotCount() {
        var s = DispatchBurndownService.series(
                List.of(span("X", "2026-11-18", "2026-11-18", false)),
                LocalDate.parse("2026-11-18"), LocalDate.parse("2026-11-19"));
        assertEquals(0, at(s, "2026-11-18"));
        assertEquals(0, at(s, "2026-11-19"));
    }

    @Test
    @DisplayName("还没建的单（created 在 to 之后）不出现在图里")
    void futureCreatedOrderNotOnChart() {
        var s = DispatchBurndownService.series(
                List.of(span("Y", "2026-11-22", null, false)),
                LocalDate.parse("2026-11-18"), LocalDate.parse("2026-11-20"));
        assertTrue(s.stream().allMatch(p -> p.remaining() == 0));
    }

    @Test
    @DisplayName("只有取消单的里程碑：全程 0，不出现负数也不占坑")
    void onlyCancelledMilestoneIsAllZero() {
        var s = DispatchBurndownService.series(
                List.of(span("Z", "2026-11-18", null, true)),
                LocalDate.parse("2026-11-18"), LocalDate.parse("2026-11-20"));
        assertTrue(s.stream().allMatch(p -> p.remaining() == 0));
    }

    @Test
    @DisplayName("compute 缺里程碑明拒，不静默查全库")
    void computeRejectsBlankMilestone() {
        DispatchBurndownService svc = new DispatchBurndownService(); // 校验在碰 mapper 之前
        var e = assertThrows(DispatchBurndownService.InvalidInput.class,
                () -> svc.compute("   ", null, null));
        assertTrue(e.getMessage().contains("不返回全库数据"));
    }
}
