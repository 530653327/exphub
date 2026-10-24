package com.exphub.mcp;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * E11：剥离「读取端渲染痕迹」。
 *
 * 真实事故：get_experience_detail 返回的是渲染视图（历史导读 + 正文 + 关联经验 + 结尾标记），
 * AI 维护文档时「读出来→写回去」，把这些注入内容固化进库；每写一次叠一层。
 * 实测 ID=101 的 content 里积了 v5~v9 五段历史导读 + 两处 shell 报错。
 */
class RenderArtifactStripTest {

    private static final String HEADER = """
            # ExpHub 项目待办清单（统一管理）

            **分类**: 项目管理
            **作者**: 白虎
            **版本**: v4（2026-10-23）
            **标签**: ExpHub,待办事项

            ---

            """;

    private static final String TOC = """
            > 📚 **历史导读**：本文档经历多次迭代，当前为 v8，共保存 7 个历史版本（v1 ~ v7）。
            > 如需了解本文档的**来龙去脉、演进过程或历史解决方案**，请调用
            > `get_experience_history(id=101)` 查看历史版本列表。

            ---

            """;

    @Test
    void stripsMultipleTocBlocks() {
        // 实测 101 叠了 5 段，必须全部剥掉
        String dirty = TOC + TOC + TOC + HEADER + "## 正文\n\n内容。";
        String clean = ExpHubTools.stripRenderArtifacts(dirty);

        assertFalse(clean.contains("历史导读"), "所有导读块都要剥掉");
        assertFalse(clean.contains("**版本**"), "头部元信息要剥掉");
        assertTrue(clean.contains("## 正文"), "正文要保留");
        assertTrue(clean.contains("内容。"), "正文内容要保留");
        assertTrue(clean.contains("# ExpHub 项目待办清单"), "主标题保留（是正文结构）");
    }

    @Test
    void stripsRelatedSectionToEnd() {
        // 「## 📎 关联经验」是读取时按 relatedIds 实时渲染的，不应入库
        String dirty = "## 正文\n\n内容。\n\n---\n\n## 📎 关联经验\n\n- **ID:585** 某文档\n  摘要: xxx\n";
        String clean = ExpHubTools.stripRenderArtifacts(dirty);

        assertTrue(clean.contains("内容。"));
        assertFalse(clean.contains("关联经验"));
        assertFalse(clean.contains("ID:585"));
    }

    @Test
    void stripsRelatedSectionInMiddleKeepsFollowingContent() {
        // 实测 101 里有 4 段关联经验夹在正文中间，
        // 每段后面还有正经章节 —— 只能跳段，不能丢后面全部
        String dirty = """
                ## 第一段正文

                内容一。

                ---

                ## 📎 关联经验

                - **ID:585** 某文档
                  摘要: xxx

                ---

                ## 第二段正文

                内容二。
                """;
        String clean = ExpHubTools.stripRenderArtifacts(dirty);

        assertTrue(clean.contains("内容一"), "前文保留");
        assertTrue(clean.contains("内容二"), "关联经验之后的正文也必须保留");
        assertTrue(clean.contains("第二段正文"));
        assertFalse(clean.contains("关联经验"));
        assertFalse(clean.contains("ID:585"));
    }

    @Test
    void stripsDocEndMarker() {
        String dirty = "## 正文\n\n内容。\n\n---\n📌 文档结尾";
        String clean = ExpHubTools.stripRenderArtifacts(dirty);
        assertFalse(clean.contains("文档结尾"));
        assertFalse(clean.endsWith("---"), "残留分隔线要清掉");
    }

    @Test
    void stripsTerminalResidue() {
        // AI 复制脚本输出时误带的终端报错
        String dirty = "## 正文\n\n内容。\n\n/tmp/probe/mcpcall.sh: line 50: 56987 Terminated: 15          curl -sN --noproxy '*' ...\n后续内容。";
        String clean = ExpHubTools.stripRenderArtifacts(dirty);

        assertFalse(clean.contains("Terminated"));
        assertFalse(clean.contains("mcpcall.sh"));
        assertTrue(clean.contains("后续内容"), "报错前后的正文都要保留");
    }

    @Test
    void keepsRealContentUnchanged() {
        // 干净正文不能被误伤——尤其正文里正常出现的「>」引用和「---」分隔线
        String real = "## 背景\n\n> 这是一段正常引用，不是历史导读。\n\n---\n\n## 方案\n\n内容。";
        assertEquals(real, ExpHubTools.stripRenderArtifacts(real));
    }

    @Test
    void keepsBodyMentioningRelatedExperience() {
        // 正文里提到「关联经验」但不是在标题位置，必须保留
        String real = "## 说明\n\n这些内容详见关联经验列表，见 ID:585。";
        assertEquals(real, ExpHubTools.stripRenderArtifacts(real));
    }

    @Test
    void collapsesDuplicatedLeadingH1FromRenderView() {
        // 渲染视图结构：# 标题 + 元信息 + 历史导读 + 正文；
        // 而文档正文自己也可能以同名 H1 开头。导读与元信息剥掉后，两行标题贴在一起。
        // （2026-10-24 23:4x 拿 ID=101 做写入端幂等实验实测出来的）
        String view = HEADER + TOC + "# ExpHub 项目待办清单（统一管理）\n\n## 说明\n\n内容。";
        String clean = ExpHubTools.stripRenderArtifacts(view);

        assertEquals(1, countOf(clean, "# ExpHub 项目待办清单（统一管理）"),
                "标题只能留一行，实际：\n" + clean);
        assertTrue(clean.contains("## 说明"), "正文要保留");
        assertFalse(clean.contains("历史导读"));
        assertFalse(clean.contains("**版本**"));
    }

    @Test
    void collapsesEvenWhenThreeCopiesStacked() {
        // 已经脏过一次的内容再被喂回来，会有三份标题，不能只删一份
        String dirty = "# 标题\n\n# 标题\n\n# 标题\n\n## 正文\n\n内容。";
        String clean = ExpHubTools.stripRenderArtifacts(dirty);
        assertEquals(1, countOf(clean, "# 标题"), "三份也要折成一份，实际：\n" + clean);
    }

    @Test
    void keepsTwoDifferentHeadings() {
        // 开头两个**不同**的 H1 是正常文档结构，不能当成重复砍掉
        String real = "# 主标题\n\n# 另一个标题\n\n## 正文\n\n内容。";
        assertEquals(real, ExpHubTools.stripRenderArtifacts(real));
    }

    @Test
    void doesNotScanWholeDocumentForDuplicateH1() {
        // 只处理开头；正文中间的 H1 是章节结构，动不得
        String real = "# 标题\n\n## 正文一\n\n# 标题\n\n内容。";
        assertEquals(real, ExpHubTools.stripRenderArtifacts(real));
    }

    private static int countOf(String s, String sub) {
        int n = 0;
        int i = 0;
        while ((i = s.indexOf(sub, i)) >= 0) {
            n++;
            i += sub.length();
        }
        return n;
    }

    @Test
    void handlesNullAndEmpty() {
        assertEquals("", ExpHubTools.stripRenderArtifacts(null));
        assertEquals("", ExpHubTools.stripRenderArtifacts(""));
    }
}
