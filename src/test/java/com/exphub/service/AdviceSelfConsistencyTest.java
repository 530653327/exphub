package com.exphub.service;

import org.junit.jupiter.api.Test;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 回执文案的<b>自洽性</b>守卫（2026-10-26 02:42 越哥实测发现，第八版阻塞项）。
 *
 * <p>他抓到的原文：
 * <pre>
 *   两侧行数 120 : 5（约 24:1）…… 而正文 0 行重合（没有任何逐行内容证据，
 *   短的那篇可能整篇被长的那篇包住，也可能只是碰巧短）
 * </pre>
 * <b>「0 行重合」和「可能整篇被包住」互斥</b> —— 真被包住，重合度该接近 100%。
 * 后果不是措辞问题：#542 标题明说是「补充」页，回执却要求「打开长篇确认短篇已全在里面」，
 * 这个确认必然失败，于是只能判 distinct —— <b>一个本该合并的补充页被永久钉在队列里。</b>
 *
 * <p>这类错误的共同点是「<b>证据</b>与<b>结论</b>不同源」：证据是 0 行重合（内容信号），
 * 结论却来自 24:1（结构信号），而结构信号<b>不能</b>推出「谁属于谁」。
 * 所以本类钉三件事：
 * <ol>
 *   <li>0 行重合时不许出现任何「被包住 / 已包含」的肯定表述</li>
 *   <li>文案里报的独有行数必须和入参算出来的一致（数字不许自己长）</li>
 *   <li>30% 这类边界占比不许用「仅」这种悄悄缩小分量的词</li>
 * </ol>
 */
class AdviceSelfConsistencyTest {

    /** 0 行重合的各种输入组合：都不许说「被包住」 */
    @Test
    void zeroSharedNeverClaimsContainment() {
        int[][] sizes = {{120, 5}, {5, 120}, {54, 7}, {40, 44}, {1, 300}};
        String[][] titles = {
                {null, null},
                {"史越 个人档案", "史越个人档案补充：家庭、饮食偏好与饮茶习惯"},
                {"10-23午餐实录", "10-24 全天饮食实录"},
                {"中央平原地图1提示词", "中央平原地图2提示词"},
                {"甲记录", "乙记录"},
        };
        for (int[] sz : sizes) {
            for (String[] t : titles) {
                String d = SimilarityScorer.advice(SimilarityScorer.Kind.SERIES, 0, 0.0, 0,
                        sz[0], sz[1], t[0], t[1]);
                assertFalse(d.contains("可能整篇被"),
                        "0 行重合 + 行数 " + sz[0] + ":" + sz[1] + " 又出现「可能整篇被包住」—— 自相矛盾：" + d);
                assertFalse(d.contains("确认母档已含"),
                        "0 行重合时「母档已含」不可能成立，不能让人去确认：" + d);
                assertTrue(d.contains("distinct"), "每条都得给拿不准时的落点：" + d);
            }
        }
    }

    /** 文案里报的独有行数必须等于 参与行数 − 共享行数，不许自己长 */
    @Test
    void reportedUniqueCountsMatchInputs() {
        int[][] cases = {{39, 195, 47}, {21, 42, 41}, {4, 120, 7}, {12, 27, 15}};
        Pattern p = Pattern.compile("A 独有 (\\d+) 行（占 (\\d+)%）");
        for (int[] c : cases) {
            int shared = c[0], a = c[1], b = c[2];
            String d = SimilarityScorer.advice(SimilarityScorer.Kind.DUPLICATE, shared, 0.8, 25, a, b);
            Matcher m = p.matcher(d);
            assertTrue(m.find(), "①层必须把两侧独有行数摆出来：" + d);
            assertEquals(a - shared, Integer.parseInt(m.group(1)),
                    "报的 A 独有行数必须等于 " + a + "−" + shared + "：" + d);
            int pct = (int) Math.round((a - shared) * 100.0 / a);
            assertEquals(pct, Integer.parseInt(m.group(2)), "占比也要对得上：" + d);
        }
    }

    /** 边界占比（30%）不许用「仅」偷偷缩小分量 */
    @Test
    void boundaryShareDoesNotUseMinimizingWording() {
        // #63↔#68 的真实形状：共享 42 行；A 198 行剩 156 行独有（79%），B 60 行剩 18 行独有（30%）
        // 30% 正好压在「真重复上限 MAX_OWN_SHARE_FOR_TRUE_DUP = 0.30」上，是全库唯一的边界样本
        String d = SimilarityScorer.advice(SimilarityScorer.Kind.DUPLICATE, 42, 0.70, 25, 198, 60);
        assertFalse(d.contains("独有仅"),
                "「仅 18 行（占 30%）」读起来像无所谓，实际是边界样本 —— 不许用「仅」：" + d);
        assertTrue(d.contains("B 侧独有 18 行"),
                "要报出真实的独有行数（18 行），别四舍五入或漏报：" + d);
        assertTrue(d.contains("边界"),
                "30% 贴近真重复上限，必须提醒打开两边确认：" + d);
    }

    /**
     * 标点不许叠：`evidence + "。"` 是唯一收尾处，任何分支自己再带句号就是「。。」。
     *
     * <p>2026-10-26 11:3x 阁主实测 ①层 4 对全部印出「说明不了谁属于谁。。」——
     * 单个分支漏删句号，扫一次就在清单里出现 4 次。文案缺陷不该靠人眼逐条读。
     */
    @Test
    void adviceMustNotDoublePunctuate() {
        SimilarityScorer.Kind[] kinds = {SimilarityScorer.Kind.DUPLICATE, SimilarityScorer.Kind.SERIES,
                SimilarityScorer.Kind.UNKNOWN, SimilarityScorer.Kind.PROGRESS};
        int[][] shapes = {{40, 195, 47}, {42, 198, 60}, {0, 120, 5}, {0, 100, 90}, {33, 33, 33}, {0, 3, 2}};
        String[][] titles = {{"地图设计", "地图3提示词"}, {"方案", "方案 v3"}, {"", ""}, {"补充说明", "主文档"}};
        for (SimilarityScorer.Kind k : kinds) {
            for (int[] s : shapes) {
                for (String[] t : titles) {
                    String d = SimilarityScorer.advice(k, s[0], 0.7, 25, s[1], s[2], t[0], t[1]);
                    assertFalse(d.contains("。。"), "标点叠了（" + k + " " + s[0] + "/" + s[1] + "/" + s[2]
                            + " " + t[0] + "↔" + t[1] + "）：" + d);
                    assertFalse(d.contains("，，") || d.contains("；；") || d.contains("：："),
                            "标点叠了：" + d);
                }
            }
        }
    }
}
