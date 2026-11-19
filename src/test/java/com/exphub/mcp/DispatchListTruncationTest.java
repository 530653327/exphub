package com.exphub.mcp;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * `dispatch_list` 的截断自证（2026-11-19）。
 *
 * <p>背景：`dispatch_list` 只有 `limit`（上限 50）、**没有 offset**。原来的回执只印
 * 「📋 50 张单」，读的人会以为那就是全部 —— 与后台列表「用展示条数冒充总数」是同一个病。
 *
 * <p>这里只钉三件事：
 * <ol>
 *   <li>没有差额时一个字都不许印（凭空的「可能还有更多」会让人去翻一个不存在的下一页）</li>
 *   <li>有差额时数字必须能对上：显示 N / 共 M / 另有 M−N</li>
 *   <li>必须明说「没有 offset」，并给出正确出口（收窄条件 / 调大 limit 及上限）</li>
 * </ol>
 */
class DispatchListTruncationTest {

    @Test
    @DisplayName("没差额不提示：看到的就是全部，不许让人去找下一页")
    void noGapMeansNoHint() {
        assertEquals("", ExpHubTools.dispatchMoreHint(3, 3L, 20));
        assertEquals("", ExpHubTools.dispatchMoreHint(20, 12L, 20), "总数比展示数还少（并发删除）时也不能印负数差额");
    }

    @Test
    @DisplayName("满 limit 时印真实差额，且三个数字互相对得上")
    void gapPrintsConsistentNumbers() {
        String hint = ExpHubTools.dispatchMoreHint(20, 57L, 20);

        assertTrue(hint.contains("本次显示 20 张"), "没印显示条数：" + hint);
        assertTrue(hint.contains("共 57 张"), "没印真实总数：" + hint);
        assertTrue(hint.contains("另有 37 张未显示"), "差额算错（57-20=37）：" + hint);
        assertTrue(hint.contains("limit=20"), "没回显调用方传的 limit：" + hint);
    }

    @Test
    @DisplayName("必须说清「没有 offset」，并给出两条真出口 + 上限")
    void hintMustSayNoOffsetAndGiveExits() {
        String hint = ExpHubTools.dispatchMoreHint(50, 120L, 50);

        assertTrue(hint.contains("没有 offset"), "没说明本工具不能翻页，下一个 AI 会一直调大 limit：" + hint);
        assertTrue(hint.contains("status") && hint.contains("orderNo"), "没给收窄条件：" + hint);
        assertTrue(hint.contains("上限 50"), "没写 limit 上限：" + hint);
        assertFalse(hint.contains("下一页 offset"), "不许给出并不存在的翻页参数");
    }

    @Test
    @DisplayName("limit 小于总数时差额按实际显示数算，不是按 50 硬减")
    void gapUsesShownCountNotHardcodedLimit() {
        // 调用方传 limit=5、库里 9 张：差额是 4，不是 45
        String hint = ExpHubTools.dispatchMoreHint(5, 9L, 5);
        assertTrue(hint.contains("另有 4 张未显示"), "差额没按实际展示条数算：" + hint);
    }
}
