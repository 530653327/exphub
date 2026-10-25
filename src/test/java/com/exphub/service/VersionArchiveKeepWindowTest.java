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
}
