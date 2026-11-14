package com.exphub.mcp;

import com.exphub.entity.MailMessage;
import com.exphub.entity.MailThreadState;
import com.exphub.entity.MailThreadStateLog;
import com.exphub.service.MailThreadSealService;
import com.exphub.service.MailboxService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * 线程封存 / 人工解锁的<b>回执</b>（2026-11-14 阁主 W-20261114-12）。
 *
 * <p>钉住的是「一个人读完就能动手」那几条：
 * <ol>
 *   <li>封存回执要说清封了几根、哪些没封（不存在/人工解锁过）；</li>
 *   <li>解锁回执要自证「谁解的、什么时候」（留痕），并说明解锁后还有几封未读；</li>
 *   <li>往已封存线程发信，回执必须写「不会触发唤醒」——不写就等于骗人说对方被叫醒了；</li>
 *   <li>非法输入明拒并回显收到的实际值（AGENTS 9c 不许静默修正）。</li>
 * </ol>
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class MailThreadSealReceiptTest {

    @Mock
    private MailboxService mailboxService;

    @Mock
    private MailThreadSealService mailSealService;

    @InjectMocks
    private ExpHubTools tools;

    @BeforeEach
    void setUp() {
        MailIdentity.set("gezhu", "cfg-gezhu-memhaven-prod");
        when(mailboxService.canonicalRole(anyString())).thenAnswer(inv -> inv.getArgument(0));
        when(mailboxService.unreadCount(anyString())).thenReturn(0);
    }

    @AfterEach
    void clear() {
        MailIdentity.clear();
    }

    private MailThreadState state(String tid, String st, String orderNo) {
        MailThreadState s = new MailThreadState();
        s.setThreadId(tid);
        s.setState(st);
        s.setOrderNo(orderNo);
        s.setSealedAt(LocalDateTime.now());
        s.setSealedBy("阁主");
        return s;
    }

    // ---------------------------------------------------------------- 唤醒源视图

    @Test
    @DisplayName("unreadOnly：封存线程不进 items，但回执要报根数/封数/线程号 + 恢复入口")
    void unreadOnlyReportsSealedThreads() {
        MailboxService.WakeFeed feed = new MailboxService.WakeFeed();
        MailboxService.Envelope e = new MailboxService.Envelope();
        e.threadId = "T-OPEN";
        e.subject = "还在跑的线";
        e.fromRole = "阁主";
        e.msgType = "dispatch";
        e.freshCount = 2;
        e.latestAt = LocalDateTime.now();
        e.latestId = 12L;
        e.orderNo = "W-20261114-13";
        feed.items.add(e);
        feed.sealedThreads = 1;
        feed.sealedMessages = 3;
        feed.sealedThreadIds.add("T-SEALED");
        when(mailboxService.wakeFeed(anyString(), anyInt())).thenReturn(feed);

        String r = tools.mailStart(null, 20, null, null, true, null, null, null, null);

        assertTrue(r.contains("可唤醒（唤醒源视图）：1 根线程 / 2 封消息"), r);
        assertTrue(r.contains("另有 1 根已封存线程 / 3 封新信"), "封存挡下的必须报数：" + r);
        assertTrue(r.contains("T-SEALED"), "要点出线程号，不能只报条数：" + r);
        assertTrue(r.contains("unseal=true"), "必须给出恢复唤醒的入口：" + r);
        assertTrue(r.contains("\"sealedThreads\":1"), "JSON 也要带封存计数：" + r);
        assertTrue(r.contains("单号 W-20261114-13"), r);
    }

    @Test
    @DisplayName("unreadOnly：去重窗口内的重复投递要报数，并说明窗口过期会重试")
    void unreadOnlyReportsDedupedThreads() {
        MailboxService.WakeFeed feed = new MailboxService.WakeFeed();
        feed.dedupedThreads = 1;
        feed.dedupedMessages = 1;
        feed.dedupedThreadIds.add("T-DUP");
        when(mailboxService.wakeFeed(anyString(), anyInt())).thenReturn(feed);

        String r = tools.mailStart(null, 20, null, null, true, null, null, null, null);

        assertTrue(r.contains("唤醒去重窗口"), r);
        assertTrue(r.contains("T-DUP"), r);
        assertTrue(r.contains("窗口过期后仍会重试"), "不许让人以为会被永久吞掉：" + r);
    }

    // ---------------------------------------------------------------- 封存 / 解锁

    @Test
    @DisplayName("封存回执：新封几根、原本已封、不存在、人工解锁跳过都要写")
    void sealReceiptListsEverything() {
        when(mailSealService.sealOrder(eq("W-20261114-06"), anyCollection(), anyString(), any(), eq(false)))
                .thenReturn(new MailThreadSealService.SealOutcome("W-20261114-06",
                        List.of("T1", "T2"), List.of("T3"), List.of("T4"), List.of("T404"),
                        List.of("T1", "T2", "T3", "T4")));

        String r = tools.mailThread(null, null, null, null, null, null, null, null,
                "W-20261114-06", null, null, "单转待验收");

        assertTrue(r.contains("本次新封存: 2"), r);
        assertTrue(r.contains("原本已封存（未重复写）: 1"), r);
        assertTrue(r.contains("人工解锁过、本次跳过: 1"), r);
        assertTrue(r.contains("线程不存在（没封）: T404"), r);
        assertTrue(r.contains("封存只影响**唤醒源**"), "必须写清代价（信还在、只是不唤醒）：" + r);
    }

    @Test
    @DisplayName("封存单号格式不对：明拒并回显收到的实际值，不做猜测")
    void rejectsBadOrderNo() {
        String r = tools.mailThread(null, null, null, null, null, null, null, null,
                "20261114-06", null, null, null);

        assertTrue(r.startsWith("❌"), r);
        assertTrue(r.contains("20261114-06"), "要回显实际收到的值：" + r);
        verify(mailSealService, never()).sealOrder(any(), anyCollection(), any(), any(), anyBoolean());
    }

    @Test
    @DisplayName("解锁回执：写清谁解的、什么时候、留痕，并说明解锁后还有几封未读")
    void unsealReceiptShowsTrace() {
        MailThreadState st = state("T1", MailThreadSealService.ST_OPEN, "W-20261114-06");
        st.setUnsealedAt(LocalDateTime.now());
        st.setUnsealedBy("阁主");
        st.setUnsealNote("要追加说明");
        when(mailSealService.unseal(eq("T1"), anyString(), any())).thenReturn(
                new MailThreadSealService.UnsealOutcome(true, st, "已解锁"));
        when(mailboxService.countAfterCursor(anyString(), eq("T1"))).thenReturn(3L);
        MailThreadStateLog l = new MailThreadStateLog();
        l.setAction("UNSEAL");
        l.setActor("阁主");
        l.setCreatedAt(LocalDateTime.now());
        when(mailSealService.logOf(eq("T1"), anyInt())).thenReturn(List.of(l));

        String r = tools.mailThread("T1", null, null, null, null, null, null, null,
                null, null, true, "要追加说明");

        assertTrue(r.contains("已解锁线程 T1"), r);
        assertTrue(r.contains("由 阁主"), "要自证谁解的：" + r);
        assertTrue(r.contains("在这根线程里还有 3 封未读"), "解锁后会把积压一起处理，必须提前说：" + r);
        assertTrue(r.contains("留痕"), r);
    }

    @Test
    @DisplayName("解锁没给 threadId / 与封存同传：明拒，不做半个动作")
    void rejectsAmbiguousUnseal() {
        assertTrue(tools.mailThread(null, null, null, null, null, null, null, null,
                null, null, true, null).contains("必须带 threadId"));

        String both = tools.mailThread("T1", null, null, null, null, null, null, null,
                "W-20261114-06", null, true, null);
        assertTrue(both.contains("互斥"), both);
        verify(mailSealService, never()).unseal(anyString(), anyString(), any());
    }

    @Test
    @DisplayName("封存/解锁不能和读参数混传（不返回正文、不推游标）")
    void rejectsMixedReadParams() {
        String r = tools.mailThread("T1", null, null, null, null, null, true, null,
                "W-20261114-06", null, null, null);
        assertTrue(r.contains("不能和 peek"), r);
    }

    // ---------------------------------------------------------------- 发信后的封存提示

    @Test
    @DisplayName("往已封存线程发信：回执写「不会触发唤醒」并给解锁入口")
    void sendIntoSealedThreadSaysNoWake() {
        MailMessage m = new MailMessage();
        m.setId(21L);
        m.setThreadId("T-Sealed");
        m.setFromRole("阁主");
        m.setToRole("烛龙");
        m.setMsgType("note");
        m.setSubject("追加说明");
        m.setBody("正文");
        m.setOrderNo("W-20261114-06");
        m.setCreatedAt(LocalDateTime.now());
        when(mailboxService.sendDetailed(anyString(), anyString(), anyString(), anyString(),
                isNull(), anyString(), any(), any(), any(), any(), any(), any()))
                .thenReturn(new MailboxService.SendResult(m, false));
        when(mailSealService.isSealed("T-Sealed")).thenReturn(true);
        when(mailSealService.autoSealIfDelivered(any())).thenReturn(null);

        String r = tools.mailSend("烛龙", "追加说明", "正文", null, "note", null,
                null, null, null, null);

        assertTrue(r.contains("不会触发唤醒"), "不写这句就等于骗人说对方被叫醒了：" + r);
        assertTrue(r.contains("unseal=true"), r);
    }

    @Test
    @DisplayName("delivered 回执：回执写明自动封存了几根，并说明本封仍会唤醒一次")
    void deliveredReceiptReportsAutoSeal() {
        MailMessage m = new MailMessage();
        m.setId(22L);
        m.setThreadId("T1");
        m.setFromRole("白泽");
        m.setToRole("阁主");
        m.setMsgType("receipt");
        m.setSubject("回执 W-20261114-06");
        m.setBody("单号: W-20261114-06\n类型: delivered");
        m.setOrderNo("W-20261114-06");
        m.setCreatedAt(LocalDateTime.now());
        when(mailboxService.sendDetailed(anyString(), anyString(), anyString(), anyString(),
                isNull(), anyString(), any(), any(), any(), any(), any(), any()))
                .thenReturn(new MailboxService.SendResult(m, false));
        when(mailSealService.isSealed(anyString())).thenReturn(false);
        when(mailSealService.autoSealIfDelivered(any())).thenReturn(
                new MailThreadSealService.AutoSealOutcome("W-20261114-06",
                        new MailThreadSealService.SealOutcome("W-20261114-06",
                                List.of("T1", "T2"), List.of(), List.of(), List.of(),
                                List.of("T1", "T2"))));

        String r = tools.mailSend("阁主", "回执 W-20261114-06", "单号: W-20261114-06\n类型: delivered",
                null, "receipt", null, null, null, null, null);

        assertTrue(r.contains("处理单 W-20261114-06 的关联线程"), r);
        assertTrue(r.contains("新封 2 根"), r);
        assertTrue(r.contains("本封回执本身仍会唤醒一次"), "别让人以为回执也被吞了：" + r);
    }
}
