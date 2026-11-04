package com.exphub.mcp;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 批量入口的 ID 解析（#1220 第②⑥条：batch_get 与 resolve_candidate 批量共用一套规则）。
 *
 * <p>为什么统一：同一个「逗号分隔 ID」参数，两处各有各的脾气（一个认 #、一个不认），
 * 下一个调用方必然踩一次。
 */
class ParseIdsTest {

    @Test
    void parsesCommaSeparated() {
        assertEquals(List.of(748L, 714L, 1220L), ExpHubTools.parseIds("748,714,1220", "ids"));
    }

    @Test
    void toleratesHashPrefixSpacesAndFullWidthComma() {
        // 回执里印的是「候选 #51」，调用方多半会连着 # 一起复制
        assertEquals(List.of(51L, 52L, 53L), ExpHubTools.parseIds(" #51， #52 、53 ", "candidateIds"));
    }

    @Test
    void dedupesButKeepsOrder() {
        assertEquals(List.of(9L, 3L, 7L), ExpHubTools.parseIds("9,3,9,7,3", "ids"));
    }

    @Test
    void rejectsNonNumeric() {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> ExpHubTools.parseIds("748,abc", "ids"));
        assertTrue(e.getMessage().contains("非数字项"), "要说清是哪一项坏了，实际=" + e.getMessage());
        assertTrue(e.getMessage().contains("abc"));
    }

    @Test
    void rejectsEmptyAndNull() {
        assertThrows(IllegalArgumentException.class, () -> ExpHubTools.parseIds(null, "ids"));
        assertThrows(IllegalArgumentException.class, () -> ExpHubTools.parseIds("  ,  ", "ids"));
    }
}
