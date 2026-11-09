package com.exphub.controller;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 删除 / 恢复后的重定向拼装（2026-11-07 修的真实缺陷）。
 *
 * <p><b>缺陷经过</b>：用户在「待删」Tab 里删一条，旧代码写死 {@code redirect:/docs}，
 * 页面直接跳回「全部」——得重新点待删才能删下一条。待删有 6 条就要重复 6 次。
 *
 * <p>这条测试钉三件事：
 * <ol>
 *   <li>带 status=PENDING_DELETE 时必须原样带回（否则修复失效）</li>
 *   <li>空筛选时回到干净的 {@code /docs}（不要出现 `?` 尾巴）</li>
 *   <li>含特殊字符的关键词要 URL 编码（不编码会拼出非法 URL 或被截断）</li>
 * </ol>
 *
 * <p>⚠️ 这测试直接调 {@link PageController#buildDocsRedirect}，不启动 Spring。
 * 所以它只能证明「拼串对」，证明不了「表单真的把参数传上来了」——
 * 后者要看模板里的 {@code docsContextQuery()}。两层都改才生效。
 */
class DocsRedirectTest {

    @Test
    void keepsPendingDeleteTab() {
        String r = PageController.buildDocsRedirect("PENDING_DELETE", null, null, null);
        assertEquals("redirect:/docs?status=PENDING_DELETE", r);
    }

    @Test
    void keepsNeedsReviewTab() {
        String r = PageController.buildDocsRedirect(null, "needs_review", null, null);
        assertEquals("redirect:/docs?flag=needs_review", r);
    }

    @Test
    void emptyFiltersGoToCleanDocs() {
        String r = PageController.buildDocsRedirect(null, null, null, null);
        assertEquals("redirect:/docs", r);
        assertFalse(r.contains("?"), "没有筛选条件时不该带问号");
    }

    @Test
    void blankStringsAreTreatedAsAbsent() {
        String r = PageController.buildDocsRedirect("", "", "  ", "");
        // 空白关键词不算筛选（"  " 是有效字符串，会被保留并编码，这里只断言不产生空值参数）
        assertTrue(r.startsWith("redirect:/docs"));
        assertFalse(r.contains("status="));
        assertFalse(r.contains("flag="));
        assertFalse(r.contains("templateType="));
    }

    @Test
    void combinesAllFilters() {
        String r = PageController.buildDocsRedirect("PENDING_DELETE", null, "部署", "bug_fix");
        assertTrue(r.startsWith("redirect:/docs?"), r);
        assertTrue(r.contains("status=PENDING_DELETE"), r);
        assertTrue(r.contains("templateType=bug_fix"), r);
        assertTrue(r.contains("keyword="), r);
        // 中文必须被编码，不能原样出现
        assertFalse(r.contains("部署"), "关键词未编码，URL 会非法：" + r);
    }

    // ===== 2026-11-09：「问题文档」视图又犯了一次同样的毛病 =====
    // 从 /docs?problem=stale 删一条后，旧代码只带回 status/flag/keyword/templateType，
    // problem 不在其中 —— 于是又跳回「全部」。同一个缺陷在加了 problem 视图后复发，
    // 因为「带当前 Tab 回来」当时是靠逐个列参数实现的，新增分类忘了加。

    @Test
    void keepsProblemTab() {
        String r = PageController.buildDocsRedirect(null, null, null, null, "stale");
        assertEquals("redirect:/docs?problem=stale", r);
    }

    @Test
    void problemCombinesWithOtherFilters() {
        String r = PageController.buildDocsRedirect("PENDING_DELETE", null, null, null, "pending_delete");
        assertEquals("redirect:/docs?status=PENDING_DELETE&problem=pending_delete", r);
    }

    @Test
    void unknownProblemValueIsDropped() {
        // 只认 5 类白名单，与 GET /docs 的口径一致；非法值丢弃，不让任意串进重定向 URL
        assertEquals("redirect:/docs",
                PageController.buildDocsRedirect(null, null, null, null, "'; DROP TABLE docs; --"));
        assertEquals("redirect:/docs", PageController.buildDocsRedirect(null, null, null, null, ""));
        assertEquals("redirect:/docs", PageController.buildDocsRedirect(null, null, null, null, "stale2"));
        // 大小写/首尾空白按 GET /docs 的同一口径归一后接受
        assertEquals("redirect:/docs?problem=stale",
                PageController.buildDocsRedirect(null, null, null, null, "  STALE  "));
    }
}
