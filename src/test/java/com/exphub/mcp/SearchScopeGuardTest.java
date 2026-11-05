package com.exphub.mcp;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
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

    /** #1272 C8：污染源标注必须同时覆盖完整模式、brief 模式和页首汇总，且不许偷偷改排序。 */
    @Test
    void pollutionMarksVisibleInBothModes() throws Exception {
        String src = source();
        assertTrue(src.contains("⚠️ 回归评测污染源"),
                "完整模式必须逐条标注「本条为污染源」");
        assertTrue(src.contains("⚠️污染源"),
                "brief 模式也要带标记（仍保持一条一行）");
        assertTrue(src.contains("条回归评测污染源（下面已逐条标注）"),
                "页首要有汇总行，别让人翻到中间才发现前几条不能用");
        assertTrue(src.contains("REGRESSION_POLLUTION_IDS.contains(doc.getId())"),
                "标注按 ID 判定，不能改成按标题猜");
        assertTrue(src.contains("不排除、不改排序"),
                "线上不许替调用方隐藏文档（这是口径注释，防止后人顺手改成 filter）");
    }

    /** #1272 C8：服务端集合与离线回归脚本的 SELF_POLLUTION_IDS 必须同源，漏一个就会两边口径打架。 */
    @Test
    void pollutionIdSetsAreIdentical() throws Exception {
        Matcher java = Pattern.compile("REGRESSION_POLLUTION_IDS\\s*=\\s*Set\\.of\\(([^)]*)\\)")
                .matcher(source());
        assertTrue(java.find(), "取不到服务端污染源集合，守卫失效");
        Set<Long> javaIds = idsFrom(java.group(1).replace("L", ""));

        String py = Files.readString(Path.of("tools/search_regression.py"), StandardCharsets.UTF_8);
        Matcher script = Pattern.compile("SELF_POLLUTION_IDS\\s*=\\s*\\{([^}]*)}").matcher(py);
        assertTrue(script.find(), "取不到离线脚本 SELF_POLLUTION_IDS，守卫失效");
        Set<Long> pyIds = idsFrom(script.group(1));

        assertEquals(javaIds, pyIds,
                "服务端标注集合与离线排除集合必须一致；#1274 曾因漏登记进了线上 Top2");
        assertTrue(javaIds.contains(1274L), "施工回执②续册 #1274 必须在污染源集合里");
    }

    private static Set<Long> idsFrom(String body) {
        Set<Long> out = new HashSet<>();
        for (String part : body.split(",")) {
            String t = part.trim();
            if (t.matches("\\d+")) out.add(Long.parseLong(t));
        }
        return out;
    }
}
