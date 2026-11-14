package com.exphub.mcp;

import com.exphub.entity.MailMessage;
import com.exphub.service.MailboxService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * mail_thread 按幂等键查消息（阁主 T261114084728-182 第 2 问）。
 *
 * <p>现场：幂等命中之后，调用方想核实「这个键到底落没落库、对应哪条消息」，原先只有
 * app.log 里那一行可以查，日志一滚就什么都查不到。本入口把它变成一次只读查询。
 *
 * <p>钉三件事：① 查到时给全核对字段；② 查不到要明说「没落库、下次会新建」，不许含糊；
 * ③ 纯只读 —— 不推游标、不认领、不标已处理，也不许与这些动作参数混用。
 */
@ExtendWith(MockitoExtension.class)
class MailThreadByClientMsgIdTest {

    @Mock
    private MailboxService mailboxService;

    @InjectMocks
    private ExpHubTools tools;

    @AfterEach
    void clearIdentity() {
        MailIdentity.clear();
    }

    private MailMessage letter() {
        MailMessage m = new MailMessage();
        m.setId(1986L);
        m.setThreadId("T261114002609-154");
        m.setInReplyTo(null);
        m.setFromRole("阁主");
        m.setToRole("白泽");
        m.setMsgType("dispatch");
        m.setSubject("W-20261114-02 忆阁发版 3.4.20");
        m.setBody("正文占位");
        m.setClientMsgId("dispatch:W-20261114-02");
        m.setCreatedAt(LocalDateTime.of(2026, 11, 14, 0, 26, 9));
        return m;
    }

    @Test
    @DisplayName("查到：给消息 id/线程/时间/收发人/类型/主题/字数，并说明这个键已占用")
    void foundGivesAllFieldsNeededToCheck() {
        when(mailboxService.byClientMsgId("dispatch:W-20261114-02")).thenReturn(letter());

        String r = tools.mailThread(null, "dispatch:W-20261114-02", null, null, null, null, null, null);

        assertTrue(r.startsWith("🔎 按 clientMsgId 查询（只读）"), r);
        assertTrue(r.contains("dispatch:W-20261114-02"), r);
        assertTrue(r.contains("消息 #1986"), r);
        assertTrue(r.contains("T261114002609-154"), r);
        assertTrue(r.contains("11-14 00:26"), "要给出创建时间，调用方才能判断是不是本次调用：" + r);
        assertTrue(r.contains("【阁主】→【白泽】"), r);
        assertTrue(r.contains("类型 dispatch") || r.contains("，类型 dispatch"), r);
        assertTrue(r.contains("W-20261114-02 忆阁发版 3.4.20"), r);
        assertTrue(r.contains("正文 4 字"), r);
        assertTrue(r.contains("再用同键发信只会回放它"), r);
        assertTrue(r.contains("mail_thread(threadId=\"T261114002609-154\")"), "要给出展开线程的下一步：" + r);
    }

    @Test
    @DisplayName("查不到：明说这个键没落库、下次发信会新建（不许含糊成「可能没发」）")
    void notFoundSaysKeyIsFree() {
        when(mailboxService.byClientMsgId("dispatch:W-20261114-09")).thenReturn(null);

        String r = tools.mailThread(null, "dispatch:W-20261114-09", null, null, null, null, null, null);

        assertTrue(r.contains("库里没有这个键对应的消息"), r);
        assertTrue(r.contains("会是一次**新建**"), r);
        assertFalse(r.contains("消息 #"), "没查到就不许编一个消息号出来：" + r);
    }

    @Test
    @DisplayName("纯只读：不推游标、不认领、不标已处理")
    void queryIsReadOnly() {
        when(mailboxService.byClientMsgId("K-1")).thenReturn(letter());

        tools.mailThread(null, "K-1", null, null, null, null, null, null);

        verify(mailboxService, never()).thread(anyString(), anyString(), anyBoolean(), anyInt(), anyBoolean());
        verify(mailboxService, never()).advanceCursorTo(anyString(), anyString(), anyLong());
        verify(mailboxService, never()).advanceCursor(anyString(), anyString(), anyLong());
        verify(mailboxService, never()).claim(anyLong(), anyString());
        verify(mailboxService, never()).markProcessed(anyLong(), anyString());
    }

    @Test
    @DisplayName("threadId 与 clientMsgId 同时传：明拒（不知道按哪个口径返回）")
    void bothKeysRejected() {
        String r = tools.mailThread("T-1", "K-1", null, null, null, null, null, null);

        assertTrue(r.startsWith("❌"), r);
        assertTrue(r.contains("只能传一个"), r);
        verifyNoInteractions(mailboxService);
    }

    @Test
    @DisplayName("按键查询与 peek / advanceTo / claimId / ackIds 混用：明拒（那些会改状态）")
    void mutatingParamsRejected() {
        String withPeek = tools.mailThread(null, "K-1", null, null, null, null, true, null);
        assertTrue(withPeek.contains("不接受 peek"), withPeek);

        String withAdvance = tools.mailThread(null, "K-1", null, null, null, null, null, 42L);
        assertTrue(withAdvance.contains("不接受 advanceTo"), withAdvance);

        String withClaim = tools.mailThread(null, "K-1", null, null, null, 42L, null, null);
        assertTrue(withClaim.contains("不接受 claimId"), withClaim);

        String withAck = tools.mailThread(null, "K-1", null, null, "7", null, null, null);
        assertTrue(withAck.contains("不接受 ackIds"), withAck);

        verifyNoInteractions(mailboxService);
    }

    @Test
    @DisplayName("传了 limit：回执明说被忽略，不静默丢掉参数")
    void limitIsReportedAsIgnored() {
        when(mailboxService.byClientMsgId("K-1")).thenReturn(letter());

        String r = tools.mailThread(null, "K-1", null, 5, null, null, null, null);

        assertTrue(r.contains("limit 对单条查询无意义，已忽略"), r);
    }
}
