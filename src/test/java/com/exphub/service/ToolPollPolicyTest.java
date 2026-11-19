package com.exphub.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 轮询排除口径守卫（W-20261115-17）。
 *
 * <p>这组断言钉的是「回执不许只报排除数、不报真实数」与「历史未分类不许冒充轮询」：
 * 页面和 get_tool_stats 共用 {@code explain}，任何一处想自己拼一句话都会在这里失守。
 */
class ToolPollPolicyTest {

    @Test
    @DisplayName("排除 SQL 是正向条件：mail_start 只留 is_poll=0，dispatch_list 整体排掉，业务行 NULL 必须保留")
    void excludeSqlKeepsBusinessRowsAndRealReads() {
        String sql = ToolPollPolicy.EXCLUDE_POLL_SQL;
        assertTrue(sql.contains("tool_name IS NULL"),
                "业务行（SEARCH/CREATE…）tool_name 是 NULL；不先短路保留，NOT(...) 三值逻辑会把全部业务行过滤掉: " + sql);
        assertTrue(sql.contains("tool_name NOT IN ('mail_start', 'dispatch_list')"),
                "非排除名单的工具必须整体保留: " + sql);
        assertTrue(sql.contains("is_poll = 0"),
                "mail_start 只保留 is_poll=0（真实读信）；历史 NULL 与轮询 1 都不计: " + sql);
        assertTrue(!sql.contains("'mail_start' AND is_poll = 1"), "正向条件里不该出现轮询保留分支: " + sql);
        assertTrue(sql.contains("'dispatch_list'") && !sql.contains("dispatch_list' AND is_poll"),
                "dispatch_list 是整体排除（它没有「真实调用」这一半），不许写成按 is_poll 分: " + sql);
    }

    @Test
    @DisplayName("口径说明必须同时给出排除数与真实读信数")
    void explainReportsBothSides() {
        String text = ToolPollPolicy.explain(198_000, 37, 0, 0, "", 7);
        assertTrue(text.contains("近 7 天"), "缺窗口天数: " + text);
        assertTrue(text.contains("198000"), "缺排除数: " + text);
        assertTrue(text.contains("真实读信 37 次仍计入"), "只报排除数等于让人以为读信也没了: " + text);
        assertFalse(text.contains("未分类"), "没有历史未分类时不许凭空提一嘴: " + text);
    }

    @Test
    @DisplayName("历史未分类必须单列，不能混进轮询数一起说")
    void legacyNullIsReportedSeparately() {
        String text = ToolPollPolicy.explain(12, 3, 99_000, 0, "", 30);
        assertTrue(text.contains("12"), "明确轮询数丢了: " + text);
        assertTrue(text.contains("99"), "历史未分类数丢了: " + text);
        assertTrue(text.contains("部署前未分类"), "必须说清这个数是迁移前的，不是现在的轮询: " + text);
        assertTrue(text.contains("真实读信 3 次仍计入"), "真实读信数丢了: " + text);
    }

    @Test
    @DisplayName("历史行全是未分类时，不许说成「排除轮询 0 次」")
    void legacyOnlyWordingDoesNotSayZeroPolls() {
        String text = ToolPollPolicy.explain(0, 0, 198_559, 0, "", 7);
        assertTrue(text.contains("已排除 mail_start 调用 198559 次"),
                "总数只报 polls=0 会让人以为什么都没排除: " + text);
        assertTrue(text.contains("均为部署前未分类"), "必须点名这是历史未分类: " + text);
    }

    @Test
    @DisplayName("dispatch_list 的排除必须说清是「历史存量」且会降到 0，否则读成「还在排除」")
    void dispatchListExclusionIsExplainedAsLegacy() {
        String withHistory = ToolPollPolicy.explain(0, 0, 0, 45_120, "11-19 15:08", 7);
        assertTrue(withHistory.contains("dispatch_list 整体不计统计"), "没说明这个工具被整体排除: " + withHistory);
        assertTrue(withHistory.contains("45120"), "历史埋点数丢了，读的人会以为库坏了: " + withHistory);
        assertTrue(withHistory.contains("11-19 15:08"), "最后一次调用时间丢了: " + withHistory);
        assertTrue(withHistory.contains("不再写新埋点") && withHistory.contains("降到 0"),
                "没说明这是历史存量、会随时间归零 —— 那就成了「排除还在继续」的错误信号: " + withHistory);

        String noHistory = ToolPollPolicy.explain(0, 0, 0, 0, "", 7);
        assertTrue(noHistory.contains("窗口内已无历史埋点"), "窗口内没有历史行时必须这么说: " + noHistory);
        assertFalse(noHistory.contains("降到 0"), "已经没有历史行时不许再提「会降到 0」: " + noHistory);
    }
}
