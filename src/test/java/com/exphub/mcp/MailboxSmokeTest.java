package com.exphub.mcp;

import com.exphub.service.MailboxService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * 协作信箱冒烟：验的是<b>行为</b>，不是源码文本（#1290 C79-② 的教训）。
 *
 * <p>三条最要紧的红线：
 * <ol>
 *   <li>没声明角色 → <b>一封私信都不给</b>。共享一把 Key，服务端不认人，宁可查不到也不能串信。</li>
 *   <li>有角色 → 回执第一行自证「你是【X】，配置来源 y」，人和模型都能一眼看出配错。</li>
 *   <li>缺 thread_id → 直接拒，不去查库；不给模型「自己编一个线程号」的机会。</li>
 * </ol>
 */
@ExtendWith(MockitoExtension.class)
class MailboxSmokeTest {

    @Mock
    private MailboxService mailboxService;

    @InjectMocks
    private ExpHubTools tools;

    @AfterEach
    void clearIdentity() {
        MailIdentity.clear();
    }

    @Test
    @DisplayName("smoke: 没声明角色，mail_start 不给任何私信，也不查库")
    void mailStartWithoutRoleMustRefuse() {
        String r = tools.mailStart(null, null, null, null, null);
        assertTrue(r.contains("未声明角色"), "没角色时必须明说，不能静默返回空列表：\n" + r);
        verifyNoInteractions(mailboxService);
    }

    @Test
    @DisplayName("smoke: 有角色时第一行自证身份与配置来源")
    void mailStartWithRoleShowsIdentity() {
        MailIdentity.set("zhulong", "cfg-zhulong-01");
        when(mailboxService.unreadCount("zhulong")).thenReturn(2);
        when(mailboxService.inbox(eq("zhulong"), isNull(), anyInt(), eq(false))).thenReturn(List.of());

        String r = tools.mailStart(null, null, null, null, null);
        assertTrue(r.startsWith("你是【zhulong】，配置来源 cfg-zhulong-01；未读 2 封"),
                "回执第一行必须能一眼看出「我是谁、哪份配置」，实际：\n" + r);
    }

    @Test
    @DisplayName("smoke: 没角色发不了信（from 不许模型自己填）")
    void mailSendWithoutRoleMustRefuse() {
        String r = tools.mailSend("baize", "主题", "正文", null, "note", null);
        assertTrue(r.startsWith("❌ 发不了"), "没角色必须拒绝，不能默认成某个人：\n" + r);
        verifyNoInteractions(mailboxService);
    }

    @Test
    @DisplayName("smoke: 缺 threadId 直接拒，不猜线程")
    void mailThreadRequiresThreadId() {
        MailIdentity.set("zhulong", "cfg-zhulong-01");
        String r = tools.mailThread(null, null, null, null, null);
        assertEquals("❌ 要 threadId。从 mail_start 的信封列表里抄，别自己编。", r);
        verifyNoInteractions(mailboxService);
    }

    @Test
    @DisplayName("smoke: sent=true 切到发件视角（必须列出「发给谁」），且不再查收件箱")
    void sentFlagSwitchesToOutbox() {
        MailIdentity.set("zhulong", "cfg-zhulong-01");
        when(mailboxService.unreadCount("zhulong")).thenReturn(0);
        when(mailboxService.outbox(eq("zhulong"), isNull(), anyInt()))
                .thenReturn(List.of(envelope("T261108000000-001", "gezhu")));

        String r = tools.mailStart(null, null, null, true, null);

        assertTrue(r.contains("发件视角"), "必须明确标出这是发件视角，否则会被当成收件箱读：\n" + r);
        assertTrue(r.contains("发给【gezhu】"), "发件视角的核心就是「我到底发给了谁」：\n" + r);
        // 这条是这次改动最要紧的兼容性约束：默认不传时行为一个字不能变
        org.mockito.Mockito.verify(mailboxService, org.mockito.Mockito.never())
                .inbox(org.mockito.Mockito.<String>any(), org.mockito.Mockito.<String>any(),
                        org.mockito.Mockito.anyInt(), org.mockito.Mockito.anyBoolean());
    }

    @Test
    @DisplayName("smoke: unreadOnly=true 只给计数/主题/类型，不给正文摘要（忆阁角标用）")
    void unreadOnlyCarriesNoSnippet() {
        MailIdentity.set("zhulong", "cfg-zhulong-01");
        when(mailboxService.unreadCount("zhulong")).thenReturn(2);
        when(mailboxService.inbox(eq("zhulong"), isNull(), anyInt(), eq(false)))
                .thenReturn(List.of(envelope("T261108000000-001", "gezhu")));

        String r = tools.mailStart(null, null, null, null, true);

        assertTrue(r.contains("\"threads\":1"), "要给出可解析的计数：" + r);
        assertTrue(r.contains("\"type\":\"note\""), "type 必须带（dispatch/receipt/accept 优先级不同）：" + r);
        // 这条是硬要求：给了摘要就等于把正文漏给了前端
        org.junit.jupiter.api.Assertions.assertFalse(r.contains("片段"), "只读摘要不许带正文摘要：" + r);
        assertTrue(r.contains("只读"), "要写明它是只读的，别让人以为能拿它标已读：" + r);
    }

    private static com.exphub.service.MailboxService.Envelope envelope(String threadId, String to) {        com.exphub.service.MailboxService.Envelope e = new com.exphub.service.MailboxService.Envelope();
        e.threadId = threadId;
        e.subject = "主题";
        e.fromRole = "zhulong";
        e.toRole = to;
        e.msgType = "note";
        e.latestAt = java.time.LocalDateTime.now();
        e.latestSnippet = "片段";
        return e;
    }
}
