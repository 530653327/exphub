package com.exphub.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * ✍️ 2026-10-29 烛龙 —— L0-1：项目别名判定。
 *
 * <p><b>线上事故形态</b>：搜「DeepSeek 余额 用量 API 接口」，工具回执写着
 * 「查询词含项目「天津工业大学校友会」（tjpu-alumni）」，前 12 条全是该校友会的 AGENTS.md 章节。
 * 根因不是别名本身有问题，而是判定用了 {@code aliases LIKE '%api%'} ——
 * {@code api} 是别名 {@code xy-api} 的<b>子串</b>，一条通用词就把整个项目域拖进了全局搜索。</p>
 *
 * <p><b>口径（2026-11-05 L0-1a 后）</b>：判定已统一到 {@link AliasMatcher} 的 #678 §5.3 三分支
 * （① 相等 / ② 项含 CJK token / ③ token 含项）+ df&lt;30% 门槛；长度只留 ≥2 的下限。
 * 因此「CJK 子串不算命中」这条旧断言<b>已作废</b>：② 就是为了放行「工业大学」这类正向用例。
 * 仍然成立且本文件继续钉死的是：<b>纯 ASCII 子串不许走 ②</b>（api 不得命中 xy-api）、
 * 以及 name / project_code 的 SQL 路径保持原样。</p>
 */
class ProjectAliasMatchTest {

    /** projects.id=5 的真实别名串（tools/df_snapshot.json，2026-10-26 抓取），勿手改 */
    private static final String TJPU =
            "天津工业大学校友会,校友会小程序,天津工大,TJPU校友会,army,army项目,xy-api,coolshiyue/army,校友会后端";

    /** projects.id=6 的真实别名串 */
    private static final String MEMHAVEN =
            "忆阁,MemHaven,memhaven,记忆阁,第二大脑,AI记忆助手,个人记忆Agent";

    @Test
    @DisplayName("通用词只作为别名子串出现时，不命中项目")
    void genericWordInsideAliasDoesNotMatch() {
        assertNull(ProjectService.matchedAliasItem(TJPU, "API"), "api 是 xy-api 的子串，不许命中");
        assertNull(ProjectService.matchedAliasItem(TJPU, "api"));
        assertNull(ProjectService.matchedAliasItem(TJPU, "接口"));
        // 反证：整串 LIKE 时代这三条全会命中 id=5
    }

    @Test
    @DisplayName("真别名全称仍能命中（首项 / 中间项 / 末项 / 带连字符项）")
    void realAliasStillMatches() {
        assertEquals("天津工业大学校友会", ProjectService.matchedAliasItem(TJPU, "天津工业大学校友会"));
        assertEquals("天津工大", ProjectService.matchedAliasItem(TJPU, "天津工大"));
        assertEquals("army", ProjectService.matchedAliasItem(TJPU, "army"));
        assertEquals("xy-api", ProjectService.matchedAliasItem(TJPU, "xy-api"), "带连字符的整项必须仍能命中");
        assertEquals("校友会后端", ProjectService.matchedAliasItem(TJPU, "校友会后端"));
        assertEquals("个人记忆Agent", ProjectService.matchedAliasItem(MEMHAVEN, "个人记忆Agent"));
    }

    @Test
    @DisplayName("多别名混排：任意位置的项都能命中，命中项原文要能回显")
    void multiAliasMixedOrder() {
        assertEquals("校友会小程序", ProjectService.matchedAliasItem(TJPU, "校友会小程序"));
        assertEquals("TJPU校友会", ProjectService.matchedAliasItem(TJPU, "TJPU校友会"));
        // L0-1a 起 ② 分支对含 CJK 的 token 开放，B6「工业大学」就是这条正向用例。
        // df(校友会)=2.0% <30%，所以「校友会」作为「天津工业大学校友会」的子串应当命中。
        assertEquals("天津工业大学校友会", ProjectService.matchedAliasItem(TJPU, "校友会"),
                "CJK 子串按 #678 §5.3 ② 命中；旧断言「子串不算命中」已随 L0-1a 作废");
    }

    @Test
    @DisplayName("短别名项被忽略（ai / api 这类通用词不参与判定）")
    void shortAliasIgnored() {
        // 两层都拦：查询词本身不足 3 直接返回 null；即便别名里真有 "ai" 这一项也不算命中
        String withShort = "ai,AI,xy-api,天津工大";
        assertNull(ProjectService.matchedAliasItem(withShort, "ai"));
        assertNull(ProjectService.matchedAliasItem(withShort, "AI"));
        assertEquals("xy-api", ProjectService.matchedAliasItem(withShort, "xy-api"), "短项被忽略不影响长项");
        assertEquals("天津工大", ProjectService.matchedAliasItem(withShort, "天津工大"));
    }

    @Test
    @DisplayName("大小写与全半角归一后比对")
    void caseAndFullWidthInsensitive() {
        assertEquals("MemHaven", ProjectService.matchedAliasItem(MEMHAVEN, "MEMHAVEN"));
        assertEquals("MemHaven", ProjectService.matchedAliasItem(MEMHAVEN, "memhaven"));
        assertEquals("MemHaven", ProjectService.matchedAliasItem(MEMHAVEN, "ｍｅｍｈａｖｅｎ"), "全角字母要能对上");
        assertEquals("MemHaven", ProjectService.matchedAliasItem(MEMHAVEN, "ＭemＨaven"), "全半角混排要能对上");
        assertEquals("TJPU校友会", ProjectService.matchedAliasItem(TJPU, "ｔｊｐｕ校友会"));
    }

    @Test
    @DisplayName("全角逗号 / 顿号 / 分号也能切项（切项本身不查 df）")
    void altSeparatorsSplit() {
        String s = "甲项目，乙项目、丙项目;丁项目";
        // 本用例只验「别名怎么切项」。命中与否还要过 df 门槛，
        // 而「乙项目」这类桩词根本不在 df 快照里（缺数不放行），
        // 所以改测 AliasMatcher.items 这个纯函数，不再拿 matchedAliasItem 的命中结果充当切项证据。
        var items = AliasMatcher.items(s, null, null);
        assertTrue(items.contains("乙项目"));
        assertTrue(items.contains("丙项目"));
        assertTrue(items.contains("丁项目"));
    }

    @Test
    @DisplayName("null / 空输入不抛异常")
    void nullSafety() {
        assertNull(ProjectService.matchedAliasItem(null, "xy-api"));
        assertNull(ProjectService.matchedAliasItem(TJPU, null));
        assertNull(ProjectService.matchedAliasItem("", "xy-api"));
        assertNull(ProjectService.matchedAliasItem(",,,", "xy-api"));
    }

    @Test
    @DisplayName("守卫：别名判定不许退回整串 LIKE")
    void aliasLikeGuard() throws IOException {
        String src = Files.readString(Path.of("src/main/java/com/exphub/service/ProjectService.java"),
                StandardCharsets.UTF_8);
        assertFalse(src.contains("like(\"aliases\""),
                "别名判定不许退回 aliases LIKE '%q%' —— api 会作为 xy-api 的子串命中整个项目域");
    }

    @Test
    @DisplayName("守卫：name / project_code 判定路径保持原样")
    void nameAndCodePathsUntouched() throws IOException {
        String src = Files.readString(Path.of("src/main/java/com/exphub/service/ProjectService.java"),
                StandardCharsets.UTF_8);
        String body = SourceScan.methodBody(src, "public List<Project> resolveProject(");
        assertFalse(body.isEmpty(), "取不到 resolveProject 方法体，守卫失效");
        assertTrue(body.contains("eq(\"name\", q)"), "name 精确匹配必须保留");
        assertTrue(body.contains("eq(\"project_code\", q)"), "project_code 精确匹配必须保留");
        assertTrue(body.contains("like(\"name\", q)"), "name 子串匹配本轮不动");
    }
}
