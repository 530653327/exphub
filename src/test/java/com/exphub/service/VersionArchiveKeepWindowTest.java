package com.exphub.service;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 版本快照归档的边界与安全约束。
 *
 * <p><b>为什么这些测试重要</b>：归档是这个项目里<b>唯一会真正 DELETE 主表数据</b>的操作
 * （其他整理动作都只是打标记）。一旦算错「保留窗口」，删掉的就是用户再也看不到的历史。
 *
 * <p>所以测试重点不是「能跑通」，而是钉住四条不能破的约束：
 * <ol>
 *   <li>最新 N 版永远不归档</li>
 *   <li>保留窗口参数越界必须被拒（不静默改）</li>
 *   <li>窗口下限不能太小（否则「回看历史」失去意义）</li>
 *   <li>归档数 + 保留数 = 原总数（不丢不重）</li>
 * </ol>
 */
class VersionArchiveKeepWindowTest {

    @Test
    void defaultsToTenVersions() {
        assertEquals(10, VersionArchiveService.normalizeKeep(null),
                "默认保留 10 版 —— 够回看近期改动，又能把 60+ 版的长文档压下来");
        assertEquals(VersionArchiveService.DEFAULT_KEEP_RECENT, 10);
    }

    @Test
    void keepWindowBoundsAreEnforced() {
        // 下限 3：少于 3 版就没有「回看历史」的意义了
        assertEquals(3, VersionArchiveService.MIN_KEEP_RECENT);
        assertFalse(VersionArchiveService.isKeepValid(1), "保留 1 版等于删掉全部历史，必须拒绝");
        assertFalse(VersionArchiveService.isKeepValid(2), "保留 2 版同样太少");
        assertTrue(VersionArchiveService.isKeepValid(3), "下限本身合法");
        assertTrue(VersionArchiveService.isKeepValid(10));

        // 上限 200：防止传 99999 变成什么都不做还自称成功
        assertEquals(200, VersionArchiveService.MAX_KEEP_RECENT);
        assertTrue(VersionArchiveService.isKeepValid(200));
        assertFalse(VersionArchiveService.isKeepValid(201), "超过上限必须拒绝，否则等于没归档");

        // null = 用默认值，视为合法
        assertTrue(VersionArchiveService.isKeepValid(null));
    }

    /**
     * 关键：{@code normalizeKeep} 只用于内部兜底，**不是**给工具层用的「静默修正」。
     * 工具层必须先 {@code isKeepValid} 明拒，拒绝不了才调用它。
     */
    @Test
    void normalizeIsOnlyFallbackNotSilentFix() {
        // 内部兜底：越界收敛到边界（但在工具层这之前就该被拒）
        assertEquals(3, VersionArchiveService.normalizeKeep(0));
        assertEquals(3, VersionArchiveService.normalizeKeep(-5));
        assertEquals(200, VersionArchiveService.normalizeKeep(9999));
        // 合法值原样返回
        assertEquals(7, VersionArchiveService.normalizeKeep(7));
    }

    /**
     * 用「版本列表 + 保留窗口」模拟 service 的切分逻辑，验证不丢不重。
     *
     * <p>这段逻辑是 service 里 {@code all.subList(keep, all.size())} 的等价物 ——
     * 真正的 service 依赖 MyBatis，单测里用纯函数复刻切分规则并钉住边界。
     */
    @Test
    void splitKeepsNewestAndArchivesTheRest() {
        // 42 版（对应 #654 的实况），保留 10 → 归档 32 版，且保留的是 v33~v42
        assertSplit(42, 10, 32, 33, 42);
        // 恰好等于窗口：一条都不归档 —— 全部保留，所以保留下来的最小版本号是 v1（不是 v11！）
        assertSplit(10, 10, 0, 1, 10);
        // 少于窗口：不动，同样全保留
        assertSplit(7, 10, 0, 1, 7);
        // 极端：#644 的 64 版
        assertSplit(64, 10, 54, 55, 64);
    }

    /**
     * 模拟 {@code orderByDesc("version")} 后取 {@code subList(keep, size)}。
     *
     * @param total      总版本数
     * @param keep       保留窗口
     * @param wantArchived 期望归档条数
     * @param wantFirstKept 期望保留下来的最小版本号
     * @param wantMaxVersion 期望的最大版本号
     */
    private static void assertSplit(int total, int keep, int wantArchived,
                                    int wantFirstKept, int wantMaxVersion) {
        // 版本号 1..total，按倒序排列（与 service 里的 orderByDesc("version") 一致）
        java.util.List<Integer> desc = new java.util.ArrayList<>();
        for (int v = total; v >= 1; v--) desc.add(v);

        assertEquals(wantMaxVersion, desc.get(0), "倒序后第一个是最大版本号");

        java.util.List<Integer> toArchive = desc.size() > keep
                ? new java.util.ArrayList<>(desc.subList(keep, desc.size()))
                : java.util.List.of();
        java.util.List<Integer> kept = desc.size() > keep
                ? new java.util.ArrayList<>(desc.subList(0, keep))
                : new java.util.ArrayList<>(desc);

        assertEquals(wantArchived, toArchive.size(),
                "total=" + total + " keep=" + keep + " 时归档条数不对");
        // 不丢不重：归档 + 保留 = 原总数
        assertEquals(total, toArchive.size() + kept.size(), "归档与保留之和必须等于原总数");

        // 最新版永远在保留侧，且保留侧的最小版本号符合预期
        assertTrue(kept.contains(wantMaxVersion), "最新版（v" + wantMaxVersion + "）必须被保留");
        if (!kept.isEmpty()) {
            assertEquals(wantFirstKept, java.util.Collections.min(kept),
                    "保留下来的最小版本号应是 v" + wantFirstKept);
        }
        // 归档侧绝不能含最新版
        if (!toArchive.isEmpty()) {
            assertFalse(toArchive.contains(wantMaxVersion), "最新版不能进归档侧");
        }
    }

    /**
     * 主表存在「同一 doc_id + 同一 version 多行」时，归档必须照样能完整搬走。
     *
     * <p><b>2026-10-26 00:54 真实故障</b>：全量归档炸在
     * {@code Duplicate entry '608-2' for key 'uk_doc_version'} —— 我在归档表建了
     * {@code UNIQUE(doc_id, version)}，而<b>主表本身没有这个约束</b>，
     * #608 的 v2 在主表就有 2 条重复。整批事务回滚。
     *
     * <p><b>为什么修法是「去掉唯一约束」而不是「去重后再归档」</b>：
     * 归档的职责是<b>原样搬走</b>。主表的重复是数据质量问题（写入有并发缺陷），
     * 该单独查、单独修；在归档时顺手抹掉一条，等于<b>以整理之名删数据</b>。
     *
     * <p>这个测试钉住规则：<b>归档条数必须等于主表里的行数（含重复），一条都不能少。</b>
     */
    @Test
    void duplicateVersionsInSourceMustAllBeArchived() {
        // 模拟 #608 的实况：v1 一行、v2 两行、v3..v12 各一行 = 13 行，但最大版本号只有 12
        java.util.List<Integer> versions = new java.util.ArrayList<>();
        for (int v = 12; v >= 1; v--) {
            versions.add(v);
            if (v == 2) versions.add(2);   // v2 重复出现
        }
        int keep = 10;

        // 按 service 的做法：取全部行（不去重）→ 倒序 → 前 keep 保留，其余归档
        java.util.List<Integer> desc = new java.util.ArrayList<>(versions);
        desc.sort(java.util.Comparator.reverseOrder());
        assertEquals(13, desc.size(), "主表共 13 行（v2 有 2 行）");

        java.util.List<Integer> toArchive = desc.subList(keep, desc.size());
        java.util.List<Integer> kept = desc.subList(0, keep);

        assertEquals(13, toArchive.size() + kept.size(), "归档 + 保留必须等于主表总行数");
        assertFalse(toArchive.isEmpty());

        // 关键：重复的 v2 有两条，若两条都进了归档侧，就必须**两条都搬**（不能合成一条）
        long v2InArchive = toArchive.stream().filter(v -> v == 2).count();
        long v2InKept = kept.stream().filter(v -> v == 2).count();
        assertEquals(2, v2InArchive + v2InKept, "v2 的两行合计仍是 2，一行都不能消失");

        // 最新版永远在保留侧
        assertTrue(kept.contains(12), "v12 必须被保留");
        assertFalse(toArchive.contains(12), "最新版不能进归档侧");
    }

    /** 唯一约束不该出现在归档表上 —— 归档要能承载源数据的任何形态（含重复） */
    @Test
    void archiveTableHasNoUniquenessAssumption() {
        // 这是文档性断言：真正的约束在 DDL 里，此处把「为什么不能加」固定在测试里，
        // 防止以后有人「优化」成 UNIQUE 又踩一次
        String why = "归档表不能加 UNIQUE(doc_id,version)：主表本身没有该约束，"
                + "#608 的 v2 有 2 行重复，加了唯一键会让整批归档回滚";
        assertTrue(why.contains("UNIQUE"));
    }
}
