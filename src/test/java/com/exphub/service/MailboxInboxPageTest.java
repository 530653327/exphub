package com.exphub.service;

import com.exphub.entity.MailCursor;
import com.exphub.entity.MailMessage;
import com.exphub.mapper.MailCursorMapper;
import com.exphub.mapper.MailMessageMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.*;

/**
 * 收件箱「分页 / 含已读 / 稳定排序键」（2026-11-10 阁主 #625）。
 *
 * <p><b>要防的三种静默失败</b>：
 * <ol>
 *   <li>把「只扫到 200 条消息窗口」显示成「没有更早的信」——窗口满必须显式标出来；</li>
 *   <li>翻页时把上一页展示过的线程再出现一次（用更旧的最新消息冒充新行）；</li>
 *   <li>读列表顺手推游标——面板每刷新一次，未读角标就被吃掉。</li>
 * </ol>
 *
 * <p>本测试直接验 service 行为与 mapper 调用，不起 Spring。
 */
class MailboxInboxPageTest {

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
        ReflectionTestUtils.setField(svc, "roleCatalog", MailRoleFixtures.catalog("烛龙"));
    }

    @Test
    @DisplayName("includeRead：false 只给有待看的，true 连已读一起给；每条带稳定排序键 latestId")
    void includeReadTogglesReadThreads() {
        when(cursorMapper.selectOne(any())).thenReturn(cursor(15L));
        when(msgMapper.selectList(any())).thenReturn(
                List.of(msg(20L, "T-B"), msg(10L, "T-A")), List.of(),
                List.of(msg(20L, "T-B"), msg(10L, "T-A")), List.of());

        MailboxService.InboxPage unreadOnly = svc.inboxPage("烛龙", null, 20, false, null, null);
        assertEquals(List.of("T-B"), threadIds(unreadOnly), "游标 15：T-A 最新 10 = 已读，T-B 最新 20 = 有新信");

        MailboxService.InboxPage all = svc.inboxPage("烛龙", null, 20, true, null, null);
        assertEquals(List.of("T-B", "T-A"), threadIds(all), "含已读要两根都给，且按最新消息 id 倒序");
        assertEquals(20L, all.items.get(0).latestId, "latestId 是稳定排序键，前端不靠自己排 lastAt");
        assertEquals(10L, all.items.get(1).latestId);

        verify(cursorMapper, never()).update(isNull(), any());
        verify(cursorMapper, never()).insert(any(MailCursor.class));
    }

    @Test
    @DisplayName("beforeId：上一页已展示过的线程不再带着更旧的消息重复出现")
    void beforeIdExcludesThreadsWhoseNewerPartWasAlreadyShown() {
        when(cursorMapper.selectOne(any())).thenReturn(cursor(0L));
        when(msgMapper.selectList(any())).thenReturn(
                List.of(msg(90L, "T2"), msg(80L, "T1"), msg(70L, "T1")),
                List.of(),
                List.of(msg(150L, "T2")));

        MailboxService.InboxPage page = svc.inboxPage("烛龙", null, 20, true, null, 100L);

        assertEquals(List.of("T1"), threadIds(page), "T2 的最新消息是 #150（在上一页），不能拿 #90 冒充本页最新");
        assertEquals(80L, page.nextBeforeId, "下一页从本页最旧一条的 latestId 继续");
        assertTrue(page.endOfInbox, "窗口没扫满且没有更多匹配 = 确实到最早了");
    }

    @Test
    @DisplayName("窗口扫满：hasMore=true、endOfInbox=false（窗口满 ≠ 信箱到底）")
    void fullScanWindowIsNotEndOfInbox() {
        List<MailMessage> many = new ArrayList<>();
        for (int i = 1; i <= 200; i++) many.add(msg(i, "T" + i));
        when(cursorMapper.selectOne(any())).thenReturn(cursor(0L));
        when(msgMapper.selectList(any())).thenReturn(many, List.of());

        MailboxService.InboxPage page = svc.inboxPage("烛龙", null, 20, true, null, null);

        assertTrue(page.windowFull, "扫了正好 200 条要标成窗口已满");
        assertTrue(page.hasMore, "窗口已满时不许说没有更早的");
        assertFalse(page.endOfInbox, "只有确实扫完才能说 endOfInbox");
        assertEquals(200, page.matched, "窗口内符合条件 200 根");
        assertEquals(20, page.items.size());
        assertEquals(page.items.get(19).latestId, page.nextBeforeId, "下一页游标取本页最后一条");
    }

    @Test
    @DisplayName("窗口满但筛选后为空：仍给 nextBeforeId，让调用方能继续往下翻")
    void emptyFilteredPageStillCarriesCursorWhenWindowFull() {
        List<MailMessage> many = new ArrayList<>();
        for (int i = 1; i <= 200; i++) many.add(msg(i, "T" + i));
        when(cursorMapper.selectOne(any())).thenReturn(cursor(0L));
        when(msgMapper.selectList(any())).thenReturn(many, List.of());

        MailboxService.InboxPage page = svc.inboxPage("烛龙", "不存在的关键词", 20, true, null, null);

        assertTrue(page.items.isEmpty());
        assertTrue(page.hasMore, "窗口满 + 筛掉全部，不能判成「没有更早的信」");
        assertEquals(1L, page.nextBeforeId, "拿窗口里最旧的消息 id 当继续翻的锚点");
    }

    @Test
    @DisplayName("lastId：只给比它更新的线程（增量刷新）")
    void lastIdReturnsOnlyNewerThreads() {
        when(cursorMapper.selectOne(any())).thenReturn(cursor(0L));
        when(msgMapper.selectList(any())).thenReturn(List.of(msg(60L, "T3"), msg(40L, "T1")), List.of());

        MailboxService.InboxPage page = svc.inboxPage("烛龙", null, 20, true, 50L, null);

        assertEquals(List.of("T3"), threadIds(page));
        assertEquals(60L, page.items.get(0).latestId);
    }

    // ---------------------------------------------------------------- 小工具

    private static List<String> threadIds(MailboxService.InboxPage p) {
        List<String> ids = new ArrayList<>();
        for (MailboxService.Envelope e : p.items) ids.add(e.threadId);
        return ids;
    }

    private static MailCursor cursor(long id) {
        MailCursor c = new MailCursor();
        c.setRole("烛龙");
        c.setThreadId("any");
        c.setLastReadId(id);
        return c;
    }

    private static MailMessage msg(long id, String threadId) {
        MailMessage m = new MailMessage();
        m.setId(id);
        m.setThreadId(threadId);
        m.setFromRole("阁主");
        m.setToRole("烛龙");
        m.setMsgType("note");
        m.setSubject("主题 " + threadId);
        m.setBody("正文");
        m.setCreatedAt(java.time.LocalDateTime.now());
        return m;
    }
}
