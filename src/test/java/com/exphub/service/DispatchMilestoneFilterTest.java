package com.exphub.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.exphub.entity.DispatchHeader;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 派单列表里程碑筛选守卫（W-20261121-000010）。盯住「读代码看不出来」的四件事：
 * 里程碑与状态/关键词是「且」、空值不进筛选但进「全部」、等值而非模糊、两页共用同一 distinct 数据源。
 *
 * <p>与 {@link DispatchKeywordSearchTest} 同一套路：不连库，直接看 QueryWrapper 生成的 WHERE 片段。
 */
class DispatchMilestoneFilterTest {

    private static String stripComments(String src) {
        return src.replaceAll("(?s)/\\*.*?\\*/", "").replaceAll("(?m)//.*$", "");
    }

    private static QueryWrapper<DispatchHeader> filter(String status, String keyword, String milestone) {
        return DispatchService.buildFilter(null, status, null, keyword, milestone, null);
    }

    @Test
    @DisplayName("空里程碑不加任何 milestone 条件 —— 「全部」要把未填里程碑的老单也包含进来")
    void blankMilestoneAddsNoCondition() {
        assertEquals("", filter(null, null, null).getTargetSql());
        assertEquals("", filter(null, null, "   ").getTargetSql());
        // 即使状态、关键词都在，空里程碑也不该往 WHERE 里塞 milestone 条件
        String s = filter("DONE", "派单", "").getTargetSql();
        assertFalse(s.contains("milestone"), "空里程碑仍产生了 milestone 条件：" + s);
    }

    @Test
    @DisplayName("非空里程碑是等值条件，且与状态/关键词是「且」关系")
    void milestoneIsEqAndAndedWithStatusAndKeyword() {
        QueryWrapper<DispatchHeader> onlyMs = filter(null, null, "M1");
        assertTrue(onlyMs.getTargetSql().contains("milestone = ?"),
                "里程碑应是等值匹配（下拉选的是完整值）：" + onlyMs.getTargetSql());

        String s = filter("DONE", "派单", "M1").getTargetSql();
        assertTrue(s.contains("milestone = ?"), "缺里程碑条件：" + s);
        assertTrue(s.contains("status = ?"), "缺状态条件：" + s);
        assertTrue(s.contains("title LIKE"), "缺关键词条件：" + s);
        assertFalse(s.contains("milestone LIKE"), "里程碑不该用模糊匹配：" + s);
    }

    @Test
    @DisplayName("distinctMilestones 只取 milestone 一列、去重、去 NULL 去空串、按名称排序")
    void distinctMilestonesSource() throws Exception {
        String src = stripComments(Files.readString(
                Path.of("src/main/java/com/exphub/service/DispatchService.java"), StandardCharsets.UTF_8));
        assertTrue(src.contains("public List<String> distinctMilestones()"), "缺 distinctMilestones 方法");
        assertTrue(src.contains(".select(\"DISTINCT milestone\")"), "没有去重，或拉了整表字段");
        assertTrue(src.contains(".isNotNull(\"milestone\")"), "没排除 NULL");
        assertTrue(src.contains(".ne(\"milestone\", \"\")"), "没排除空串");
        assertTrue(src.contains(".sorted()"), "下拉选项要按名称排序");
    }

    @Test
    @DisplayName("PC 列表页 / 手机列表页 / 燃尽图页三处共用同一个 distinct 数据源，不各自拼 WHERE")
    void bothPagesShareOneDistinctSource() throws Exception {
        String pc = stripComments(Files.readString(
                Path.of("src/main/java/com/exphub/controller/DispatchController.java"), StandardCharsets.UTF_8));
        String mobile = stripComments(Files.readString(
                Path.of("src/main/java/com/exphub/controller/MobileController.java"), StandardCharsets.UTF_8));
        // PC 控制器里列表页 + 燃尽图页各调一次；手机控制器里列表页调一次
        assertEquals(2, count(pc, "distinctMilestones()"),
                "PC 列表页与燃尽图页都要调 distinctMilestones()，否则两处口径会漂：" + count(pc, "distinctMilestones()"));
        assertEquals(1, count(mobile, "distinctMilestones()"),
                "手机列表页要调 distinctMilestones()");
        assertFalse(pc.contains("select(\"DISTINCT milestone\")"), "Controller 不该再自己拼一份 distinct 查询");
        assertFalse(mobile.contains("select(\"DISTINCT milestone\")"), "Controller 不该再自己拼一份 distinct 查询");
    }

    private static int count(String haystack, String needle) {
        int n = 0, i = 0;
        while ((i = haystack.indexOf(needle, i)) >= 0) {
            n++;
            i += needle.length();
        }
        return n;
    }
}
