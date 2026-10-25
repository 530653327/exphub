package com.exphub.service;

import com.exphub.entity.Doc;
import com.exphub.entity.DocVersion;
import com.exphub.mapper.DocMapper;
import com.exphub.mapper.DocVersionMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 文档作者 vs 操作人署名（越哥 2026-10-25 21:30 定：加 updated_name，谁修改的就记上谁的名字）。
 *
 * <p>这两个字段的坑特别隐蔽，因为**代码读起来是对的**：
 * <ul>
 *   <li>快照署名硬传 null → 走 Key 兜底，看起来「有名字」，其实不是真正的操作人</li>
 *   <li>{@code newDoc.setAuthorName(old.getAuthorName())} → authorName 参数静默失效，
 *       既不报错也不生效，测试不查库根本发现不了</li>
 * </ul>
 * 所以这里三条断言都盯着「到底写进了哪个字段」。
 */
@ExtendWith(MockitoExtension.class)
class AuthorVsUpdatedNameTest {

    @Mock
    private DocMapper docMapper;

    @Mock
    private DocVersionMapper versionMapper;

    @Mock
    private CallLogService callLogService;

    @InjectMocks
    private DocService docService;

    private Doc existing() {
        Doc d = new Doc();
        d.setId(1L);
        d.setVersion(3);
        d.setTitle("测试文档");
        d.setContent("正文");
        d.setAuthorName("阁主");       // 原文档作者
        return d;
    }

    @Test
    void updatedNameGoesToVersionSnapshot() {
        when(docMapper.selectById(1L)).thenReturn(existing());

        Doc patch = new Doc();
        patch.setSummary("新摘要");
        docService.update(1L, patch, Set.of(), "烛龙");

        ArgumentCaptor<DocVersion> cap = ArgumentCaptor.forClass(DocVersion.class);
        verify(versionMapper).insert(cap.capture());
        assertEquals("烛龙", cap.getValue().getUpdatedName(), "操作人署名必须落在快照的 updated_name");
    }

    @Test
    void updatedNameDoesNotTouchDocAuthor() {
        Doc old = existing();
        when(docMapper.selectById(1L)).thenReturn(old);

        Doc patch = new Doc();
        patch.setSummary("新摘要");
        docService.update(1L, patch, Set.of(), "烛龙");

        // 关键：署名不能顺手改掉作品归属
        assertEquals("阁主", patch.getAuthorName(), "操作人署名绝不能改文档作者");
    }

    @Test
    void authorNameActuallyTakesEffect() {
        // 这条盯的是「死参数」：原实现无条件 setAuthorName(old)，传了也不生效
        Doc old = existing();
        when(docMapper.selectById(1L)).thenReturn(old);

        Doc patch = new Doc();
        patch.setSummary("x");
        patch.setAuthorName("新作者");
        docService.update(1L, patch, Set.of(), "烛龙");

        assertEquals("新作者", patch.getAuthorName(), "显式传作者时必须能改（原实现会覆盖回旧值）");
    }

    @Test
    void missingAuthorFallsBackToOldValue() {
        Doc old = existing();
        when(docMapper.selectById(1L)).thenReturn(old);

        Doc patch = new Doc();
        patch.setSummary("x");
        // 不传 authorName
        docService.update(1L, patch, Set.of(), "烛龙");

        assertEquals("阁主", patch.getAuthorName(), "不传作者时应保留原值，不能被清空");
    }

    @Test
    void nullUpdatedNameFallsBackToKeyName() {
        when(docMapper.selectById(1L)).thenReturn(existing());

        Doc patch = new Doc();
        patch.setSummary("x");
        docService.update(1L, patch, Set.of(), null);

        // 没有 current assistant 的单测环境 → 落到 "system"
        ArgumentCaptor<DocVersion> cap = ArgumentCaptor.forClass(DocVersion.class);
        verify(versionMapper).insert(cap.capture());
        // 兜底路径不得写入空串（空串会让库里出现「有版本记录但没名字」的行）
        String name = cap.getValue().getUpdatedName();
        assertNotNull(name, "兜底署名不能为 null");
        assertTrue(!name.trim().isEmpty(), "兜底署名不能是空串，实际: " + name);
    }

    @Test
    void threeArgUpdateStillWorks() {
        // 向后兼容：老的 3 参调用（clearFields）不能因为新增重载而报错
        when(docMapper.selectById(1L)).thenReturn(existing());

        Doc patch = new Doc();
        patch.setSummary("x");
        docService.update(1L, patch, Set.of("tags"));

        verify(docMapper).updateById(any(Doc.class));
    }
}
