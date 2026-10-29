package com.exphub.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 按节整节替换（P2-6，越哥 2026-10-29）。
 *
 * <p>为什么值得单独测：这个工具的价值全在「边界算得对」和「错了能说清楚」——
 * 定位错会静默改坏别人的文档，命中多处却不拒绝就是把选择权交给了运气。
 */
class SectionSplicerTest {

    private static final String DOC = String.join("\n",
            "# 滚动日志",
            "",
            "## 第一节",
            "A 内容",
            "",
            "## 第二节",
            "B 内容",
            "第三行",
            "",
            "## 第三节",
            "C 内容");

    @Test
    @DisplayName("正常替换中间一节：前后两节一字不动")
    void replacesMiddleSection() {
        SectionSplicer.Result r = SectionSplicer.splice(DOC, "第二节", "新的 B 内容");
        assertTrue(r.ok(), r.message());
        String expected = String.join("\n",
                "# 滚动日志",
                "",
                "## 第一节",
                "A 内容",
                "",
                "## 第二节",
                "",
                "新的 B 内容",
                "",
                "## 第三节",
                "C 内容");
        assertEquals(expected, r.newContent());
    }

    @Test
    @DisplayName("回执给出字符区间，且切片就是被替换掉的那一整节（否则人不知道截到哪）")
    void reportsExactRange() {
        SectionSplicer.Result r = SectionSplicer.splice(DOC, "第二节", "X");
        assertTrue(r.ok(), r.message());
        String replaced = DOC.substring(r.start(), r.end());
        assertTrue(replaced.startsWith("## 第二节"), "区间应从标题行开始：" + replaced);
        assertTrue(replaced.contains("B 内容"), "区间应包含旧内容：" + replaced);
        assertTrue(replaced.contains("第三行"), "区间应包含本节所有行：" + replaced);
        assertFalse(replaced.contains("## 第三节"), "区间不能吃掉下一节：" + replaced);
        // 前 40 字是「本节之前」的窗口，用 ⏎ 表示换行、… 表示还有更多
        assertTrue(r.before().endsWith("⏎⏎…"), "紧邻本节前应是空行并带省略号：" + r.before());
        assertTrue(r.before().contains("A 内容"), "前 40 字应包含上一节内容：" + r.before());
        assertTrue(r.after().contains("## 第三节"), "后 40 字上下文应看到下一节：" + r.after());
    }

    @Test
    @DisplayName("最后一节没有下一个标题 → 替换到正文末尾")
    void replacesLastSection() {
        SectionSplicer.Result r = SectionSplicer.splice(DOC, "第三节", "收尾");
        assertTrue(r.ok(), r.message());
        assertTrue(r.newContent().endsWith("## 第三节\n\n收尾\n\n"), "实际：" + r.newContent());
        assertTrue(r.newContent().contains("## 第二节"));
    }

    @Test
    @DisplayName("三级标题属于本节内容，不构成边界")
    void level3DoesNotEndSection() {
        String doc = "## 甲\n\n### 甲-1\ndetail\n\n## 乙\nB";
        SectionSplicer.Result r = SectionSplicer.splice(doc, "甲", "新甲");
        assertTrue(r.ok(), r.message());
        assertFalse(r.newContent().contains("### 甲-1"), "三级标题内容应随本节一起被替换");
        assertTrue(r.newContent().contains("## 乙"), "下一节必须还在");
    }

    @Test
    @DisplayName("一级标题也构成边界（# 之后不该被吞进来）")
    void level1AlsoEndsSection() {
        String doc = "## 甲\nA\n\n# 大标题\nX";
        SectionSplicer.Result r = SectionSplicer.splice(doc, "甲", "新甲");
        assertTrue(r.ok(), r.message());
        assertTrue(r.newContent().contains("# 大标题\nX"), "实际：" + r.newContent());
    }

    @Test
    @DisplayName("命中 0 个：拒绝，并列出正文现有的二级标题")
    void rejectsWhenNotFound() {
        SectionSplicer.Result r = SectionSplicer.splice(DOC, "第五节", "x");
        assertFalse(r.ok());
        assertTrue(r.message().contains("没找到"), r.message());
        assertTrue(r.headings().toString().contains("第二节"), "应列出可用标题：" + r.headings());
    }

    @Test
    @DisplayName("命中 ≥2 个：拒绝，并报出每一处行号（并列候选必须由人挑）")
    void rejectsWhenAmbiguous() {
        String doc = "## 重复\nA\n\n## 重复\nB";
        SectionSplicer.Result r = SectionSplicer.splice(doc, "重复", "x");
        assertFalse(r.ok());
        assertEquals(2, r.candidates().size(), "应给出两处候选：" + r.candidates());
        assertTrue(r.candidates().get(0).contains("第 1 行"), r.candidates().toString());
        assertTrue(r.candidates().get(1).contains("第 4 行"), r.candidates().toString());
    }

    @Test
    @DisplayName("newContent 为空：拒绝（清空整节请走 patch，别静默删）")
    void rejectsEmptyBody() {
        assertFalse(SectionSplicer.splice(DOC, "第二节", "").ok());
        assertFalse(SectionSplicer.splice(DOC, "第二节", "   \n  ").ok());
    }

    @Test
    @DisplayName("标题写法容错：带不带 ## 都认")
    void headingFormTolerant() {
        assertTrue(SectionSplicer.splice(DOC, "## 第二节", "x").ok());
        assertTrue(SectionSplicer.splice(DOC, "  第二节  ", "x").ok());
        assertTrue(SectionSplicer.splice(DOC, "##第二节", "x").ok());
    }
}
