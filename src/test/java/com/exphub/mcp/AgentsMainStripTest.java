package com.exphub.mcp;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * MAIN 索引清洗：标题行 / 版本行 / 章节目录一律由代码生成，
 * content 里手写的必须剥掉，否则返回两份目录且版本号脱节（E1）。
 */
class AgentsMainStripTest {

    @Test
    void stripsHandWrittenTitleAndVersion() {
        String dirty = "# 忆阁 MemHaven · AGENTS.md 索引\n\n> 版本: v26 | 项目ID: 6\n\n# 项目定位\n\n这是定位。";
        String clean = ExpHubTools.stripSelfGeneratedParts(dirty);

        assertFalse(clean.contains("AGENTS.md 索引"), "手写标题行应被剥掉");
        assertFalse(clean.contains("版本: v"), "手写版本行应被剥掉");
        assertTrue(clean.contains("这是定位"), "正文必须保留");
    }

    @Test
    void stripsHandWrittenTocFromItsHeadingOnward() {
        String dirty = "## 项目定位\n\n正文内容\n\n---\n\n## 章节目录\n\n"
                + "- 第 1 章 · 五大能力\n- 第 2 章 · 技术栈\n";
        String clean = ExpHubTools.stripSelfGeneratedParts(dirty);

        assertTrue(clean.contains("正文内容"));
        assertFalse(clean.contains("章节目录"), "手写目录段应整段丢弃");
        assertFalse(clean.contains("五大能力"), "手写目录条目应整段丢弃");
        assertFalse(clean.endsWith("---"), "残留分隔线应清掉");
    }

    @Test
    void keepsCleanContentUnchanged() {
        String clean = "# 项目定位\n\nExpHub 是经验共享平台。\n\n## 绝对红线\n\n1. 不写敏感信息";
        assertEquals(clean, ExpHubTools.stripSelfGeneratedParts(clean));
    }

    @Test
    void handlesRepeatedDirtyBlocks() {
        // 脏数据可能叠加了多组标题+版本行（历史反复写入）
        String dirty = "# A · AGENTS.md 索引\n\n> 版本: v6 | 项目ID: 1\n\n"
                + "# A · AGENTS.md 索引\n\n> 版本: v7 | 项目ID: 1\n\n正文。";
        String clean = ExpHubTools.stripSelfGeneratedParts(dirty);

        assertFalse(clean.contains("AGENTS.md 索引"));
        assertFalse(clean.contains("版本: v"));
        assertEquals("正文。", clean);
    }

    @Test
    void keepsBodyMentionsOfAgentsMd() {
        // 正文里提到「AGENTS.md 索引」但不是在标题行里 → 必须保留
        String clean = "# 使用说明\n\n读 AGENTS.md 索引前先看红线。\n";
        assertEquals(clean.trim(), ExpHubTools.stripSelfGeneratedParts(clean));
    }

    @Test
    void handlesNullAndEmpty() {
        assertEquals("", ExpHubTools.stripSelfGeneratedParts(null));
        assertEquals("", ExpHubTools.stripSelfGeneratedParts(""));
    }

    @Test
    void stripsTitlesWithSuffix() {
        // 实测（项目 2）：标题行带「（MAIN）」后缀
        String dirty = "# 回金算 · AGENTS.md 索引（MAIN）\n\n> 版本: v1 | 项目ID: 2\n\n正文内容。";
        String clean = ExpHubTools.stripSelfGeneratedParts(dirty);
        assertFalse(clean.contains("AGENTS.md 索引"));
        assertEquals("正文内容。", clean);
    }

    @Test
    void stripsTocHeadingWithAnnotation() {
        // 实测（项目 3）：目录标题带括号说明
        String dirty = "正文。\n\n## 章节目录（先看摘要+关键词判断相关性，再决定是否读全文）\n\n- 第 1 章 · 概述\n";
        String clean = ExpHubTools.stripSelfGeneratedParts(dirty);
        assertEquals("正文。", clean);
    }

    @Test
    void stripsLeadingH1WithIndexWord() {
        // 实测（项目 3）：首行是「# 拆解宝 · 报废车回收模块 — MAIN 索引」，与系统标题重复
        String dirty = "# 拆解宝 · 报废车回收模块 — MAIN 索引\n\n**定位**：报废车子模块。";
        String clean = ExpHubTools.stripSelfGeneratedParts(dirty);
        assertFalse(clean.contains("MAIN 索引"));
        assertEquals("**定位**：报废车子模块。", clean);
    }

    @Test
    void keepsLeadingH1AsContentHeading() {
        // 「# 项目定位」是正文标题，不含「索引」字样，必须保留
        String dirty = "# 项目定位\n\n正文。";
        assertEquals(dirty, ExpHubTools.stripSelfGeneratedParts(dirty));
    }
}
