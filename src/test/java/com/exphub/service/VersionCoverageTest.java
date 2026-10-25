package com.exphub.service;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * 导读「条数」与「版本号区间」必须来自同一批数据（2026-10-26 03:1x 阁主实测 #101）。
 *
 * <p>原导读写「共 18 条（v1 ~ v20）：主表 10 条、冷存档 8 条」：
 * 条数是 COUNT 出来的、上界是 {@code docs.version - 1} 推的，<b>两个来源拼成一句话</b>。
 * 实情是主表 v9,10,11,12,14,16,17,18,19,20（缺 v13、v15），归档 v1~v8 连续 ——
 * 两个数各自都对，但缺的那 2 个版本号没人说，读者只会怀疑归档搬丢了数据。
 */
class VersionCoverageTest {

    /** #101 的真实形状：归档 v1~v8 连续 + 主表缺 v13/v15 */
    @Test
    void realShapeOf101() {
        List<Integer> main = List.of(9, 10, 11, 12, 14, 16, 17, 18, 19, 20);
        List<Integer> arc = List.of(1, 2, 3, 4, 5, 6, 7, 8);
        VersionArchiveService.VersionCoverage cov = VersionArchiveService.VersionCoverage.of(main, arc);

        assertEquals(18, cov.totalRows(), "条数 = 主表 10 + 冷存档 8");
        assertEquals(20, cov.maxVersion(), "上界取实际存在的最大版本号，不用 docs.version 推");
        assertEquals(List.of(13, 15), cov.missing(), "缺号必须算出来，不能只报总数");
        assertTrue(cov.duplicated().isEmpty(), "这份数据没有重号");
        assertTrue(cov.missingNote().contains("v13") && cov.missingNote().contains("v15"),
                "缺号要逐个点名：" + cov.missingNote());
        assertTrue(cov.missingNote().contains("没有副本"), cov.missingNote());
    }

    /** #608 的形状：同一版本号存了两份（归档 v2 有 2 行）→ 条数会比版本号个数多 */
    @Test
    void duplicatedVersionIsNotReportedAsMissing() {
        List<Integer> arc = List.of(1, 2, 2, 3);
        List<Integer> main = List.of(4, 5, 6, 7, 8, 9, 10, 11, 12, 13);
        VersionArchiveService.VersionCoverage cov = VersionArchiveService.VersionCoverage.of(main, arc);

        assertEquals(14, cov.totalRows(), "重号按行数算，条数会多于版本号个数");
        assertEquals(13, cov.maxVersion());
        assertTrue(cov.missing().isEmpty(), "v2 有两份，2 不等于缺：" + cov.missing());
        assertEquals(List.of(2), cov.duplicated(), "重号要单独报出来");
        assertTrue(cov.duplicateNote().contains("v2"), cov.duplicateNote());
    }

    /** 连续无缺口时不许乱报警 */
    @Test
    void contiguousRangeHasNoNotes() {
        var cov = VersionArchiveService.VersionCoverage.of(List.of(4, 5, 6), List.of(1, 2, 3));
        assertEquals(6, cov.totalRows());
        assertEquals(6, cov.maxVersion());
        assertTrue(cov.missing().isEmpty());
        assertEquals("", cov.missingNote(), "没缺口就别说话 —— 空串表示不渲染这一行");
        assertEquals("", cov.duplicateNote());
    }

    @Test
    void emptyInputIsZero() {
        var cov = VersionArchiveService.VersionCoverage.of(List.of(), List.of());
        assertEquals(0, cov.totalRows());
        assertEquals(0, cov.maxVersion());
        assertEquals("", cov.missingNote());
    }

    /**
     * 源级守卫：导读不许再拿 {@code doc.getVersion() - 1} 当区间上界。
     *
     * <p>这个写法正是「两个来源」的病根：条数是数出来的，上界是推出来的，一缺号就对不上。
     */
    @Test
    void guideMustNotDeriveUpperBoundFromCurrentVersion() throws Exception {
        String src = Files.readString(
                Path.of("src/main/java/com/exphub/mcp/ExpHubTools.java"), StandardCharsets.UTF_8);
        assertFalse(src.contains("getVersion() - 1"),
                "导读的版本号上界必须来自 VersionCoverage.maxVersion()，不能用 docs.version-1 推");
        assertTrue(src.contains("VersionCoverage.of("), "导读要用 VersionCoverage 做唯一事实来源");
        assertTrue(src.contains("missingNote()"), "有缺号时必须主动说明，不能只报总数");
    }
}
