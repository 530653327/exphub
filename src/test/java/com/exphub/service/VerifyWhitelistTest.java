package com.exphub.service;

import com.exphub.entity.Doc;
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
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 「什么算重新确认」的白名单（越哥 2026-10-29 签字：**只留 content**）。
 *
 * <p>这三条断言都读代码看不出来 —— 它们看的是「写进 docMapper.updateById 的那个实体里
 * last_verified_at 是什么」，只能在调用边界上验证。
 *
 * <p>为什么必须钉住：这一列是 90 天过期判定的唯一依据。一旦元数据更新也开始刷它，
 * 过期判定就永远触发不了（等价于没有这一列）；反过来一旦正文更新不刷它，
 * 改过的结论就永远带着旧时间，成为「已过期」的假信号。
 */
@ExtendWith(MockitoExtension.class)
class VerifyWhitelistTest {

    private static final LocalDateTime OLD_VERIFIED = LocalDateTime.of(2026, 5, 1, 9, 0);

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
        d.setVersion(3);
        d.setTitle("测试文档");
        d.setContent("正文 A");
        d.setSummary("旧摘要");
        d.setAuthorName("烛龙");
        d.setCallCount(0);
        d.setSuccessCount(0);
        d.setFailCount(0);
        d.setRating(new BigDecimal("0.00"));
        d.setRatingCount(0);
        // backfill = 由 updated_at 回填出来的值，不是人工确认过。重贴标签等于伪造证据
        d.setLastVerifiedAt(OLD_VERIFIED);
        d.setLastVerifiedSource(DocService.VERIFIED_BACKFILL);
        return d;
    }

    @Test
    @DisplayName("只改 summary（不在白名单）→ 验证时间与来源原样保留，不被重贴成 auto")
    void summaryOnlyChangeKeepsVerifyTime() {
        when(docMapper.selectById(9L)).thenReturn(existing());

        Doc patch = new Doc();
        patch.setSummary("新摘要（压缩脚本改的）");
        docService.update(9L, patch, Set.of(), "烛龙");

        ArgumentCaptor<Doc> cap = ArgumentCaptor.forClass(Doc.class);
        verify(docMapper).updateById(cap.capture());
        assertEquals(OLD_VERIFIED, cap.getValue().getLastVerifiedAt(),
                "summary 不在白名单：压缩/翻译脚本一跑全库，验证时间会被一次性刷成同一天");
        assertEquals(DocService.VERIFIED_BACKFILL, cap.getValue().getLastVerifiedSource(),
                "来源必须保住 backfill —— 改成 auto 就是伪造一次「写入时自动确认」");
    }

    @Test
    @DisplayName("正文变了 → 填现在 + auto（要改正文就得先读，读过就算重新确认）")
    void contentChangeRefreshes() {
        when(docMapper.selectById(9L)).thenReturn(existing());

        Doc patch = new Doc();
        patch.setContent("正文 B");
        docService.update(9L, patch, Set.of(), "烛龙");

        ArgumentCaptor<Doc> cap = ArgumentCaptor.forClass(Doc.class);
        verify(docMapper).updateById(cap.capture());
        assertTrue(cap.getValue().getLastVerifiedAt().isAfter(OLD_VERIFIED),
                "正文变更必须刷新验证时间，否则改过的结论永远带着旧时间");
        assertEquals(DocService.VERIFIED_AUTO, cap.getValue().getLastVerifiedSource());
    }

    @Test
    @DisplayName("调用方显式传旧值 → 原样采纳并记 manual（改个错字不该把 90 天前的验证刷成今天）")
    void explicitValueWinsOverContentChange() {
        when(docMapper.selectById(9L)).thenReturn(existing());

        Doc patch = new Doc();
        patch.setContent("正文 B（顺手改了个错字）");
        patch.setLastVerifiedAt(OLD_VERIFIED);
        docService.update(9L, patch, Set.of(), "烛龙");

        ArgumentCaptor<Doc> cap = ArgumentCaptor.forClass(Doc.class);
        verify(docMapper).updateById(cap.capture());
        assertEquals(OLD_VERIFIED, cap.getValue().getLastVerifiedAt());
        assertEquals(DocService.VERIFIED_MANUAL, cap.getValue().getLastVerifiedSource());
    }

    @Test
    @DisplayName("判定函数本身：content 未变且无显式值 → 保留旧值；old 为 null 时不抛 NPE")
    void decideVerifyEdgeCases() {
        assertSame(OLD_VERIFIED,
                DocService.decideVerify(existing(), false, null).at());
        assertEquals(DocService.VERIFIED_BACKFILL,
                DocService.decideVerify(existing(), false, null).source());

        DocService.VerifyDecision nullOld = DocService.decideVerify(null, false, null);
        assertNull(nullOld.at());
        assertNull(nullOld.source());
    }
}
