package com.exphub.mcp;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * E10：章节 summary 占位值识别。
 * 62/82 个章节的 summary 是创建时默认填的「项目 AGENTS.md 第 N 章」，
 * 目录里显示等于废话，读取端要过滤掉。
 */
class PlaceholderSummaryTest {

    @Test
    void detectsDefaultPlaceholder() {
        assertTrue(ExpHubTools.isPlaceholderSummary("项目 AGENTS.md 第 1 章"));
        assertTrue(ExpHubTools.isPlaceholderSummary("项目 AGENTS.md 第 15 章"));
        assertTrue(ExpHubTools.isPlaceholderSummary("项目AGENTS.md第3章"));
    }

    @Test
    void detectsEmptyAndNull() {
        assertTrue(ExpHubTools.isPlaceholderSummary(null));
        assertTrue(ExpHubTools.isPlaceholderSummary(""));
        assertTrue(ExpHubTools.isPlaceholderSummary("   "));
    }

    @Test
    void keepsRealDescriptions() {
        assertFalse(ExpHubTools.isPlaceholderSummary("定位、背景、当前阶段"));
        assertFalse(ExpHubTools.isPlaceholderSummary("Java17 + SpringBoot3.2 等版本硬约束"));
        assertFalse(ExpHubTools.isPlaceholderSummary("高频坑的结论速查；排障前读"));
    }

    @Test
    void keepsSummaryThatMentionsAgentsMdButIsReal() {
        // 提到 AGENTS.md 但不是占位模式的，必须保留
        assertFalse(ExpHubTools.isPlaceholderSummary("改 AGENTS.md 分片前必读"));
        assertFalse(ExpHubTools.isPlaceholderSummary("项目 AGENTS.md 结构与章节约定说明"));
    }

    @Test
    void autoSummaryExtractsFirstMeaningfulLine() {
        // E10 治本：没传 summary 时从正文首段提取（不足 30 字不截断）
        assertEquals("忆阁是 AI 记忆入口，面向个人记忆场景。",
                ExpHubTools.autoSummaryFromContent("# 标题\n\n> 版本：v6\n\n忆阁是 AI 记忆入口，面向个人记忆场景。\n- 其他内容"));
        assertEquals("定位、背景与当前阶段（含版本硬约束）",
                ExpHubTools.autoSummaryFromContent("**定位、背景与当前阶段**（含版本硬约束）"));
    }

    @Test
    void autoSummaryTruncatesTo30Chars() {
        String long1 = "这是一段非常长的正文内容用来测试自动摘要截断逻辑是否正常工作";
        String s = ExpHubTools.autoSummaryFromContent(long1);
        assertTrue(s.length() <= 30, "应截断到 30 字内，实际 " + s.length());
        assertFalse(s.contains("*"), "不应残留 markdown 符号");
    }

    @Test
    void autoSummaryHandlesEmptyContent() {
        assertEquals("", ExpHubTools.autoSummaryFromContent(null));
        assertEquals("", ExpHubTools.autoSummaryFromContent("# 只有标题\n\n---\n"));
    }
}
