package com.exphub.service;

import com.exphub.entity.MailCursor;
import com.exphub.entity.MailMessage;
import com.exphub.mapper.MailCursorMapper;
import com.exphub.mapper.MailMessageMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.*;

/**
 * 信箱游标「只读不推」（2026-11-09 阁主 T261109015413-804）。
 *
 * <p><b>要防的是哪一种丢信</b>：游标一推，这封信在服务端就算读过了。如果模型调用超时、
 * 进程被杀、客户端截断，正文根本没进上下文 —— 信既不算未读、也没被读到。
 * 自动唤醒读得越勤，这个窗口被踩中的次数越多。
 *
 * <p><b>为什么推进权必须交给调用方</b>：服务端只能确认「我返回了」，确认不了「你用上了」。
 * 所以 peek 只读，{@link MailboxService#advanceCursorTo} 单独做确认步。
 *
 * <p>本测试直接验 mapper 调用：peek 路径<b>一次游标写都不能有</b>，非 peek 路径必须有。
 */
class MailboxCursorPeekTest {

    private MailboxService svc;
    private MailMessageMapper msgMapper;
    private MailCursorMapper cursorMapper;

    @BeforeEach
    void setUp() {
        svc = new MailboxService();
        msgMapper = mock(MailMessageMapper.class);
        cursorMapper = mock(MailCursorMapper.class);
        ReflectionTestUtils.setField(svc, "msgMapper", msgMapper);
        ReflectionTestUtils.setField(svc, "cursorMapper", cursorMapper);
    }

    @Test
    @DisplayName("peek=true：正文照给，但游标一次都不写")
    void peekNeverWritesCursor() {
        when(cursorMapper.selectOne(any())).thenReturn(cursor(40L));
        when(msgMapper.selectList(any())).thenReturn(List.of(msg(41L, "T1"), msg(42L, "T1")));

        List<MailMessage> list = svc.thread("zhulong", "T1", false, 10, true);

        assertEquals(2, list.size());
        verify(cursorMapper, never()).update(isNull(), any());
        verify(cursorMapper, never()).insert(any(MailCursor.class));
    }

    @Test
    @DisplayName("peek=false：默认行为一个字没变，读完照样推游标到最后一条")
    void defaultStillAdvancesCursor() {
        when(cursorMapper.selectOne(any())).thenReturn(cursor(40L));
        when(msgMapper.selectList(any())).thenReturn(List.of(msg(41L, "T1"), msg(42L, "T1")));
        when(cursorMapper.update(isNull(), any())).thenReturn(1);

        svc.thread("zhulong", "T1", false, 10, false);

        verify(cursorMapper, times(1)).update(isNull(), any());
    }

    @Test
    @DisplayName("advanceCursorTo：确认步只推游标，且校验消息属于本线程")
    void advanceCursorToValidatesThread() {
        when(msgMapper.selectById(42L)).thenReturn(msg(42L, "T1"));
        // 第 1 次读是推进前（40），第 2 次是推进后回读（42）—— 回读是「不按入参自证」那一条
        when(cursorMapper.selectOne(any())).thenReturn(cursor(40L), cursor(42L));

        assertEquals(1, svc.advanceCursorTo("zhulong", "T1", 42L));
        verify(cursorMapper, times(1)).update(isNull(), any());
    }

    @Test
    @DisplayName("advanceCursorTo：跨线程的ID明拒，绝不把别的线程标成已读")
    void advanceCursorToRejectsOtherThread() {
        when(msgMapper.selectById(9L)).thenReturn(msg(9L, "T2"));

        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> svc.advanceCursorTo("zhulong", "T1", 9L));

        assertTrue(e.getMessage().contains("T2"), "要说清它真实属于哪个线程：" + e.getMessage());
        verify(cursorMapper, never()).update(isNull(), any());
        verify(cursorMapper, never()).insert(any(MailCursor.class));
    }

    @Test
    @DisplayName("advanceCursorTo：目标不比游标新时幂等返回 0，不写库")
    void advanceCursorToIsMonotonic() {
        when(msgMapper.selectById(38L)).thenReturn(msg(38L, "T1"));
        when(cursorMapper.selectOne(any())).thenReturn(cursor(40L));

        assertEquals(0, svc.advanceCursorTo("zhulong", "T1", 38L));
        verify(cursorMapper, never()).update(isNull(), any());
    }

    // ---------------------------------------------------------------- 小工具

    private static MailCursor cursor(long id) {
        MailCursor c = new MailCursor();
        c.setRole("zhulong");
        c.setThreadId("T1");
        c.setLastReadId(id);
        return c;
    }

    private static MailMessage msg(long id, String threadId) {
        MailMessage m = new MailMessage();
        m.setId(id);
        m.setThreadId(threadId);
        return m;
    }
}
