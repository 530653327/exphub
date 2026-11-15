package com.exphub.controller;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 后台「物理删除单个历史版本」这条链路的回归守卫（2026-11-16）。
 *
 * <p>为什么要有它：这是<b>不可逆</b>的删除口，三个部件分布在三个文件里 ——
 * 服务端校验、路由、页面按钮。任何一处被后来的人删掉或改成 GET，功能就悄悄失效或变成
 * 「点一下就删」（浏览器预取/爬虫都能触发）。这类静默失效在 16 条源码守卫里已经拦下过两次。
 *
 * <p>只挡「结构被拆掉」，不挡实现细节 —— 具体行为由 {@code DocVersionDeleteTest} 跑真代码验。
 * 断言前先剥 Java 注释（AGENTS 第 10 章第 10 条：注释会把 contains 喂饱）。
 */
class DocVersionDeletePageGuardTest {

    private static String java(String p) throws IOException {
        String raw = Files.readString(Path.of(p), StandardCharsets.UTF_8);
        return raw.replaceAll("(?s)/\\*.*?\\*/", "").replaceAll("(?m)^\\s*//.*$", "");
    }

    private static String template(String p) throws IOException {
        return Files.readString(Path.of(p), StandardCharsets.UTF_8);
    }

    @Test
    @DisplayName("服务端：DocService 必须有 deleteVersion(docId, versionId)，且它才是真删的地方")
    void serviceHasDeleteVersion() throws IOException {
        String src = java("src/main/java/com/exphub/service/DocService.java");
        assertTrue(src.contains("public DocVersion deleteVersion(Long docId, Long versionId)"),
                "deleteVersion 签名变了或被人删了：后台删除版本的唯一落点");
        assertTrue(src.contains("versionMapper.deleteById(versionId)"),
                "没看到 deleteById —— 换成批量/条件删也要在这里看得见，否则是空删");
    }

    @Test
    @DisplayName("路由：必须是 POST，不接受 GET（GET 会被预取/爬虫触发不可逆删除）")
    void routeIsPostOnly() throws IOException {
        String src = java("src/main/java/com/exphub/controller/PageController.java");
        assertTrue(src.contains("@PostMapping(\"/docs/{id}/versions/{versionId}/delete\")"),
                "版本删除端点不见了，或被改成了 GET/RequestMapping");
    }

    @Test
    @DisplayName("页面：历史版本行必须带 form 指向该端点，并保留二次确认")
    void pageHasConfirmedForm() throws IOException {
        String html = template("src/main/resources/templates/docs/view.html");
        assertTrue(html.contains("'/versions/'"), "页面上已经没有指向版本删除的链接了");
        assertTrue(html.contains("return confirm('物理删除"), "二次确认被去掉了 —— 不可逆操作必须有");
        assertTrue(html.contains("不可恢复"), "确认框里必须写明不可恢复，别让人以为是移进回收站");
        assertTrue(html.contains("版本号也不会回退"), "确认框必须说明 docs.version 不回退，否则用户会以为删了能退号");
    }

    @Test
    @DisplayName("页面：删除入口只在历史版本里，当前版本那一行不许出现删除按钮")
    void currentVersionHasNoDeleteButton() throws IOException {
        String html = template("src/main/resources/templates/docs/view.html");
        int currentRow = html.indexOf("data-version=current");
        int listStart = html.indexOf("id=\"versionList\"");
        assertTrue(currentRow > 0 && listStart > 0, "版本列表结构变了，先核对这个守卫");
        // 当前版本行到它自己的 </a> 之间不该出现 /versions/ 删除表单
        int currentRowEnd = html.indexOf("</a>", currentRow);
        String currentBlock = html.substring(currentRow, currentRowEnd);
        assertTrue(!currentBlock.contains("/versions/"),
                "当前版本行里出现了版本删除入口 —— 当前正文不在 doc_versions 里，这一定是挂错了位置");
    }
}
