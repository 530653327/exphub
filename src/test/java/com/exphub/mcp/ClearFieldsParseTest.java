package com.exphub.mcp;

import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A2（#631）：clearFields 的解析与安全边界。
 *
 * <p>起因：MCP 侧「字段没传」与「显式传 null」反序列化后在 Java 里都是 null，
 * 分不开，所以清空必须由调用方显式声明（clearFields），不能靠 null 表达。
 * 这里锁两件事：白名单外的字段（尤其 content/title）绝不能被清空；写法上的小差异不能影响结果。
 */
class ClearFieldsParseTest {

    @Test
    void parsesSingleField() {
        assertEquals(Set.of("tags"), ExpHubTools.parseClearFields("tags"));
    }

    @Test
    void parsesMultipleWithSpacesAndChineseComma() {
        assertEquals(Set.of("tags", "aliases", "summary"),
                ExpHubTools.parseClearFields("tags, aliases，summary"));
    }

    @Test
    void rejectsContentAndTitle() {
        // 这是本测试最重要的一条：清空正文/标题没有合理语义，
        // 万一调用方写了也必须被静默过滤掉，不能让它真的变成空文档
        Set<String> out = ExpHubTools.parseClearFields("content,title,tags");
        assertEquals(Set.of("tags"), out);
        assertFalse(out.contains("content"));
        assertFalse(out.contains("title"));
    }

    @Test
    void blankGivesEmptySet() {
        assertTrue(ExpHubTools.parseClearFields(null).isEmpty());
        assertTrue(ExpHubTools.parseClearFields("").isEmpty());
        assertTrue(ExpHubTools.parseClearFields("   ").isEmpty());
    }

    @Test
    void deduplicates() {
        assertEquals(Set.of("tags"), ExpHubTools.parseClearFields("tags,TAGS,tags"));
    }

    // ===== abbreviate：patch 报错里回显片段用 =====

    @Test
    void abbreviateEscapesNewlines() {
        // 回显里的换行必须转成字面 \n，否则返回内容会被拆成多行、破坏排版
        String out = ExpHubTools.abbreviate("第一行\n第二行", 100);
        assertEquals("第一行\\n第二行", out);
    }

    @Test
    void abbreviateCutsLongText() {
        String out = ExpHubTools.abbreviate("一".repeat(200), 100);
        assertEquals(101, out.length(), "100 字 + 省略号");
        assertTrue(out.endsWith("…"));
    }

    @Test
    void abbreviateHandlesNull() {
        assertEquals("", ExpHubTools.abbreviate(null, 10));
    }
}
