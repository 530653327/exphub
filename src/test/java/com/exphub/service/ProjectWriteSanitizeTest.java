package com.exphub.service;

import com.exphub.entity.AiAssistant;
import com.exphub.entity.Doc;
import com.exphub.interceptor.ApiKeyInterceptor;
import com.exphub.mapper.AiAssistantMapper;
import com.exphub.mapper.DocMapper;
import com.exphub.mapper.ProjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * E13：写入端剥离渲染痕迹必须覆盖 service 层（项目进度 / AGENTS.md MAIN / 章节）。
 *
 * <p>真实事故：剥离逻辑原先只加在 ExpHubTools 的经验增删改与导入路径上，
 * append_project_progress / update_project_agents_main / update_project_agents_chapter
 * 走的是 ProjectService，**没有任何剥离**。实测库里已被踩脏 4 篇：
 * ID=252 / 293 / 515 尾部有「--- 📌 文档结尾」（515 叠了 3 次）、
 * ID=585 正文中间夹着一段 v3 的旧历史导读（与真实 v6 矛盾）。</p>
 *
 * <p>这些用例直接拿线上脏数据的形态做输入，锁住「写入即净化」。</p>
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ProjectWriteSanitizeTest {

    /** 线上 ID=515 的真实形态：尾部叠了 3 个结尾标记 */
    private static final String PROGRESS_DIRTY = """
            ## MCP 请求体上限 256KB → 5MB

            改的是 spring.codec.max-in-memory-size 与 Netty 参数。

            ---
            📌 文档结尾

            ---
            📌 文档结尾

            ---
            📌 文档结尾""";

    /** 线上 ID=585 的真实形态：旧导读块 + 终端残留 */
    private static final String CHAPTER_DIRTY = """
            ## ✅ E1/E2 已修复

            正文第一段。

            ---

            > 📚 **历史导读**：本文档经历多次迭代，当前为 v3，共保存 2 个历史版本（v1 ~ v2）。
            > 如需了解本文档的**来龙去脉、演进过程或历史解决方案**，请调用
            > `get_experience_history(id=585)` 查看历史版本列表；
            > `get_experience_history(id=585, version=版本号)` 查看指定版本完整内容（如 version=1 可看最初版本）。

            ---

            ## 二、问题清单

            正文第二段。

            /tmp/probe/mcpcall.sh: line 50: 59240 Terminated: 15          curl -sN ...""";

    @Mock
    private ProjectMapper projectMapper;

    @Mock
    private DocMapper docMapper;

    @Mock
    private DocService docService;

    @Mock
    private AiAssistantMapper assistantMapper;

    @InjectMocks
    private ProjectService projectService;

    @AfterEach
    void clearContext() {
        ApiKeyInterceptor.removeCurrentAssistant();
    }

    @Test
    void appendProgressStripsDocEndMarkers() {
        when(docService.create(any(Doc.class))).thenAnswer(inv -> inv.getArgument(0));

        Doc saved = projectService.appendProgress(1L, PROGRESS_DIRTY, "上限调整");

        assertFalse(saved.getContent().contains("文档结尾"), "写入端应剥掉 📌 文档结尾：\n" + saved.getContent());
        assertFalse(saved.getContent().endsWith("---"), "尾部残留分隔线要清掉");
        assertTrue(saved.getContent().contains("改的是 spring.codec.max-in-memory-size"), "正文要保留");
        assertFalse(saved.getSummary().contains("文档结尾"), "摘要取自正文，不能带渲染痕迹");
    }

    @Test
    void updateAgentsChapterStripsStaleTocAndTerminalResidue() {
        when(docMapper.selectList(any())).thenReturn(java.util.Collections.emptyList());
        when(docService.create(any(Doc.class))).thenAnswer(inv -> inv.getArgument(0));

        Doc saved = projectService.updateAgentsChapter(1L, "1", "项目概述", CHAPTER_DIRTY, "ALWAYS", 1, null);

        assertFalse(saved.getContent().contains("历史导读"), "旧导读块必须剥掉：\n" + saved.getContent());
        assertFalse(saved.getContent().contains("Terminated"), "终端残留必须剥掉");
        assertTrue(saved.getContent().contains("正文第一段"));
        assertTrue(saved.getContent().contains("正文第二段"), "污染块之后的正文必须保留");
        assertTrue(saved.getContent().contains("## 二、问题清单"));
    }

    @Test
    void updateAgentsMainStripsStaleToc() {
        when(docMapper.selectList(any())).thenReturn(java.util.Collections.emptyList());
        when(docService.create(any(Doc.class))).thenAnswer(inv -> inv.getArgument(0));

        Doc saved = projectService.updateAgentsMain(1L, CHAPTER_DIRTY);

        assertFalse(saved.getContent().contains("历史导读"));
        assertTrue(saved.getContent().contains("## 二、问题清单"));
    }

    @Test
    void keepsCleanContentUnchanged() {
        when(docService.create(any(Doc.class))).thenAnswer(inv -> inv.getArgument(0));

        String clean = "## 背景\n\n> 正常引用。\n\n---\n\n## 方案\n\n内容。";
        Doc saved = projectService.appendProgress(1L, clean, "干净进度");

        assertEquals(clean, saved.getContent(), "干净正文不能被误伤");
    }

    @Test
    void keepsNullAndEmptyContentMeaningClear() {
        when(docService.create(any(Doc.class))).thenAnswer(inv -> {
            Doc d = inv.getArgument(0);
            if (d.getContent() == null) d.setContent("");
            return d;
        });

        Doc saved = projectService.appendProgress(1L, "", "空进度");
        assertEquals("", saved.getContent(), "空内容保持空，不能被改成其它内容");
    }

    @Test
    void usesAssignedApiKeyWhenContextPresent() {
        AiAssistant a = new AiAssistant();
        a.setApiKey("exp-test");
        a.setAssistantName("test");
        ApiKeyInterceptor.setCurrentAssistant(a);
        when(docService.create(any(Doc.class))).thenAnswer(inv -> inv.getArgument(0));

        Doc saved = projectService.appendProgress(1L, "## 正文", "带身份");

        ArgumentCaptor<Doc> captor = ArgumentCaptor.forClass(Doc.class);
        verify(docService).create(captor.capture());
        assertEquals("exp-test", saved.getApiKey());
        assertEquals(1L, saved.getProjectId());
    }
}
