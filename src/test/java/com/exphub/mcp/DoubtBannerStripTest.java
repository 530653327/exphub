package com.exphub.mcp;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * D1 存疑横幅的剥离（2026-10-25）。
 *
 * <p>为什么单独一个测试类：横幅是**读取端的提示**，不是文档内容。
 * 剥不掉就会在「读出来 → 改一点 → 整篇写回」时被固化进正文，
 * 而且那行「本条被标记为待复核」不会随后续清标记而消失 —— 等于给文档刻了个假碑。
 *
 * <p>这一类的坑不在于「多剥了」而在于「漏剥了」，所以三件事都要断言：
 * ① 该剥的全剥干净；② 正文里出现过「标记」「已被取代」的真实内容不能误伤；③ 与旧规则叠加时不留残渣。
 */
class DoubtBannerStripTest {

    /** 真实 export_document 输出的头部（含新加的存疑横幅） */
    private static final String EXPORT_HEADER_WITH_BANNER = """
            📄 文档导出成功！经验 ID:642「Nginx HTTPS 配置」

            内容已完整导出（共 2100 字）。

            **⛔ 已被取代**: 本条（#642）已被 **#643**「Nginx HTTPS 配置（新版）」 取代
            > 结论以新条为准，本条保留仅为留存历史。若判断有误，可调用 mark_superseded / clear_superseded 修正。

            **⚑ 标记**: 待复核 (needs_review)
            **标记说明**: 结论可能已过时
            **标记人**: 阁主 | 2026-10-25T16:14:13
            > ⚠️ 本条被标记为「待复核」，引用前请先核实；如标记已过时，可调用 cancel 或 set_flags 清空。

            ---

            ## 概述

            正文第一段。

            """;

    @Test
    void stripsExportHeaderIncludingBanner() {
        String out = com.exphub.util.RenderArtifactStripper.strip(EXPORT_HEADER_WITH_BANNER);

        // 导出回执头（也是渲染产物，历史上一直没剥）
        assertFalse(out.contains("文档导出成功"), "导出回执头必须剥掉: " + out);
        assertFalse(out.contains("内容已完整导出"), "字数统计行必须剥掉: " + out);
        // 存疑横幅四类行
        assertFalse(out.contains("已被取代"), "取代横幅必须剥掉: " + out);
        assertFalse(out.contains("结论以新条为准"), "取代横幅的说明行必须剥掉: " + out);
        assertFalse(out.contains("⚑ 标记"), "标记横幅必须剥掉: " + out);
        assertFalse(out.contains("标记说明"), "标记说明必须剥掉: " + out);
        assertFalse(out.contains("标记人"), "标记人必须剥掉: " + out);
        assertFalse(out.contains("本条被标记为"), "标记横幅的说明行必须剥掉: " + out);
        // 正文必须原样留下
        assertTrue(out.contains("## 概述"), "正文标题不能丢: " + out);
        assertTrue(out.contains("正文第一段。"), "正文不能丢: " + out);
    }

    @Test
    void keepsBodyThatMentionsFlagsOrSupersedeWords() {
        // 正文里出现「标记」「已被取代」这些词很常见，不能因为含关键字就吃掉
        String content = """
                ## 标记机制设计

                本条经验讲的是**标记**怎么用。历史上有人说「结论已被取代」，那是一句正文引用。

                | 字段 | 含义 |
                |---|---|
                | flags | 标记 |
                """;

        String out = com.exphub.util.RenderArtifactStripper.strip(content);

        assertTrue(out.contains("## 标记机制设计"), "正文标题被误删: " + out);
        assertTrue(out.contains("本条经验讲的是**标记**怎么用"), "正文含「标记」的行被误删: " + out);
        assertTrue(out.contains("历史上有人说「结论已被取代」"), "正文含「已被取代」的行被误删: " + out);
        assertTrue(out.contains("| flags | 标记 |"), "表格里的「标记」被误删: " + out);
    }

    @Test
    void stripsDetailViewCompletely() {
        // detail 的完整渲染视图：头部元信息 + 取代横幅 + 标记横幅 + 历史导读 + 正文
        String detail = """
                # Nginx HTTPS 配置

                **分类**: 运维
                **作者**: 阁主
                **版本**: v4
                **标签**: nginx,https

                **⛔ 已被取代**: 本条（#642）已被 **#643**「Nginx HTTPS 配置（新版）」 取代
                > 结论以新条为准，本条保留仅为留存历史。若判断有误，可调用 mark_superseded / clear_superseded 修正。

                **📌 本条取代了**: #600「Nginx 证书申请」

                **⚑ 标记**: 待复核 (needs_review)
                **标记说明**: 结论可能已过时
                **标记人**: 阁主 | 2026-10-25T16:14:13
                > ⚠️ 本条被标记为「待复核」，引用前请先核实；如标记已过时，可调用 cancel 或 set_flags 清空。

                ---

                > 📚 **历史导读**：本文档经历多次迭代，当前为 v4，共保存 3 个历史版本（v1 ~ v3）。
                > 如需了解本文档的**来龙去脉**，请调用 get_experience_history(id=642)。

                ---

                ## 配置步骤

                ```nginx
                server { listen 443 ssl; }
                ```
                """;

        String out = com.exphub.util.RenderArtifactStripper.strip(detail);

        // 剥干净的判据：只剩标题 + 正文
        assertTrue(out.startsWith("# Nginx HTTPS 配置"), "应以标题开头: " + out);
        assertTrue(out.contains("## 配置步骤"), "正文标题不能丢: " + out);
        assertTrue(out.contains("server { listen 443 ssl; }"), "代码块不能丢: " + out);

        // 所有渲染产物必须一个不剩
        for (String artifact : new String[]{
                "**分类**", "**作者**", "**版本**", "**标签**",
                "已被取代", "结论以新条为准", "本条取代了",
                "⚑ 标记", "标记说明", "标记人", "本条被标记为",
                "历史导读"}) {
            assertFalse(out.contains(artifact), "残留渲染痕迹「" + artifact + "」: " + out);
        }
    }

    @Test
    void onlyBannerWithoutBodyLeavesEmptyish() {
        // 只有横幅没有正文（极端情况）：不能抛异常，也不能把横幅当正文返回
        String onlyBanner = """
                **⛔ 已被取代**: 本条（#1）已被 **#2** 取代
                **⚑ 标记**: 待复核 (needs_review)
                """;

        String out = com.exphub.util.RenderArtifactStripper.strip(onlyBanner);

        assertEquals("", out.trim(), "横幅不应被当正文返回: " + out);
    }

    @Test
    void handlesCrlfBanner() {
        // Windows 换行也要认（AI 复制粘贴经常带 \r）
        String crlf = "**⛔ 已被取代**: 本条（#1）已被 **#2** 取代\r\n\r\n## 正文\r\n";

        String out = com.exphub.util.RenderArtifactStripper.strip(crlf);

        assertFalse(out.contains("已被取代"), "CRLF 下的横幅未剥离: " + out);
        assertTrue(out.contains("## 正文"), "CRLF 下正文不能丢: " + out);
    }
}
