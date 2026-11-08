package com.exphub.mcp;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.exphub.entity.AiAssistant;
import com.exphub.entity.Doc;
import com.exphub.interceptor.ApiKeyInterceptor;
import com.exphub.service.CallLogService;
import com.exphub.service.DocService;
import com.exphub.service.DocTemplateService;
import com.exphub.service.MailboxService;
import com.exphub.service.ProjectService;
import com.exphub.service.ReviewCandidateService;
import com.exphub.service.SimilarityService;
import com.exphub.service.VersionArchiveService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 主要 MCP 入口的「默认参数真跑一次」冒烟（2026-11-08，阁主 #86 排期第一件）。
 *
 * <p><b>要防的是哪类事故</b>：2026-11-06 两次线上 500，613 条测试一条没拦住 ——
 * 一次是 `@Select` 里的 `<>` 直写（启动即死），一次是 `Set.of(...).contains(tt)` 在
 * 默认 `tt=null` 时抛 NPE。共同点：绝大多数测试在扫源码文本，没有一条按线上默认入参
 * **真的调用过方法**。本类不扫源码，只调方法。
 *
 * <p><b>怎么算过</b>：不抛异常、返回非 null、且回执里不能出现被 catch 包住的异常类名。
 * 第三条很要紧 —— 工具层普遍有 `catch (Exception)` 把 500 变成「搜索失败: java.lang…」，
 * 只断言「不抛异常」会让这种事故照样全绿。
 *
 * <p><b>不碰数据库</b>：依赖全部 mock 成空结果 / 最小合法对象。验的是「最常见调用方式
 * 跑不跑得起来」，不是数据对不对。
 */
@ExtendWith(MockitoExtension.class)
class MainEntrySmokeTest {

    @Mock private DocService docService;
    @Mock private DocTemplateService templateService;
    @Mock private CallLogService callLogService;
    @Mock private McpToolRegistry mcpToolRegistry;
    @Mock private ProjectService projectService;
    @Mock private ReviewCandidateService reviewCandidateService;
    @Mock private VersionArchiveService versionArchiveService;
    @Mock private SimilarityService similarityService;
    @Mock private MailboxService mailboxService;

    @InjectMocks private ExpHubTools tools;

    @BeforeEach
    void setUp() {
        // 让权限校验真的通过，否则一半入口会在第一行就「无法识别调用者身份」提前返回，
        // 冒烟就退化成只验了一个 if —— 这正是 CheckTodosSmokeTest 自己承认的浅。
        AiAssistant assistant = mock(AiAssistant.class);
        lenient().when(assistant.getCanSearch()).thenReturn(true);
        lenient().when(assistant.getCanCreate()).thenReturn(true);
        lenient().when(assistant.getCanUpdate()).thenReturn(true);
        lenient().when(assistant.getAssistantId()).thenReturn("smoke");
        lenient().when(assistant.getAssistantName()).thenReturn("冒烟");
        ApiKeyInterceptor.setCurrentAssistant(assistant);

        // 检索链路：8 参重载是 MCP 层真实入参（SearchSmokeTest 踩过 6 参重载的坑）
        lenient().when(docService.search(any(), any(), any(), any(), anyInt(), anyInt(), any(), anyBoolean()))
                .thenReturn(new Page<>(1, 5, 0));
        lenient().when(docService.listRecentAccessible(anyInt(), any(), any()))
                .thenReturn(new DocService.RecentPage(List.of(), null));
        lenient().when(templateService.listAll()).thenReturn(List.of());
        lenient().when(callLogService.getToolOverview()).thenReturn(Map.of());
        lenient().when(callLogService.getToolRanking(anyInt())).thenReturn(List.of());
        lenient().when(callLogService.getToolDailyTrend(anyInt())).thenReturn(List.of());
        lenient().when(mcpToolRegistry.getTools()).thenReturn(Map.of());
        lenient().when(projectService.listProjects()).thenReturn(List.of());
        lenient().when(similarityService.checkOnCreate(any()))
                .thenReturn(new SimilarityService.L0Result(false, "冒烟：无相似", 0, List.of(), 0));
        lenient().when(docService.create(any())).thenAnswer(inv -> inv.getArgument(0));
        lenient().when(docService.appendContent(anyLong(), any(), any(), any())).thenReturn(minimalDoc());
        lenient().when(docService.update(anyLong(), any(), any(), any(), any())).thenReturn(minimalDoc());
        lenient().when(docService.getById(anyLong())).thenReturn(null);
    }

    @AfterEach
    void clear() {
        ApiKeyInterceptor.removeCurrentAssistant();
        MailIdentity.clear();
    }

    /**
     * 每个入口只带「必填参数的最小合法值 + 可选参数全部不传」。
     * 这正是线上最常见、也是两次事故都没被覆盖的调用形态。
     */
    @Test
    @DisplayName("smoke: 主要 MCP 入口按默认参数真跑一遍，不抛异常、不返回异常回执")
    void mainEntriesWithDefaultArgsMustNotBlowUp() {
        Map<String, Callable<String>> cases = new LinkedHashMap<>();
        cases.put("search_experience", () -> tools.searchExperience("部署", null, null, null, null, null, null, null, null));
        cases.put("get_experience_detail", () -> tools.getExperienceDetail(1L, null));
        cases.put("get_experience_history", () -> tools.getExperienceHistory(1L, null, null));
        cases.put("get_template", () -> tools.getTemplate(null));
        cases.put("get_tool_stats", () -> tools.getToolStats(null, null));
        cases.put("batch_get", () -> tools.batchGet("1", null, null));
        cases.put("list_projects", () -> tools.listProjects());
        cases.put("get_project_agents", () -> tools.getProjectAgents(1L, null));
        cases.put("get_project_progress", () -> tools.getProjectProgress(1L, null));
        cases.put("list_recent_memories", () -> tools.listRecentMemories(null, null, null));
        cases.put("list_project_experiences", () -> tools.listProjectExperiences(1L, null, null, null));
        cases.put("check_my_todos", () -> tools.checkMyTodos(null, null));
        // 写入口：天天在用，也是两次线上事故最该护住的地方。正文用无害文本，不触发凭据闸。
        cases.put("create_experience", () -> tools.createExperience(
                "冒烟", "正文", "problem_solution", "开发", "冒烟", "冒烟", "摘要", "烛龙", null));
        cases.put("append_experience", () -> tools.appendExperience(
                1L, "### 补一句", null, null, null));
        cases.put("patch_experience", () -> tools.patchExperience(
                1L, "锚点文本", "替换文本", null, null, null));

        for (Map.Entry<String, Callable<String>> e : cases.entrySet()) {
            String r = assertDoesNotThrow(e.getValue()::call,
                    e.getKey() + " 以默认参数调用抛异常 —— 线上这个入口就挂了");
            assertNotNull(r, e.getKey() + " 返回不得为 null");
            assertFalse(r.contains("Exception"),
                    e.getKey() + " 把异常包进了回执（等于线上 500 换了个说法）：" + r);
            assertFalse(r.contains("失败: "),
                    e.getKey() + " 返回了失败回执，默认参数路径跑不通：" + r);
        }
    }

    /**
     * 默认参数里最容易踩的是「可选参数为 null」：历史事故就是 `Set.of(...).contains(null)`。
     * 这条再单独把几个入口的空字符串 / 空可选组合跑一遍。
     */
    @Test
    @DisplayName("smoke: 可选参数为 null / 空串的组合，同样不得抛异常或返回异常回执")
    void emptyOptionalArgsAreSafe() {
        Map<String, Callable<String>> cases = new LinkedHashMap<>();
        cases.put("search_experience(scope=all)", () -> tools.searchExperience("部署", null, null, null, "all", null, null, null, null));
        cases.put("search_experience(brief=true,explain=true)", () -> tools.searchExperience("部署", null, null, null, null, 5, 0, true, true));
        cases.put("get_tool_stats(7,20)", () -> tools.getToolStats(7, 20));
        cases.put("list_recent_memories(since=today)", () -> tools.listRecentMemories(10, null, "today"));
        cases.put("list_recent_memories(badCursor)", () -> tools.listRecentMemories(null, "not-a-cursor", null));
        cases.put("check_my_todos(ALL)", () -> tools.checkMyTodos(0L, "ALL"));

        for (Map.Entry<String, Callable<String>> e : cases.entrySet()) {
            String r = assertDoesNotThrow(e.getValue()::call, e.getKey() + " 抛异常");
            assertNotNull(r, e.getKey() + " 返回不得为 null");
            assertFalse(r.contains("Exception"), e.getKey() + " 回执里出现异常类名：" + r);
        }
    }

    /** 给 create 用的最小合法文档，避免成功回执里对 null 正文取长度。 */
    private static Doc minimalDoc() {
        Doc d = new Doc();
        d.setId(1L);
        d.setTitle("冒烟");
        d.setContent("正文");
        d.setVersion(1);
        return d;
    }
}
