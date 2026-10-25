package com.exphub.service;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 聚合查询「没有行」的解析守卫（2026-10-26 03:0x 越哥读 #668 时暴露）。
 *
 * <p>`SELECT MAX(version) FROM doc_versions_archive WHERE doc_id=?` 在**没有归档行**时
 * 仍然返回一行，而那一行整行是 NULL；MyBatis-Plus 的 `selectMaps` 把它映射成
 * `List` 里的一个 **null 元素**。于是 `maps.get(0).get("v")` 抛
 * 「Cannot invoke "java.util.Map.get(Object)" because the return value of
 * "java.util.List.get(int)" is null」。
 *
 * <p>后果不是某一条读不了：**所有从未归档过的文档 detail 全打不开**（全库绝大多数），
 * 只是因为验收一直用 654（它有 59 条冷存档）才没暴露。
 */
class ArchivedSummaryNullRowTest {

    @Test
    void emptyListMeansZero() {
        assertEquals(0, VersionArchiveService.maxVersionOf(List.of()));
        assertEquals(0, VersionArchiveService.maxVersionOf(null));
    }

    /** 关键：`[null]` —— isEmpty() 挡不住，必须逐元素判空 */
    @Test
    void listWithNullRowMeansZero() {
        List<Map<String, Object>> maps = new ArrayList<>();
        maps.add(null);
        assertEquals(0, VersionArchiveService.maxVersionOf(maps),
                "聚合查询无行时 MyBatis 返回 [null]，必须当 0 处理，不能 get(0).get()");
    }

    @Test
    void nullValueOrNonNumberMeansZero() {
        List<Map<String, Object>> maps = new ArrayList<>();
        Map<String, Object> row = new HashMap<>();
        row.put("v", null);
        maps.add(row);
        assertEquals(0, VersionArchiveService.maxVersionOf(maps));

        row.put("v", "12");
        assertEquals(0, VersionArchiveService.maxVersionOf(maps), "值不是数字就当 0，别强转崩");

        row.put("v", 73L);
        assertEquals(73, VersionArchiveService.maxVersionOf(maps));
    }

    /** 源级守卫：没有归档行时不该再发那条聚合查询 */
    @Test
    void skipAggregateWhenNoArchivedRows() throws Exception {
        String src = java.nio.file.Files.readString(
                java.nio.file.Path.of("src/main/java/com/exphub/service/VersionArchiveService.java"),
                java.nio.charset.StandardCharsets.UTF_8);
        assertTrue(src.contains("if (rows == 0) return new ArchivedSummary(0, 0);"),
                "没有归档行就早返回，别发 MAX(version) 聚合查询");
    }
}
