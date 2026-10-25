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
        return series(id, title, tagLine, body, null);
    }

    private static SimilarityScorer.Sketch series(long id, String title, String tagLine, String body,
                                                 String templateType) {
        return SimilarityScorer.applyDistinctLines(List.of(
                SimilarityScorer.Sketch.of(id, title, tagLine, "", "运维", null, templateType, body))).get(0);
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
                dup.total(), dup.reason(), null, dup.kind(), dup.sharedLines(), dup.lineCoverage());
        SimilarityService.Row rowChain = new SimilarityService.Row(470L, "A", 477L, "B",
                chain.total(), chain.reason(), null, chain.kind(), chain.sharedLines(), chain.lineCoverage());

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
    void sharedSkeletonIsNotDuplicate() {
        // 真实案例（#74 水晶鹿 vs #85 蝴蝶仙子）：同一套提示词模板的**两个不同实例**。
        // 共享 12 行骨架（death-死亡[6帧] / 适用工具：PixelLab / 更新时间），
        // 但它们是两只不同的宠物 —— 绝不能判 duplicate。
        String skeleton = String.join("\n",
                "基于 AI绘画提示语.md 中的宠物描述生成",
                "适用工具：PixelLab Animate 功能",
                "更新时间：2026-04-21",
                "## 动画类型",
                "map_idle - 待机[4帧循环]",
                "battle_idle - 战斗待机[4帧循环]",
                "attack - 普通攻击[6帧]",
                "hurt - 受击[4帧]",
                "death - 死亡[6帧]",
                "spell - 技能施法[8帧]");
        // 真实比例：实测 #74 是 98 行正文 / 57 行去模板后，骨架只占约 1/5。
        // 一开始把测试文档写得太短（骨架占 10/15），导致覆盖率虚高到 0.60 —— 那是测试数据不真实，不是算法错
        StringBuilder deerBody = new StringBuilder("# 水晶鹿 (P-025) - 动画提示词\n").append(skeleton);
        StringBuilder fairyBody = new StringBuilder("# 蝴蝶仙子 (P-024) - 动画提示词\n").append(skeleton);
        String[] 帧明细 = {
                "map_idle：4 帧循环，身体轻微起伏，阴影随之呼吸", "battle_idle：4 帧循环，重心下沉，随时起手",
                "attack：6 帧，前冲后撤，命中帧要有明显顿挫", "hurt：4 帧，后仰位移，边缘泛红闪一下",
                "death：6 帧，倒地渐隐，最后一帧留空位", "spell：8 帧，蓄力→爆发→收招三段"};
        for (String f : 帧明细) {
            deerBody.append("\n- ").append(f.replace("身体", "鹿身").replace("泛红", "泛冰蓝"))
                    .append("；水晶鹿的四蹄踏过处留下细碎冰晶，痕迹保持 3 帧后消散");
        }
        for (String f : 帧明细) {
            fairyBody.append("\n- ").append(f.replace("身体", "蝶翼").replace("泛红", "泛粉金"))
                    .append("；蝴蝶仙子的鳞粉随振翅洒落，颗粒在光下呈暖黄高光");
        }
        deerBody.append("\n\n配色以淡蓝与银白为主，强调通透感与高贵气质，避免过饱和。")
                .append("\n技能特效为环形冰霜扩散，收招时冰晶碎裂成雾。")
                .append("\n背景使用雪原渐变，禁止出现文字或水印。")
                .append("\n额外要求：动作幅度收敛，体现轻盈，不要做出野兽式扑咬。");
        fairyBody.append("\n\n配色以粉紫与暖黄为主，鳞粉要有颗粒感，避免灰暗。")
                .append("\n技能特效为花雨爆发，收招时花瓣向四周飘散。")
                .append("\n背景使用花海虚化，禁止出现文字或水印。")
                .append("\n额外要求：保留大量留白，体现柔美，不要用冷色调。");
        String deer = deerBody.toString();
        String fairy = fairyBody.toString();

        SimilarityScorer.Score s = scorePair(
                series(74, "百炼宠物-水晶鹿P-025动画提示词", "百炼宠物,动画提示词", deer),
                series(85, "百炼宠物-蝴蝶仙子P-024动画提示词", "百炼宠物,动画提示词", fairy));

        assertTrue(s.sharedLines() > 0, "骨架行确实有重合，说明测的是真实形态：" + s.reason());
        // ⚠️ 这里**不能**断言 coverage < 阈值：骨架行数固定，文档越短占比越高。
        // 本用例的文档只有 20 行左右，骨架占 45% —— 单看覆盖率会误判成重复。
        // 真正把它拦下来的是「共享行平均字数」（骨架行都是短标签）。
        assertTrue(s.sharedAvgLen() < SimilarityScorer.MIN_SHARED_LINE_LEN,
                "骨架行很短（平均 " + s.sharedAvgLen() + " 字），这是第二道门：" + s.reason());
        assertEquals(SimilarityScorer.Kind.SERIES, s.kind(),
                "共用模板骨架 ≠ 内容重复，不能升成 DUPLICATE。reason=" + s.reason());
        assertTrue(s.diagnosis().contains("骨架") || s.diagnosis().contains("演进链")
                        || s.diagnosis().contains("不够算重复"),
                "必须说清为什么不算重复：" + s.diagnosis());
        // 记录这个坑：如果只有覆盖率一道门，这条用例会判 DUPLICATE（45% >= 40%）
        assertTrue(s.lineCoverage() >= SimilarityScorer.MIN_LINE_COVERAGE,
                "本用例刻意构造成「覆盖率会过关」，用来说明必须有第二道门，实际 " + s.lineCoverage());
    }

    @Test
    void coverageIsWhatSeparatesContentFromSkeleton() {
        // 同一份内容被摘要进另一篇：共享行占较短那篇绝大部分 → DUPLICATE
        String full = String.join("\n",
                "地图1 平原入口：缓坡草地，散落碎石，边缘有栅栏。",
                "地图2 花田小径：成片野花，中间一条土路，两侧灌木。",
                "地图3 北部丘陵：起伏地形，裸露岩层，顶部有废弃哨塔。",
                "地图4 森林边界：密林边缘，光线斑驳，地面落叶厚。",
                "配色以青绿与土黄为主，避免高饱和，保持新手区观感。");
        String summary = String.join("\n",
                "地图2 花田小径：成片野花，中间一条土路，两侧灌木。",
                "地图3 北部丘陵：起伏地形，裸露岩层，顶部有废弃哨塔。",
                "地图4 森林边界：密林边缘，光线斑驳，地面落叶厚。",
                "配色以青绿与土黄为主，避免高饱和，保持新手区观感。");
        SimilarityScorer.Score dup = scorePair(
                series(1, "中央平原地图设计", "百炼宠物,地图", full),
                series(2, "中央平原地图3提示词", "百炼宠物,地图", summary));
        assertEquals(SimilarityScorer.Kind.DUPLICATE, dup.kind(),
                "短篇 4 行里 4 行都能在长篇找到 → 覆盖率 100%，必判重复。reason=" + dup.reason());
        assertTrue(dup.lineCoverage() >= SimilarityScorer.MIN_LINE_COVERAGE);
    }

    @Test
    void kindOrderingPutsDuplicateFirst() {
        // 枚举顺序就是排序顺序，改动会导致排序语义悄悄变化
        assertTrue(SimilarityScorer.Kind.DUPLICATE.ordinal() < SimilarityScorer.Kind.SERIES.ordinal());
        assertTrue(SimilarityScorer.Kind.SERIES.ordinal() < SimilarityScorer.Kind.UNKNOWN.ordinal());
        assertTrue(SimilarityScorer.MIN_SHARED_LINES >= 2,
                "1 行重合几乎一定是残留套话，不能当证据");
    }

    // ==================== 进度/日志类降级（阁主 2026-10-26 判完 5 条后的结论） ====================

    /**
     * 两侧都是 `project_progress` 时必须降到 PROGRESS 层 —— 即使正文有明显重合。
     *
     * <p><b>为什么这条值得单独钉死</b>：阁主逐对读完 5 条候选后全判 distinct，
     * 其中 #13–#16 涉及的 6 篇文档**全部是 project_progress**：
     * 「要做成什么样」「做了什么」「上线后踩到什么」「这活归谁」——
     * 它们天然标签全同、标题高度相似，却在语义上是不同角色的记录。
     * 靠分数区分不了，只能靠 template_type 降级。
     */
    @Test
    void progressRecordsAreDemotedEvenWithRealOverlap() {
        String bodyA = String.join("\n",
                "## 待开发：新增 MCP 工具 list_recent_memories",
                "目标是给忆阁加一个枚举最近记忆的入口，先定参数：pageSize、cursor。",
                "不支持按关键词搜索，那是 search_experience 的职责，不要混进来。",
                "需要评估分页游标能否稳定，以及和现有搜索结果的边界在哪里。");
        String bodyB = String.join("\n",
                "## 实现 MCP 工具 list_recent_memories",
                "目标是给忆阁加一个枚举最近记忆的入口，先定参数：pageSize、cursor。",
                "不支持按关键词搜索，那是 search_experience 的职责，不要混进来。",
                "已实现并部署上线，线上验证分页游标可用，返回 20 条，客户端需还原 JSON 编码。");

        SimilarityScorer.Score s = scorePair(
                series(470, "待开发：新增 MCP 工具 list_recent_memories", "进度,交接",
                        bodyA, "project_progress"),
                series(477, "实现 MCP 工具 list_recent_memories（待部署）", "进度,交接",
                        bodyB, "project_progress"));

        assertEquals(SimilarityScorer.Kind.PROGRESS, s.kind(),
                "两侧都是 project_progress → 必须降级。reason=" + s.reason());
        assertTrue(s.progressPair(), "progressPair 标记要置位");
        assertTrue(s.diagnosis().contains("进度") || s.diagnosis().contains("不用判"), s.diagnosis());
    }

    /** 只要有一侧不是进度类，就按内容判定 —— 不能因为一侧是进度就整对放水 */
    @Test
    void progressOnOneSideOnlyIsNotDemoted() {
        String full = String.join("\n",
                "地图1 平原入口：缓坡草地，散落碎石，边缘有栅栏。",
                "地图2 花田小径：成片野花，中间一条土路，两侧灌木。",
                "地图3 北部丘陵：起伏地形，裸露岩层，顶部有废弃哨塔。",
                "配色以青绿与土黄为主，避免高饱和，保持新手区观感。");
        String summary = String.join("\n",
                "地图2 花田小径：成片野花，中间一条土路，两侧灌木。",
                "地图3 北部丘陵：起伏地形，裸露岩层，顶部有废弃哨塔。",
                "配色以青绿与土黄为主，避免高饱和，保持新手区观感。");
        SimilarityScorer.Score s = scorePair(
                series(1, "中央平原地图设计", "百炼宠物,地图", full, "knowledge_doc"),
                series(2, "中央平原地图3提示词", "百炼宠物,地图", summary, "project_progress"));
        assertFalse(s.progressPair(), "只有一侧是进度类，不该整体降级");
        assertEquals(SimilarityScorer.Kind.DUPLICATE, s.kind(),
                "另一侧是知识文档，内容重复就照常报重复。reason=" + s.reason());
    }

    @Test
    void progressTypeRecognition() {
        assertTrue(SimilarityScorer.isProgressType("project_progress"));
        assertTrue(SimilarityScorer.isProgressType("todo_list"));
        // 大小写/空格容错：库里存在手填值
        assertTrue(SimilarityScorer.isProgressType(" PROJECT_PROGRESS "));
        // 这些不是进度类 —— 用标题正则替代会把这几种误伤（实测 62 篇）
        assertFalse(SimilarityScorer.isProgressType("knowledge_doc"));
        assertFalse(SimilarityScorer.isProgressType("problem_solution"));
        assertFalse(SimilarityScorer.isProgressType("bug_fix"));
        assertFalse(SimilarityScorer.isProgressType(null));
        assertFalse(SimilarityScorer.isProgressType(""));
    }

    /** 枚举顺序 = 排序顺序：PROGRESS 必须排最后，否则降级没有意义 */
    @Test
    void progressSortsLast() {
        assertTrue(SimilarityScorer.Kind.PROGRESS.ordinal() > SimilarityScorer.Kind.UNKNOWN.ordinal(),
                "PROGRESS 必须是序数最大的那层（排序最末）");
        SimilarityService.Row prog = new SimilarityService.Row(1L, "A", 2L, "B", 0.99,
                "x", null, SimilarityScorer.Kind.PROGRESS, 40, 0.9);
        SimilarityService.Row dup = new SimilarityService.Row(3L, "C", 4L, "D", 0.51,
                "y", null, SimilarityScorer.Kind.DUPLICATE, 39, 0.83);
        List<SimilarityService.Row> rows = new java.util.ArrayList<>(List.of(prog, dup));
        rows.sort(SimilarityService.Row.order());
        assertEquals(3L, rows.get(0).docA(),
                "哪怕进度对分数 0.99，也必须排在字面重复（0.51）之后");
    }
}
