package com.exphub.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.exphub.entity.Project;
import com.exphub.mapper.ProjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Collection;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 后台项目列表的搜索 / 分页口径守卫（2026-11-20，派单 W-20261120-000005）。
 *
 * <p>盯的是「生成的 WHERE 片段」而不是页面渲染：搜索条件拼错时页面照样 200，
 * 只是搜出来的结果多了或少了，线上几乎不会有人报。</p>
 */
class ProjectListTest {

    private static String sql(String keyword) {
        QueryWrapper<Project> w = new QueryWrapper<>();
        ProjectService.applyProjectKeyword(w, keyword);
        return w.getTargetSql();
    }

    private static Collection<Object> paramValues(String keyword) {
        QueryWrapper<Project> w = new QueryWrapper<>();
        ProjectService.applyProjectKeyword(w, keyword);
        w.getTargetSql();   // 参数值是在拼片段时才填进去的
        return w.getParamNameValuePairs().values();
    }

    @Test
    @DisplayName("空关键词不产生任何搜索条件")
    void blankKeywordAddsNothing() {
        assertEquals("", sql(null));
        assertEquals("", sql(""));
        assertEquals("", sql("   "));
    }

    @Test
    @DisplayName("单词命中名称 / 编码 / 别名三列，且整组带括号")
    void singleTokenSearchesThreeColumnsInOneGroup() {
        String s = sql("hjs");

        for (String col : new String[]{"name", "project_code", "aliases"}) {
            assertTrue(s.contains(col + " LIKE"), "没搜这一列：" + col + " —— 按别名/编码搜就搜不到");
        }
        assertTrue(s.trim().startsWith("("),
                "关键词条件没有整体加括号，会和 status='ACTIVE' 串成错误的优先级（归档项目被捞回来）");
        assertTrue(s.contains(" OR "), "三列之间应该是 OR（任一一列命中都算）");
    }

    @Test
    @DisplayName("多个词之间是 AND：搜「忆阁 记忆」必须两个词都命中")
    void multipleTokensAreAnded() {
        String s = sql("忆阁 记忆");

        assertTrue(s.contains(") AND ("), "多词没有按 AND 拆成两组：" + s);
        int n = 0, i = 0;
        while ((i = s.indexOf("name LIKE", i)) >= 0) {
            n++;
            i += "name LIKE".length();
        }
        assertEquals(2, n, "词数不对，可能被当成了一个整串");
    }

    @Test
    @DisplayName("通配符当字面量：搜 100% / hjs_app 不会退化成模糊匹配")
    void wildcardsAreEscaped() {
        assertTrue(paramValues("100%").stream().anyMatch(v -> String.valueOf(v).contains("100\\%")),
                "百分号没转义 —— 搜「100%」会退化成匹配所有行：" + paramValues("100%"));
        assertTrue(paramValues("hjs_app").stream().anyMatch(v -> String.valueOf(v).contains("hjs\\_app")),
                "下划线没转义 —— 单下划线在 LIKE 里匹配任意一个字符：" + paramValues("hjs_app"));
    }

    @Test
    @DisplayName("分页参数归一：page=0 不许原样传给数据库")
    void pageIsClampedToOne() {
        ProjectMapper mapper = mock(ProjectMapper.class);
        when(mapper.selectPage(any(), any())).thenAnswer(inv -> inv.getArgument(0));

        ProjectService service = new ProjectService();
        ReflectionTestUtils.setField(service, "projectMapper", mapper);

        Page<Project> result = service.listProjects(null, 0, 0);

        ArgumentCaptor<Page<Project>> captor = ArgumentCaptor.forClass(Page.class);
        verify(mapper).selectPage(captor.capture(), any());
        assertEquals(1L, captor.getValue().getCurrent(), "page=0 没被归一成第 1 页");
        assertEquals(1L, captor.getValue().getSize(), "size=0 没被归一成 1，MySQL LIMIT 0 会返回空结果");
        assertFalse(result == null);
    }
}
