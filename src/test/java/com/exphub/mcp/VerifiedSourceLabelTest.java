package com.exphub.mcp;

import com.exphub.entity.Doc;
import com.exphub.service.DocService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 回执里的「验证时间来源」（2026-10-29 阁主第 ① 条）。
 *
 * <p>时间只有一个，来源才是判据：{@code backfill} 是 {@code updated_at} 推出来的下界，
 * 不是「有人确认过结论仍然成立」。回执不标这一态，读者会把 645 篇回填值当成刚核过的证据 ——
 * 而它错得毫无痕迹（不报错、不异常、时间看起来完全正常）。
 */
class VerifiedSourceLabelTest {

    @Test
    @DisplayName("回填值必须写成「非验证证据」，不能只写「回填」")
    void backfillIsExplicitlyNotEvidence() {
        String label = ExpHubTools.verifiedSourceLabel(DocService.VERIFIED_BACKFILL);
        assertTrue(label.contains("非验证证据"), "回填标签必须自带否定语义，实际：" + label);
        assertTrue(label.contains("回填"), label);
    }

    @Test
    @DisplayName("auto 与 manual 必须能分开：前者是写入顺带记的，后者是调用方声明")
    void autoAndManualDiffer() {
        String auto = ExpHubTools.verifiedSourceLabel(DocService.VERIFIED_AUTO);
        String manual = ExpHubTools.verifiedSourceLabel(DocService.VERIFIED_MANUAL);
        assertFalse(auto.equals(manual), "两种来源印成同一句话，等于没区分");
        assertTrue(manual.contains("人工"), manual);
        assertTrue(auto.contains("自动"), auto);
    }

    @Test
    @DisplayName("来源为 null / 陌生值：明说「来源未记录」，陌生值要把原值带出来")
    void unknownSourceIsHonest() {
        assertTrue(ExpHubTools.verifiedSourceLabel(null).contains("来源未记录"));
        assertTrue(ExpHubTools.verifiedSourceLabel("  ").contains("来源未记录"));
        String odd = ExpHubTools.verifiedSourceLabel("legacy-import");
        assertTrue(odd.contains("来源未记录") && odd.contains("legacy-import"),
                "陌生值不许静默套成三态之一，实际：" + odd);
    }

    @Test
    @DisplayName("回填值的验证时间行不许写「本次刷新」——它不是一次写操作产生的")
    void backfillLineDoesNotClaimRefresh() {
        Doc d = new Doc();
        d.setLastVerifiedAt(LocalDateTime.now().minusDays(10));
        d.setLastVerifiedSource(DocService.VERIFIED_BACKFILL);

        String line = ExpHubTools.verifiedLine(d, false);
        assertTrue(line.contains("历史回填"), line);
        assertTrue(line.contains("非验证证据"), line);
        assertFalse(line.contains("本次刷新"), "回填值不是本次刷新的结果：" + line);
    }

    @Test
    @DisplayName("正常写入仍区分「本次刷新」与「沿用你传的值」，且带来源")
    void normalLinesKeepRefreshSemantics() {
        Doc d = new Doc();
        d.setLastVerifiedAt(LocalDateTime.now().minusDays(3));
        d.setLastVerifiedSource(DocService.VERIFIED_AUTO);
        assertTrue(ExpHubTools.verifiedLine(d, false).contains("本次刷新"),
                ExpHubTools.verifiedLine(d, false));

        d.setLastVerifiedSource(DocService.VERIFIED_MANUAL);
        String kept = ExpHubTools.verifiedLine(d, true);
        assertTrue(kept.contains("沿用你传的值"), kept);
        assertTrue(kept.contains("人工声明"), kept);
    }

    @Test
    @DisplayName("搜索顶部：回填条数要单独点出来，且不许说成「最近核过」")
    void staleLineSeparatesBackfilledEntries() {
        LocalDate today = LocalDate.now();
        List<Doc> docs = List.of(
                stale(1L, 200, DocService.VERIFIED_BACKFILL),
                stale(2L, 120, DocService.VERIFIED_AUTO),
                stale(3L, 2, DocService.VERIFIED_AUTO));

        String line = ExpHubTools.staleVerifyLine(docs, today);
        assertTrue(line.contains("2 条超过 90 天"), line);
        assertTrue(line.contains("其中 1 条"), "回填条数必须单独列出：" + line);
        assertTrue(line.contains("回填值") && line.contains("不是验证证据"), line);
    }

    @Test
    @DisplayName("没有回填条目时不许凭空提一句回填（否则每屏都是噪音）")
    void staleLineSilentWhenNothingBackfilled() {
        LocalDate today = LocalDate.now();
        String line = ExpHubTools.staleVerifyLine(
                List.of(stale(1L, 200, DocService.VERIFIED_AUTO)), today);
        assertTrue(line.contains("1 条超过 90 天"), line);
        assertFalse(line.contains("回填"), line);
    }

    @Test
    @DisplayName("全部新鲜 → 空串；回填也不该让「新鲜」变成提示（是否过期只看时间）")
    void freshEntriesStaySilent() {
        LocalDate today = LocalDate.now();
        assertEquals("", ExpHubTools.staleVerifyLine(
                List.of(stale(1L, 1, DocService.VERIFIED_BACKFILL)), today));
    }

    private static Doc stale(long id, int daysAgo, String source) {
        Doc d = new Doc();
        d.setId(id);
        d.setLastVerifiedAt(LocalDateTime.now().minusDays(daysAgo));
        d.setLastVerifiedSource(source);
        return d;
    }
}
