package com.exphub.service;

import com.exphub.entity.Doc;
import com.exphub.entity.DocVersion;
import com.exphub.mapper.DocMapper;
import com.exphub.mapper.DocVersionMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * D1 施工包 1：取代链（2026-10-25）。
 *
 * <p>重点不是「能标上」，而是三条边界：
 * 1) 标取代不能写版本快照（整理动作不该制造版本垃圾）；
 * 2) 不能成环（A→B→A 会让「最新版是谁」永远算不出来）；
 * 3) 不能自己取代自己。
 */
@ExtendWith(MockitoExtension.class)
class SupersedeChainTest {

    @Mock
    private DocMapper docMapper;

    @Mock
    private DocVersionMapper versionMapper;

    @Mock
    private CallLogService callLogService;

    @InjectMocks
    private DocService docService;

    private Doc doc(long id, Long supersededBy) {
        Doc d = new Doc();
        d.setId(id);
        d.setVersion(3);
        d.setTitle("文档" + id);
        d.setSupersededBy(supersededBy);
        return d;
    }

    // ---------- chainReaches：防环的基础 ----------

    @Test
    void chainReachesDetectsDirectLoop() {
        Map<Long, Long> chain = Map.of(2L, 1L);   // 2 被 1 取代
        assertTrue(DocService.chainReaches(2L, 1L, chain::get, 20));
    }

    @Test
    void chainReachesDetectsIndirectLoop() {
        Map<Long, Long> chain = Map.of(3L, 2L, 2L, 1L);   // 3→2→1
        assertTrue(DocService.chainReaches(3L, 1L, chain::get, 20));
    }

    @Test
    void chainReachesFalseWhenChainEnds() {
        Map<Long, Long> chain = Map.of(3L, 2L);   // 2 的取代链到头了
        assertFalse(DocService.chainReaches(3L, 1L, chain::get, 20));
    }

    @Test
    void chainReachesStopsAtMaxHops() {
        // 自己指向自己会无限循环，靠 maxHops 兜底，不能挂死
        assertFalse(DocService.chainReaches(9L, 1L, id -> id, 5));
    }

    // ---------- markSuperseded 的三条拒绝 ----------

    @Test
    void markSupersededRejectsSelfReference() {
        when(docMapper.selectById(1L)).thenReturn(doc(1L, null));

        var r = docService.markSuperseded(1L, 1L, "烛龙");

        assertFalse(r.ok());
        assertTrue(r.message().contains("自己取代自己"));
        verify(docMapper, never()).update(isNull(), any());
    }

    @Test
    void markSupersededRejectsMissingTarget() {
        when(docMapper.selectById(1L)).thenReturn(doc(1L, null));
        when(docMapper.selectById(99L)).thenReturn(null);

        var r = docService.markSuperseded(1L, 99L, "烛龙");

        assertFalse(r.ok());
        assertTrue(r.message().contains("99"));
        verify(docMapper, never()).update(isNull(), any());
    }

    @Test
    void markSupersededRejectsCycle() {
        // 1 已被 2 取代；现在想标 2 被 1 取代 → 成环
        when(docMapper.selectById(2L)).thenReturn(doc(2L, null));
        when(docMapper.selectById(1L)).thenReturn(doc(1L, 2L));

        var r = docService.markSuperseded(2L, 1L, "烛龙");

        assertFalse(r.ok());
        assertTrue(r.message().contains("成环"));
    }

    // ---------- 正常路径 ----------

    @Test
    void markSupersededWritesPointerOnly() {
        when(docMapper.selectById(1L)).thenReturn(doc(1L, null));
        when(docMapper.selectById(2L)).thenReturn(doc(2L, null));

        var r = docService.markSuperseded(1L, 2L, "烛龙");

        assertTrue(r.ok());
        // 关键：只更新，不写版本快照 —— 否则每次整理都多一份全文副本
        verify(docMapper).update(isNull(), any());
        verify(versionMapper, never()).insert(any(DocVersion.class));
        verify(callLogService).logUpdate(any(Doc.class));
    }

    @Test
    void markSupersededIsIdempotentOnSameTarget() {
        when(docMapper.selectById(1L)).thenReturn(doc(1L, 2L));
        when(docMapper.selectById(2L)).thenReturn(doc(2L, null));

        var r = docService.markSuperseded(1L, 2L, "烛龙");

        assertTrue(r.ok());
        assertTrue(r.message().contains("已存在"));
        verify(docMapper, never()).update(isNull(), any());
    }

    @Test
    void clearSupersededClearsPointer() {
        when(docMapper.selectById(1L)).thenReturn(doc(1L, 2L));

        Doc cleared = docService.clearSuperseded(1L);

        assertEquals(1L, cleared.getId());
        verify(docMapper).update(isNull(), any());
        verify(versionMapper, never()).insert(any(DocVersion.class));
    }

    @Test
    void listSupersedingQueriesReversePointer() {
        when(docMapper.selectList(any())).thenReturn(List.of(doc(5L, 1L)));

        List<Doc> replaced = docService.listSuperseding(1L);

        assertEquals(1, replaced.size());
        assertEquals(5L, replaced.get(0).getId());
    }
}
