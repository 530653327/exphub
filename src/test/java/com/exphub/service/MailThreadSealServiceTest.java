package com.exphub.service;

import com.exphub.entity.MailMessage;
import com.exphub.entity.MailThreadState;
import com.exphub.entity.MailThreadStateLog;
import com.exphub.mapper.MailMessageMapper;
import com.exphub.mapper.MailThreadStateLogMapper;
import com.exphub.mapper.MailThreadStateMapper;
import com.exphub.mapper.MailWakeOfferMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * 线程封存 + 唤醒去重（2026-11-14 阁主 W-20261114-12）。
 *
 * <p>钉住四件事，每一条都对应 W-06 那次真实事故的一环：
 * <ol>
 *   <li><b>按单号封存必须覆盖全部线程</b>，不是只封主线程；</li>
 *   <li><b>人工解锁优先</b>：之后的自动封存不许把它又封回去（否则「解锁了也不生效」，
 *       而这正是本单要求「解锁入口是必做项」的理由）；</li>
 *   <li><b>delivered 回执才自动封存</b>，blocked 不封（单还在跑，封了就没人叫醒）；</li>
 *   <li><b>去重是有窗口的</b>：窗口内吞掉重复投递，窗口外放行重试 ——
 *       永久只唤醒一次会把「提供过但回合崩了」的信变成静默死信。</li>
 * </ol>
 */
class MailThreadSealServiceTest {

    private MailThreadSealService svc;
    private MailThreadStateMapper stateMapper;
    private MailThreadStateLogMapper logMapper;
    private MailWakeOfferMapper offerMapper;
    private MailMessageMapper msgMapper;

    @BeforeEach
    void setUp() {
        svc = new MailThreadSealService();
        stateMapper = mock(MailThreadStateMapper.class);
        logMapper = mock(MailThreadStateLogMapper.class);
        offerMapper = mock(MailWakeOfferMapper.class);
        msgMapper = mock(MailMessageMapper.class);
        ReflectionTestUtils.setField(svc, "stateMapper", stateMapper);
        ReflectionTestUtils.setField(svc, "logMapper", logMapper);
        ReflectionTestUtils.setField(svc, "offerMapper", offerMapper);
        ReflectionTestUtils.setField(svc, "msgMapper", msgMapper);
    }

    // ---------------------------------------------------------------- 协议解析

    @Test
    @DisplayName("单号解析：回执首行「单号: W-…」、全角冒号、主题里的单号都要认")
    void parsesOrderNoFromProtocolLines() {
        assertEquals("W-20261114-06",
                MailThreadSealService.orderNoOf("回执", "单号: W-20261114-06\n类型: delivered\n正文"));
        assertEquals("W-20261114-06",
                MailThreadSealService.orderNoOf("回执", "单号：W-20261114-06\n类型: blocked"));
        assertEquals("W-20261114-12",
                MailThreadSealService.orderNoOf("派单 W-20261114-12：线程封存", "正文里没有单号行"));
        assertEquals("W-20261114-12",
                MailThreadSealService.orderNoOf("w-20261114-12 小写也要归一", null));
    }

    @Test
    @DisplayName("单号解析：正文里引用别人的单号不算（只看前 15 行），解析不到就 return null 不猜")
    void doesNotGuessOrderNo() {
        String body = "正文第一行\n".repeat(20) + "单号: W-20261114-99";
        assertNull(MailThreadSealService.orderNoOf("没有单号的主题", body));
        assertNull(MailThreadSealService.orderNoOf(null, null));
        assertNull(MailThreadSealService.orderNoOf("W-2026", "随便写写"));
    }

    @Test
    @DisplayName("回执类型解析：delivered / blocked 认，其它形态 null")
    void parsesReceiptVerdict() {
        assertEquals("delivered", MailThreadSealService.receiptVerdict("单号: W-20261114-06\n类型: delivered"));
        assertEquals("blocked", MailThreadSealService.receiptVerdict("单号: W-20261114-06\n类型：blocked"));
        assertNull(MailThreadSealService.receiptVerdict("单号: W-20261114-06\n类型: 待定"));
        assertNull(MailThreadSealService.receiptVerdict(null));
    }

    // ---------------------------------------------------------------- 封存

    @Test
    @DisplayName("按单号封存：该单全部线程都要封，不能只封主线程")
    void sealsAllThreadsOfOrder() {
        when(msgMapper.selectThreadIdsByOrderNo("W-20261114-06")).thenReturn(List.of("T1", "T2", "T3"));
        when(msgMapper.selectCount(any())).thenReturn(1L);
        when(stateMapper.selectById(anyString())).thenReturn(null);
        when(stateMapper.insert(any(MailThreadState.class))).thenReturn(1);
        when(logMapper.insert(any(MailThreadStateLog.class))).thenReturn(1);

        MailThreadSealService.SealOutcome r =
                svc.sealOrder("W-20261114-06", "阁主", "单转待验收", false);

        assertEquals(3, r.sealed().size(), "3 根线程都要封：" + r);
        assertEquals(3, r.orderThreads().size());
        verify(stateMapper, times(3)).insert(any(MailThreadState.class));
        verify(logMapper, times(3)).insert(any(MailThreadStateLog.class));
    }

    @Test
    @DisplayName("重复封存不重复写、不重复留痕（幂等）")
    void reSealIsIdempotent() {
        when(msgMapper.selectThreadIdsByOrderNo("W-1")).thenReturn(List.of("T1"));
        when(msgMapper.selectCount(any())).thenReturn(1L);
        MailThreadState exist = new MailThreadState();
        exist.setThreadId("T1");
        exist.setOrderNo("W-20261114-01");
        exist.setState(MailThreadSealService.ST_SEALED);
        when(stateMapper.selectById("T1")).thenReturn(exist);

        MailThreadSealService.SealOutcome r = svc.sealOrder("W-1", "阁主", "再来一次", false);

        assertEquals(0, r.sealed().size());
        assertEquals(List.of("T1"), r.alreadySealed());
        verify(stateMapper, never()).insert(any(MailThreadState.class));
        verify(logMapper, never()).insert(any(MailThreadStateLog.class));
    }

    @Test
    @DisplayName("人工解锁过的线程：自动封存跳过它，手动封存可以重新封")
    void autoSealMustNotOverrideHumanUnseal() {
        when(msgMapper.selectThreadIdsByOrderNo("W-20261114-06")).thenReturn(List.of("T1"));
        when(msgMapper.selectCount(any())).thenReturn(1L);
        MailThreadState open = new MailThreadState();
        open.setThreadId("T1");
        open.setState(MailThreadSealService.ST_OPEN);
        when(stateMapper.selectById("T1")).thenReturn(open);
        when(logMapper.insert(any(MailThreadStateLog.class))).thenReturn(1);

        MailThreadSealService.SealOutcome auto =
                svc.sealOrder("W-20261114-06", "auto:receipt-delivered#9", "", true);
        assertEquals(List.of("T1"), auto.skippedHumanOpen(), "自动封存不许覆盖人工解锁：" + auto);
        assertEquals(0, auto.sealed().size());

        MailThreadSealService.SealOutcome manual =
                svc.sealOrder("W-20261114-06", "阁主", "重新封", false);
        assertEquals(1, manual.sealed().size(), "人工显式封存应当生效：" + manual);
    }

    @Test
    @DisplayName("线程不存在时明说，不假装封了")
    void reportsMissingThreads() {
        when(msgMapper.selectCount(any())).thenReturn(0L);

        MailThreadSealService.SealOutcome r =
                svc.sealThreads(List.of("T404"), null, null, "阁主", "", false);

        assertEquals(List.of("T404"), r.notFound());
        assertEquals(0, r.sealed().size());
        verify(stateMapper, never()).insert(any(MailThreadState.class));
    }

    // ---------------------------------------------------------------- 自动封存触发

    @Test
    @DisplayName("delivered 回执触发自动封存该单全部线程")
    void deliveredReceiptTriggersAutoSeal() {
        when(msgMapper.selectThreadIdsByOrderNo("W-20261114-06")).thenReturn(List.of("T1", "T2"));
        when(msgMapper.selectCount(any())).thenReturn(1L);
        when(stateMapper.selectById(anyString())).thenReturn(null);
        when(logMapper.insert(any(MailThreadStateLog.class))).thenReturn(1);

        MailMessage m = new MailMessage();
        m.setId(9L);
        m.setThreadId("T1");
        m.setMsgType("receipt");
        m.setSubject("回执 W-20261114-06");
        m.setBody("单号: W-20261114-06\n类型: delivered\n干完了");
        m.setOrderNo("W-20261114-06");

        MailThreadSealService.AutoSealOutcome r = svc.autoSealIfDelivered(m);

        assertNotNull(r);
        assertEquals("W-20261114-06", r.orderNo());
        assertEquals(2, r.outcome().sealed().size());
    }

    @Test
    @DisplayName("blocked 回执 / 普通信不自动封存（单还在跑，封了没人叫醒）")
    void blockedOrNoteNeverAutoSeals() {
        MailMessage blocked = new MailMessage();
        blocked.setId(9L);
        blocked.setThreadId("T1");
        blocked.setMsgType("receipt");
        blocked.setBody("单号: W-20261114-06\n类型: blocked\n卡住了");
        blocked.setOrderNo("W-20261114-06");
        assertNull(svc.autoSealIfDelivered(blocked));

        MailMessage note = new MailMessage();
        note.setId(10L);
        note.setThreadId("T1");
        note.setMsgType("note");
        note.setBody("单号: W-20261114-06\n类型: delivered");
        assertNull(svc.autoSealIfDelivered(note));

        verify(stateMapper, never()).insert(any(MailThreadState.class));
    }

    // ---------------------------------------------------------------- 解锁

    @Test
    @DisplayName("解封：写 OPEN + 留痕（谁、何时、备注），行不删")
    void unsealKeepsRowAndLogs() {
        MailThreadState sealed = new MailThreadState();
        sealed.setThreadId("T1");
        sealed.setOrderNo("W-20261114-06");
        sealed.setState(MailThreadSealService.ST_SEALED);
        when(stateMapper.selectById("T1")).thenReturn(sealed);
        when(logMapper.insert(any(MailThreadStateLog.class))).thenReturn(1);

        MailThreadSealService.UnsealOutcome r = svc.unseal("T1", "阁主", "要追加说明");

        assertTrue(r.changed());
        assertEquals(MailThreadSealService.ST_OPEN, r.state().getState());
        assertNotNull(r.state().getUnsealedAt());
        assertEquals("阁主", r.state().getUnsealedBy());
        assertEquals("要追加说明", r.state().getUnsealNote());
        verify(stateMapper).updateById(sealed);
        verify(logMapper).insert(any(MailThreadStateLog.class));
    }

    @Test
    @DisplayName("解封：本来就没封过 → 明说「没有发生变更」，不假装成功")
    void unsealOnOpenThreadIsHonest() {
        when(stateMapper.selectById("T1")).thenReturn(null);
        MailThreadSealService.UnsealOutcome r = svc.unseal("T1", "阁主", "");
        assertFalse(r.changed());
        assertTrue(r.detail().contains("从来没有被封存过"), r.detail());

        MailThreadState open = new MailThreadState();
        open.setThreadId("T2");
        open.setState(MailThreadSealService.ST_OPEN);
        when(stateMapper.selectById("T2")).thenReturn(open);
        assertFalse(svc.unseal("T2", "阁主", "").changed());
        verify(logMapper, never()).insert(any(MailThreadStateLog.class));
    }

    // ---------------------------------------------------------------- 唤醒去重

    @Test
    @DisplayName("唤醒去重：第一次放行、窗口内吞掉、窗口外放行重试")
    void wakeOfferIsWindowed() {
        when(offerMapper.tryInsert(anyString(), anyString(), anyString(), anyString(), anyLong())).thenReturn(1);
        assertEquals(MailThreadSealService.WakeOffer.FIRST,
                svc.claimWakeOffer("W-20261114-06", "CM-1", "T1", 11L));

        when(offerMapper.tryInsert(anyString(), anyString(), anyString(), anyString(), anyLong())).thenReturn(0);
        when(offerMapper.touchIfExpired(anyString(), anyLong())).thenReturn(0);
        assertEquals(MailThreadSealService.WakeOffer.SUPPRESSED,
                svc.claimWakeOffer("W-20261114-06", "CM-1", "T1", 11L));

        when(offerMapper.touchIfExpired(anyString(), anyLong())).thenReturn(1);
        assertEquals(MailThreadSealService.WakeOffer.RETRY,
                svc.claimWakeOffer("W-20261114-06", "CM-1", "T1", 11L));
    }

    @Test
    @DisplayName("没有单号或没有幂等键的信不做去重（老单行为完全不变）")
    void noDedupeWithoutOrderOrClientKey() {
        assertEquals(MailThreadSealService.WakeOffer.FIRST, svc.claimWakeOffer(null, "CM-1", "T1", 1L));
        assertEquals(MailThreadSealService.WakeOffer.FIRST, svc.claimWakeOffer("W-1", null, "T1", 1L));
        assertEquals(MailThreadSealService.WakeOffer.FIRST, svc.claimWakeOffer("W-1", " ", "T1", 1L));
        verify(offerMapper, never()).tryInsert(anyString(), anyString(), anyString(), anyString(), anyLong());
    }

    @Test
    @DisplayName("去重记账异常时按首次放行（宁可多唤醒一次，也不静默吞信）")
    void dedupeFailureFailsOpen() {
        when(offerMapper.tryInsert(anyString(), anyString(), anyString(), anyString(), anyLong()))
                .thenThrow(new RuntimeException("db down"));
        assertEquals(MailThreadSealService.WakeOffer.FIRST,
                svc.claimWakeOffer("W-20261114-06", "CM-1", "T1", 11L));
    }
}
