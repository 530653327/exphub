package com.exphub.service;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 回执建议的判法守卫（2026-10-26 01:35 越哥实测发现，第三次同类问题）。
 *
 * <p>他在线上看到的原文案是：
 * <pre>
 *   ① 疑似字面重复 → 「优先判 duplicate」
 *   ② 同话题演进链 → 「大概率该判 superseded」
 * </pre>
 * 而实际命中的两对是：
 * <pre>
 *   #77 灵狐 ↔ #83 神龙（0 行重合，两只不同的宠物）
 *   #82 神牛 ↔ #88 雪狼（0 行重合）
 *   #159 ↔ #160  前端开发专家 vs 系统测试工程师（21 行重合、覆盖 51%、两侧各有约一半独有）
 * </pre>
 * 照原文案判下去 = 用一只宠物取代另一只、用一个角色取代另一个角色。
 * 根因不是分数算错，而是**建议写得比证据更肯定**：证据只说明「重合多 / 标题像」，
 * 文案却直接下了「判 duplicate / 判 superseded」的结论。
 *
 * <p>本类钉死两件事：
 * <ol>
 *   <li>①层必须按「两侧独有行数」分出真重复 / 母集-子集 / 同模板实例，只对真重复说可以判 duplicate</li>
 *   <li>正文 0 行重合时，不得出现「大概率」这类把猜测说成结论的措辞</li>
 *   <li>{@code Score.diagnosis()} 与 {@code SimilarityScorer.advice(...)} 必须是同一段文字
 *       （工具回执与单测断言共用，防止两处文案漂移）</li>
 * </ol>
 */
class AdviceShapeTest {

    private static SimilarityScorer.Score scorePair(String a, String b) {
        SimilarityScorer.Sketch sa = SimilarityScorer.Sketch.of(1L, "甲", "t", "", "开发", null, "how_to", a);
        SimilarityScorer.Sketch sb = SimilarityScorer.Sketch.of(2L, "乙", "t", "", "开发", null, "how_to", b);
        List<SimilarityScorer.Sketch> scope = SimilarityScorer.applyDistinctLines(List.of(sa, sb));
        return SimilarityScorer.score(scope.get(0), scope.get(1));
    }

    /** 9 行共享 + 各自 1 行独有：整篇复制 */
    private static final String TRUE_DUP_A = String.join("\n",
            "共享第一条：部署脚本必须先在预发环境完整跑通一次再上生产环境",
            "共享第二条：回滚脚本要和执行脚本放在同一目录，版本号写在文件名里",
            "共享第三条：执行前先备份数据库，备份文件名带上时间戳和分支名",
            "共享第四条：日志目录统一放到固定路径，方便出问题的时候快速定位",
            "共享第五条：发布完成后必须验证健康检查接口返回正常才算结束",
            "共享第六条：配置文件里的敏感项统一走环境变量，禁止写进代码仓库",
            "共享第七条：失败时不要重试超过三次，超过就停下来等人来看日志",
            "共享第八条：每一步都要留下操作人，便于事后回溯是谁执行的发布",
            "共享第九条：发布窗口避开业务高峰，遇到故障优先回滚而不是现场修",
            "甲独有的一条：本次补充了灰度流量的切分比例说明");

    private static final String TRUE_DUP_B = String.join("\n",
            "共享第一条：部署脚本必须先在预发环境完整跑通一次再上生产环境",
            "共享第二条：回滚脚本要和执行脚本放在同一目录，版本号写在文件名里",
            "共享第三条：执行前先备份数据库，备份文件名带上时间戳和分支名",
            "共享第四条：日志目录统一放到固定路径，方便出问题的时候快速定位",
            "共享第五条：发布完成后必须验证健康检查接口返回正常才算结束",
            "共享第六条：配置文件里的敏感项统一走环境变量，禁止写进代码仓库",
            "共享第七条：失败时不要重试超过三次，超过就停下来等人来看日志",
            "共享第八条：每一步都要留下操作人，便于事后回溯是谁执行的发布",
            "共享第九条：发布窗口避开业务高峰，遇到故障优先回滚而不是现场修",
            "乙独有的一条：本次补充了夜间窗口的时间安排说明");

    /** 母集 13 行（8 独有 + 5 共享），子集只有那 5 行：整篇被包含 */
    private static final String SUBSET_MOTHER = String.join("\n",
            "母集独有的第一段：整体架构分三层，接入层负责鉴权与限流",
            "母集独有的第二段：业务层只做编排，不直接操作数据库表",
            "母集独有的第三段：数据层统一走仓储接口，方便后面换存储",
            "母集独有的第四段：异步任务单独部署，避免拖慢主链路响应",
            "母集独有的第五段：配置中心负责下发开关，本地不保存开关状态",
            "母集独有的第六段：监控指标按接口维度上报，便于定位慢接口",
            "母集独有的第七段：灰度策略按用户维度分流，保证体验一致",
            "母集独有的第八段：日志需要带上链路标识，跨服务才能串起来",
            "共享段落一：接口统一返回结构体，错误码集中在一处维护",
            "共享段落二：分页参数统一命名，避免每个接口各写一套",
            "共享段落三：敏感字段在网关层统一脱敏，业务层不感知",
            "共享段落四：所有写操作必须幂等，重复请求不能产生脏数据",
            "共享段落五：外部调用必须设置超时，禁止无限等待下游响应");

    private static final String SUBSET_CHILD = String.join("\n",
            "共享段落一：接口统一返回结构体，错误码集中在一处维护",
            "共享段落二：分页参数统一命名，避免每个接口各写一套",
            "共享段落三：敏感字段在网关层统一脱敏，业务层不感知",
            "共享段落四：所有写操作必须幂等，重复请求不能产生脏数据",
            "共享段落五：外部调用必须设置超时，禁止无限等待下游响应");

    /** 同模板实例：4 行骨架共享，两侧各有 5 行写各自的实体（对应 #159↔#160 那种） */
    private static final String PARALLEL_A = String.join("\n",
            "共享骨架一：角色定位需要先写清楚，再写它负责的具体工作范围",
            "共享骨架二：交付物清单必须逐条列出，避免验收的时候互相扯皮",
            "共享骨架三：与其他角色的边界要写明，重叠部分指定唯一负责人",
            "共享骨架四：验收标准必须可量化，不能写「表现良好」这种话",
            "甲实体独有：前端开发专家负责页面实现，包括组件拆分与状态管理",
            "甲实体独有：前端开发专家需要关注首屏加载时间与交互流畅度",
            "甲实体独有：前端开发专家的交付物包含组件库与页面测试用例",
            "甲实体独有：前端开发专家与设计侧的边界是视觉稿的还原程度",
            "甲实体独有：前端开发专家的验收标准是核心页面首屏不超过两秒");

    private static final String PARALLEL_B = String.join("\n",
            "共享骨架一：角色定位需要先写清楚，再写它负责的具体工作范围",
            "共享骨架二：交付物清单必须逐条列出，避免验收的时候互相扯皮",
            "共享骨架三：与其他角色的边界要写明，重叠部分指定唯一负责人",
            "共享骨架四：验收标准必须可量化，不能写「表现良好」这种话",
            "乙实体独有：系统测试工程师负责用例设计，覆盖功能与回归场景",
            "乙实体独有：系统测试工程师需要维护自动化用例并关注执行时长",
            "乙实体独有：系统测试工程师的交付物包含测试报告与缺陷清单",
            "乙实体独有：系统测试工程师与开发侧的边界是缺陷的判定标准",
            "乙实体独有：系统测试工程师的验收标准是主干用例全部通过执行");

    /** 0 行重合：只有标签/标题像（对应 #77 灵狐 ↔ #83 神龙） */
    private static final String SERIES_A = String.join("\n",
            "灵狐的攻击动作是六帧，起手抬爪，收尾甩尾，帧间隔保持一致",
            "灵狐的待机动作四帧循环，耳朵轻微抖动，尾巴缓慢摆动",
            "灵狐的死亡动作六帧，身体倒向一侧并逐渐淡出画面");

    private static final String SERIES_B = String.join("\n",
            "神龙的攻击动作是八帧，起手张口喷火，收尾收翼落地",
            "神龙的待机动作四帧循环，双翼缓慢扇动，身体上下浮动",
            "神龙的施法动作八帧，蓄力时全身发光然后向前推出火球");

    @Test
    void trueCopySaysDuplicateIsOk() {
        SimilarityScorer.Score s = scorePair(TRUE_DUP_A, TRUE_DUP_B);
        assertEquals(SimilarityScorer.Kind.DUPLICATE, s.kind(), s.reason());
        assertEquals(SimilarityScorer.Shape.TRUE_DUP, s.shape(),
                "两侧独有都很少 → 整篇复制。独有 A=" + s.uniqueA() + " B=" + s.uniqueB());
        String d = s.diagnosis();
        assertTrue(d.contains("可以判 duplicate"), "真复制必须给出肯定建议：" + d);
    }

    @Test
    void subsetMustNotBeAdvisedAsDuplicate() {
        SimilarityScorer.Score s = scorePair(SUBSET_MOTHER, SUBSET_CHILD);
        assertEquals(SimilarityScorer.Kind.DUPLICATE, s.kind(), s.reason());
        assertEquals(SimilarityScorer.Shape.SUBSET, s.shape(),
                "子集那侧独有行应该很少。独有 A=" + s.uniqueA() + " B=" + s.uniqueB());
        String d = s.diagnosis();
        assertTrue(d.contains("母集-子集"), "要点名形态：" + d);
        assertTrue(d.contains("别判 duplicate"), "母集-子集不能建议判 duplicate：" + d);
        assertFalse(d.contains("可以判 duplicate"), d);
        // ⚠️ 2026-10-26 02:2x 越哥实测：上一版把 duplicate 挡住了，却写「先看是不是 superseded」——
        // superseded 对母集-子集正是会丢内容的那个方向（子页独有行被埋在「已被取代」后面）。
        assertTrue(d.contains("也别判 superseded"),
                "母集-子集两个方向都不能判：只挡 duplicate、放过 superseded 等于同一个坑换个出口：" + d);
        assertFalse(d.contains("看是不是 superseded"),
                "不许再写「先看是不是 superseded」——superseded 会掩埋子页独有行：" + d);
        assertTrue(d.contains("并回母档"),
                "必须给出正确动作：先把子页剩余细节并回母档：" + d);
        assertTrue(d.contains("distinct"), "并不了时的落点必须是 distinct：" + d);
    }

    /**
     * ②层也要判形态：行数差悬殊就是母集-子集的强信号（2026-10-26 02:2x 越哥实测指出）。
     *
     * <p>反例：#514（120 行）↔ #542（5 行）落在②层，回执只写「只有标签/标题像…拿不准判 distinct」——
     * 结论碰巧对，<b>理由却是错的</b>：它是典型的 24:1 母集-子集，而形态检测当时只在①层做。
     * 建议对了、理由错了，下次换个数据就会把错的理由用错地方。
     */
    @Test
    void seriesLayerAlsoNamesSubsetByLineRatio() {
        String d = SimilarityScorer.advice(SimilarityScorer.Kind.SERIES, 0, 0.0, 0, 120, 5);
        assertTrue(d.contains("母集-子集"), "行数 120:5 要认出母集-子集，不能只说「拿不准」：" + d);
        assertTrue(d.contains("24:1"), "要把行数比摆出来，人才判得快：" + d);
        assertTrue(d.contains("也别判 superseded"), "②层同样不许放过 superseded 这个方向：" + d);
        assertTrue(d.contains("并回母档"), "要给出正确动作：" + d);
        assertFalse(d.contains("大概率"), d);
    }

    /** 行数差不悬殊时不许乱扣「母集-子集」的帽子 */
    @Test
    void balancedLineCountsAreNotCalledSubset() {
        String d = SimilarityScorer.advice(SimilarityScorer.Kind.SERIES, 0, 0.0, 0, 20, 12);
        assertFalse(d.contains("母集-子集（"), "20:12 不是母集-子集，别乱扣帽子：" + d);
        assertTrue(d.contains("distinct"), d);
    }

    @Test
    void sameTemplateInstancesMustNotBeAdvisedAsDuplicate() {
        // 反例：#159 前端开发专家 ↔ #160 系统测试工程师（21 行重合、覆盖 51%、平均 38 字）
        SimilarityScorer.Score s = scorePair(PARALLEL_A, PARALLEL_B);
        assertEquals(SimilarityScorer.Kind.DUPLICATE, s.kind(),
                "覆盖率达标，仍在①层 —— 形态是靠独有行数分的，不是靠覆盖率：" + s.reason());
        assertEquals(SimilarityScorer.Shape.PARALLEL, s.shape(),
                "两侧各有约一半独有行。独有 A=" + s.uniqueA() + " B=" + s.uniqueB());
        String d = s.diagnosis();
        assertTrue(d.contains("别判 duplicate"), "同模板实例不能建议判 duplicate：" + d);
        assertFalse(d.contains("可以判 duplicate"), d);
        assertTrue(d.contains("取代另一份"), "要说清判错的后果：" + d);
    }

    @Test
    void noContentEvidenceMustNotSoundCertain() {
        // 反例：#77 灵狐 ↔ #83 神龙（0 行重合），原回执写「大概率该判 superseded」
        SimilarityScorer.Score s = scorePair(SERIES_A, SERIES_B);
        assertEquals(SimilarityScorer.Kind.SERIES, s.kind(), s.reason());
        assertEquals(0, s.sharedLines(), "正文确实一行不重");
        String d = s.diagnosis();
        assertFalse(d.contains("大概率"),
                "没有内容证据时，不许把猜测写成「大概率」—— 判 superseded 等于用其中一份取代另一份：" + d);
        assertFalse(d.contains("该判 superseded"), d);
        assertTrue(d.contains("distinct"), "要给出拿不准时的默认动作：" + d);
    }

    @Test
    void diagnosisAndAdviceAreTheSameText() {
        // 单一来源：工具回执（ExpHubTools.kindLabel 转发）与单测断言用的是同一段文字。
        // 之前两处各写一份，文档改了工具没改，线上建议与实际判法相反。
        for (String[] pair : new String[][]{
                {TRUE_DUP_A, TRUE_DUP_B}, {SUBSET_MOTHER, SUBSET_CHILD},
                {PARALLEL_A, PARALLEL_B}, {SERIES_A, SERIES_B}}) {
            SimilarityScorer.Score s = scorePair(pair[0], pair[1]);
            assertEquals(SimilarityScorer.advice(s.kind(), s.sharedLines(), s.lineCoverage(),
                            s.sharedAvgLen(), s.linesA(), s.linesB()),
                    s.diagnosis(), "diagnosis 必须转发 advice，不许自己再写一份");
        }
    }
}
