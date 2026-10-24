package com.exphub.mcp;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 章节标题清洗：标题行与加载策略行由代码生成，content 手写的要剥掉。
 * 与 MAIN 的区别：只处理开头，正文里正常引用「第 3 章」必须保留。
 */
class ChapterHeaderStripTest {

    @Test
    void stripsLeadingTitleAndStrategy() {
        // 实测（项目 1 第 15 章）：返回里出现过 v3/v2 两份标题
        String dirty = "# 第 15 章 · 高频踩坑速查\n\n> 加载策略: ALWAYS | 版本: v2\n\n## 1. MCP 会话失效\n内容。";
        String clean = ExpHubTools.stripChapterHeader(dirty);

        assertFalse(clean.contains("第 15 章 · 高频踩坑速查"), "手写标题应剥掉");
        assertFalse(clean.contains("加载策略"), "手写加载策略行应剥掉");
        assertTrue(clean.startsWith("## 1. MCP 会话失效"), "正文应完整保留");
    }

    @Test
    void stripsTitleWithSuffix() {
        String dirty = "# 第 3 章 · 目录结构（补充版）\n\n> 加载策略: ON_DEMAND | 版本: v4\n\n正文。";
        assertEquals("正文。", ExpHubTools.stripChapterHeader(dirty));
    }

    @Test
    void keepsChapterReferencesInBody() {
        // 正文里引用「第 3 章」不是标题行，必须保留
        String clean = "## 使用说明\n\n细节见第 3 章「目录结构」。\n\n> 注意：第 15 章是必读章节。";
        assertEquals(clean, ExpHubTools.stripChapterHeader(clean));
    }

    @Test
    void doesNotStripMiddleHeadings() {
        // 正文中间出现的「## 第 2 章 xxx」是小标题引用，不在开头，保留
        String content = "## 前置阅读\n\n## 第 2 章 技术栈的补充说明\n\n内容。";
        assertEquals(content, ExpHubTools.stripChapterHeader(content));
    }

    @Test
    void handlesCleanContent() {
        String clean = "## 1. 第一条踩坑\n\n细节。";
        assertEquals(clean, ExpHubTools.stripChapterHeader(clean));
    }

    @Test
    void handlesNullAndEmpty() {
        assertEquals("", ExpHubTools.stripChapterHeader(null));
        assertEquals("", ExpHubTools.stripChapterHeader(""));
    }

    @Test
    void stripsBareH1AndChineseColonVersion() {
        // 2026-10-24 22:30 忆阁复测发现的漏网形态：裸 H1 + 中文冒号版本行
        String dirty = "# 产品定位与核心决策\n\n> 版本：v6（2026-10-24 从“单用户只读”扩展为“多用户读写 + 图片”）\n\n## 定位\n- 忆阁是记忆入口";
        String clean = ExpHubTools.stripChapterHeader(dirty);
        assertFalse(clean.contains("产品定位与核心决策"), "裸 H1 应剥掉");
        assertFalse(clean.contains("版本：v6"), "中文冒号版本行应剥掉");
        assertTrue(clean.startsWith("## 定位"), "正文必须保留");
    }

    @Test
    void keepsContentQuoteBlockAtStart() {
        // 正文以普通引用块开头（不含版本/加载策略字样）应保留
        String clean = "> 这是一段普通引用\n\n正文。";
        assertEquals(clean, ExpHubTools.stripChapterHeader(clean));
    }
}
