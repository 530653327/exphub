package com.exphub.mcp;

import com.exphub.entity.Doc;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 时效（last_verified_at）的解析、回执与搜索顶部提示。
 *
 * <p>为什么这些必须是纯函数测试：三处逻辑都不碰 DB，但每一处错了都会让「过期判定」失效 ——
 * 而失效是**静默**的（不报错、不异常），只能靠用例钉住。
 */
class VerifiedAtTest {

    // ===== parseVerifiedAtParam：三态语义 =====

    @Test
    @DisplayName("不传/空串 → null（交给服务层填当前时间）")
    void blankMeansNow() {
        assertNull(ExpHubTools.parseVerifiedAtParam(null));
        assertNull(ExpHubTools.parseVerifiedAtParam("   "));
    }

    @Test
    @DisplayName("只给日期 → 当天 00:00；给到分钟/秒也认；带 T 也认")
    void parsesCommonForms() {
        assertEquals(LocalDateTime.of(2026, 10, 20, 0, 0),
                ExpHubTools.parseVerifiedAtParam("2026-10-20"));
        assertEquals(LocalDateTime.of(2026, 10, 20, 15, 30),
                ExpHubTools.parseVerifiedAtParam("2026-10-20 15:30"));
        assertEquals(LocalDateTime.of(2026, 10, 20, 15, 30),
                ExpHubTools.parseVerifiedAtParam("2026-10-20T15:30"));
        assertEquals(LocalDateTime.of(2026, 10, 20, 15, 30, 45),
                ExpHubTools.parseVerifiedAtParam("2026-10-20 15:30:45"));
    }

    @Test
    @DisplayName("格式不认识必须抛 —— 静默忽略会把它当「未传」而刷新成今天")
    void rejectsGarbage() {
        assertThrows(IllegalArgumentException.class, () -> ExpHubTools.parseVerifiedAtParam("昨天"));
        assertThrows(IllegalArgumentException.class, () -> ExpHubTools.parseVerifiedAtParam("2026/10/20"));
        // 形状对但日期非法（13 月 45 日）也要拒，不能静默回落成「未传」
        assertThrows(IllegalArgumentException.class, () -> ExpHubTools.parseVerifiedAtParam("2026-13-45"));
    }

    // ===== verifiedLine：写回执自证 =====

    @Test
    @DisplayName("回执要区分「本次刷新」与「沿用你传的值」，否则传了旧值也无法确认生效")
    void verifiedLineStatesWhetherRefreshed() {
        Doc d = new Doc();
        d.setLastVerifiedAt(LocalDateTime.now().minusDays(3));
        assertTrue(ExpHubTools.verifiedLine(d, false).contains("本次刷新"),
                ExpHubTools.verifiedLine(d, false));
        assertTrue(ExpHubTools.verifiedLine(d, true).contains("沿用你传的值"),
                ExpHubTools.verifiedLine(d, true));
        assertTrue(ExpHubTools.verifiedLine(d, true).contains("3 天前"),
                "要带天数，否则「10-20」到底是多久前得自己算");
    }

    @Test
    @DisplayName("没落库时要明说「未记录」，显式传值却没写进去还要提示报缺陷")
    void verifiedLineHandlesNull() {
        Doc d = new Doc();
        assertTrue(ExpHubTools.verifiedLine(d, false).contains("未记录"));
        assertTrue(ExpHubTools.verifiedLine(d, true).contains("请报缺陷"));
    }

    // ===== staleVerifyLine：搜索顶部那一行 =====

    private static Doc doc(long id, Integer daysAgo) {
        Doc d = new Doc();
        d.setId(id);
        d.setLastVerifiedAt(daysAgo == null ? null : LocalDateTime.now().minusDays(daysAgo));
        return d;
    }

    @Test
    @DisplayName("全部在窗口内 → 空串（每次搜索都印「全部新鲜」只是噪音）")
    void silentWhenAllFresh() {
        LocalDate today = LocalDate.now();
        List<Doc> docs = List.of(doc(1L, 1), doc(2L, 89), doc(3L, 0));
        assertEquals("", ExpHubTools.staleVerifyLine(docs, today));
        assertEquals("", ExpHubTools.staleVerifyLine(List.of(), today));
        assertEquals("", ExpHubTools.staleVerifyLine(null, today));
    }

    @Test
    @DisplayName("90 天整不算超过，91 天才算 —— 边界写死在用例里")
    void boundaryIsStrictlyGreater() {
        LocalDate today = LocalDate.now();
        assertEquals("", ExpHubTools.staleVerifyLine(List.of(doc(1L, 90)), today),
                "「超过 90 天」不含第 90 天");
        assertTrue(ExpHubTools.staleVerifyLine(List.of(doc(1L, 91)), today).contains("1 条超过 90 天"));
    }

    @Test
    @DisplayName("N 条 + 最久那条；未记录验证时间的优先被点名")
    void countsAndNamesOldest() {
        LocalDate today = LocalDate.now();
        List<Doc> docs = List.of(doc(11L, 120), doc(12L, 300), doc(13L, 3));
        String line = ExpHubTools.staleVerifyLine(docs, today);
        assertTrue(line.contains("以下 3 条中 2 条超过 90 天未验证"), line);
        assertTrue(line.contains("#12") && line.contains("300 天"), "要点名最久那条：" + line);

        // null（从未记录）比「很旧」更该被点名：它连起点都没有
        String withNull = ExpHubTools.staleVerifyLine(List.of(doc(21L, 200), doc(22L, null)), today);
        assertTrue(withNull.contains("#22") && withNull.contains("未记录验证时间"), withNull);
        assertTrue(withNull.contains("2 条超过 90 天"), withNull);
    }

    @Test
    @DisplayName("只读：不改顺序、不改字段、不隐藏 —— 这是「提示」不是「过滤」")
    void isReadOnly() {
        LocalDate today = LocalDate.now();
        List<Doc> docs = new ArrayList<>(List.of(doc(31L, 400), doc(32L, 1)));
        List<Long> before = new ArrayList<>();
        for (Doc d : docs) before.add(d.getId());
        ExpHubTools.staleVerifyLine(docs, today);
        List<Long> after = new ArrayList<>();
        for (Doc d : docs) after.add(d.getId());
        assertEquals(before, after, "排序不能被动过");
        assertEquals(2, docs.size(), "不能移除任何一条");
    }
}
