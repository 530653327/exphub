package com.exphub.mcp;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * L0-2（#101 S2.2）：total 双口径。
 *
 * <p><b>病</b>：RERANK 档把 {@code ranked.size()} 当 total，而候选集固定上限 200。
 * 回执说「共 60 条」，既对不上一条 {@code count(*)}，也解释不了翻到后面为什么空了
 * （#677 12.6 记的「total 假 60 实 19」就是这个形态）。</p>
 *
 * <p><b>修法</b>：{@code SearchMeta.matchedTotal} 存数据库口径真实匹配数，回执在两者不一致时
 * 明写两个数；{@code Page.total} 与翻页语义**不动**（换 DB 数会翻出空页）。</p>
 */
class TotalScopeNoteTest {

    @Test
    @DisplayName("两个口径一致时不加噪音")
    void sameNumberIsSilent() {
        assertEquals("", ExpHubTools.totalScopeNote(60, 60));
    }

    @Test
    @DisplayName("未计算（STRICT / LIKE / NONE 档）时保持 -1，不输出")
    void unknownIsSilent() {
        assertEquals("", ExpHubTools.totalScopeNote(-1, 50));
    }

    @Test
    @DisplayName("接受数大于匹配数属异常形态，不输出（不能编个解释糊过去）")
    void acceptedGreaterThanMatchedIsSilent() {
        assertEquals("", ExpHubTools.totalScopeNote(19, 60));
    }

    @Test
    @DisplayName("真实匹配远大于接受数时，两个数都要出现，并说明翻页边界")
    void mismatchExplainsBothNumbers() {
        String note = ExpHubTools.totalScopeNote(120, 19);
        assertTrue(note.contains("120"), note);
        assertTrue(note.contains("19"), note);
        assertTrue(note.contains("翻页以接受数为界"), "必须说明翻页用的是哪个数：" + note);
        assertTrue(note.contains("匹配") && note.contains("接受"), note);
    }

    @Test
    @DisplayName("守卫：count 与候选集必须同条件，否则两个数不可比")
    void countUsesSameScopeConditions() throws Exception {
        String src = Files.readString(
                Path.of("src/main/java/com/exphub/service/DocService.java"), StandardCharsets.UTF_8);
        assertTrue(src.contains("countByFulltext(orQuery, apiKey, projectId, tt, statusList,"),
                "count 必须与 searchByFulltext 同参数（尤其 scopeAll / ownerKey / excludeProgress）");
        assertTrue(src.contains("meta.matchedTotal = orTotal"), "计数没回传，回执拿不到真实匹配数");
        assertTrue(src.contains("public long matchedTotal = -1L"), "未计算档必须用 -1 表示，不能默认 0");

        String tools = Files.readString(
                Path.of("src/main/java/com/exphub/mcp/ExpHubTools.java"), StandardCharsets.UTF_8);
        assertTrue(tools.contains("totalScopeNote(searchMeta.matchedTotal, effTotal)"),
                "回执没接上双口径说明，等于白算");
    }
}
