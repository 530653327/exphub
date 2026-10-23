package com.exphub.mcp;

import com.exphub.entity.Doc;
import com.exphub.entity.Project;
import com.exphub.service.CallLogService;
import com.exphub.service.DocService;
import com.exphub.service.DocTemplateService;
import com.exphub.service.ProjectService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * list_recent_memories 的输出格式：忆阁按 `- [标题](id:123) — 项目 · 类型 · 时间` 解析，格式不可随意改。
 */
@ExtendWith(MockitoExtension.class)
class ExpHubToolsMemoryTest {

    @Mock
    private DocService docService;

    @Mock
    private DocTemplateService templateService;

    @Mock
    private CallLogService callLogService;

    @Mock
    private ProjectService projectService;

    @InjectMocks
    private ExpHubTools tools;

    @Test
    void formatsMarkdownLinesAndCursor() {
        Project project = new Project();
        project.setId(1L);
        project.setName("回金算");
        when(projectService.listProjects()).thenReturn(List.of(project));

        List<Doc> docs = List.of(
                doc(437L, 1L, "MySQL 5.7 不支持 CTE", "problem_solution", "2026-10-22T19:05:12"),
                doc(438L, null, "全局经验", "knowledge_doc", "2026-10-22T18:00:00")
        );
        when(docService.listRecentAccessible(10, null))
                .thenReturn(new DocService.RecentPage(docs, "2026-10-22T18:00:00|438"));

        String out = tools.listRecentMemories(null, null);

        assertEquals(
                "- [MySQL 5.7 不支持 CTE](id:437) — 回金算 · 🛠️ 问题解决方案 · 2026-10-22 19:05\n" +
                "- [全局经验](id:438) — 全局 · 📚 知识文档 · 2026-10-22 18:00\n" +
                "NEXT_CURSOR: 2026-10-22T18:00:00|438",
                out);
    }

    @Test
    void sanitizesBracketsAndNewlinesInTitle() {
        when(projectService.listProjects()).thenReturn(List.of());
        List<Doc> docs = List.of(doc(1L, null, "a[b]\nc", "knowledge_doc", "2026-10-22T19:05:12"));
        when(docService.listRecentAccessible(10, null))
                .thenReturn(new DocService.RecentPage(docs, "-"));

        String out = tools.listRecentMemories(null, null);

        assertTrue(out.startsWith("- [a【b】 c](id:1) — 全局"), "实际: " + out);
    }

    @Test
    void pageSizeAboveLimitIsPassedAs50() {
        when(docService.listRecentAccessible(50, "2026-10-22T19:05:12|1"))
                .thenReturn(new DocService.RecentPage(List.of(), "-"));

        String out = tools.listRecentMemories(100, "2026-10-22T19:05:12|1");

        assertEquals("没有更多记忆了。\nNEXT_CURSOR: -", out);
        verify(docService).listRecentAccessible(50, "2026-10-22T19:05:12|1");
    }

    @Test
    void emptyPageReturnsDashCursor() {
        when(docService.listRecentAccessible(10, null))
                .thenReturn(new DocService.RecentPage(List.of(), "-"));

        assertEquals("没有更多记忆了。\nNEXT_CURSOR: -", tools.listRecentMemories(null, null));
    }

    @Test
    void failureReturnsReadableMessage() {
        when(docService.listRecentAccessible(10, null)).thenThrow(new RuntimeException("db down"));

        assertTrue(tools.listRecentMemories(null, null).startsWith("查询失败: db down"));
    }

    private static Doc doc(long id, Long projectId, String title, String type, String updatedAt) {
        Doc d = new Doc();
        d.setId(id);
        d.setProjectId(projectId);
        d.setTitle(title);
        d.setTemplateType(type);
        d.setUpdatedAt(LocalDateTime.parse(updatedAt));
        return d;
    }
}
