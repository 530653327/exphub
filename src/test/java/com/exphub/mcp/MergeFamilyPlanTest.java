package com.exphub.mcp;

import com.exphub.service.SimilarityScorer;
import com.exphub.service.SimilarityService;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 同族多对 merge 的串行依赖（2026-10-26 10:4x 阁主第 1 条 —— 唯一「会让人按错数据动手」的问题）。
 *
 * <p>现场：①层 4 对 merge 全部指向母档 #63（子页 #65/#66/#67/#68），回执把 4 对并列呈现，
 * 看起来像 4 个独立操作。但**每并完一对 #63 的行数就变**，下一对的「独有 8 行」「覆盖 83%」
 * 立刻过期 —— 照旧数字并第二对，并进去的可能已经不是那 8 行了。
 *
 * <p>所以回执必须：① 明说这是串行依赖、不是一个一个独立操作；② 给出执行顺序；
 * ③ 明说后几对的数字是「并之前」的，执行时要重读母档再核。
 */
class MergeFamilyPlanTest {

    private static SimilarityService.Row mergeRow(long mother, long kid, int kidLines) {
        return new SimilarityService.Row(mother, "母档", kid, "子页", 0.54, "r", null,
                SimilarityScorer.Kind.DUPLICATE, 40, 0.83, "advice", mother, kid, kidLines);
    }

    @Test
    void mustStateSerialDependencyAndGiveOrder() {
        List<SimilarityService.Row> pairs = new ArrayList<>(List.of(
                mergeRow(63, 66, 12),
                mergeRow(63, 68, 18),
                mergeRow(63, 65, 8),
                mergeRow(63, 67, 8)));

        String plan = ExpHubTools.mergeFamilyPlan(63L, pairs);

        assertTrue(plan.contains("串行依赖"), "要明说是串行依赖：" + plan);
        assertTrue(plan.contains("不是 4 个独立操作"), "不许让 4 对看起来像 4 个独立操作：" + plan);
        assertTrue(plan.contains("母档 #63"), "要点名母档：" + plan);
        // 顺序：子页独有行从少到多；同为 8 行时按 ID 升序
        int p1 = plan.indexOf("第1步 #65"), p2 = plan.indexOf("第2步 #67");
        int p3 = plan.indexOf("第3步 #66"), p4 = plan.indexOf("第4步 #68");
        assertTrue(p1 > 0 && p2 > p1 && p3 > p2 && p4 > p3, "顺序应按子页独有行从少到多：" + plan);
        assertTrue(plan.contains("独有 18 行"), "每一步要摆出子页独有行数（执行时要拿它核）：" + plan);
    }

    @Test
    void mustWarnThatNumbersExpireAfterEachMerge() {
        String plan = ExpHubTools.mergeFamilyPlan(63L, new ArrayList<>(List.of(
                mergeRow(63, 65, 8), mergeRow(63, 66, 12))));

        assertTrue(plan.contains("并之前"), "要说清「独有 N 行 / 覆盖 X%」是并之前算的：" + plan);
        assertTrue(plan.contains("一次只并一对"), "必须要求一次只并一对：" + plan);
        assertTrue(plan.contains("打开 #63") && plan.contains("对不上就停下来"),
                "要给出「并完重读母档 → 核下一对 → 对不上就停」的动作：" + plan);
        assertTrue(plan.contains("略去"),
                "被略去的同族交叉对要能被回头核对（阁主原话：动手前已无从核对）");
    }
}
