package com.exphub.service;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * M10：记忆「标记待删」机制的检索侧保证。
 *
 * <p>红线：AI 能标记，不能删除。标记后的经验必须立刻从检索里消失，
 * 但内容还留在库里，用户可一键恢复。实现方式是复用 status 字段，
 * 所以「默认只查 ACTIVE」和「ALL 也不含待删」这两条一旦被改坏，
 * 被标记要删的内容就会重新回到 AI 的搜索结果里 —— 这里把它锁住。
 */
class PendingDeleteStatusTest {

    @Test
    void blankMeansNullFilter() {
        // 返回 null 表示不加状态条件；MCP 侧会把它替换成默认的 ACTIVE
        assertNull(DocService.parseStatusList(null));
        assertNull(DocService.parseStatusList(""));
        assertNull(DocService.parseStatusList("   "));
    }

    @Test
    void allExpandsButExcludesPendingDelete() {
        List<String> list = DocService.parseStatusList("ALL");
        assertTrue(list.contains("ACTIVE"));
        assertTrue(list.contains("COMPLETED"));
        assertTrue(list.contains("BROKEN"));
        assertTrue(list.contains("DEPRECATED"));
        assertFalse(list.contains(DocService.PENDING_DELETE),
                "ALL 不能把待删项带进检索结果——那是等用户确认要删的东西");
    }

    @Test
    void explicitValuesPassThrough() {
        List<String> list = DocService.parseStatusList("COMPLETED, DEPRECATED");
        assertEquals(List.of("COMPLETED", "DEPRECATED"), list);
    }

    @Test
    void explicitPendingDeleteStillAllowed() {
        // 后台待删队列要靠显式传值拿到数据，这是唯一合法入口
        assertTrue(DocService.parseStatusList(DocService.PENDING_DELETE)
                .contains(DocService.PENDING_DELETE));
    }

    @Test
    void statusValueIsStable() {
        // 库里的历史数据按字符串匹配，值一旦改动，存量待删项会全部「复活」
        assertEquals("PENDING_DELETE", DocService.PENDING_DELETE);
    }
}
