package com.exphub.mcp;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * #1220 第②条落地：搜索结果给命中片段，别再让调用方靠摘要猜「要不要读全文」。
 */
class HitSnippetTest {

    @Test
    void returnsNullWhenNothingMatches() {
        assertNull(ExpHubTools.hitSnippet("完全无关的正文", "Nginx 部署"));
        assertNull(ExpHubTools.hitSnippet(null, "部署"));
        assertNull(ExpHubTools.hitSnippet("正文", null));
        assertNull(ExpHubTools.hitSnippet("正文", "   "));
    }

    @Test
    void singleCharTokensDoNotCount() {
        // 「的」「包」这类单字在正文里到处都有，当成命中会给出毫无定位意义的片段
        assertNull(ExpHubTools.hitSnippet("这是一个包含很多东西的正文", "包 的"));
    }

    @Test
    void extractsContextAroundHit() {
        String content = "前".repeat(100) + "部署脚本要改 commit" + "后".repeat(100);
        String s = ExpHubTools.hitSnippet(content, "部署脚本");
        assertNotNull(s);
        assertTrue(s.contains("部署脚本要改"), "必须包含命中词本身");
        assertTrue(s.startsWith("…"), "前面还有内容就该标截断");
        assertTrue(s.endsWith("…"), "后面还有内容就该标截断");
        // 40 字上下文 + 命中词，长度要可控，否则 20 条结果会把回执撑爆
        assertTrue(s.length() < 120, "片段长度必须有界，实际=" + s.length());
    }

    @Test
    void noEllipsisWhenHitAtBothEnds() {
        String s = ExpHubTools.hitSnippet("部署脚本", "部署脚本");
        assertEquals("部署脚本", s);
    }

    @Test
    void picksEarliestHitInContentNotFirstToken() {
        // 查询词第二个 token 在正文里更靠前 —— 应该按正文位置取，让人看到最靠前的上下文
        String content = "开头就提到 Nginx 这事，" + "x".repeat(200) + " 然后是部署脚本";
        String s = ExpHubTools.hitSnippet(content, "部署 Nginx");
        assertNotNull(s);
        assertTrue(s.contains("Nginx"), "应取正文中最靠前的命中，实际=" + s);
    }

    @Test
    void doesNotSplitSurrogatePair() {
        // emoji 是代理对，从中间切开会变成乱码方块
        String content = "😀".repeat(200) + "部署脚本" + "😀".repeat(200);
        String s = ExpHubTools.hitSnippet(content, "部署脚本");
        assertNotNull(s);
        assertFalse(s.contains("\uFFFD"), "不能出现替换字符");
        // 逐字符检查没有落单的代理
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (Character.isHighSurrogate(c)) {
                assertTrue(i + 1 < s.length() && Character.isLowSurrogate(s.charAt(i + 1)),
                        "高位代理后面必须跟低位代理，位置 " + i);
            }
        }
    }

    @Test
    void collapsesNewlinesInSnippet() {
        String content = "第一行\n\n第二行 部署脚本 第三行\n第四行";
        String s = ExpHubTools.hitSnippet(content, "部署脚本");
        assertNotNull(s);
        assertFalse(s.contains("\n"), "片段是单行显示，换行会让列表排版错乱");
    }

    // ===== 候选队列全量分布（#1220 第⑥条）=====

    @Test
    void statusCountsUseFixedOrder() {
        String s = ExpHubTools.formatStatusCounts(Map.of("DISTINCT", 12L, "PENDING", 34L));
        assertTrue(s.startsWith("PENDING 34｜DUPLICATE 0｜SUPERSEDED 0｜DISTINCT 12｜SKIPPED 0"),
                "顺序必须固定，否则每次回执都不一样。实际=" + s);
        assertTrue(s.endsWith("｜合计 46"), "合计要等于各项之和。实际=" + s);
    }

    @Test
    void emptyQueueStillPrintsZeros() {
        String s = ExpHubTools.formatStatusCounts(Map.of());
        assertEquals("PENDING 0｜DUPLICATE 0｜SUPERSEDED 0｜DISTINCT 0｜SKIPPED 0｜合计 0", s);
        assertEquals("PENDING 0｜DUPLICATE 0｜SUPERSEDED 0｜DISTINCT 0｜SKIPPED 0｜合计 0",
                ExpHubTools.formatStatusCounts(null));
    }

    @Test
    void unknownStatusIsReportedNotSwallowed() {
        String s = ExpHubTools.formatStatusCounts(Map.of("PENDING", 1L, "WHATEVER", 2L));
        assertTrue(s.contains("WHATEVER 2"), "未知状态要报出来，静默吞掉等于把异常藏起来。实际=" + s);
        assertTrue(s.endsWith("｜合计 3"), "未知状态也要计入合计。实际=" + s);
    }
}
