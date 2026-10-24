package com.exphub.mcp;

import org.junit.jupiter.api.Test;

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
}
