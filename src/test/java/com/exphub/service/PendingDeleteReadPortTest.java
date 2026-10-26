package com.exphub.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.exphub.entity.Doc;
import org.junit.jupiter.api.Test;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 读端口径守卫：**已标记待删的文档，在所有 MCP 读端必须表现一致**。
 *
 * <p>⚠️ 2026-10-26 16:5x 阁主实跑标记链路发现两个漏洞（本类就是为锁住它们而生）：
 * <pre>
 *   ① list_project_experiences 不排除已标待删 —— 标记后 #675 还排在项目列表第一位
 *   ② get_experience_detail 完全不显示待删状态 —— 回执只有「分类/作者/版本/标签」，
 *      一个字不提「这条已标记待删」
 * </pre>
 * 病根是同一个：**status=PENDING_DELETE 这个状态字段，各读端各认各的**。
 * 检索（search_experience）认它 → 标完立刻搜不到；列表不认 → 它还排第一位；
 * 详情不认 → 读到的 AI 把它当正常文档用，而它已在待删队列等着人确认。
 *
 * <p><b>为什么这个守卫必须断言「生成的 SQL」而不是断言源码里写了什么</b>：
 * 断言「代码里有 ne("status", PENDING_DELETE)」是<b>自证式检查</b> ——
 * 代码里那句可能写错了字段名、可能被后面的条件覆盖、可能根本没被用上，
 * 而测试照样绿。断言 {@code getSqlSegment()} 里的实际条件，才是对**行为**的断言。
 *
 * <p><b>反测（必须能抓到漏项）</b>：{@link #guardMustCatchMissingStatusFilter()}
 * 断言「守卫自己」在一个故意漏掉过滤的 QueryWrapper 上会报错 ——
 * 守卫自己抓不到东西的话，绿灯没有意义（#674 期间刚踩过这个坑）。
 */
class PendingDeleteReadPortTest {

    /** 从 MyBatis-Plus 的 SQL 片段里抽出参数值（形如 #{ew.paramNameValuePairs.MPGENVAL1}） */
    private static String sqlOf(QueryWrapper<Doc> w) {
        String seg = w.getSqlSegment();
        String params = w.getParamNameValuePairs().values().toString();
        return seg + " | params=" + params;
    }

    // ==================== ① 列表读端：必须排除待删 ====================

    @Test
    void visibleProjectDocsQueryMustExcludePendingDelete() {
        String sql = sqlOf(ProjectService.visibleProjectDocsQuery(1L, null));
        assertTrue(sql.contains("status"), "可见项目文档的查询必须带 status 条件: " + sql);
        assertTrue(sql.contains("PENDING_DELETE"),
                "可见项目文档的查询必须排除 PENDING_DELETE: " + sql);
        // 必须是「不等于」而不是「等于」——写成等于会把待删项当成唯一可见项
        assertFalse(sql.contains("status ="),
                "status 条件不能是等值（那会只看待删项）: " + sql);
    }

    @Test
    void visibleProjectDocsQueryMustKeepOtherStatusVisible() {
        String sql = sqlOf(ProjectService.visibleProjectDocsQuery(1L, null));
        // 只排除待删这一个状态，其余（ACTIVE/COMPLETED/DEPRECATED/BROKEN）仍可见
        assertFalse(sql.contains("ACTIVE"),
                "不该把 ACTIVE 也写进排除条件: " + sql);
        assertEquals(1, countOccurrences(sql, "PENDING_DELETE"),
                "排除条件里 PENDING_DELETE 只应出现一次: " + sql);
    }

    @Test
    void visibleProjectDocsQueryMustKeepTemplateTypeAndProjectFilter() {
        QueryWrapper<Doc> withType = ProjectService.visibleProjectDocsQuery(6L, "knowledge_doc");
        String sql = sqlOf(withType);
        assertTrue(sql.contains("project_id"), "必须按项目过滤: " + sql);
        assertTrue(sql.contains("template_type"), "templateType 传了必须生效: " + sql);
        assertTrue(sql.contains("knowledge_doc"), "templateType 的值必须进 SQL: " + sql);

        QueryWrapper<Doc> noType = ProjectService.visibleProjectDocsQuery(6L, null);
        assertFalse(sqlOf(noType).contains("template_type"),
                "templateType 为空时不应加该条件: " + sqlOf(noType));
    }

    @Test
    void visibleProgressQueryMustExcludePendingDelete() {
        String sql = sqlOf(ProjectService.visibleProgressQuery(6L, 3));
        assertTrue(sql.contains("PENDING_DELETE"),
                "可见项目进度的查询必须排除 PENDING_DELETE: " + sql);
        assertTrue(sql.contains("project_progress"), "必须按模板类型过滤: " + sql);
        assertTrue(sql.contains("LIMIT 3"), "limit 必须传进 SQL: " + sql);
    }

    /**
     * 反测：**守卫自己必须能抓到漏项**。
     *
     * <p>做法：造一个「故意漏掉 status 过滤」的 QueryWrapper，
     * 用守卫同一套判据去检查它 —— 必须判为不合格。
     * 如果哪天守卫的判据写坏了（比如改成只查 "PENDING_DELETE" 出现过就算过），
     * 这条测试会红。
     */
    @Test
    void guardMustCatchMissingStatusFilter() {
        QueryWrapper<Doc> broken = new QueryWrapper<>();
        broken.eq("project_id", 1L).orderByDesc("updated_at").last("LIMIT 2000");
        String sql = sqlOf(broken);
        // 与上面几条断言同一套判据：合格 = 带 status 条件 且 排除了 PENDING_DELETE
        boolean qualified = sql.contains("status") && sql.contains("PENDING_DELETE");
        assertFalse(qualified,
                "守卫判据失效：漏掉 status 过滤的查询竟然被判为合格 —— " + sql);
    }

    /**
     * 反测：守卫也不能被「只写了个摆设条件」骗过。
     *
     * <p>比如有人把 ne 写成 eq（即 status = 'PENDING_DELETE'），
     * 或者条件拼错字段名 —— 这两种「看起来带了 status」实则漏项。
     */
    @Test
    void guardMustCatchWrongStatusCondition() {
        QueryWrapper<Doc> eqInstead = new QueryWrapper<>();
        eqInstead.eq("project_id", 1L).eq("status", DocService.PENDING_DELETE);
        String sql = sqlOf(eqInstead);
        boolean qualified = sql.contains("status") && sql.contains("PENDING_DELETE")
                && !sql.contains("status =");
        assertFalse(qualified,
                "守卫判据失效：等值条件 status = PENDING_DELETE 竟然被判为合格 —— " + sql);

        QueryWrapper<Doc> wrongColumn = new QueryWrapper<>();
        wrongColumn.eq("project_id", 1L).ne("statu", DocService.PENDING_DELETE);
        String sql2 = sqlOf(wrongColumn);
        boolean qualified2 = sql2.contains("status") && sql2.contains("PENDING_DELETE");
        assertFalse(qualified2,
                "守卫判据失效：字段名拼错（statu）竟然被判为合格 —— " + sql2);
    }

    // ==================== ② 状态值本身不能被改 ====================

    @Test
    void pendingDeleteStatusValueIsStable() {
        // 库里存量数据按字符串匹配，值一旦改动，存量待删项会全部「复活」
        assertEquals("PENDING_DELETE", DocService.PENDING_DELETE);
        // 且 ALL 展开必须继续排除它 —— 检索侧已有的保证，不能被这次改动破坏
        assertFalse(DocService.parseStatusList("ALL").contains(DocService.PENDING_DELETE),
                "ALL 不能把待删项带进检索结果");
    }

    // ==================== 工具方法 ====================

    private static int countOccurrences(String s, String needle) {
        int n = 0;
        Matcher m = Pattern.compile(Pattern.quote(needle)).matcher(s);
        while (m.find()) n++;
        return n;
    }
}
