package com.exphub.mcp;

import com.exphub.entity.Doc;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * M18-b：search_experience 的 limit / offset / brief。
 *
 * <p>背景（忆阁 #578 M18-b）：原实现固定 page=1 size=20，合并项目域召回后约 30 条，
 * 第 26 条起永远拿不到 —— 一个没人说出口的「隐式 25 条上限」。
 * 这里锁的不是「分页能跑」，而是三条容易回退的边界：
 * ① offset 越界不能抛异常（AI 会传离谱值）；② 空页必须能表达出来；③ brief 必须真的一行一条。
 */
class SearchPagingTest {

    @Test
    void firstPage() {
        int[] b = ExpHubTools.searchPageBounds(30, 0, 20);
        assertEquals(0, b[0]);
        assertEquals(20, b[1]);
    }

    @Test
    void secondPageCoversTheTail() {
        // 这正是原来拿不到的部分：第 21-30 条
        int[] b = ExpHubTools.searchPageBounds(30, 20, 20);
        assertEquals(20, b[0]);
        assertEquals(30, b[1], "越界应夹到最后一条，不能返回 40");
    }

    @Test
    void offsetBeyondPoolGivesEmptyPage() {
        int[] b = ExpHubTools.searchPageBounds(30, 100, 20);
        assertEquals(30, b[0]);
        assertEquals(30, b[1], "空页：from == to，不抛异常");
    }

    @Test
    void emptyPool() {
        int[] b = ExpHubTools.searchPageBounds(0, 0, 20);
        assertEquals(0, b[0]);
        assertEquals(0, b[1]);
    }

    @Test
    void negativeInputsAreClamped() {
        int[] b = ExpHubTools.searchPageBounds(30, -5, -1);
        assertEquals(0, b[0], "负 offset 当 0");
        assertEquals(0, b[1], "负 limit 当 0 条，不返回全量");
    }

    @Test
    void briefLineIsSingleLine() {
        Doc doc = new Doc();
        doc.setId(101L);
        doc.setTitle("ExpHub 项目待办清单");
        doc.setTemplateType("todo_list");
        doc.setStatus("ACTIVE");
        doc.setSummary("统一管理所有待办\n第二行应被折叠");

        String line = ExpHubTools.briefLine(1, doc);
        assertEquals(1, line.chars().filter(c -> c == '\n').count(), "只允许结尾一个换行");
        assertTrue(line.contains("【ID:101】"));
        assertTrue(line.contains("✅ 待办事项"), "类型要有可读标签，不能裸露 todo_list");
        assertTrue(line.contains("🟢 正常"));
        assertFalse(line.contains("作者"), "brief 模式不带作者行");
        assertFalse(line.contains("标签"), "brief 模式不带标签行");
    }

    @Test
    void briefLineLongSummaryStillTruncated() {
        Doc doc = new Doc();
        doc.setId(7L);
        doc.setTitle("x");
        doc.setSummary("很长的摘要".repeat(50));
        doc.setStatus("ACTIVE");
        String line = ExpHubTools.briefLine(1, doc);
        assertTrue(line.length() < 140, "brief 单条不该超过一行 140 字符，实际=" + line.length());
        assertTrue(line.contains("…"));
    }
}
