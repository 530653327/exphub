package com.exphub.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.exphub.entity.DispatchHeader;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 派单列表关键词搜索的 SQL 拼装守卫（2026-11-17 加）。
 *
 * <p>为什么单测盯 SQL 片段而不是渲染页面：搜索错了页面照样 200、照样有数据，
 * 只有「筛了状态再搜词却捞回别的状态」这类问题才会露馅，而它在线上一眼看不出来。
 * 这里不连库，直接看 QueryWrapper 生成的 WHERE 片段。
 */
class DispatchKeywordSearchTest {

    private static String sql(String keyword) {
        QueryWrapper<DispatchHeader> q = new QueryWrapper<>();
        DispatchService.applyKeyword(q, keyword);
        return q.getTargetSql();
    }

    @Test
    @DisplayName("空关键词不产生任何 WHERE 条件")
    void blankKeywordAddsNothing() {
        assertEquals("", sql(null));
        assertEquals("", sql(""));
        assertEquals("", sql("   "));
    }

    @Test
    @DisplayName("单词命中六列，且整体被括号包住（否则会和 status 的 AND 串味）")
    void singleTokenSearchesAllColumnsInOneGroup() {
        String s = sql("派单");

        for (String col : new String[]{"order_no", "title", "assignee", "creator", "accepter", "description"}) {
            assertTrue(s.contains(col + " LIKE"), "没搜这一列：" + col + " —— 用户按人名/单号搜就搜不到");
        }
        // 六个条件必须是同一个括号组里的 OR；没有括号就会变成 a AND b OR c OR ...
        assertTrue(s.trim().startsWith("("), "关键词条件没有整体加括号，会和状态筛选串成错误的优先级");
        assertTrue(s.contains(" OR "), "多列之间应该是 OR（任一词落在任一一列都算命中）");
    }

    @Test
    @DisplayName("多个词之间是 AND：搜「派单 手机」必须两个词都命中")
    void multipleTokensAreAnded() {
        String s = sql("派单 手机");

        // 两个独立的括号组，中间用 AND 连
        assertTrue(s.contains(") AND ("), "多词没有按 AND 拆成两组：" + s);
        assertEquals(2, countOccurrences(s, "order_no LIKE"), "词数不对，可能被当成了一个整串");
    }

    @Test
    @DisplayName("用户输入的通配符当字面量，不能变成「匹配所有」")
    void wildcardsAreEscaped() {
        // 值走的是 #{} 占位符，所以转义得看参数值，不是看 SQL 文本
        assertTrue(paramValues("100%").stream().anyMatch(v -> String.valueOf(v).contains("100\\%")),
                "百分号没转义 —— 搜「100%」会退化成全表匹配：" + paramValues("100%"));

        assertTrue(paramValues("a_b").stream().anyMatch(v -> String.valueOf(v).contains("a\\_b")),
                "下划线没转义 —— 单下划线在 LIKE 里匹配任意一个字符：" + paramValues("a_b"));

        // 反斜杠自己也要先转义，否则后面的转义符会被吃掉
        assertEquals("a\\\\b", DispatchService.escapeLike("a\\b"));
    }

    private static java.util.Collection<Object> paramValues(String keyword) {
        QueryWrapper<DispatchHeader> q = new QueryWrapper<>();
        DispatchService.applyKeyword(q, keyword);
        // 参数值是在拼 SQL 片段时才填进去的，所以先触发一次再读
        q.getTargetSql();
        return q.getParamNameValuePairs().values();
    }

    private static int countOccurrences(String haystack, String needle) {
        int n = 0, i = 0;
        while ((i = haystack.indexOf(needle, i)) >= 0) {
            n++;
            i += needle.length();
        }
        return n;
    }
}
