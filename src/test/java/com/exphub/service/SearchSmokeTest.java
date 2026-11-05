package com.exphub.service;

import com.exphub.entity.Doc;
import com.exphub.mapper.DocMapper;
import com.exphub.mapper.DocVersionMapper;
import com.exphub.mapper.ProjectMapper;
import com.exphub.mapper.ReviewCandidateMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.lenient;

/**
 * 冒烟：默认参数真的走一遍检索链路（#1290 C79-②）。
 *
 * <p><b>为什么要这条</b>：2026-11-06 两小时内两次线上事故，613 条测试一条没抓到。
 * <ul>
 *   <li>{@code a0ab1d3}：把 {@code <>} 写进 {@code @Select} 的 {@code <script>}，MyBatis 解析即炸 —— 启动就死；</li>
 *   <li>{@code dfc406d}：判据写成 {@code Set.of(...).contains(tt)}，而默认搜索 {@code tt=null}，
 *       {@code contains(null)} 抛 NPE —— 所有搜索 500。</li>
 * </ul>
 * 两次的共同点不是「守卫不够多」，而是 <b>没有任何一条测试以默认参数真的调用过检索方法</b>：
 * 613 条里绝大多数在扫源码文本。守卫扫的是「文案有没有被改」，扫不到「这段代码跑不跑得起来」。
 *
 * <p>这条测试不碰数据库：{@code DocMapper} 全部 mock 成空结果。它验证的只有一件事 ——
 * <b>最常见的调用方式不会抛异常</b>。NPE 发生在进入 mapper 之前，所以 mock 也拦得住。
 */
@ExtendWith(MockitoExtension.class)
class SearchSmokeTest {

    @Mock private DocMapper docMapper;
    @Mock private DocVersionMapper versionMapper;
    @Mock private ProjectMapper projectMapper;
    @Mock private ReviewCandidateMapper reviewCandidateMapper;
    @Mock private CallLogService callLogService;

    @InjectMocks private DocService docService;

    private void stubEmptyMapper() {
        lenient().when(docMapper.countByFulltext(any(), any(), any(), any(), any(), anyBoolean(), any(), anyBoolean()))
                .thenReturn(0L);
        lenient().when(docMapper.searchByFulltext(any(), any(), any(), any(), any(), any(),
                        anyLong(), anyLong(), anyBoolean(), any(), anyBoolean()))
                .thenReturn(List.of());
    }

    @Test
    @DisplayName("smoke: search(\"部署\") 不传任何可选参数，不得抛异常")
    void searchWithDefaultArgsMustNotThrow() {
        stubEmptyMapper();
        // ⚠️ 必须用 8 参重载并传 scopeAll=true —— 这是 MCP 层的真实入参。
        // 第一版写成 6 参重载，内部把 scopeAll 默认成 false，`all` 直接为 false，
        // `all && ...` 短路，NPE 根本没机会发生：**测试绿、复现不了那个 bug**。
        // 所以这里显式按线上路径构造，并用「注入 bug 必红」验证过（见提交说明）。
        assertDoesNotThrow(() -> docService.search("部署", null, null, null, 1, 5, null, true),
                "默认参数的 search 抛异常 = 线上所有搜索挂掉，这条必须绿");
        assertNotNull(docService.search("部署", null, null, null, 1, 5, null, true), "返回不得为 null");
    }

    @Test
    @DisplayName("smoke: scopeAll 下 search 同样不得抛异常（排除集判据走的就是这条分支）")
    void searchScopeAllMustNotThrow() {
        stubEmptyMapper();
        assertDoesNotThrow(() -> docService.search("部署", null, null, null, 1, 5, null, true),
                "scope=all 是默认档，排除集判据在这里求值");
    }

    @Test
    @DisplayName("smoke: findSimilar 默认参数不得抛异常（0 结果兜底，线上同样常走）")
    void findSimilarWithDefaultArgsMustNotThrow() {
        stubEmptyMapper();
        assertDoesNotThrow(() -> docService.findSimilar("部署", null, null, null, 3),
                "0 结果兜底抛异常会让「查无此经验」变成「工具坏了」");
    }

    @Test
    @DisplayName("smoke: 显式按被排除的类型查，不得抛异常（tt 非空分支）")
    void explicitExcludedTypeMustNotThrow() {
        stubEmptyMapper();
        assertDoesNotThrow(() -> docService.search("部署", "project_progress", null, null, 1, 5, null, true),
                "显式按类型查是排除集判据的另一支，同样要跑得到");
        assertDoesNotThrow(() -> docService.search("部署", "todo_list", null, null, 1, 5, null, true),
                "todo_list 扩进排除集后，这一支必须仍然可走");
    }

    /**
     * C74 的硬条件：补位段必须覆盖 todo_list，不能只覆盖 project_progress。
     *
     * <p>反面情形很隐蔽：若补位段写死 `"project_progress".equals(type)`，
     * 待办退出主排名后就两头落空 —— 既不在主排名，也不在补位，
     * 用户搜「erp 运维待办」会得到「查无此经验」。所以这条用**行为**验，不扫源码文本。
     */
    @Test
    @DisplayName("补位段必须同时覆盖 project_progress 与 todo_list")
    void processHitsMustCoverBothExcludedTypes() {
        Doc progress = doc(9001L, "project_progress", "施工回执：某次上线");
        Doc todo = doc(9002L, "todo_list", "运维待办清单");
        Doc knowledge = doc(9003L, "knowledge_doc", "普通知识文档");
        lenient().when(docMapper.searchByFulltext(any(), any(), any(), any(), any(), any(),
                        anyLong(), anyLong(), anyBoolean(), any(), anyBoolean()))
                .thenReturn(List.of(knowledge, progress, todo));

        List<Doc> hits = docService.findProcessHits("部署", null, null, 3);

        List<Long> ids = hits.stream().map(Doc::getId).toList();
        assertTrue(ids.contains(9001L), "project_progress 必须进补位段");
        assertTrue(ids.contains(9002L), "todo_list 必须进补位段 —— 只覆盖 pp 就等于把待办弄丢了");
        assertFalse(ids.contains(9003L), "补位段只放被主排名排除的类型，知识文档不该混进来");
    }

    private static Doc doc(Long id, String type, String title) {
        Doc d = new Doc();
        d.setId(id);
        d.setTemplateType(type);
        d.setTitle(title);
        d.setAliases("");
        d.setSummary("");
        d.setTags("");
        d.setContent("部署 相关正文内容");
        d.setStatus("ACTIVE");
        d.setVisibility("PUBLIC");
        d.setCallCount(0);
        d.setUpdatedAt(java.time.LocalDateTime.now());
        return d;
    }
}
