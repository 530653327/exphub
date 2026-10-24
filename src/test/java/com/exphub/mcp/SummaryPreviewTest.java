package com.exphub.mcp;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * E6 工具返回瘦身：列表摘要压到 60 字，需要完整内容请走 get_experience_detail。
 */
class SummaryPreviewTest {

    @Test
    void truncatesLongSummary() {
        String long1 = "这是一段很长很长的摘要".repeat(20);   // 200 字
        String p = ExpHubTools.previewSummary(long1);
        assertEquals(61, p.length(), "60 字 + 省略号");
        assertTrue(p.endsWith("…"));
    }

    @Test
    void keepsShortSummaryAsIs() {
        assertEquals("简短摘要", ExpHubTools.previewSummary("简短摘要"));
    }

    @Test
    void collapsesNewlines() {
        // summary 里带换行会把列表排版撑乱，必须折叠成单空格
        assertEquals("第一行 第二行", ExpHubTools.previewSummary("第一行\n第二行"));
        assertEquals("a b", ExpHubTools.previewSummary("a\n\n  b"));
    }

    @Test
    void handlesNullAndBlank() {
        assertEquals("无", ExpHubTools.previewSummary(null));
        assertEquals("无", ExpHubTools.previewSummary(""));
        assertEquals("无", ExpHubTools.previewSummary("   "));
    }
}
