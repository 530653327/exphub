package com.exphub.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * L0-1a：#678 §5.3 定稿表的 Java 验算，与 tools/search_regression.py 的 B 组 7 条一一对应。
 *
 * <p>项目别名与 df 都取自真实快照，不手写桩数据 —— 这样「防误召 / 防漏召同时验」跑的就是线上语料。
 * 旧 {@code ProjectEmbeddedDetectionTest} 的 C9 独立兜底已并入 ③ 分支，本类取代它。</p>
 */
class AliasMatcherTest {

    private static final String TOOLS_SNAPSHOT = "tools/df_snapshot.json";
    private static final String RES_SNAPSHOT = "src/main/resources/df_snapshot.json";

    private static JsonNode projects() throws Exception {
        return new ObjectMapper().readTree(Path.of(TOOLS_SNAPSHOT).toFile()).path("projects");
    }

    private static List<String> itemsOf(long id) throws Exception {
        for (JsonNode p : projects()) {
            if (p.path("id").asLong() == id) {
                return AliasMatcher.items(p.path("aliases").asText(""),
                        p.path("name").asText(""), p.path("project_code").asText(""));
            }
        }
        throw new IllegalArgumentException("快照里没有项目 #" + id);
    }

    private static boolean projectHits(long id, String query) throws Exception {
        return AliasMatcher.firstMatch(itemsOf(id), AliasMatcher.tokens(query), DfTable.loaded()) != null;
    }

    private static boolean anyProjectHits(String query) throws Exception {
        for (JsonNode p : projects()) {
            List<String> items = AliasMatcher.items(p.path("aliases").asText(""),
                    p.path("name").asText(""), p.path("project_code").asText(""));
            if (AliasMatcher.anyMatch(items, AliasMatcher.tokens(query), DfTable.loaded())) return true;
        }
        return false;
    }

    // ==================== B 组：#678 §5.4 定稿表 7 条 ====================

    @Test
    @DisplayName("B1 hjs：① 相等，df 5.7%，不限长度")
    void b1() throws Exception {
        assertTrue(projectHits(2, "hjs"));
        assertEquals(AliasMatcher.Branch.EQ,
                AliasMatcher.judge("HJS", "hjs", DfTable.loaded()).branch);
    }

    @Test
    @DisplayName("B2 hjs的入库单：③ token 含项，后接 CJK 边界成立")
    void b2() throws Exception {
        assertTrue(projectHits(2, "hjs的入库单"));
        assertEquals(AliasMatcher.Branch.TOKEN_CONTAINS_ITEM,
                AliasMatcher.judge("hjs", "hjs的入库单", DfTable.loaded()).branch);
    }

    @Test
    @DisplayName("B3 用 api 接口：② 纯 ASCII 结构性拒绝，任何项目都不命中")
    void b3() throws Exception {
        assertFalse(anyProjectHits("用 api 接口"));
        assertFalse(AliasMatcher.judge("xy-api", "api", DfTable.loaded()).hit);
        assertTrue(AliasMatcher.judge("xy-api", "api", DfTable.loaded()).reason.contains("结构性拒绝"));
    }

    @Test
    @DisplayName("B4 xy-api：① 相等，命中项目 5（§5.2 裁定）")
    void b4() throws Exception {
        assertTrue(projectHits(5, "xy-api"));
    }

    @Test
    @DisplayName("B5 xy：纯 ASCII 走 ② 被拒（定案表新增的已知漏召面）")
    void b5() throws Exception {
        assertFalse(anyProjectHits("xy"));
    }

    @Test
    @DisplayName("B6 工业大学：② 的 CJK 正向用例，没有它 ② 只验了拒绝")
    void b6() throws Exception {
        assertTrue(projectHits(5, "工业大学"));
    }

    @Test
    @DisplayName("B7 拆解宝（报废车回收模块）：① 全名相等")
    void b7() throws Exception {
        assertTrue(projectHits(3, "拆解宝（报废车回收模块）"));
    }

    // ==================== A 组形态 + C14 关心的护栏 ====================

    @Test
    @DisplayName("A7 中文长句：③ 分支承担 C9 的反向内嵌识别")
    void a7LongCjkQuery() throws Exception {
        assertTrue(projectHits(5, "工业大学校友会小程序架构说明"),
                "别名项「校友会小程序」嵌在长句里，L0-1a 的 ③ 必须命中");
        assertEquals(AliasMatcher.Branch.TOKEN_CONTAINS_ITEM,
                AliasMatcher.judge("校友会小程序", "工业大学校友会小程序架构说明",
                        DfTable.loaded()).branch);
    }

    @Test
    @DisplayName("A3 忆阁DeepSeek余额仍不命中：df(忆阁)=33.9% 超线 + ③ 右邻 ASCII 非边界")
    void a3IsRejectedByTheSpec() throws Exception {
        assertFalse(anyProjectHits("忆阁DeepSeek余额"),
                "按 #678 §5.3 定稿表，这条本来就该拒；A3 期望 #6 与定案表冲突，待阁主/越哥裁定");
        AliasMatcher.Verdict v = AliasMatcher.judge("忆阁", "忆阁DeepSeek余额", DfTable.loaded());
        assertFalse(v.hit);
        assertTrue(v.reason.contains("边界") || v.reason.contains("df"),
                "拒绝原因要能自证，实际=" + v.reason);
        Double pct = DfTable.loaded().pct("忆阁");
        assertNotNull(pct, "df(忆阁) 必须在快照里");
        assertTrue(pct >= AliasMatcher.DF_MAX_PCT,
                "df(忆阁) 实测 " + pct + "%，已过 30% 门槛");
    }

    @Test
    @DisplayName("放宽长度到 2 后，api 类词不回流：df(api)=28% 已低于阈值，仍被结构性拒绝")
    void lengthRelaxationDoesNotBringBackApi() {
        DfTable df = DfTable.loaded();
        assertEquals(2, AliasMatcher.MIN_ITEM_LEN, "定案表只保留 >=2 的下限");
        Double apiPct = df.pct("api");
        assertNotNull(apiPct);
        assertTrue(apiPct < AliasMatcher.DF_MAX_PCT,
                "df(api) 已从 35.4% 漂到 " + apiPct + "%，单靠 df 门槛挡不住了");
        assertFalse(AliasMatcher.judge("xy-api", "api", df).hit,
                "这正是 #678 §5.1 结构性拒绝存在的理由");
        assertFalse(AliasMatcher.judge("xy-api", "xy", df).hit);
        assertFalse(AliasMatcher.judge("hjs-admin", "api", df).hit);
    }

    @Test
    @DisplayName("缺数不放行：df 未收录的词即便相等也不许命中")
    void unknownDfNeverPasses() {
        AliasMatcher.Verdict v = AliasMatcher.judge("某个没收录的别名", "某个没收录的别名", DfTable.loaded());
        assertFalse(v.hit);
        assertTrue(v.reason.contains("UNKNOWN"), v.reason);
    }

    @Test
    @DisplayName("守卫：服务端 resources 里的 df 快照必须与 tools/ 那份完全一致")
    void dfSnapshotIsInSyncWithTools() throws Exception {
        String tools = Files.readString(Path.of(TOOLS_SNAPSHOT), StandardCharsets.UTF_8).trim();
        String res = Files.readString(Path.of(RES_SNAPSHOT), StandardCharsets.UTF_8).trim();
        assertEquals(tools, res, "重抓 df 后要同步两份，否则服务端用的是旧语料");
        assertEquals(new ObjectMapper().readTree(tools).path("n_global").asInt(),
                DfTable.loaded().nGlobal(), "服务端加载的 N 必须与快照一致");
    }

    @Test
    @DisplayName("守卫：ProjectService 只剩一套别名判定，C9 的独立兜底已移除")
    void onlyOneAliasJudgementPath() throws Exception {
        String src = Files.readString(
                Path.of("src/main/java/com/exphub/service/ProjectService.java"), StandardCharsets.UTF_8);
        assertTrue(src.contains("AliasMatcher.firstMatch"), "matchAlias 必须走三分支");
        assertFalse(src.contains("MIN_ALIAS_LEN"), "旧长度门槛（拿长度当 df 代理）不许复活");
        assertFalse(src.contains("detectEmbeddedProject"), "C9 独立兜底不许与 ③ 并存");
        String tools = Files.readString(
                Path.of("src/main/java/com/exphub/mcp/ExpHubTools.java"), StandardCharsets.UTF_8);
        assertFalse(tools.contains("detectEmbeddedProject("), "detectProjectInQuery 不许再调独立兜底");
    }
}
