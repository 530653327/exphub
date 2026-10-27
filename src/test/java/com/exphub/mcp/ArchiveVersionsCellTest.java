package com.exphub.mcp;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 归档回执里「归档版本」这一格怎么写（#714 第 3 项：逐篇结果要能核对，而不是只看总数）。
 *
 * <p>口径：连续区间压成 {@code v60–v84（25 版）}，零散的列前 8 个。
 * 不压的话 #654 一篇就 25 个版本号，表格会横到看不清。
 */
class ArchiveVersionsCellTest {

    @Test
    @DisplayName("空清单写 -，不留空白格")
    void empty() {
        assertEquals("-", ExpHubTools.archiveVersionsCell(null));
        assertEquals("-", ExpHubTools.archiveVersionsCell(List.of()));
    }

    @Test
    @DisplayName("单版直接写 vN")
    void single() {
        assertEquals("v9", ExpHubTools.archiveVersionsCell(List.of(9)));
    }

    @Test
    @DisplayName("连续区间压成 v60–v84（25 版）")
    void continuous() {
        List<Integer> vs = IntStream.rangeClosed(60, 84).boxed().toList();
        assertEquals("v60–v84（25 版）", ExpHubTools.archiveVersionsCell(vs));
    }

    @Test
    @DisplayName("零散版本列前 8 个并报总数（这里 3 个就全列）")
    void sparse() {
        assertEquals("v1、v3、v7", ExpHubTools.archiveVersionsCell(List.of(1, 3, 7)));
    }

    @Test
    @DisplayName("零散且超过 8 个：只列前 8 个 + 总数，避免刷屏")
    void sparseTruncated() {
        List<Integer> vs = List.of(1, 3, 5, 7, 9, 11, 13, 15, 17, 19);
        String cell = ExpHubTools.archiveVersionsCell(vs);
        assertTrue(cell.startsWith("v1、v3、v5、v7、v9、v11、v13、v15"), cell);
        assertTrue(cell.contains("等 10 版"), cell);
    }

    @Test
    @DisplayName("缺号不算连续：v1,v2,v4 不能被当成区间")
    void gapIsNotContinuous() {
        assertEquals("v1、v2、v4", ExpHubTools.archiveVersionsCell(List.of(1, 2, 4)));
    }
}
