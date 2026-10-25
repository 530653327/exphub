package com.exphub.service;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 权重失衡 / 排序反转（2026-10-25 23:38 越哥实测发现）。
 *
 * <p>现象：队列里排在第一位的是 #470↔#477（相似度 0.62、正文 0 行重合），
 * 而正文 <b>33 行完全一致</b>的 #63↔#67 只拿 0.51、排到第 16 位。**排序是反的。**
 *
 * <p>根因：`标签 1.00 + 标题 0.75` 就能凑出 0.59 —— 正文完全不重也能过阈值。
 * 三个信号的含义根本不同：标签 = 项目归属、标题 = 话题、正文 = 内容。
 *
 * <p>所以修法不是改权重（会让所有历史分数漂移、也让「为什么像」失去解释力），
 * 而是<b>先分层再比分数</b>：{@link SimilarityScorer.Kind}。
 */
class RankingTierTest {

    /**
     * 造快照。
     *
     * <p>⚠️ 必须成对走 {@link SimilarityScorer#applyDistinctLines}（模拟作用域索引）——
     * 直接用裸 {@code Sketch.of} 会得到 {@code lineModeReliable=false}，
     * 于是所有对都会掉进 UNKNOWN 层、分层失效。这个坑我在生产代码里已经踩过一次
     * （`8a7c538`：L0 拿裸 Sketch 比对 → 永远回退 2-gram），测试里又踩了一次。
     */
    private static SimilarityScorer.Sketch series(long id, String title, String tagLine, String body) {
        return SimilarityScorer.applyDistinctLines(List.of(
                SimilarityScorer.Sketch.of(id, title, tagLine, "", "运维", null, body))).get(0);
    }

    /** 一对文档必须放进同一个作用域一起建索引，否则各自的 df 不一致、模板行剔不干净 */
    private static SimilarityScorer.Score scorePair(SimilarityScorer.Sketch a, SimilarityScorer.Sketch b) {
        List<SimilarityScorer.Sketch> scope = SimilarityScorer.applyDistinctLines(List.of(a, b));
        return SimilarityScorer.score(scope.get(0), scope.get(1));
    }

    private static final String SERIES_A = String.join("\n",
            "# 待开发：新增 MCP 工具 list_recent_memories",
            "目标是给忆阁加一个枚举最近记忆的入口，先定参数：pageSize、cursor。",
            "不支持按关键词搜索，那是 search_experience 的职责。",
            "需要评估分页游标能否稳定，以及和现有搜索的边界。");

    private static final String SERIES_B = String.join("\n",
            "# 实现 MCP 工具 list_recent_memories",
            "已实现并部署上线，线上验证分页游标可用，返回 20 条。",
            "客户端需要还原 JSON 编码，否则中文会乱码。",
            "下一步是把它接进忆阁的能力说明，让模型知道能用。");

    /** 真·字面重复：一大段内容被复制进了两篇 */
    private static final String DUP_A = String.join("\n",
            "# 中央平原地图设计",
            "区域等级 1-15 级，共 4 张地图，图块尺寸 32x32 像素。",
            "地图1 平原入口：缓坡草地，散落碎石，边缘有栅栏。",
            "地图2 花田小径：成片野花，中间一条土路，两侧灌木。",
            "地图3 北部丘陵：起伏地形，裸露岩层，顶部有废弃哨塔。",
            "地图4 森林边界：密林边缘，光线斑驳，地面落叶厚。",
            "配色以青绿与土黄为主，避免高饱和，保持新手区观感。");

    private static final String DUP_B = String.join("\n",
            "# 中央平原地图3 提示词（北部丘陵）",
            "地图3 北部丘陵：起伏地形，裸露岩层，顶部有废弃哨塔。",
            "地图4 森林边界：密林边缘，光线斑驳，地面落叶厚。",
            "配色以青绿与土黄为主，避免高饱和，保持新手区观感。",
            "补充：丘陵区需要额外的风向与雾气层次，避免显得平。",
            "生成工具 PixelLab，输出 40x30 格。");

    @Test
    void evolutionChainIsSeriesNotDuplicate() {
        SimilarityScorer.Sketch a = series(470, "待开发：新增 MCP 工具 list_recent_memories",
                "进度,交接,项目进度", SERIES_A);
        SimilarityScorer.Sketch b = series(477, "实现 MCP 工具 list_recent_memories（待部署）",
                "进度,交接,项目进度", SERIES_B);
        SimilarityScorer.Score s = scorePair(a, b);

        assertEquals(SimilarityScorer.Kind.SERIES, s.kind(),
                "标签全同标题很像但正文一行不重 → 演进链，不是重复。reason=" + s.reason());
        assertEquals(0, s.sharedLines(), "正文确实一行不重");
        // 分数依然可以很高 —— 分数本身没错，错的是拿它当排序唯一依据
        assertTrue(s.total() > 0.5, "三路信号加权后仍会过阈值：" + s.total());
        assertTrue(s.diagnosis().contains("演进链"), s.diagnosis());
        assertTrue(s.diagnosis().contains("superseded"), "要给出该判什么：" + s.diagnosis());
    }

    @Test
    void literalDuplicateIsTieredAboveSeriesEvenWithLowerScore() {
        SimilarityScorer.Sketch da = series(63, "百炼宠物 - 中央平原地图设计", "百炼宠物,地图,提示词", DUP_A);
        SimilarityScorer.Sketch db = series(67, "百炼宠物 - 中央平原地图3提示词（北部丘陵）",
                "百炼宠物,地图,提示词", DUP_B);
        SimilarityScorer.Sketch sa = series(470, "待开发：新增 MCP 工具 list_recent_memories",
                "进度,交接,项目进度", SERIES_A);
        SimilarityScorer.Sketch sb = series(477, "实现 MCP 工具 list_recent_memories（待部署）",
                "进度,交接,项目进度", SERIES_B);

        SimilarityScorer.Score dup = scorePair(da, db);
        SimilarityScorer.Score chain = scorePair(sa, sb);

        assertEquals(SimilarityScorer.Kind.DUPLICATE, dup.kind(),
                "正文多行完全一致 → 字面重复。shared=" + dup.sharedLines() + " reason=" + dup.reason());
        assertTrue(dup.sharedLines() >= SimilarityScorer.MIN_SHARED_LINES);

        // 核心断言：这是真实数据里的排序反转 —— 重复对的分数反而更低
        // 我们不改分数（历史候选不该漂移），但必须保证分层后重复对排在前面
        SimilarityService.Row rowDup = new SimilarityService.Row(63L, "A", 67L, "B",
                dup.total(), dup.reason(), null, dup.kind(), dup.sharedLines());
        SimilarityService.Row rowChain = new SimilarityService.Row(470L, "A", 477L, "B",
                chain.total(), chain.reason(), null, chain.kind(), chain.sharedLines());

        List<SimilarityService.Row> rows = new java.util.ArrayList<>(List.of(rowChain, rowDup));
        rows.sort(SimilarityService.Row.order());

        assertEquals(63L, rows.get(0).docA(),
                "字面重复必须排在演进链之前，哪怕它的分数更低（dup=" + dup.total()
                        + " chain=" + chain.total() + "）");
    }

    @Test
    void singleSharedLineIsNotEnoughEvidence() {
        // 只共享一行（典型的残留套话）不足以判字面重复
        String x = String.join("\n",
                "# 甲文档",
                "更新于 2026-04-21",
                "甲独有的第一段内容，讲的是部署脚本怎么跑。",
                "甲独有的第二段内容，讲的是日志怎么看。");
        String y = String.join("\n",
                "# 乙文档",
                "更新于 2026-04-21",
                "乙独有的第一段内容，讲的是数据库备份。",
                "乙独有的第二段内容，讲的是证书续期。");
        SimilarityScorer.Score s = scorePair(series(1, "甲文档", "t", x),
                series(2, "乙文档", "t", y));
        assertTrue(s.sharedLines() <= 1, "只有一行套话重合，实际 " + s.sharedLines());
        assertEquals(SimilarityScorer.Kind.SERIES, s.kind(),
                "共享 1 行不该升级成 DUPLICATE（阈值是 " + SimilarityScorer.MIN_SHARED_LINES + "）");
    }

    @Test
    void paragraphOnlyDocsAreUnknownTier() {
        String p1 = "这一段是长段落说明，没有换行也没有列表，讲的是同一套部署要点，包括构建上传重启与健康检查。";
        String p2 = "另一段长段落说明，讲的也是部署要点，只是措辞不同，没有换行，因此拿不到可用的行边界。";
        SimilarityScorer.Score s = scorePair(series(1, "部署要点一", "部署", p1),
                series(2, "部署要点二", "部署", p2));
        assertFalse(s.lineMode(), "行太少应回退 2-gram");
        assertEquals(SimilarityScorer.Kind.UNKNOWN, s.kind(), "回退口径下无法分层，标 UNKNOWN");
        assertTrue(s.diagnosis().contains("信息不足"), s.diagnosis());
    }

    @Test
    void kindOrderingPutsDuplicateFirst() {
        // 枚举顺序就是排序顺序，改动会导致排序语义悄悄变化
        assertTrue(SimilarityScorer.Kind.DUPLICATE.ordinal() < SimilarityScorer.Kind.SERIES.ordinal());
        assertTrue(SimilarityScorer.Kind.SERIES.ordinal() < SimilarityScorer.Kind.UNKNOWN.ordinal());
        assertTrue(SimilarityScorer.MIN_SHARED_LINES >= 2,
                "1 行重合几乎一定是残留套话，不能当证据");
    }
}
