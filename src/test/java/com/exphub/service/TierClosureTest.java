package com.exphub.service;

import com.exphub.service.SimilarityScorer.Kind;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * 「每层超阈值 = 展示 + 各原因之和」的闭合性回归（2026-10-29 越哥指出缺口后补）。
 *
 * <p><b>为什么要单测而不是线上跑</b>：线上本库构造不出 per-doc 与抽样两种截断 ——
 * 每篇文档最多命中 1~2 对（per-doc 闸不生效），③层未判只有 2 条（抽样上限 5 到不了）。
 * 我跑 `maxPerDoc=1` 与 `progressSample=0`，回执与 `limit=50` **一字不差**，
 * 说明那两个场景根本没被触发，只能改测 {@link SimilarityService#select}。
 *
 * <p><b>为什么这条最值得钉</b>：回执里那句「加总不等于超阈值 − 展示 → 请报缺陷」一旦误报，
 * 它就是**假警报**，下一个人会去查数据而不是查代码；反过来漏报，差额就被读成「没有问题」。
 * 两种都让这段归因失去意义。而闭合涉及的项有七个（展示 / per-doc / limit / 抽样 / 同族 /
 * 同话题 / 已判过），任何一个没进求和都会破。
 */
class TierClosureTest {

    private static SimilarityService.Row row(long a, long b, Kind kind, double score,
                                             String titleA, String titleB) {
        return new SimilarityService.Row(a, titleA, b, titleB, score, "test", null, kind,
                3, 0.5, "看看", null, null, 0, "", "");
    }

    private static SimilarityService.Selection run(List<SimilarityService.Row> rows,
                                                   int maxRows, int perDoc, int sample) {
        return run(rows, maxRows, perDoc, sample, Set.of());
    }

    private static SimilarityService.Selection run(List<SimilarityService.Row> rows,
                                                   int maxRows, int perDoc, int sample,
                                                   Set<String> judged) {
        List<SimilarityService.Row> sorted = new ArrayList<>(rows);
        sorted.sort(SimilarityService.Row.order());
        // select 是包内可见的纯函数，直接调 —— 不走 DB、不走 MCP
        return SimilarityService.select(sorted, maxRows, perDoc, sample, judged);
    }

    /** 核心断言：每一层都必须「超阈值 = 展示 + 各项原因」 */
    private static void assertClosed(SimilarityService.Selection sel, String scene) {
        for (Map.Entry<Kind, SimilarityService.TierDetail> e : sel.detail().entrySet()) {
            SimilarityService.TierDetail d = e.getValue();
            int sum = d.shown() + d.droppedPerDoc() + d.droppedByLimit() + d.droppedBySample()
                    + d.droppedRedundant() + d.droppedSameTopic() + d.droppedJudged();
            assertEquals(d.above(), sum,
                    scene + "｜层 " + e.getKey() + " 不闭合：超阈值 " + d.above()
                            + " ≠ 展示 " + d.shown() + " + per-doc " + d.droppedPerDoc()
                            + " + limit " + d.droppedByLimit() + " + 抽样 " + d.droppedBySample()
                            + " + 同族 " + d.droppedRedundant() + " + 同话题 " + d.droppedSameTopic()
                            + " + 已判过 " + d.droppedJudged());
        }
    }

    @Test
    @DisplayName("limit 生效时闭合（线上唯一真正跑到过的一项）")
    void limitCloses() {
        List<SimilarityService.Row> rows = new ArrayList<>();
        for (int i = 2; i <= 8; i++) {
            rows.add(row(300L + i, 400L + i, Kind.SERIES, 0.90 - i * 0.01, "a" + i, "b" + i));
        }
        SimilarityService.Selection sel = run(rows, 2, 5, 5);
        assertClosed(sel, "limit");
        SimilarityService.TierDetail d = sel.detail().get(Kind.SERIES);
        assertEquals(7, d.above());
        assertEquals(2, d.shown());
        assertEquals(5, d.droppedByLimit(), "名额填满后的 5 对才算 limit 截断");
    }

    @Test
    @DisplayName("per-doc 生效时闭合：补位捞回一部分，剩下的才算「per-doc 挤掉」")
    void perDocCloses() {
        // 5 对全部共用文档 1 → maxPerDoc=1 时只有第一对能进；名额(3)有余会补回一批
        List<SimilarityService.Row> rows = new ArrayList<>();
        for (int i = 2; i <= 6; i++) {
            rows.add(row(1L, i, Kind.SERIES, 0.90 - i * 0.01, "t" + i, "u" + i));
        }
        SimilarityService.Selection sel = run(rows, 3, 1, 5);
        assertClosed(sel, "per-doc");
        SimilarityService.TierDetail d = sel.detail().get(Kind.SERIES);
        assertEquals(5, d.above());
        assertEquals(3, d.shown(), "maxRows=3 应被填满（含补位补回的）");
        assertEquals(2, d.droppedPerDoc(), "补位之后仍留在队列外的才是 per-doc 挤掉");
    }

    @Test
    @DisplayName("③层抽样生效时闭合（抽样省掉的必须计入求和，否则会误报「请报缺陷」）")
    void sampleCloses() {
        // 每对用互不相同的文档与标题，隔离 per-doc 与同话题两道闸
        List<SimilarityService.Row> rows = new ArrayList<>();
        for (int i = 2; i <= 11; i++) {
            rows.add(row(100L + i, 200L + i, Kind.PROGRESS, 0.80 - i * 0.005,
                    "uniquetopicAlfa" + i, "uniquetopicBeta" + i));
        }
        SimilarityService.Selection sel = run(rows, 50, 2, 3);
        assertClosed(sel, "sample");
        SimilarityService.TierDetail d = sel.detail().get(Kind.PROGRESS);
        assertEquals(10, d.above());
        assertEquals(3, d.shown(), "抽样上限 3");
        assertEquals(7, d.droppedBySample(), "剩 7 条应记为抽样省掉");
    }

    @Test
    @DisplayName("progressSample=0（不限）时抽样项必须为 0，且仍然闭合")
    void sampleDisabledCloses() {
        List<SimilarityService.Row> rows = new ArrayList<>();
        for (int i = 2; i <= 11; i++) {
            rows.add(row(120L + i, 220L + i, Kind.PROGRESS, 0.80,
                    "uniquetopicGama" + i, "uniquetopicDelta" + i));
        }
        SimilarityService.Selection sel = run(rows, 50, 2, 0);
        assertClosed(sel, "sample=0");
        SimilarityService.TierDetail d = sel.detail().get(Kind.PROGRESS);
        assertEquals(10, d.shown(), "传 0 = 不限，10 条全展示");
        assertEquals(0, d.droppedBySample(), "闸没开，就不该有抽样省掉的数");
    }

    @Test
    @DisplayName("「已判过」也要进求和 —— 它是最容易被漏掉的一项")
    void judgedCloses() {
        List<SimilarityService.Row> rows = new ArrayList<>();
        for (int i = 2; i <= 6; i++) {
            rows.add(row(900L + i, 950L + i, Kind.PROGRESS, 0.80,
                    "jtopicAlfa" + i, "jtopicBeta" + i));
        }
        Set<String> judged = new HashSet<>();
        judged.add(ReviewCandidateService.pairKey(902L, 952L));
        judged.add(ReviewCandidateService.pairKey(903L, 953L));
        SimilarityService.Selection sel = run(rows, 50, 2, 5, judged);
        assertClosed(sel, "judged");
        assertEquals(2, sel.detail().get(Kind.PROGRESS).droppedJudged(),
                "已判过的对不占名额，但必须记进这一项");
    }

    @Test
    @DisplayName("多类混合时仍闭合；顺带确认「per-doc 残留 / 抽样 / limit」三项其实互斥")
    void mixedCloses() {
        List<SimilarityService.Row> rows = new ArrayList<>();
        // ①层 3 对：per-doc 豁免，但受 limit
        for (int i = 2; i <= 4; i++) {
            rows.add(row(500L, 500L + i, Kind.DUPLICATE, 0.95, "dup" + i, "dupB" + i));
        }
        // ②层 4 对：共用文档 600 → per-doc 生效（名额充裕时会被补位捞回）
        for (int i = 2; i <= 5; i++) {
            rows.add(row(600L, 600L + i, Kind.SERIES, 0.88, "ser" + i, "serB" + i));
        }
        // ③层 6 对：各自独立 → 名额够时走抽样，名额满时先撞 limit
        for (int i = 2; i <= 7; i++) {
            rows.add(row(700L + i, 800L + i, Kind.PROGRESS, 0.80,
                    "progtopicAlfa" + i, "progtopicBeta" + i));
        }
        SimilarityService.Selection sel = run(rows, 6, 1, 2);
        assertClosed(sel, "mixed");

        // ⚠️ 这里原本断言「抽样也应 >0」，实测为 0 —— 不是缺陷，是闸序决定的：
        // ① per-doc 残留只在**名额填满**时才出现（名额有余就补位捞回，实测本例 ②层剩 1 条）；
        // ② ③层闸序是「已判过 → 抽样 → limit」。名额已满时第一条就撞 limit，
        //    而抽样只在「③层已展示数 ≥ progressSample」时才记账 —— 名额满时这个数永远到不了。
        // ③ 反过来，一旦抽样触发，名额就不再被消耗，limit 也就永远没有机会记账。
        // → **同一层里「抽样」与「limit 截断」互斥**。这条必须钉住，
        //    否则下一个人会以为一个回执里该同时看到两种原因，看到只有一个就去查代码。
        for (Map.Entry<Kind, SimilarityService.TierDetail> e : sel.detail().entrySet()) {
            SimilarityService.TierDetail d = e.getValue();
            org.junit.jupiter.api.Assertions.assertFalse(
                    d.droppedBySample() > 0 && d.droppedByLimit() > 0,
                    "层 " + e.getKey() + " 同时出现抽样与 limit 截断，闸序被改坏了：抽样 "
                            + d.droppedBySample() + " / limit " + d.droppedByLimit());
        }

        int anyLimit = 0;
        for (SimilarityService.TierDetail d : sel.detail().values()) anyLimit += d.droppedByLimit();
        org.junit.jupiter.api.Assertions.assertTrue(anyLimit > 0, "名额被填满，必然有 limit 截断");
    }

    /**
     * 端到端：真实 `select()` 的输出直接喂进回执文案。
     *
     * <p>这条防的是「两份公式各写一遍」的漂移：{@code assertClosed} 里的求和与
     * {@link com.exphub.mcp.ExpHubTools#tierLimitLine} 里的求和是两处代码，
     * 将来改一个忘另一个，闭合测试照过、回执却开始误报「请报缺陷」。
     */
    @Test
    @DisplayName("端到端：select 的真实输出喂进文案，任何场景都不许出现「自相矛盾」")
    void receiptNeverCriesWolf() {
        int[][] params = {{2, 5, 5}, {3, 1, 5}, {50, 2, 3}, {50, 2, 0}};
        List<List<SimilarityService.Row>> scenes = new ArrayList<>();

        List<SimilarityService.Row> s1 = new ArrayList<>();
        for (int i = 2; i <= 8; i++) {
            s1.add(row(300L + i, 400L + i, Kind.SERIES, 0.90, "a" + i, "b" + i));
        }
        scenes.add(s1);

        List<SimilarityService.Row> s2 = new ArrayList<>();
        for (int i = 2; i <= 6; i++) {
            s2.add(row(1L, i, Kind.SERIES, 0.90, "t" + i, "u" + i));
        }
        scenes.add(s2);

        List<SimilarityService.Row> s3 = new ArrayList<>();
        for (int i = 2; i <= 11; i++) {
            s3.add(row(100L + i, 200L + i, Kind.PROGRESS, 0.80,
                    "uniquetopicAlfa" + i, "uniquetopicBeta" + i));
        }
        scenes.add(s3);

        List<SimilarityService.Row> s4 = new ArrayList<>();
        for (int i = 2; i <= 11; i++) {
            s4.add(row(120L + i, 220L + i, Kind.PROGRESS, 0.80,
                    "uniquetopicGama" + i, "uniquetopicDelta" + i));
        }
        scenes.add(s4);

        int lines = 0;
        for (int idx = 0; idx < scenes.size(); idx++) {
            int maxRows = params[idx][0], perDoc = params[idx][1], sample = params[idx][2];
            SimilarityService.Selection sel = run(scenes.get(idx), maxRows, perDoc, sample);
            for (Map.Entry<Kind, SimilarityService.TierDetail> e : sel.detail().entrySet()) {
                String line = com.exphub.mcp.ExpHubTools.tierLimitLine(
                        com.exphub.mcp.ExpHubTools.tierShortName(e.getKey()), e.getValue(), perDoc);
                org.junit.jupiter.api.Assertions.assertFalse(line.contains("自相矛盾"),
                        "场景" + (idx + 1) + " 层 " + e.getKey() + " 误报缺陷：" + line);
                org.junit.jupiter.api.Assertions.assertFalse(line.contains("未归因"),
                        "场景" + (idx + 1) + " 层 " + e.getKey() + " 有漏项：" + line);
                lines++;
            }
        }
        org.junit.jupiter.api.Assertions.assertTrue(lines >= 3, "四个场景合起来至少该有 3 行分层文案");
    }
}
