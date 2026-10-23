package com.exphub.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.exphub.entity.AiAssistant;
import com.exphub.entity.Doc;
import com.exphub.interceptor.ApiKeyInterceptor;
import com.exphub.mapper.DocMapper;
import com.exphub.mapper.ProjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * 记忆列表游标分页：分页边界、游标生成、非法游标降级、无身份拒绝。
 */
@ExtendWith(MockitoExtension.class)
class DocServiceRecentTest {

    @Mock
    private DocMapper docMapper;

    @Mock
    private ProjectMapper projectMapper;

    @InjectMocks
    private DocService docService;

    private static final LocalDateTime BASE = LocalDateTime.of(2026, 10, 22, 19, 5, 12);

    @BeforeEach
    void setUp() {
        AiAssistant assistant = new AiAssistant();
        assistant.setApiKey("key-a");
        ApiKeyInterceptor.setCurrentAssistant(assistant);
        lenient().when(projectMapper.selectList(any())).thenReturn(List.of());
    }

    @AfterEach
    void tearDown() {
        ApiKeyInterceptor.removeCurrentAssistant();
    }

    @Test
    void pageSizeAboveLimitIsCappedAt50() {
        when(docMapper.selectList(any())).thenReturn(List.of());

        docService.listRecentAccessible(100, null);

        QueryWrapper<Doc> wrapper = captureWrapper();
        assertTrue(lastSql(wrapper).contains("LIMIT 51"),
                "应多取 1 条判断下一页，实际: " + lastSql(wrapper));
    }

    @Test
    void pageSizeBelowOneFallsBackTo10() {
        when(docMapper.selectList(any())).thenReturn(List.of());

        docService.listRecentAccessible(0, null);

        assertTrue(lastSql(captureWrapper()).contains("LIMIT 11"));
    }

    @Test
    void moreRowsProduceNextCursorFromLastReturnedDoc() {
        List<Doc> rows = new ArrayList<>();
        IntStream.range(0, 11).forEach(i -> rows.add(doc(100 - i, BASE.minusMinutes(i))));
        when(docMapper.selectList(any())).thenReturn(rows);

        DocService.RecentPage page = docService.listRecentAccessible(10, null);

        assertEquals(10, page.docs().size());
        assertEquals("2026-10-22T18:56:12|91", page.nextCursor());
    }

    @Test
    void exactlyOnePageReturnsDashCursor() {
        List<Doc> rows = new ArrayList<>();
        IntStream.range(0, 10).forEach(i -> rows.add(doc(100 - i, BASE.minusMinutes(i))));
        when(docMapper.selectList(any())).thenReturn(rows);

        DocService.RecentPage page = docService.listRecentAccessible(10, null);

        assertEquals(10, page.docs().size());
        assertEquals("-", page.nextCursor());
    }

    @Test
    void invalidCursorFallsBackToFirstPage() {
        when(docMapper.selectList(any())).thenReturn(List.of());

        docService.listRecentAccessible(10, "not-a-cursor");

        assertFalse(captureWrapper().getSqlSegment().contains("updated_at <"),
                "非法游标不应产生 keyset 条件");
    }

    @Test
    void validCursorAddsKeysetCondition() {
        when(docMapper.selectList(any())).thenReturn(List.of());

        docService.listRecentAccessible(10, "2026-10-22T19:05:12|91");

        assertTrue(captureWrapper().getSqlSegment().contains("updated_at <"));
    }

    @Test
    void missingIdentityReturnsEmptyWithoutQuery() {
        ApiKeyInterceptor.removeCurrentAssistant();

        DocService.RecentPage page = docService.listRecentAccessible(10, null);

        assertTrue(page.docs().isEmpty());
        assertEquals("-", page.nextCursor());
        verifyNoInteractions(docMapper);
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private QueryWrapper<Doc> captureWrapper() {
        ArgumentCaptor<QueryWrapper<Doc>> captor = ArgumentCaptor.forClass((Class) QueryWrapper.class);
        verify(docMapper).selectList(captor.capture());
        return captor.getValue();
    }

    /** MyBatis-Plus 3.5.5 没有 lastSql 的公开 getter，测试里反射读取 */
    private static String lastSql(QueryWrapper<Doc> wrapper) {
        Object value = org.springframework.test.util.ReflectionTestUtils.getField(wrapper, "lastSql");
        return value == null ? "" : value.toString();
    }

    private static Doc doc(long id, LocalDateTime updatedAt) {
        Doc d = new Doc();
        d.setId(id);
        d.setTitle("记忆 " + id);
        d.setUpdatedAt(updatedAt);
        d.setTemplateType("knowledge_doc");
        return d;
    }
}
