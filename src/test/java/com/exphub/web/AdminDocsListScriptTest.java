package com.exphub.web;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 后台经验列表的前端脚本守卫（2026-11-09 加）。
 *
 * <p><b>事故</b>：越哥在「经验管理 → 问题文档」里点删除按钮，页面毫无反应 ——
 * 没有请求、没有确认框、没有报错提示。
 *
 * <p><b>根因</b>：{@code docs/list.html} 的脚本里
 * {@code document.getElementById('searchInput').addEventListener(...)} 是<b>无条件</b>执行的，
 * 而搜索框所在的筛选工具栏在问题文档视图下被 {@code th:if} 整块藏掉了。
 * 于是 {@code getElementById} 返回 null → 直接抛 TypeError →
 * <b>后面所有语句都不再执行</b>，包括三条「删除 / 恢复 / 分享」的事件委托。
 * 表现就是「点了没反应」，而不是「报错」。
 *
 * <p>这类缺陷测试最容易漏：模板能渲染（{@link PageTemplateRenderTest} 全绿）、
 * 接口能删（后端测试全绿）、构建能过 —— 只有浏览器里点一下才看得见。
 * 所以这里做两条源级守卫：**不许对可能不存在的元素直接绑事件**，事件委托必须挂在 {@code document} 上。
 */
class AdminDocsListScriptTest {

    private static final Path TEMPLATE = Path.of("src/main/resources/templates/docs/list.html");

    /** 直接绑在某个具体元素上的 addEventListener —— 元素被 th:if 藏掉就是一次 TypeError。 */
    private static final Pattern DIRECT_BIND =
            Pattern.compile("getElementById\\(\\s*'([^']+)'\\s*\\)\\s*\\.addEventListener");

    private static String html() throws IOException {
        return Files.readString(TEMPLATE, StandardCharsets.UTF_8);
    }

    @Test
    @DisplayName("脚本不许对可能不存在的元素直接 addEventListener（问题文档视图下会抛异常，把后续绑定全废掉）")
    void scriptMustNotBindDirectlyOnOptionalElements() throws IOException {
        var m = DIRECT_BIND.matcher(html());
        if (m.find()) {
            throw new AssertionError("docs/list.html 里对 id=" + m.group(1) + " 直接 addEventListener。"
                    + "该元素在问题文档视图（/docs?problem=...）下并不渲染，getElementById 返回 null 会抛 TypeError，"
                    + "并让后面的删除/恢复/分享绑定全部不执行 —— 用户看到的就是「点删除没反应」。"
                    + "正确做法：先判空，或把事件委托挂到 document 上。");
        }
    }

    @Test
    @DisplayName("删除/恢复/分享的事件委托必须挂在 document 上，且搜索框绑定必须先判空")
    void clickDelegationMustSurviveMissingContainers() throws IOException {
        String html = html();

        int delegate = html.indexOf("document.addEventListener('click'");
        assertTrue(delegate > 0, "没有在 document 上注册点击委托 —— 容器元素缺失时按钮会彻底失效");

        // 三个按钮的判定必须都在那条委托里（出现位置在委托之后）
        for (String cls : new String[]{".delete-btn", ".restore-btn", ".share-btn"}) {
            int at = html.indexOf("'" + cls + "'");
            assertTrue(at > delegate, cls + " 的处理不在 document 的点击委托里，问题文档视图下会失效");
        }

        assertTrue(html.contains("if (searchInput)"),
                "搜索框的 keyup 绑定没有判空 —— 筛选工具栏在问题文档视图下不存在");
        assertFalse(html.contains("document.getElementById('docList').addEventListener"),
                "事件委托还挂在 #docList 上：candidates 视图没有这个容器");
    }

    @Test
    @DisplayName("前端拼 POST query 时必须带上 problem，否则后端收了也白收")
    void docsContextQueryMustCarryProblem() throws IOException {
        String html = html();
        assertTrue(html.contains("var currentProblem = /*[[${problem}]]*/"),
                "脚本没读出当前 problem，删除后无法回到原分类");
        assertTrue(html.contains("if (currentProblem) qs.push('problem='"),
                "docsContextQuery 没把 problem 拼进 POST query");
    }
}
