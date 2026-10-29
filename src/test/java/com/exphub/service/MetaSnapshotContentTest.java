package com.exphub.service;

import com.exphub.entity.Doc;
import com.exphub.entity.DocVersion;
import com.exphub.mapper.DocMapper;
import com.exphub.mapper.DocVersionMapper;
import com.exphub.mapper.ReviewCandidateMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 快照的正文成本：**只有正文真的变了才存全文**（越哥 2026-10-29 P0 工单）。
 *
 * <p>为什么这条要钉死：618 篇文档的版本表是「只增不减」的。一次「只复核、不改内容」的动作若也
 * 存一份全文，复核越勤成本越高，最后会压制复核行为本身 —— 这是行为问题，不是存储问题。
 *
 * <p>同时钉住反向红线：**正文变更的快照一个字都不许省**。省了之后审计链就断了，
 * 那比多占几 KB 严重得多。两条断言必须成对存在，只留一条都会把实现往错的方向推。
 */
@ExtendWith(MockitoExtension.class)
class MetaSnapshotContentTest {

    private static final String OLD_CONTENT = "第一段正文\n\n## 小节\n\n" + "长".repeat(9000);

    @Mock
    private DocMapper docMapper;

    @Mock
    private DocVersionMapper versionMapper;

    @Mock
    private CallLogService callLogService;

    @Mock
    private ReviewCandidateMapper reviewCandidateMapper;

    @InjectMocks
    private DocService docService;

    private Doc existing() {
        Doc d = new Doc();
        d.setId(9L);
        d.setVersion(7);
        d.setTitle("长文测试");
        d.setContent(OLD_CONTENT);
        d.setAuthorName("烛龙");
        d.setCallCount(0);
        d.setSuccessCount(0);
        d.setFailCount(0);
        d.setRating(new BigDecimal("0.00"));
        d.setRatingCount(0);
        d.setLastVerifiedAt(LocalDateTime.of(2026, 5, 1, 9, 0));
        d.setLastVerifiedSource(DocService.VERIFIED_BACKFILL);
        return d;
    }

    private DocVersion capturedSnapshot() {
        ArgumentCaptor<DocVersion> cap = ArgumentCaptor.forClass(DocVersion.class);
        verify(versionMapper).insert(cap.capture());
        return cap.getValue();
    }

    @Test
    @DisplayName("只传 verifiedAt（复核动作）→ 快照 content 必须为 NULL，只记 META")
    void verifyOnlyWritesNoContent() {
        when(docMapper.selectById(9L)).thenReturn(existing());

        Doc patch = new Doc();
        patch.setLastVerifiedAt(LocalDateTime.now());   // 只传验证时间，content 为 null
        docService.update(9L, patch, Set.of(), "烛龙");

        DocVersion v = capturedSnapshot();
        assertNull(v.getContent(),
                "复核一次不该存一份全文 —— " + OLD_CONTENT.length() + " 字的正文会被复制进版本表，"
                        + "复核越勤成本越高，最后逼人不复核");
        assertEquals("META", v.getChangeType());
        assertEquals(7, v.getVersion(), "快照记的是被替换掉的那一版");
    }

    @Test
    @DisplayName("只改 tags → 同样不存全文（元数据变更一律 META）")
    void tagsOnlyWritesNoContent() {
        when(docMapper.selectById(9L)).thenReturn(existing());

        Doc patch = new Doc();
        patch.setTags("a,b");
        docService.update(9L, patch, Set.of(), "烛龙");

        DocVersion v = capturedSnapshot();
        assertNull(v.getContent());
        assertEquals("META", v.getChangeType());
    }

    @Test
    @DisplayName("红线：改了正文 → 快照必须是变更前的完整全文，一个字都不许省")
    void contentChangeKeepsFullText() {
        when(docMapper.selectById(9L)).thenReturn(existing());

        Doc patch = new Doc();
        patch.setContent("新正文");
        docService.update(9L, patch, Set.of(), "烛龙");

        DocVersion v = capturedSnapshot();
        assertNotNull(v.getContent(), "正文变更必须存全文快照 —— 省了就是审计链断裂");
        assertEquals("CONTENT", v.getChangeType());
        assertEquals(OLD_CONTENT, v.getContent(),
                "快照存的应是**变更前**那一版，" + OLD_CONTENT.length() + " 字整份，不是差异、不是摘要");
    }
}
