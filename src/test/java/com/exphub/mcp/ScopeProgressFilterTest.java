package com.exphub.mcp;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 守卫：scope=all 默认排除「项目内的进度流水」（2026-11-05 实测回归）。
 *
 * <p>为什么会有这条规则：scope 默认改 all 之后，项目里的 project_progress 文档大量涌入主排序 ——
 * 实测「部署流程 deploy.sh」在 scope=global 下 #95 排第 1，切到 all 后两条忆阁进度流水
 * （#729 / #698）挤进前 4，把 #95 顶到第 2。进度是施工日志，不是答案，而且它数量远超项目经验
 * （exphub #1：34 条进度 vs 8 条经验）。
 *
 * <p>这条守卫拦的是「好心办坏事」：下一个人发现「搜不到项目进度」，
 * 顺手把这个条件删掉 —— 于是全局经验又被流水挤下去一遍。
 */
class ScopeProgressFilterTest {

    private String read(String rel) throws IOException {
        return Files.readString(Path.of(rel), StandardCharsets.UTF_8);
    }

    @Test
    @DisplayName("守卫：两条搜索 SQL 都带「排除项目内进度」条件，计数与结果必须同口径")
    void bothSearchSqlExcludeProjectProgress() throws IOException {
        String mapper = read("src/main/java/com/exphub/mapper/DocMapper.java");
        // 判据只认「SQL 里真的用了这个开关」，不数 @Param / javadoc 出现次数 ——
        // 数总数那种判据，加一行注释就红、少一个 @Param 反而绿。
        assertEquals(2, countOf(mapper, "<if test='excludeProjectProgress'>"),
                "排除条件必须同时出现在搜索与计数两条 SQL 里（只改一边会让「匹配 N 条」与实际不符）");

        String cond = "AND NOT (project_id IS NOT NULL AND template_type = 'project_progress')";
        assertEquals(2, countOf(mapper, cond), "两条 SQL 各需一份排除条件");
    }

    @Test
    @DisplayName("守卫：显式搜 templateType=project_progress 时不排除（否则那条路就死了）")
    void explicitProgressSearchIsNotExcluded() throws IOException {
        String svc = read("src/main/java/com/exphub/service/DocService.java");
        assertTrue(svc.contains("excludeProgress = all && !\"project_progress\".equals(tt)"),
                "排除条件必须挂在「没显式要进度」这个前提上，否则 templateType=project_progress 永远搜不到东西");
    }

    @Test
    @DisplayName("守卫：LIKE 兜底与全文检索口径一致")
    void likeFallbackUsesSameRule() throws IOException {
        String svc = read("src/main/java/com/exphub/service/DocService.java");
        assertTrue(svc.contains("ne(\"template_type\", \"project_progress\")"),
                "LIKE 兜底也要排除项目进度，否则换个档位结果就变了（同一个查询两个答案）");
    }

    private static int countOf(String haystack, String needle) {
        int n = 0;
        int i = 0;
        while ((i = haystack.indexOf(needle, i)) >= 0) {
            n++;
            i += needle.length();
        }
        return n;
    }
}
