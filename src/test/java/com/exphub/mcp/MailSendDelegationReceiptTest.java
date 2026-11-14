package com.exphub.mcp;

import com.exphub.entity.MailMessage;
import com.exphub.service.MailboxService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.when;

/**
 * 代发的<b>出口</b>：收件侧必须看到「越哥（经阁主代发）」，同时不能把真实发件人藏掉
 * （2026-11-14 阁主 W-20261114-09）。
 *
 * <p>三条要钉住的行为：
 * <ol>
 *   <li>回执自证「显示什么 / 真实是谁」，并明写这是委托信任不是鉴权；</li>
 *   <li>{@code mail_thread} 与 {@code mail_start}（人读 + 末行 JSON）看到的是同一个展示串
 *       —— 两个出口各写一份文案，必然有一份是旧的；</li>
 *   <li>无代发的信逐字不变（历史信一个字都不动）。</li>
 * </ol>
 */
@ExtendWith(MockitoExtension.class)
class MailSendDelegationReceiptTest {

    @Mock
    private MailboxService mailboxService;

    @InjectMocks
    private ExpHubTools tools;

    @BeforeEach
    void setUp() {
        MailIdentity.set("gezhu", "cfg-gezhu-memhaven-prod");
    }

    @AfterEach
    void clear() {
        MailIdentity.clear();
    }

    private static MailMessage msg(String from, String onBehalfOf, String to) {
        MailMessage m = new MailMessage();
        m.setId(2073L);
        m.setThreadId("T261114134802-723");
        m.setInReplyTo(null);
        m.setFromRole(from);
        m.setOnBehalfOf(onBehalfOf);
        m.setToRole(to);
        m.setMsgType("dispatch");
        m.setSubject("W-20261114-09 派单");
        m.setBody("正文");
        m.setCreatedAt(LocalDateTime.now());
        return m;
    }

    @Test
    @DisplayName("真发一封代发信：回执同时写「显示越哥」与「真实发件人仍是阁主」")
    void newSendReceiptShowsBothIdentities() {
        MailMessage m = msg("阁主", "越哥", "烛龙");
        when(mailboxService.canonicalRole("gezhu")).thenReturn("阁主");
        when(mailboxService.canonicalRole("烛龙")).thenReturn("烛龙");
        when(mailboxService.sendDetailed(eq("阁主"), eq("烛龙"), anyString(), anyString(),
                isNull(), anyString(), isNull(), anyString(), isNull(), isNull(), isNull(), eq("越哥")))
                .thenReturn(new MailboxService.SendResult(m, false));

        String r = tools.mailSend("烛龙", "W-20261114-09 派单", "正文", null, "dispatch", null,
                null, null, null, "越哥");

        assertTrue(r.contains("收件侧显示「越哥（经阁主代发）」"), "收件侧必须看到越哥：" + r);
        assertTrue(r.contains("真实发件人仍是【阁主】"), "不许把真实发件人藏掉：" + r);
        assertTrue(r.contains("委托信任，不是密码学鉴权"), "必须写清边界，不许暗示已成为鉴权：" + r);
    }

    @Test
    @DisplayName("mail_thread：消息头显示「越哥（经阁主代发）」")
    void threadRendersDelegatedSender() {
        MailMessage m = msg("阁主", "越哥", "烛龙");
        when(mailboxService.canonicalRole("zhulong")).thenReturn("烛龙");
        when(mailboxService.thread(eq("烛龙"), eq("T261114134802-723"), eq(true), anyInt(), eq(false)))
                .thenReturn(List.of(m));
        when(mailboxService.countThread("T261114134802-723")).thenReturn(1L);
        when(mailboxService.threadSubject("T261114134802-723")).thenReturn("W-20261114-09 派单");
        MailIdentity.set("zhulong", "cfg-zhulong-opencode-mac");

        String r = tools.mailThread("T261114134802-723", null, true, null, null, null, null, null);

        assertTrue(r.contains("[dispatch] 越哥（经阁主代发） → 烛龙"), "线程头必须显示代发身份：" + r);
        assertFalse(r.contains("[dispatch] 阁主 → 烛龙"), "不许只显示阁主：" + r);
    }

    @Test
    @DisplayName("无代发的信：展示保持原样（不凭空多出括号）")
    void nonDelegatedMessageUnchanged() {
        MailMessage m = msg("阁主", null, "烛龙");
        when(mailboxService.canonicalRole("zhulong")).thenReturn("烛龙");
        when(mailboxService.thread(eq("烛龙"), eq("T261114134802-723"), eq(true), anyInt(), eq(false)))
                .thenReturn(List.of(m));
        when(mailboxService.countThread("T261114134802-723")).thenReturn(1L);
        when(mailboxService.threadSubject("T261114134802-723")).thenReturn("旧派单");
        MailIdentity.set("zhulong", "cfg-zhulong-opencode-mac");

        String r = tools.mailThread("T261114134802-723", null, true, null, null, null, null, null);

        assertTrue(r.contains("[dispatch] 阁主 → 烛龙"), r);
        assertFalse(r.contains("经阁主代发"), "没有代发就不该出现代发字样：" + r);
    }

    @Test
    @DisplayName("mail_start 末行 JSON：from 是展示串，另给 fromRole / onBehalfOf（老解析器零改动）")
    void inboxJsonCarriesDelegationFields() {
        MailboxService.Envelope env = new MailboxService.Envelope();
        env.threadId = "T261114134802-723";
        env.subject = "W-20261114-09 派单";
        env.fromRole = "阁主";
        env.onBehalfOf = "越哥";
        env.toRole = "烛龙";
        env.msgType = "dispatch";
        env.latestAt = LocalDateTime.now();
        env.latestSnippet = "正文";
        env.latestId = 2073L;
        env.freshCount = 1;
        MailIdentity.set("zhulong", "cfg-zhulong-opencode-mac");
        when(mailboxService.canonicalRole("zhulong")).thenReturn("烛龙");
        when(mailboxService.unreadCount("烛龙")).thenReturn(1);
        when(mailboxService.inbox(eq("烛龙"), isNull(), anyInt(), eq(false))).thenReturn(List.of(env));

        // unreadOnly 分支带末行 JSON（默认分支本来就不印 JSON，别拿它验 JSON）
        String r = tools.mailStart(null, null, null, null, true, null, null, null, null);

        assertTrue(r.contains("· 越哥（经阁主代发） · 1 封新"), "人读行显示代发身份：" + r);
        assertTrue(r.contains("\"from\":\"越哥（经阁主代发）\""), "JSON from 是展示串：" + r);
        assertTrue(r.contains("\"fromRole\":\"阁主\""), "真实发件人单独给：：\n" + r);
        assertTrue(r.contains("\"onBehalfOf\":\"越哥\""), r);
    }
}
