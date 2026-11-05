package com.exphub.mcp;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * #678 §7 的三条可观测前提 + #1272 C6 的 A 组可观测性，钉成守卫。
 *
 * <p>这是**源码级守卫**：只能防「有人漏改/删掉」，防不了行为回归。
 * 线上行为另由 {@code tools/search_regression.py} 与部署后回读验收，两者不互相替代。
 */
class SearchScopeGuardTest {

    private static String source() throws Exception {
        return Files.readString(
                Path.of("src/main/java/com/exphub/mcp/ExpHubTools.java"),
                StandardCharsets.UTF_8);
    }

    /** 前提 1：混排时每条项目文档必须带来源标注，不标 = 不可接受。 */
    @Test
    void mixedScopeMustLabelProjectOrigin() throws Exception {
        String src = source();
        assertTrue(src.contains("🗂 来源:"), "必须输出来源标注行");
        assertTrue(src.contains("doc.getProjectId() != null"),
                "来源行必须只在文档真属于某项目时输出，否则全局文档会被标成项目文档");
    }

    /** 前提 2：默认档排除进度流水，且回执必须说清 all / project 两档口径不同。 */
    @Test
    void receiptMustExplainProgressExclusionDifference() throws Exception {
        String src = source();
        assertTrue(src.contains("不含项目内进度流水"), "scope=all 的回执必须写明排除进度流水");
        assertTrue(src.contains("含进度流水"), "scope=project 的回执必须写明不排除");
    }

    /** 前提 3：global 与 all 共用同一条检索调用，切换只改参数，不重写检索链路。 */
    @Test
    void globalAndAllShareOneSearchCall() throws Exception {
        String src = source();
        assertTrue(src.contains(
                "docService.search(query, templateType, statusFilter, projectId, 1, poolSize, searchMeta, scopeAll)"),
                "两档 scope 必须落到同一个 docService.search 调用，scopeAll 只作参数传入");
    }

    /** #1272 C6：A 组回归脚本要能解析「识别到哪个项目」，否则 6 条全变假 FAIL。 */
    @Test
    void detectionLineIsObservableAndSkippedInBrief() throws Exception {
        String src = source();
        assertTrue(src.contains("🔎 项目识别: "), "缺少可解析的项目识别行");
        assertTrue(src.contains("if (!briefMode && projectId == null)"),
                "项目识别行不能污染 brief 的一条一行输出");
        assertTrue(src.contains("查询词含项目「"),
                "识别行要保留旧格式片段，回归脚本的 detected_project() 才能兼容");
    }

    /** #1272 C1：中文长 query 的子串切分不能被顺手删掉。 */
    @Test
    void cjkLongQuerySubstringPathMustStay() throws Exception {
        String src = source();
        assertTrue(src.contains("longestCjkSubstringHit"), "中文长串的子串切分入口必须在");
        assertTrue(src.contains("hitSnippetWithFallback"), "摘要/标题兜底必须在");
    }
}
