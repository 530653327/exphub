package com.exphub.service;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * D1 施工包 4：相似度打分口径（2026-10-25）。
 *
 * <p>这些断言不是为了「跑绿」，每一条都对应线上 563 篇真实数据里看到的形态：
 * <ul>
 *   <li>同模板兄弟（一批同格式提示词）用 2-gram 口径会算到 0.79~0.84 —— 越像同一个模板分越高，
 *       而它们恰恰最不该进整理队列；换「去模板整行」口径后掉到 0.45 以下。</li>
 *   <li>真候选（「待办：新增 X」→「实现 X」）反而字面完全不重，靠标题 + 标签才认得出。</li>
 *   <li>长段落型文档没有可用的行边界，必须回退 2-gram 并**在理由里说明回退了**（双路径自证走位）。</li>
 * </ul>
 */
class SimilarityScorerTest {

    private static final String TEMPLATE = String.join("\n",
            "# 动画提示词模板",
            "## 使用说明",
            "本提示词用于生成宠物战斗动画，请按下面的参数逐项填写后使用。",
            "## 输出要求",
            "输出 8 帧序列帧，透明背景，分辨率 512x512，不要出现文字水印。",
            "## 风格约束",
            "国风手绘质感，柔和光照，避免过度写实；主体居中，动作舒展。",
            "## 交付格式",
            "交付 PNG 序列帧与一份参数说明，参数说明里写清每帧对应的动作描述。");

    private static final String TEMPLATE2 = String.join("\n",
            "# 动画提示词模板",
            "## 使用说明",
            "本提示词用于生成宠物战斗动画，请按下面的参数逐项填写后使用。",
            "## 输出要求",
            "输出 8 帧序列帧，透明背景，分辨率 512x512，不要出现文字水印。",
            "## 风格约束",
            "国风手绘质感，柔和光照，避免过度写实；主体居中，动作舒展。",
            "## 交付格式",
            "交付 PNG 序列帧与一份参数说明，参数说明里写清每帧对应的动作描述。");

    private static SimilarityScorer.Sketch sketch(long id, String title, String tags, String body) {
        return SimilarityScorer.Sketch.of(id, title, tags, "", "宠物素材", null, body);
    }

    /** 一批同模板兄弟：模板行在作用域内出现 5 次 → 被剔除，只剩各自的关键词行 */
    private static List<SimilarityScorer.Sketch> templateFamily() {
        List<SimilarityScorer.Sketch> list = List.of(
                sketch(1, "百炼宠物-白虎P-001动画提示词", "百炼宠物,动画提示词,白虎,雷系",
                        TEMPLATE + "\n白虎动作：前扑撕咬，雷光缠身，尾巴横扫带电流。"),
                sketch(2, "百炼宠物-野猪P-005动画提示词", "百炼宠物,动画提示词,野猪,土系",
                        TEMPLATE2 + "\n野猪动作：低头冲撞，尘土翻滚，獠牙顶飞对手。"),
                sketch(3, "百炼宠物-灵狐P-015动画提示词", "百炼宠物,动画提示词,灵狐,幻术",
                        TEMPLATE + "\n灵狐动作：分身闪现，粉色幻雾，尾巴甩出光刃。"),
                sketch(4, "百炼宠物-玄龟P-019动画提示词", "百炼宠物,动画提示词,玄龟,水系",
                        TEMPLATE + "\n玄龟动作：缩壳硬抗，水幕护盾，缓慢推进撞开对手。"),
                sketch(5, "百炼宠物-雪狼P-022动画提示词", "百炼宠物,动画提示词,雪狼,冰系",
                        TEMPLATE + "\n雪狼动作：跃起扑击，冰锥追踪，落地炸开霜环。"));
        return SimilarityScorer.applyDistinctLines(list);
    }

    @Test
    void identicalDocsScoreNearOne() {
        SimilarityScorer.Sketch a = sketch(10, "ExpHub 部署流程", "exphub,部署,systemd",
                "# 部署流程\n执行 bash deploy.sh 并轮询 /health 确认启动成功。\n回滚时用 git reset 到上一个 tag。");
        SimilarityScorer.Sketch b = sketch(11, "ExpHub 部署流程", "exphub,部署,systemd",
                "# 部署流程\n执行 bash deploy.sh 并轮询 /health 确认启动成功。\n回滚时用 git reset 到上一个 tag。");
        SimilarityScorer.Score s = SimilarityScorer.score(a, b);
        assertTrue(s.total() >= 0.95, "同标题同标签同正文应当接近 1，实际 " + s.total());
        assertEquals(1.0, s.content(), 0.001, "正文行完全一致");
        assertTrue(s.total() <= 1.0, "分数不得越界");
    }

    @Test
    void templateSiblingsStayLow() {
        List<SimilarityScorer.Sketch> family = templateFamily();
        SimilarityScorer.Score s = SimilarityScorer.score(family.get(0), family.get(1));
        // 关键断言：模板行被剔除后，白虎与野猪的正文一行不重 —— 这才是它们该有的样子
        assertEquals(0, s.sharedLines(), "模板行剔除后不应有共享行");
        assertTrue(s.content() < 0.10, "同模板兄弟的正文重合应接近 0，实际 " + s.content());
        assertTrue(s.lineMode(), "行数够用时应走整行口径");
        assertTrue(s.total() < SimilarityService.DEFAULT_THRESHOLD,
                "同模板兄弟不该过默认阈值，否则队列会被模板族占满，实际 " + s.total());
    }

    @Test
    void realCandidateBeatsTemplateSibling() {
        // 「待办：新增 X」与「实现 X」：字面完全不重，但标题+标签指向同一件事
        SimilarityScorer.Sketch todo = sketch(20, "待开发：新增 MCP 工具 list_recent_memories",
                "expHub,MCP,list_recent_memories,待办",
                "目标是给忆阁加一个枚举最近记忆的入口，先定参数：pageSize、cursor，不支持按关键词搜。");
        SimilarityScorer.Sketch done = sketch(21, "实现 MCP 工具 list_recent_memories",
                "expHub,MCP,list_recent_memories,已上线",
                "已实现并部署，线上验证分页游标可用，返回 20 条，下一页 cursor 正常。");

        SimilarityScorer.Score real = SimilarityScorer.score(todo, done);
        List<SimilarityScorer.Sketch> family = templateFamily();
        SimilarityScorer.Score sibling = SimilarityScorer.score(family.get(0), family.get(1));

        assertTrue(real.total() > sibling.total(),
                "真候选（同主题不同记录）必须排在模板兄弟之前：" + real.total() + " vs " + sibling.total());
        assertEquals(0, real.sharedLines(), "字面不重也应当能被识别 —— 靠的是标题与标签");
    }

    @Test
    void paragraphOnlyFallsBackToGram() {
        String p1 = "这一段是长段落说明，没有换行也没有列表，讲的是同一套部署要点，"
                + "包括构建、上传、重启服务、健康检查、日志观察和回滚步骤，全部写在一行里。";
        String p2 = "这一段同样是长段落说明，讲的也是部署要点，包含构建上传重启与健康检查，"
                + "只是措辞稍有不同，没有换行，因此拿不到可用的行边界。";
        SimilarityScorer.Sketch a = sketch(30, "部署要点一", "部署,要点", p1);
        SimilarityScorer.Sketch b = sketch(31, "部署要点二", "部署,要点", p2);
        SimilarityScorer.Score s = SimilarityScorer.score(a, b);
        assertFalse(s.lineMode(), "行数不足时必须回退 2-gram 口径");
        assertTrue(s.reason().contains("回退 2-gram"), "回执必须自证走了哪条路：" + s.reason());
        assertTrue(s.total() >= 0 && s.total() <= 1);
    }

    @Test
    void emptyAndTinyInputsAreSafe() {
        SimilarityScorer.Sketch empty = SimilarityScorer.Sketch.of(40L, null, null, null, null, null, null);
        SimilarityScorer.Sketch tiny = SimilarityScorer.Sketch.of(41L, "", "", "", "", null, "短");
        SimilarityScorer.Score s = SimilarityScorer.score(empty, tiny);
        assertTrue(s.total() >= 0 && s.total() <= 1, "空输入不得算出越界分数");
        assertTrue(SimilarityScorer.titleGrams(null).isEmpty());
        assertTrue(SimilarityScorer.lines(null).isEmpty());
        assertTrue(SimilarityScorer.tokens(null).isEmpty());
    }

    @Test
    void scoreIsSymmetric() {
        SimilarityScorer.Sketch a = sketch(50, "回金算 P1 计划", "回金算,排期", "# 计划\n先做仓库模块，再做拆解工单。");
        SimilarityScorer.Sketch b = sketch(51, "回金算 P1 排期", "回金算,排期,工单", "# 计划\n先做拆解工单，仓库模块随后。");
        double ab = SimilarityScorer.score(a, b).total();
        double ba = SimilarityScorer.score(b, a).total();
        assertEquals(ab, ba, 1e-9, "相似度必须对称，否则同一对换个顺序分数就变");
    }

    @Test
    void distinctLinesDropTemplateButKeepDistinctive() {
        List<SimilarityScorer.Sketch> list = templateFamily();
        SimilarityScorer.Sketch first = list.get(0);
        assertTrue(first.distinctLines().size() < first.lines().size(),
                "模板行应当被剔除（原始 " + first.lines().size() + " 行）");
        assertTrue(first.lineModeReliable(), "剩下的行仍够用来判重");
        // 模板行本身出现在 5 篇里，必须被剔掉
        String templateLine = SimilarityScorer.stripLinePrefix(
                "本提示词用于生成宠物战斗动画，请按下面的参数逐项填写后使用。");
        assertFalse(first.distinctLines().contains(templateLine), "模板句不该留在去模板行集里");
    }

    @Test
    void linePrefixIsStrippedSoHeadingLevelsMatch() {
        assertEquals(SimilarityScorer.stripLinePrefix("## 环境信息"),
                SimilarityScorer.stripLinePrefix("### 环境信息"));
        assertEquals("环境信息", SimilarityScorer.stripLinePrefix("- ## 环境信息"));
        assertEquals("第三步启动服务", SimilarityScorer.stripLinePrefix("3. 第三步启动服务"));
        // 太短的行不参与（噪声）
        assertTrue(SimilarityScorer.lines("## 短\n好").isEmpty());
    }

    @Test
    void tokensSplitOnCommaAndDropPlaceholders() {
        Set<String> t = SimilarityScorer.tokens("exphub, 部署，systemd 无 -");
        assertEquals(Set.of("exphub", "部署", "systemd"), t);
    }

    @Test
    void sameProjectAndCategoryGetSmallBonusOnly() {
        SimilarityScorer.Sketch a = SimilarityScorer.Sketch.of(60L, "甲文档标题足够长", "tagA",
                "", "分类A", 7L, "# 标题\n第一行内容写得足够长便于比较。\n第二行内容也不同。");
        SimilarityScorer.Sketch b = SimilarityScorer.Sketch.of(61L, "乙文档标题足够长", "tagB",
                "", "分类B", 7L, "# 标题\n另外一段完全不同的内容写在这里。\n再一行。");
        SimilarityScorer.Sketch c = SimilarityScorer.Sketch.of(62L, "乙文档标题足够长", "tagB",
                "", "分类B", 8L, "# 标题\n另外一段完全不同的内容写在这里。\n再一行。");
        double sameScope = SimilarityScorer.score(a, b).total();
        double crossScope = SimilarityScorer.score(a, c).total();
        assertTrue(sameScope > crossScope, "同项目/同分类应略有加分");
        assertTrue(sameScope - crossScope <= 0.05, "加分必须微弱，不能把无关文档顶过阈值");
    }

    @Test
    void reasonTextIsHumanReadable() {
        List<SimilarityScorer.Sketch> family = templateFamily();
        SimilarityScorer.Score s = SimilarityScorer.score(family.get(0), family.get(1));
        String r = s.reason();
        assertTrue(r.contains("标签交集"), r);
        assertTrue(r.contains("标题重合"), r);
        assertTrue(r.contains("模板行已剔除"), "行口径必须说明模板行已剔除，否则人看不懂为什么 0 行重：" + r);
        Map<String, Integer> df = SimilarityScorer.lineDf(family);
        assertFalse(df.isEmpty());
    }
}
