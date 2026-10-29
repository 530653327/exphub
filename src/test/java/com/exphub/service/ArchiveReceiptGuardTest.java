package com.exphub.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 归档回执不许谎报（#746）。
 *
 * <p>事故经过：归档 #677 时回执写「主表删除 0 / 期望 2 行」，而库里主表确实从 12 条降到 10 条、
 * 归档表也收到了 v1/v2 —— <b>数据全对，是回执在撒谎</b>。根因是照抄了
 * {@code deleteBatchIds()} 的返回值（实测该返回值不可信）。
 *
 * <p>为什么这行字危害大：它和 #714 那 71 条重复副本同源 ——
 * 一个是「超时被当成没执行」，一个是「回执说没删」，<b>两者都诱导人再跑一遍</b>，
 * 而重试正是给归档表添副本的路径。所以这条不能靠「我会记得」，要用断言钉住。
 */
class ArchiveReceiptGuardTest {

    private static String read(String rel) throws Exception {
        Path p = Path.of(rel);
        assertTrue(Files.exists(p), "找不到源文件：" + p.toAbsolutePath());
        return new String(Files.readAllBytes(p), StandardCharsets.UTF_8);
    }

    @Test
    @DisplayName("deleteBatchIds 的返回值不许被接住 —— 它就是那句「删除 0 行」的来源")
    void returnedValueNotUsed() throws Exception {
        String code = read("src/main/java/com/exphub/service/VersionArchiveService.java");
        assertFalse(code.matches("(?s).*=\\s*versionMapper\\.deleteBatchIds\\(.*"),
                "又把 deleteBatchIds 的返回值接住了。它不可信（#677 删了 2 行返回 0），"
                        + "接住它就必然写进回执，而执行者据此重试会造重复副本");
    }

    @Test
    @DisplayName("主表减少量来自事务内回读，并且出现在结果对象里")
    void removedComesFromReadBack() throws Exception {
        String code = read("src/main/java/com/exphub/service/VersionArchiveService.java");
        assertTrue(code.contains("int before = countVersions(id);"), "删前没数");
        assertTrue(code.contains("int after = countVersions(id);"), "删后没数");
        assertTrue(code.contains("int removed = before - after;"), "差值才是真减少量");

        // record 组件：removed 必须能传到回执层，否则服务层算得再准也没人看得见
        var comps = Arrays.stream(VersionArchiveService.DocArchiveResult.class.getRecordComponents())
                .map(c -> c.getName()).toList();
        assertTrue(comps.contains("removed"), "DocArchiveResult 缺 removed：" + comps);
    }

    @Test
    @DisplayName("回执要给出真实减少量，且异常行必须写明「不要重试」")
    void receiptSaysReducedAndForbidsRetry() throws Exception {
        String code = read("src/main/java/com/exphub/mcp/ExpHubTools.java");
        assertTrue(code.contains("主表本次减少") && code.contains("事务回读"),
                "回执合计行没给真实减少量（或没标明口径）");
        assertTrue(code.contains("主表减少"), "表格缺「主表减少」列");
        assertFalse(code.contains("主表删除 "),
                "回执里不该再出现「主表删除 N 行」这种措辞 —— 它读起来就是「没删掉」");

        String svc = read("src/main/java/com/exphub/service/VersionArchiveService.java");
        assertTrue(svc.contains("不要为这行数字重试"),
                "残留提示必须写明禁止重试：执行者看到「没删干净」的默认反应就是再跑一遍");
    }
}
