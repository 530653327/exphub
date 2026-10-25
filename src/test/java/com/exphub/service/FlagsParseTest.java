package com.exphub.service;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * set_flags：标记解析（#631 A1 的第三件，2026-10-25）。
 *
 * 为什么这些要单测：白名单过滤错了的后果是「标记看起来打上了，实际被静默丢弃」，
 * 而标记的用途正是让后续 AI 判断「这条结论存疑」——静默丢失等于骗人。
 */
class FlagsParseTest {

    @Test
    void nullAndBlankYieldEmpty() {
        assertTrue(DocService.parseFlags(null).isEmpty());
        assertTrue(DocService.parseFlags("   ").isEmpty());
        assertTrue(DocService.parseFlags(",,,").isEmpty());
    }

    @Test
    void whitelistValuesPassThrough() {
        assertEquals(List.of("needs_review"), DocService.parseFlags("needs_review"));
        assertEquals(List.of("duplicate", "stale"), DocService.parseFlags("duplicate,stale"));
    }

    @Test
    void caseInsensitiveAndTrimmed() {
        assertEquals(List.of("needs_review"), DocService.parseFlags("  NEEDS_REVIEW  "));
    }

    @Test
    void chineseAliasesMapToCanonical() {
        assertEquals(List.of("needs_review"), DocService.parseFlags("待复核"));
        assertEquals(List.of("duplicate"), DocService.parseFlags("疑似重复"));
        assertEquals(List.of("stale"), DocService.parseFlags("过期"));
        assertEquals(List.of("low_quality"), DocService.parseFlags("低质"));
    }

    @Test
    void mixedSeparatorsAndDedup() {
        // 中英文逗号、分号、空格都要能当分隔符；重复值只保留一个
        assertEquals(List.of("needs_review", "duplicate"),
                DocService.parseFlags("needs_review，重复；needs_review"));
    }

    @Test
    void unknownTokensAreDroppedButReported() {
        assertTrue(DocService.parseFlags("bogus").isEmpty());
        // 关键：丢弃的 token 必须能被单独报出来，否则调用方永远不知道自己写错了
        assertEquals(List.of("bogus"), DocService.rejectedFlagTokens("bogus,needs_review"));
        assertTrue(DocService.rejectedFlagTokens("needs_review,重复").isEmpty());
    }

    @Test
    void labelTextIsHumanReadable() {
        assertEquals("待复核、疑似重复", DocService.flagLabelText("needs_review,duplicate"));
        assertEquals("", DocService.flagLabelText(null));
        assertEquals("", DocService.flagLabelText(""));
        // 非法值不产生标签，也不会抛异常
        assertEquals("", DocService.flagLabelText("bogus"));
    }

    @Test
    void whitelistCoversEveryCanonicalValue() {
        // flags 列注释里写的 5 个值必须都在白名单内，避免文档与实现漂移
        for (String f : List.of("needs_review", "duplicate", "superseded", "stale", "low_quality")) {
            assertTrue(DocService.FLAG_WHITELIST.contains(f), "白名单缺少 " + f);
            assertEquals(List.of(f), DocService.parseFlags(f));
            assertTrue(!DocService.flagLabel(f).isEmpty());
        }
    }
}
