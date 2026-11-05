package com.exphub.service;

import com.exphub.entity.Project;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * ✍️ 2026-11-05 烛龙 —— #1272 C9：中文长 query 里的项目名/别名识别。
 *
 * <p><b>线上复现</b>：{@code search_experience(query="工业大学校友会小程序架构说明")} 的识别行是
 * 「🔎 项目识别: 无」，而短 query「工业大学」能识别到 #5。根因是 detectProjectInQuery 按空白切词后，
 * 只做「token 整串 == 名称 / name LIKE %token% / 别名项等值」，方向全是 token ⊆ 名称；
 * 长句反过来（名称 ⊆ token）一律落空。本组钉住反向内嵌匹配与三道防误召。</p>
 *
 * <p>注意：这是 C9 的最小修复，<b>不是</b> #678 §5 的 L0-1a 三分支；df 门槛尚未接入。</p>
 */
class ProjectEmbeddedDetectionTest {

    private static final String TJPU =
            "天津工业大学校友会,校友会小程序,天津工大,TJPU校友会,army,army项目,xy-api,coolshiyue/army,校友会后端";

    private static Project project(long id, String name, String aliases) {
        Project p = new Project();
        p.setId(id);
        p.setName(name);
        p.setProjectCode("p" + id);
        p.setAliases(aliases);
        return p;
    }

    @Test
    @DisplayName("C9 主用例：中文长句里嵌着完整别名项，必须识别到项目")
    void cjkLongQueryWithEmbeddedAlias() {
        Project p5 = project(5L, "天津工业大学校友会", TJPU);
        assertEquals(p5, ProjectService.pickEmbeddedProject(List.of(p5),
                "工业大学校友会小程序架构说明"),
                "别名项「校友会小程序」整段嵌在长句里，旧逻辑识别为「无」");
        assertEquals(p5, ProjectService.pickEmbeddedProject(List.of(p5),
                "天津工业大学校友会小程序架构说明"),
                "项目名整段嵌入也能命中（且按最长片段取项目）");
    }

    @Test
    @DisplayName("纯 ASCII token 结构性拒绝（#678 §5.1：api 不许命中 xy-api）")
    void pureAsciiTokenRejected() {
        Project p5 = project(5L, "天津工业大学校友会", TJPU);
        assertNull(ProjectService.pickEmbeddedProject(List.of(p5), "xy-api接口说明"),
                "内嵌匹配只认含 CJK 的别名项，xy-api 不参与");
        assertNull(ProjectService.pickEmbeddedProject(List.of(p5), "API"),
                "整串不含 CJK 直接不进内嵌匹配");
    }

    @Test
    @DisplayName("片段两侧是 ASCII 词字符时拒绝，防止夹在英文词里误命中")
    void asciiBoundaryRejects() {
        Project p = project(9L, "测试项目", "校友会小程序");
        assertNull(ProjectService.pickEmbeddedProject(List.of(p), "abc校友会小程序def"));
        assertTrue(ProjectService.embeddedHit("查一下校友会小程序架构", "校友会小程序"),
                "两侧是 CJK 时正常命中");
    }

    @Test
    @DisplayName("多个项目并列最长：宁可不报，也不猜")
    void tieReturnsNull() {
        Project a = project(1L, "甲项目", "校友会小程序");   // 6 字
        Project b = project(2L, "乙项目", "入库单流程表");   // 6 字
        assertNull(ProjectService.pickEmbeddedProject(List.of(a, b),
                "校友会小程序入库单流程表说明"));
    }

    @Test
    @DisplayName("多个命中取最长片段对应的项目")
    void longestItemWins() {
        Project shortHit = project(1L, "甲项目", "校友会");
        Project longHit = project(2L, "乙项目", "校友会小程序");
        assertEquals(longHit, ProjectService.pickEmbeddedProject(
                List.of(shortHit, longHit), "工业大学校友会小程序架构说明"));
    }

    @Test
    @DisplayName("归一化后长度 < 3 的别名项不参与判定")
    void shortItemsIgnored() {
        Project p = project(3L, "甲项目", "工大,校友");
        assertNull(ProjectService.pickEmbeddedProject(List.of(p), "天津工业大学校友会"));
    }

    @Test
    @DisplayName("null / 空输入不抛异常")
    void nullSafety() {
        assertNull(ProjectService.pickEmbeddedProject(null, "工业大学校友会"));
        assertNull(ProjectService.pickEmbeddedProject(List.of(), null));
        assertFalse(ProjectService.embeddedHit(null, "校友会小程序"));
        assertFalse(ProjectService.embeddedHit("工业大学", ""));
    }

    @Test
    @DisplayName("守卫：detectProjectInQuery 必须保留反向内嵌兜底，且只在原路径未命中后调用")
    void detectionPathWired() throws IOException {
        String src = Files.readString(
                Path.of("src/main/java/com/exphub/mcp/ExpHubTools.java"), StandardCharsets.UTF_8);
        assertTrue(src.contains("projectService.detectEmbeddedProject(token)"),
                "C9 的兜底调用被删掉，长句识别会重新失效");
        int original = src.indexOf("var candidates = projectService.resolveProject(token)");
        int embedded = src.indexOf("projectService.detectEmbeddedProject(token)");
        assertTrue(original >= 0 && original < embedded,
                "内嵌匹配必须在原精确路径之后，否则会抢先改变已有命中行为");
    }
}
