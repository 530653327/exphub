package com.exphub.service;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 限流守卫：**分层必须优先于 limit**（2026-10-26 02:1x 越哥实测发现）。
 *
 * <p>他看到的现场：
 * <pre>
 *   ①层统计 6 对，清单里只有 4 条（0.54、0.54、0.54、0.45）
 *   ②层统计 15 对，只展示 10 条
 *   ④层却展示 36 条，最低 0.47 都进来了
 * </pre>
 * 于是判断「limit 按总分取前 50，分层只决定显示顺序」，也就是最该判的①层项可能根本不出现。
 *
 * <p>实测根因不是按分数截断，而是 <b>per-doc 限流</b>（默认「单篇最多 2 条候选」）：
 * #63 在①层命中 4 个子页（65/66/67/68），第 3、4 条被限流砍掉，
 * 空出的名额被低层填满 —— <b>观感与「按分数截断」完全一样，但病在别处</b>。
 * 所以修法是：①层豁免 per-doc，并且每层把「挤掉了哪几对」如实写进回执。
 */
class SelectionLimitTest {

    private static SimilarityService.Row row(SimilarityScorer.Kind kind, long a, long b, double score) {
        return new SimilarityService.Row(a, "A" + a, b, "B" + b, score, "r", null, kind, 0, 0.0, "advice");
    }

    /** 模拟 scan：按层+分数排好序的输入 */
    private static List<SimilarityService.Row> sorted() {
        List<SimilarityService.Row> all = new ArrayList<>();
        // ①层 4 对，全部挂在 #63 上（真实现场：#63↔65/66/67/68）
        all.add(row(SimilarityScorer.Kind.DUPLICATE, 63, 67, 0.54));
        all.add(row(SimilarityScorer.Kind.DUPLICATE, 63, 66, 0.54));
        all.add(row(SimilarityScorer.Kind.DUPLICATE, 63, 65, 0.53));
        all.add(row(SimilarityScorer.Kind.DUPLICATE, 63, 68, 0.45));
        // ②层 4 对，也都挂在 #74 上
        all.add(row(SimilarityScorer.Kind.SERIES, 74, 85, 0.62));
        all.add(row(SimilarityScorer.Kind.SERIES, 74, 79, 0.61));
        all.add(row(SimilarityScorer.Kind.SERIES, 74, 81, 0.60));
        all.add(row(SimilarityScorer.Kind.SERIES, 77, 83, 0.50));
        // ④层 3 对
        all.add(row(SimilarityScorer.Kind.PROGRESS, 470, 477, 0.55));
        all.add(row(SimilarityScorer.Kind.PROGRESS, 464, 471, 0.54));
        all.add(row(SimilarityScorer.Kind.PROGRESS, 478, 479, 0.53));
        all.sort(SimilarityService.Row.order());
        return all;
    }

    @Test
    void tierOneIsNeverSqueezedOutByPerDocLimit() {
        List<SimilarityService.Row> all = sorted();
        SimilarityService.Selection sel = SimilarityService.select(all, 50, 2);

        SimilarityService.TierDetail dup = sel.detail().get(SimilarityScorer.Kind.DUPLICATE);
        assertEquals(4, dup.above(), "①层统计 4 对");
        assertEquals(4, dup.shown(), "①层必须 4 对全部展示 —— 豁免 per-doc，否则 #63 的 4 个子页只剩 2 条");
        assertEquals(0, dup.droppedPerDoc(), "①层不该被 per-doc 挤掉任何一对");

        // ①层 4 条必须排在②层、④层任何一条之前（分层优先）
        for (int i = 0; i < 4; i++) {
            assertEquals(SimilarityScorer.Kind.DUPLICATE, sel.rows().get(i).kind(),
                    "第 " + i + " 条应是①层，实际 " + sel.rows().get(i).kind()
                            + " —— 分层必须先于 limit");
        }
    }

    /**
     * per-doc 只在①层豁免，②层恢复（2026-10-26 02:51 越哥实测）。
     *
     * <p>上一版把豁免扩散到②层，结果 6 个名额被 #63 家族占满 ——
     * ②层展示的 14 条里 6 条是 C(4,2) 完全图，结论全是 distinct。
     * ①层豁免的理由是「一篇母档命中多个子页该一起判」，②层那批是<b>同一家族内部两两比较</b>，
     * 而且 #63 与四个子页在①层已逐对给过结论，摆在②层纯占位。
     */
    @Test
    void perDocIsExemptOnlyInTierOne() {
        List<SimilarityService.Row> all = new ArrayList<>();
        all.add(row(SimilarityScorer.Kind.DUPLICATE, 63, 67, 0.54));
        all.add(row(SimilarityScorer.Kind.SERIES, 67, 68, 0.50));
        all.add(row(SimilarityScorer.Kind.SERIES, 66, 68, 0.49));
        all.add(row(SimilarityScorer.Kind.SERIES, 65, 68, 0.48));
        all.sort(SimilarityService.Row.order());

        // limit 宽松 → 严格限流先挤掉 1 条，但**名额有余就补回来**（2026-10-26 03:01 越哥要求）
        SimilarityService.Selection sel = SimilarityService.select(all, 50, 2);
        SimilarityService.TierDetail dup = sel.detail().get(SimilarityScorer.Kind.DUPLICATE);
        SimilarityService.TierDetail ser = sel.detail().get(SimilarityScorer.Kind.SERIES);
        assertEquals(1, dup.shown(), "①层照旧全部展示");
        assertEquals(3, ser.shown(), "名额有余，#65↔#68 被补回：三条都展示");
        assertEquals(0, ser.droppedPerDoc(), "补回后不该再算「被挤掉」");
        assertEquals(1, sel.filledPerDoc(), "要如实报「1 条原本被 per-doc 挤掉、已补回」");
    }

    /**
     * 名额真用完时，per-doc 挤掉的仍然要如实报（补位只在有余量时发生）。
     *
     * <p>否则「补位」会把 per-doc 变成摆设：一旦有空位就全放进来，
     * 那就等于没限流 —— 限流的本意是防同族刷屏，只在「宁可空着」时才不生效。
     */
    @Test
    void perDocDropStillReportedWhenListIsFull() {
        List<SimilarityService.Row> all = new ArrayList<>();
        all.add(row(SimilarityScorer.Kind.SERIES, 74, 85, 0.62));
        all.add(row(SimilarityScorer.Kind.SERIES, 74, 79, 0.61));
        all.add(row(SimilarityScorer.Kind.SERIES, 74, 81, 0.60));   // per-doc 挤掉
        all.add(row(SimilarityScorer.Kind.SERIES, 77, 83, 0.59));
        all.add(row(SimilarityScorer.Kind.SERIES, 78, 84, 0.58));
        all.sort(SimilarityService.Row.order());

        SimilarityService.Selection sel = SimilarityService.select(all, 4, 2);   // 只有 4 个名额
        SimilarityService.TierDetail ser = sel.detail().get(SimilarityScorer.Kind.SERIES);
        assertEquals(4, ser.shown(), "4 个名额全用满");
        assertEquals(1, ser.droppedPerDoc(), "名额已满，#74↔#81 只能如实报被挤掉");
        assertEquals(0, sel.filledPerDoc(), "没余量就不补位");
        assertTrue(ser.droppedSamples().contains("#74↔#81"), "要点名：" + ser.droppedSamples());
    }

    /**
     * ③层（进度/日志）默认只展示几条样本（2026-10-26 02:51 越哥定）。
     *
     * <p>现场：50 条里③层占 30 条 = 60%，而它是「一般不用判」的一层，
     * 把名额从①②层抢走了。默认抽 5 条，其余如实报「另有 N 条」。
     */
    @Test
    void progressTierIsSampledByDefaultAndCanBeFullyShown() {
        List<SimilarityService.Row> all = new ArrayList<>();
        all.add(row(SimilarityScorer.Kind.DUPLICATE, 1, 2, 0.9));
        for (int i = 0; i < 8; i++) {
            all.add(row(SimilarityScorer.Kind.PROGRESS, 100L + i, 200L + i, 0.6 - i * 0.01));
        }
        all.sort(SimilarityService.Row.order());

        SimilarityService.Selection byDefault = SimilarityService.select(all, 50, 2);
        SimilarityService.TierDetail p = byDefault.detail().get(SimilarityScorer.Kind.PROGRESS);
        assertEquals(8, p.above(), "统计仍按全库 8 条");
        assertEquals(SimilarityService.DEFAULT_PROGRESS_SAMPLE, p.shown(), "默认只展示 5 条样本");
        assertEquals(3, p.droppedBySample(), "剩下的如实报「另有 3 条抽样省略」");
        assertEquals(0, p.droppedByLimit(), "不是被 limit 截断，别混为一谈");

        SimilarityService.Selection allShown = SimilarityService.select(all, 50, 2, 0);
        assertEquals(8, allShown.detail().get(SimilarityScorer.Kind.PROGRESS).shown(),
                "progressSample=0 表示不限，要能全看");
    }

    /**
     * ③层（none）不抢名额 —— 名额紧张时它一条都不该进（2026-10-26 03:15 越哥定）。
     *
     * <p>他的判据：`progressSample=0` 时③层 33 条占 66% 名额，而②层 4 对被 per-doc 挤掉；
     * 「none 意味着不用判，占名额等于浪费；抢位会造成新的顺序复杂性和更多状态」。
     * 所以规则不是「补位去抢③层的名额」，而是**③层排到最后，名额有余才给**。
     */
    @Test
    void progressTierYieldsSeatsToJudgableTiers() {
        List<SimilarityService.Row> all = new ArrayList<>();
        // 3 条可判（②层）+ 5 条进度类，limit 只够 3 条，progressSample=0（不限）
        all.add(row(SimilarityScorer.Kind.SERIES, 11, 12, 0.60));
        all.add(row(SimilarityScorer.Kind.SERIES, 13, 14, 0.59));
        all.add(row(SimilarityScorer.Kind.SERIES, 15, 16, 0.58));
        for (int i = 0; i < 5; i++) {
            all.add(row(SimilarityScorer.Kind.PROGRESS, 300L + i, 400L + i, 0.57 - i * 0.01));
        }
        all.sort(SimilarityService.Row.order());

        SimilarityService.Selection sel = SimilarityService.select(all, 3, 2, 0);
        assertEquals(3, sel.rows().size());
        assertTrue(sel.rows().stream().noneMatch(r -> r.kind() == SimilarityScorer.Kind.PROGRESS),
                "名额只够 3 条时，进度类一条都不该进：\" + sel.rows()");
        assertEquals(5, sel.detail().get(SimilarityScorer.Kind.PROGRESS).shown() == 0
                ? 5 : 5, "占位断言见下两行");

        SimilarityService.TierDetail p = sel.detail().get(SimilarityScorer.Kind.PROGRESS);
        assertEquals(0, p.shown(), "③层展示 0 条");
        assertEquals(5, p.droppedByLimit(), "5 条全部记「被 limit 截断」，如实报");
    }

    /**
     * ①层家族的内部交叉对不再展示（2026-10-26 08:5x 阁主实测，两件事同一个病）。
     *
     * <p>① 跨层冲突：①层 4 对 merge 各自说「清掉这一份」（#65/#66/#67/#68 各被点名一次），
     * ②层那 6 条 `#65↔#66 …` 却全给 distinct「谁也别清」——同一个 #66 两边说法相反。
     * <p>② 补位把上一轮修法抵消：per-doc 豁免收回①层后，补位又把这 6 条补回②层（占 40% 名额、纯占位）。
     */
    @Test
    void familyCrossPairsAreOmittedNotRefilled() {
        List<SimilarityService.Row> all = new ArrayList<>();
        // ①层：#63 与四个子页逐对给过结论（4 对 merge）
        all.add(row(SimilarityScorer.Kind.DUPLICATE, 63, 65, 0.54));
        all.add(row(SimilarityScorer.Kind.DUPLICATE, 63, 66, 0.54));
        all.add(row(SimilarityScorer.Kind.DUPLICATE, 63, 67, 0.54));
        all.add(row(SimilarityScorer.Kind.DUPLICATE, 63, 68, 0.53));
        // ②层：子页之间两两比较（纯占位，且与①层的「清掉这一份」打架）
        all.add(row(SimilarityScorer.Kind.SERIES, 65, 66, 0.50));
        all.add(row(SimilarityScorer.Kind.SERIES, 65, 67, 0.50));
        all.add(row(SimilarityScorer.Kind.SERIES, 65, 68, 0.50));
        all.add(row(SimilarityScorer.Kind.SERIES, 66, 67, 0.50));
        all.add(row(SimilarityScorer.Kind.SERIES, 66, 68, 0.50));
        all.add(row(SimilarityScorer.Kind.SERIES, 67, 68, 0.50));
        // ②层：家族成员 vs 家族外 —— 这个有信息量，必须留下
        all.add(row(SimilarityScorer.Kind.SERIES, 65, 100, 0.49));
        all.sort(SimilarityService.Row.order());

        SimilarityService.Selection sel = SimilarityService.select(all, 50, 2);
        SimilarityService.TierDetail ser = sel.detail().get(SimilarityScorer.Kind.SERIES);

        assertEquals(1, ser.shown(), "②层只该留下「家族成员 ↔ 家族外」那一条");
        assertEquals(6, ser.droppedRedundant(), "6 条家族内部交叉对要如实计数并说明，不能悄悄消失");
        assertTrue(ser.hasDrop(), "略去也是「没展示」，不能装作没有");
        assertTrue(sel.rows().stream().anyMatch(r -> r.docB() == 100L),
                "家族外的对不许被一起丢掉：" + sel.rows());
        assertTrue(sel.rows().stream().noneMatch(
                        r -> r.kind() == SimilarityScorer.Kind.SERIES
                                && r.docA() != 63 && r.docB() != 100L),
                "②层不该再出现子页之间的交叉对：" + sel.rows());
    }

    /**
     * 「略去」的键必须是**同一母档家族**，不是「①层已判文档全集」（2026-10-26 10:4x 阁主第 3 条）。
     *
     * <p>现状①层恰好有三个互不相干的家族（#63 族、#159↔#160、#59↔#71），所以「两侧都在集合里」
     * 碰巧等于「同族」。但只要 #159 与 #63 在②层成对，就会被误略 ——
     * 而这两条**从来没在①层判过关系**，略去等于凭空删掉一条有信息量的候选。
     */
    @Test
    void crossFamilyPairsMustNotBeOmitted() {
        List<SimilarityService.Row> all = new ArrayList<>();
        all.add(row(SimilarityScorer.Kind.DUPLICATE, 63, 65, 0.54));    // 家族 A
        all.add(row(SimilarityScorer.Kind.DUPLICATE, 159, 160, 0.53));  // 家族 B（与 A 无关）
        all.add(row(SimilarityScorer.Kind.SERIES, 65, 159, 0.50));      // 跨家族的一对
        all.sort(SimilarityService.Row.order());

        SimilarityService.Selection sel = SimilarityService.select(all, 50, 2);
        SimilarityService.TierDetail ser = sel.detail().get(SimilarityScorer.Kind.SERIES);

        assertEquals(1, ser.shown(), "跨家族的对必须展示：两侧都在①层出现过 ≠ 同族");
        assertEquals(0, ser.droppedRedundant(), "只有同族内部的交叉对才能略去");
        assertTrue(ser.omittedSamples().isEmpty(), "没略去就不许列「已略去」的 ID");
    }

    /** 同族交叉对仍要略去，并且**把被略去的 ID 如实列出来**（阁主：动手前要能回头核对） */
    @Test
    void omittedFamilyPairsMustBeListedById() {
        List<SimilarityService.Row> all = new ArrayList<>();
        all.add(row(SimilarityScorer.Kind.DUPLICATE, 63, 65, 0.54));
        all.add(row(SimilarityScorer.Kind.DUPLICATE, 63, 66, 0.53));
        all.add(row(SimilarityScorer.Kind.SERIES, 65, 66, 0.50));
        all.sort(SimilarityService.Row.order());

        SimilarityService.Selection sel = SimilarityService.select(all, 50, 2);
        SimilarityService.TierDetail ser = sel.detail().get(SimilarityScorer.Kind.SERIES);

        assertEquals(1, ser.droppedRedundant());
        assertEquals(List.of("#65↔#66"), ser.omittedSamples(), "略去的对要点到具体 ID，不能只报个条数");
    }

    /**
     * 母档 / 子页不许取反 —— 取反的后果是「把母档清掉、把子页留下」。
     *
     * <p>用母集-子集的形态：大 195 行、小 47 行，共享 40 行 → 子页独有 7 行。
     * 标题故意**不带编号**：带编号的那种由 {@link #numberedKidMustNotBeMergedIntoUnnumberedMother} 钉住，
     * 两种情况必须分开测，否则「编号排除生效」会被误当成「母档取反」。
     */
    @Test
    void mergeSidesMustNotSwapMotherAndKid() {
        SimilarityScorer.Score s = new SimilarityScorer.Score(0.55, 0.5, 0.5, 0.5,
                40, 195, 47, true, 40.0 / 47, 40, 25, false,
                "前端开发专家", "前端开发专家（旧稿）");
        long[] sides = SimilarityService.mergeSides(s, 63L, 67L);
        assertTrue(sides != null, "母集-子集应给出 merge");
        assertEquals(63L, sides[0], "母档是行数多的那侧");
        assertEquals(67L, sides[1], "子页是行数少的那侧");
        assertEquals(7L, sides[2], "子页独有行 = 47 - 40");

        // 判不了的动作不许硬给方向
        SimilarityScorer.Score other = new SimilarityScorer.Score(0.55, 0.5, 0.5, 0.5,
                0, 100, 90, true, 0.0, 0, 0, false, "地图1", "地图2");
        assertEquals(null, SimilarityService.mergeSides(other, 1L, 2L), "0 行重合 + 并列记录 → 没有 merge 方向");
    }

    /**
     * ①层编号排除（2026-10-26 11:0x 阁主，阻塞项）：
     * 子页带「带字编号」、母档没有编号 → <b>不许</b>判「并进母档、清掉子页」。
     *
     * <p>现场：#63「中央平原地图设计」（195 行，无编号）↔ #65「中央平原地图1提示词」（47 行，带「地图1」），
     * 共享 40 行 → 形态确实是母集-子集，但 #65~#68 是**并列的四个场景提示词**，
     * 逐个并进 #63 再清掉 = 把四个场景压成一份。
     */
    @Test
    void numberedKidMustNotBeMergedIntoUnnumberedMother() {
        SimilarityScorer.Score s = new SimilarityScorer.Score(0.55, 0.5, 0.5, 0.5,
                40, 195, 47, true, 40.0 / 47, 40, 25, false,
                "百炼宠物 - 中央平原地图设计", "百炼宠物 - 中央平原地图1提示词（平原入口）");

        assertEquals(SimilarityScorer.Kind.DUPLICATE, s.kind(), "前置条件：仍属①层母集-子集形态");
        assertEquals(SimilarityScorer.Action.DISTINCT,
                SimilarityScorer.decide(s.kind(), s.sharedLines(), s.linesA(), s.linesB(), s.titleA(), s.titleB()),
                "带编号的是「一套里的第 N 份」，不是母档的从属部分");
        assertEquals(null, SimilarityService.mergeSides(s, 63L, 65L),
                "没有合并方向，就不许渲染「并进母档、清掉子页」");
        assertTrue(s.diagnosis().contains("第 N 份"), "回执必须说清为什么不能并：" + s.diagnosis());
        assertTrue(!s.diagnosis().contains("动作：先把这"), "更不许出现合并动作句：" + s.diagnosis());
    }

    @Test
    void lowerTierDropsAreReportedWithSamples() {
        List<SimilarityService.Row> all = sorted();
        SimilarityService.Selection sel = SimilarityService.select(all, 50, 2);

        SimilarityService.TierDetail ser = sel.detail().get(SimilarityScorer.Kind.SERIES);
        assertEquals(4, ser.above());
        assertEquals(4, ser.shown(), "limit 宽松：per-doc 先挤掉 #74↔#81，随后因名额有余补回 → 4 条全展示");
        assertEquals(1, sel.filledPerDoc(), "补回的条数要自证");
        assertTrue(ser.hasDrop() || sel.filledPerDoc() > 0, "补位也是一种「差点没展示」，要能看出来");

        // 注：真正「被挤掉并点名」的场景由 perDocDropStillReportedWhenListIsFull 覆盖 ——
        // 名额充裕时这些对会被补回，droppedSamples 自然为空，不能在这条里断。
    }

    @Test
    void truncationOnlyHitsTheLastTier() {
        List<SimilarityService.Row> all = sorted();
        SimilarityService.Selection sel = SimilarityService.select(all, 6, 2);

        assertEquals(6, sel.rows().size());
        // 前 4 条是①层全部
        assertEquals(4, sel.detail().get(SimilarityScorer.Kind.DUPLICATE).shown());
        // 剩 2 条给②层，④层整层被 limit 截断（而且是在②层之后）
        SimilarityService.TierDetail prog = sel.detail().get(SimilarityScorer.Kind.PROGRESS);
        assertEquals(3, prog.droppedByLimit(), "④层应被 limit 整层截断");
        assertEquals(0, prog.shown());
    }
}
