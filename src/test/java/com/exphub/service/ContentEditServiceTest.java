package com.exphub.service;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A1：追加 / 局部替换的底层保证（#631 阁主清单）。
 *
 * <p>要锁的不是「能跑」，而是两条出事就会静默丢数据的性质：
 * ① append **一个字都不能删原文**（这正是原来整篇写回的病）；
 * ② patch 依赖「find 唯一」，计数错了就会改错地方。
 */
class ContentEditServiceTest {

    // ===== mergeAppend：追加不能丢原文 =====

    @Test
    void appendKeepsOriginalIntact() {
        String base = "# 标题\n\n第一段\n\n## 第二节\n\n正文内容";
        String out = DocService.mergeAppend(base, "### 2026-10-25 补充\n\n新结论");
        assertTrue(out.startsWith(base), "原文必须完整保留在开头，一个字都不能少");
        assertTrue(out.contains("### 2026-10-25 补充"));
        assertTrue(out.endsWith("新结论"));
    }

    @Test
    void appendCollapsesTrailingBlankLines() {
        // 原文尾部带一堆空行时，不该出现三四个连续换行
        String out = DocService.mergeAppend("正文\n\n\n\n", "追加");
        assertEquals("正文\n\n追加", out);
    }

    @Test
    void appendToEmptyDoc() {
        assertEquals("追加内容", DocService.mergeAppend(null, "追加内容"));
        assertEquals("追加内容", DocService.mergeAppend("", "追加内容"));
        assertEquals("追加内容", DocService.mergeAppend("   ", "追加内容"));
    }

    @Test
    void appendHandlesNullAddition() {
        // addition 为 null 时不能抛异常（工具层会拦，但底层不该炸）
        assertEquals("正文", DocService.mergeAppend("正文", null));
    }

    @Test
    void appendDoesNotDropInnerContentOnLongDoc() {
        // 模拟长文档：拼接后长度必须严格 = 原文去尾空白 + 2 + 追加内容
        String base = "一".repeat(5000) + "\n";
        String out = DocService.mergeAppend(base, "尾巴");
        assertEquals(5000 + 2 + 2, out.length());
        assertTrue(out.startsWith("一".repeat(5000)));
    }

    // ===== countOccurrences：patch 唯一性校验的地基 =====

    @Test
    void countsZeroOneMany() {
        assertEquals(0, DocService.countOccurrences("abc", "xyz"));
        assertEquals(1, DocService.countOccurrences("abc def", "def"));
        assertEquals(4, DocService.countOccurrences("a-b-c-d-e", "-"));
    }

    @Test
    void countsNonOverlappingOnly() {
        // "aaaa" 里找 "aa"：indexOf 推进 len，得 2（不重叠）。
        // 这个口径够用且不会死循环 —— 关键是不能返回 3 让 patch 误判成多处
        assertEquals(2, DocService.countOccurrences("aaaa", "aa"));
    }

    @Test
    void handlesNullAndEmpty() {
        assertEquals(0, DocService.countOccurrences(null, "x"));
        assertEquals(0, DocService.countOccurrences("abc", null));
        assertEquals(0, DocService.countOccurrences("abc", ""));
    }

    @Test
    void countsMultilineAnchor() {
        String body = "第一行\n目标片段\n第三行";
        assertEquals(1, DocService.countOccurrences(body, "第一行\n目标片段"));
    }
}
