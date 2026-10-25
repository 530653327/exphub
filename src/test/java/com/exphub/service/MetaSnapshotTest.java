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
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * A3：元数据变更不产生正文快照（2026-10-25）。
 *
 * 这三个断言就是 A3 的全部契约。之前的行为是「无条件存全文」，
 * 结果是只改一个标签也要多一份 4KB 正文副本 —— 实测 463 条快照里有 21 条是纯冗余。
 */
@ExtendWith(MockitoExtension.class)
class MetaSnapshotTest {

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
        d.setContent("原始正文");
        return d;
    }

    private DocVersion capturedSnapshot() {
        ArgumentCaptor<DocVersion> cap = ArgumentCaptor.forClass(DocVersion.class);
        verify(versionMapper).insert(cap.capture());
        return cap.getValue();
    }

    @Test
    void metadataOnlyUpdateStoresNoContentCopy() {
        when(docMapper.selectById(1L)).thenReturn(existing());

        Doc patch = new Doc();
        patch.setTags("新标签");
        docService.update(1L, patch, Set.of());

        DocVersion v = capturedSnapshot();
        assertNull(v.getContent(), "只改标签时不应存正文副本");
        assertEquals("META", v.getChangeType());
        assertEquals(3, v.getVersion(), "快照记的是被替换掉的旧版本号");
    }

    @Test
    void contentUpdateStoresFullText() {
        when(docMapper.selectById(1L)).thenReturn(existing());

        Doc patch = new Doc();
        patch.setContent("改过的正文");
        docService.update(1L, patch, Set.of());

        DocVersion v = capturedSnapshot();
        assertEquals("原始正文", v.getContent(), "正文变了要存旧版全文");
        assertEquals("CONTENT", v.getChangeType());
    }

    @Test
    void identicalContentStillCountsAsMetadata() {
        when(docMapper.selectById(1L)).thenReturn(existing());

        Doc patch = new Doc();
        patch.setContent("原始正文");   // 传了但内容和原来一样
        patch.setTags("新标签");
        docService.update(1L, patch, Set.of());

        DocVersion v = capturedSnapshot();
        assertNull(v.getContent(), "内容没变就不该存第二份一模一样的正文");
        assertEquals("META", v.getChangeType());
    }

    @Test
    void appendAlwaysCountsAsContentChange() {
        when(docMapper.selectById(1L)).thenReturn(existing());

        docService.appendContent(1L, "追加的一段", "烛龙");

        DocVersion v = capturedSnapshot();
        assertEquals("原始正文", v.getContent());
        assertEquals("CONTENT", v.getChangeType());
        assertEquals("烛龙", v.getUpdatedName());
    }

    @Test
    void patchAlwaysCountsAsContentChange() {
        when(docMapper.selectById(1L)).thenReturn(existing());

        docService.patchContent(1L, "原始", "改后", "烛龙");

        DocVersion v = capturedSnapshot();
        assertEquals("原始正文", v.getContent());
        assertEquals("CONTENT", v.getChangeType());
    }

    @Test
    void setFlagsWritesNoSnapshotAtAll() {
        when(docMapper.selectById(1L)).thenReturn(existing());

        docService.setFlags(1L, java.util.List.of("needs_review"), "说明", "烛龙");

        // 连 META 快照都不该有：打标记属于元信息的元信息，进版本表纯属噪音
        verify(versionMapper, org.mockito.Mockito.never()).insert(any(DocVersion.class));
    }
}
