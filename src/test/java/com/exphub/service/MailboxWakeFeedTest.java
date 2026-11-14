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
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * 唤醒源视图（2026-11-14 阁主 W-20261114-12）。
 *
 * <p><b>ExpHub 不做唤醒</b>：唤醒回合由忆阁按 {@code mail_start(unreadOnly=true)} 的 items 决定。
 * 所以「封存后不再唤醒」在服务端的落地只有一件事：<b>把已封存线程挡在 items 之外</b>。
 * 这个测试钉的就是这一个动作，外加三条边界：
 * <ol>
 *   <li>被封存挡下的信<b>必须报数 + 报线程号</b>（不许静默消失）；</li>
 *   <li>去重窗口内的重复投递同样报数；</li>
 *   <li>没有封存服务（老环境 / 单测）时<b>行为与改动前完全一致</b>。</li>
 * </ol>
 */
class MailboxWakeFeedTest {

    private MailboxService svc;
    private MailMessageMapper msgMapper;
    private MailCursorMapper cursorMapper;
    private MailRoleCatalog roleCatalog;
    private MailThreadSealService sealService;

    @BeforeEach
    void setUp() {
        svc = new MailboxService();
        msgMapper = mock(MailMessageMapper.class);
        cursorMapper = mock(MailCursorMapper.class);
        roleCatalog = mock(MailRoleCatalog.class);
        sealService = mock(MailThreadSealService.class);
        ReflectionTestUtils.setField(svc, "msgMapper", msgMapper);
        ReflectionTestUtils.setField(svc, "cursorMapper", cursorMapper);
        ReflectionTestUtils.setField(svc, "roleCatalog", roleCatalog);
        ReflectionTestUtils.setField(svc, "sealService", sealService);
        when(roleCatalog.variants("白泽")).thenReturn(Set.of("白泽"));
    }

    private MailMessage msg(long id, String threadId, String key) {
        MailMessage m = new MailMessage();
        m.setId(id);
        m.setThreadId(threadId);
        m.setFromRole("阁主");
        m.setToRole("白泽");
        m.setMsgType("receipt");
        m.setSubject("回执 " + threadId);
        m.setBody("单号: W-20261114-06\n类型: delivered");
        m.setOrderNo("W-20261114-06");
        m.setClientMsgId(key);
        return m;
    }

    private void givenThreads(List<MailMessage> received) {
        when(msgMapper.selectList(any())).thenReturn(received, List.of());
        when(cursorMapper.selectOne(any())).thenReturn(cursor(0L));
    }

    private MailCursor cursor(long lastRead) {
        MailCursor c = new MailCursor();
        c.setRole("白泽");
        c.setThreadId("T1");
        c.setLastReadId(lastRead);
        return c;
    }

    @Test
    @DisplayName("未封存 + 首次投递：正常进唤醒源（老行为不变）")
    void normalThreadStillWakes() {
        givenThreads(List.of(msg(2L, "T1", "CM-1")));
        when(sealService.sealedWatermarks()).thenReturn(java.util.Map.of());
        when(sealService.claimWakeOffer(anyString(), anyString(), anyString(), anyLong()))
                .thenReturn(MailThreadSealService.WakeOffer.FIRST);

        MailboxService.WakeFeed feed = svc.wakeFeed("白泽", 20);

        assertEquals(1, feed.items.size());
        assertEquals(1, feed.items.get(0).freshCount);
        assertEquals(0, feed.sealedThreads);
        assertEquals(0, feed.dedupedThreads);
    }

    @Test
    @DisplayName("已封存线程：不进唤醒源，但必须报根数/封数/线程号（信仍在信箱里）")
    void sealedThreadIsExcludedButReported() {
        givenThreads(List.of(msg(2L, "T1", "CM-1"), msg(1L, "T1", "CM-0")));
        when(sealService.sealedWatermarks()).thenReturn(java.util.Map.of("T1", 0L));

        MailboxService.WakeFeed feed = svc.wakeFeed("白泽", 20);

        assertTrue(feed.items.isEmpty(), "封存线程不许进唤醒源");
        assertEquals(1, feed.sealedThreads);
        assertEquals(2, feed.sealedMessages);
        assertEquals(List.of("T1"), feed.sealedThreadIds);
        verify(sealService, never()).claimWakeOffer(anyString(), anyString(), anyString(), anyLong());
    }

    @Test
    @DisplayName("封存水位：触发封存的那封（id <= 水位）仍唤醒一次，之后的信不再唤醒")
    void triggerMessageStillWakesOnce() {
        givenThreads(List.of(msg(3L, "T1", "CM-3"), msg(2L, "T1", "CM-2")));
        when(sealService.sealedWatermarks()).thenReturn(java.util.Map.of("T1", 2L));
        when(sealService.claimWakeOffer(anyString(), anyString(), anyString(), anyLong()))
                .thenReturn(MailThreadSealService.WakeOffer.FIRST);

        MailboxService.WakeFeed feed = svc.wakeFeed("白泽", 20);

        assertEquals(1, feed.items.size(), "触发封存的那封必须还能叫醒一次（否则回执被吞、验收推进不了）");
        assertEquals(1, feed.items.get(0).freshCount);
        assertEquals(1, feed.sealedTriggerThreads);
        assertEquals(1, feed.sealedSuppressedMessages, "水位之上的那封不许再唤醒");
        assertEquals(0, feed.sealedThreads, "有触发封继续唤醒时不能把它算成「整根都没了」");
    }

    @Test
    @DisplayName("去重窗口内的重复投递：不进唤醒源，报根数/封数")
    void suppressedThreadIsReported() {
        givenThreads(List.of(msg(2L, "T1", "CM-1")));
        when(sealService.sealedWatermarks()).thenReturn(java.util.Map.of());
        when(sealService.claimWakeOffer(anyString(), anyString(), anyString(), anyLong()))
                .thenReturn(MailThreadSealService.WakeOffer.SUPPRESSED);

        MailboxService.WakeFeed feed = svc.wakeFeed("白泽", 20);

        assertTrue(feed.items.isEmpty());
        assertEquals(1, feed.dedupedThreads);
        assertEquals(1, feed.dedupedMessages);
        assertEquals(List.of("T1"), feed.dedupedThreadIds);
    }

    @Test
    @DisplayName("窗口过后的重试照常放行，并报「本次是重试」")
    void retryAfterWindowIsOffered() {
        givenThreads(List.of(msg(2L, "T1", "CM-1")));
        when(sealService.sealedWatermarks()).thenReturn(java.util.Map.of());
        when(sealService.claimWakeOffer(anyString(), anyString(), anyString(), anyLong()))
                .thenReturn(MailThreadSealService.WakeOffer.RETRY);

        MailboxService.WakeFeed feed = svc.wakeFeed("白泽", 20);

        assertEquals(1, feed.items.size());
        assertEquals(1, feed.retriedMessages);
    }

    @Test
    @DisplayName("同一线程里一封被去重、一封是新的：只数新那封")
    void partialSuppressionCountsOnlyOffered() {
        givenThreads(List.of(msg(2L, "T1", "CM-2"), msg(1L, "T1", "CM-1")));
        when(sealService.sealedWatermarks()).thenReturn(java.util.Map.of());
        when(sealService.claimWakeOffer(anyString(), eq("CM-2"), anyString(), anyLong()))
                .thenReturn(MailThreadSealService.WakeOffer.FIRST);
        when(sealService.claimWakeOffer(anyString(), eq("CM-1"), anyString(), anyLong()))
                .thenReturn(MailThreadSealService.WakeOffer.SUPPRESSED);

        MailboxService.WakeFeed feed = svc.wakeFeed("白泽", 20);

        assertEquals(1, feed.items.size());
        assertEquals(1, feed.items.get(0).freshCount, "只数真正会唤醒的那封");
        assertEquals(1, feed.suppressedMessages);
    }

    @Test
    @DisplayName("没有封存服务时行为与改动前一致（降级不改变老行为）")
    void degradesToOldBehaviourWithoutSealService() {
        ReflectionTestUtils.setField(svc, "sealService", null);
        givenThreads(List.of(msg(2L, "T1", "CM-1")));

        MailboxService.WakeFeed feed = svc.wakeFeed("白泽", 20);

        assertEquals(1, feed.items.size());
        assertEquals(1, feed.items.get(0).freshCount);
        assertEquals(0, feed.sealedThreads);
    }

    @Test
    @DisplayName("写封信给已封存线程：仍然入库、仍然能在默认列表里看到（只是不进唤醒源）")
    void sealedLettersRemainVisibleInNormalInbox() {
        givenThreads(List.of(msg(2L, "T1", "CM-1")));
        com.exphub.entity.MailThreadState sealed = new com.exphub.entity.MailThreadState();
        sealed.setThreadId("T1");
        sealed.setState(MailThreadSealService.ST_SEALED);
        sealed.setSealedBy("阁主");
        sealed.setOrderNo("W-20261114-06");
        when(sealService.stateOf("T1")).thenReturn(sealed);

        List<MailboxService.Envelope> inbox = svc.inbox("白泽", null, 20, false);

        assertEquals(1, inbox.size(), "默认列表必须仍然看得到这封信");
        assertTrue(inbox.get(0).sealed);
        assertEquals("W-20261114-06", inbox.get(0).orderNo);
    }
}
