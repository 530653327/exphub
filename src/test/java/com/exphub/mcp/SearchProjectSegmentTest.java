package com.exphub.mcp;

import com.exphub.entity.Doc;
import com.exphub.entity.Project;
import com.exphub.service.SourceScan;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * ✍️ 2026-10-30 烛龙 —— 单4：全局搜索给项目命中留「可见段」（L0-1b）+ total 重算（L0-2）。
 *
 * <p><b>要治的痛点</b>：全局搜索限定在 {@code project_id IS NULL}，项目里的经验一条都搜不到。
 * 用户搜不到就以为没记过，于是重复记一遍。
 *
 * <p><b>口径（越哥 2026-10-30 拍板 B「隔离＋末尾提示」）</b>：
 * 主排序逐字不动、只排全局；项目侧命中单独成段放在<b>全部全局结果之后</b>，最多 5 条，
 * N 是项目侧命中<b>总数</b>不是 5；每条只给标题+摘要，<b>不出正文</b>；
 * 这段不吃全局 offset/limit；顶部「匹配 N 条」仍只算全局。
 *
 * <p>本类不连库，钉的是渲染规则与源码口径；线上行为由回执里的实跑数据证明。
 */
class SearchProjectSegmentTest {

    private static Doc doc(long id, String title, String summary, String content) {
        Doc d = new Doc();
        d.setId(id);
        d.setTitle(title);
        d.setSummary(summary);
        d.setContent(content);
        return d;
    }

    private static Project project(long id, String name, String code) {
        Project p = new Project();
        p.setId(id);
        p.setName(name);
        p.setProjectCode(code);
        return p;
    }

    @Test
    @DisplayName("没有项目命中（N=0 / 未识别到项目）→ 返回空串，回执与之前逐字一致")
    void noProjectHitsRendersNothing() {
        Project p = project(5, "天津工业大学校友会", "tjpu-alumni");
        assertEquals("", ExpHubTools.renderProjectSegment(null, 0, List.of()));
        assertEquals("", ExpHubTools.renderProjectSegment(p, 0, List.of(doc(1, "t", "s", "c"))),
                "N=0 时必须一个字都不加 —— 否则不命中项目的查询回执会变，L0-1b 就污染了主路径");
        assertEquals("", ExpHubTools.renderProjectSegment(p, 7, List.of()),
                "total 与 records 矛盾（有数没条）时宁可不显示，也不编一个空段");
    }

    @Test
    @DisplayName("N 是项目侧命中总数，不是列出来的条数")
    void headerShowsRealTotalNotShownCount() {
        Project p = project(5, "天津工业大学校友会", "tjpu-alumni");
        List<Doc> top = List.of(doc(101, "甲", "s1", "c1"), doc(102, "乙", "s2", "c2"), doc(103, "丙", "s3", "c3"));
        String seg = ExpHubTools.renderProjectSegment(p, 37, top);
        assertTrue(seg.contains("【项目「天津工业大学校友会」内还有 37 条，仅看项目请传 projectName】"),
                "段前必须是这一行，且数字是 37（总数）不是 3（本次列出）：\n" + seg);
        assertFalse(seg.contains("还有 3 条"), "不许把「列了几条」当成「还有几条」");
    }

    @Test
    @DisplayName("最多 5 条：上限常量 = 5，渲染只吃传进来的条数")
    void atMostFiveEntries() {
        assertEquals(5, ExpHubTools.PROJECT_SEGMENT_MAX, "上限就是 5，改这个数要同时改回执口径");
        Project p = project(6, "忆阁", "memhaven");
        List<Doc> top = new ArrayList<>();
        for (int i = 1; i <= 5; i++) top.add(doc(200 + i, "条" + i, "摘要" + i, "正文" + i));
        String seg = ExpHubTools.renderProjectSegment(p, 88, top);
        for (int i = 1; i <= 5; i++) {
            assertTrue(seg.contains("条" + i), "第 " + i + " 条应在段内");
        }
        assertFalse(seg.contains("6. 【ID:"), "第 6 条不该出现");
    }

    @Test
    @DisplayName("只给标题+摘要：正文一个字都不许漏")
    void titleAndSummaryOnlyNoContent() {
        Project p = project(5, "天津工业大学校友会", "tjpu-alumni");
        Doc d = doc(410, "第 3 章 部署", "校友会小程序后端的部署步骤", "SECRET_BODY_MARKER: 这里是不该出现的正文");
        String seg = ExpHubTools.renderProjectSegment(p, 1, List.of(d));
        assertTrue(seg.contains("第 3 章 部署"), "标题要在");
        assertTrue(seg.contains("校友会小程序后端的部署步骤"), "摘要要在");
        assertFalse(seg.contains("SECRET_BODY_MARKER"), "不带 projectName 就不许拿到项目正文：\n" + seg);
    }

    @Test
    @DisplayName("摘要截到 60 字（与列表口径一致），超长要打省略号")
    void summaryTruncatedTo60() {
        Project p = project(6, "忆阁", "memhaven");
        String longSummary = "一二三四五六七八九十".repeat(10); // 100 字
        String seg = ExpHubTools.renderProjectSegment(p, 1, List.of(doc(301, "长摘要", longSummary, "c")));
        assertTrue(seg.contains("…"), "超过 60 字必须截断并打省略号");
        assertFalse(seg.contains(longSummary), "不许把整段摘要都印出来");
        assertEquals(60, ExpHubTools.previewSummary(longSummary).replace("…", "").length());
    }

    @Test
    @DisplayName("守卫：主排序只排全局，项目文档不许再被混进 docs")
    void globalRankingNotPollutedByProject() throws IOException {
        String src = Files.readString(Path.of("src/main/java/com/exphub/mcp/ExpHubTools.java"),
                StandardCharsets.UTF_8);
        String body = SourceScan.methodBody(src, "public String searchExperience(");
        assertFalse(body.isEmpty(), "取不到 searchExperience 方法体，守卫失效");
        assertFalse(body.contains("projDocsHit"), "E5 那套「项目命中前置混排」的变量必须消失");
        assertFalse(body.contains("docs = merged"), "不许再把项目文档合并进主列表（主排序只能有一个域）");
        // 项目段只出现在三个位置：全局为空时的兜底、以及全局结果与翻页提示之后
        assertEquals(2, occurrences(body, "renderProjectSegment(detected, projectTotal, projectTop)"),
                "项目段只该在「全局 0 条」和「全部全局结果之后」两处出现");
    }

    @Test
    @DisplayName("守卫：project_id IS NULL 硬隔离一字不动")
    void projectIsolationUntouched() throws IOException {
        String mapper = Files.readString(Path.of("src/main/java/com/exphub/mapper/DocMapper.java"),
                StandardCharsets.UTF_8);
        assertEquals(2, occurrences(mapper, "<if test='projectId == null'>AND project_id IS NULL</if>"),
                "DocMapper 里搜索与计数两条 SQL 的全局隔离条件必须都在，一条都不能少");
        assertTrue(mapper.contains("<if test='projectId != null'>AND project_id = #{projectId}</if>"),
                "项目域分支也必须还在：有 projectId 才放开隔离");

        String svc = Files.readString(Path.of("src/main/java/com/exphub/service/DocService.java"),
                StandardCharsets.UTF_8);
        String like = SourceScan.methodBody(svc,
                "private Page<Doc> searchWithLike(String keyword, String templateType, String includeStatus, Long projectId, int page, int size)");
        assertFalse(like.isEmpty(), "取不到 searchWithLike 方法体，守卫失效");
        assertTrue(like.contains("wrapper.isNull(\"project_id\")"), "LIKE 兜底的全局隔离不许动");
        assertTrue(like.contains("wrapper.eq(\"project_id\", projectId)"), "LIKE 兜底的项目域分支不许动");
    }

    @Test
    @DisplayName("守卫：total 口径不变 —— 顶部「匹配 N 条」只算全局，项目数走 projectTotal 单独一条")
    void totalStillGlobalOnly() throws IOException {
        String src = Files.readString(Path.of("src/main/java/com/exphub/mcp/ExpHubTools.java"),
                StandardCharsets.UTF_8);
        String body = SourceScan.methodBody(src, "public String searchExperience(");
        assertTrue(body.contains("long effTotal = Math.max(matchedTotal, poolTotal);"),
                "effTotal 仍是全局命中数（matchedTotal 来自 docService.search 的全局结果）");
        assertFalse(body.contains("effTotal + projectTotal") || body.contains("effTotal = matchedTotal + projectTotal"),
                "项目条数不许并进 total —— 那两个是不同域的数量，混装后「匹配 N 条」就不可解释了");
        assertTrue(body.contains("projectTotal = docService.countProjectHits("),
                "项目数必须走 countProjectHits（纯 DB 计数），不能用 search().getTotal()（重排后的条数，SQL 复现不出来）");
        assertFalse(body.contains("projectTotal = projResult.getTotal()"),
                "退回 getTotal() 会让提示里的 N 无法被一条 count(*) 对账");
    }

    @Test
    @DisplayName("守卫：N 是纯 DB 计数 —— 不过覆盖率重排、不分页")
    void projectCountIsPlainSqlCount() throws IOException {
        String svc = Files.readString(Path.of("src/main/java/com/exphub/service/DocService.java"),
                StandardCharsets.UTF_8);
        String body = SourceScan.methodBody(svc,
                "public long countProjectHits(String keyword, String templateType, String includeStatus, Long projectId)");
        assertFalse(body.isEmpty(), "取不到 countProjectHits 方法体，守卫失效");
        assertTrue(body.contains("docMapper.countByFulltext(terms.toOrQuery()"),
                "必须是 countByFulltext（一条 SQL 计数），口径与搜索第 2 级召回一致");
        assertFalse(body.contains("rankAndFilter"),
                "过了重排就不是纯计数了 —— N 会随候选池浮动，别人拿 SQL 对不上");
        assertTrue(body.contains("canAccessProject"), "项目域鉴权必须在：不许靠这个提示越权窥探别人的项目");
    }

    @Test
    @DisplayName("守卫：L0-2 tier 判定不许再依赖调用方 size")
    void tierDecisionIndependentOfPageSize() throws IOException {
        String svc = Files.readString(Path.of("src/main/java/com/exphub/service/DocService.java"),
                StandardCharsets.UTF_8);
        String body = SourceScan.methodBody(svc,
                "public Page<Doc> search(String keyword, String templateType, String includeStatus, Long projectId, int page, int size)");
        assertFalse(body.isEmpty(), "取不到 search 方法体，守卫失效");
        assertFalse(body.contains("strictTotal >= size"),
                "tier 判定挂在调用方 size 上，同一查询 limit=5 / limit=20 就会走不同档、报不同 total（L0-2 的根因）");
        assertTrue(body.contains("strictTotal >= DocService.STRICT_DIRECT_MIN_HITS")
                        || body.contains("strictTotal >= STRICT_DIRECT_MIN_HITS"),
                "门槛必须用固定常量，不能是入参");
        assertTrue(SourceScan.methodBody(svc, "public Page<Doc> search(String keyword, String templateType, String includeStatus, Long projectId, int page, int size)")
                        .contains("Math.max(size, STRICT_DIRECT_MIN_HITS)"),
                "覆盖度探针窗口也要与 size 解耦，否则 size 太小会探不到整词覆盖，档位又跟着变");
    }

    private static int occurrences(String haystack, String needle) {
        int n = 0, from = 0;
        while (true) {
            int at = haystack.indexOf(needle, from);
            if (at < 0) return n;
            n++;
            from = at + needle.length();
        }
    }
}
