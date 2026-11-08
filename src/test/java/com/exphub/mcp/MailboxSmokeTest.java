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
        String r = tools.mailStart(null, null, null, null, null, null);
        assertTrue(r.contains("未声明角色"), "没角色时必须明说，不能静默返回空列表：\n" + r);
        verifyNoInteractions(mailboxService);
    }

    @Test
    @DisplayName("smoke: 有角色时第一行自证身份与配置来源")
    void mailStartWithRoleShowsIdentity() {
        MailIdentity.set("zhulong", "cfg-zhulong-01");
        when(mailboxService.unreadCount("zhulong")).thenReturn(2);
        when(mailboxService.inbox(eq("zhulong"), isNull(), anyInt(), eq(false))).thenReturn(List.of());

        String r = tools.mailStart(null, null, null, null, null, null);
        assertTrue(r.startsWith("你是【zhulong】，配置来源 cfg-zhulong-01；未读 2 封"),
                "回执第一行必须能一眼看出「我是谁、哪份配置」，实际：\n" + r);
    }

    @Test
    @DisplayName("smoke: 配置里写拼音，回执第一行也显示汉字正名（出口一律汉字）")
    void mailStartShowsCanonicalChineseName() {
        MailIdentity.set("zhulong", "cfg-zhulong-01");
        when(mailboxService.canonicalRole("zhulong")).thenReturn("烛龙");
        when(mailboxService.unreadCount("烛龙")).thenReturn(0);
        when(mailboxService.inbox(eq("烛龙"), isNull(), anyInt(), eq(false))).thenReturn(List.of());

        String r = tools.mailStart(null, null, null, null, null, null);

        assertTrue(r.startsWith("你是【烛龙】，配置来源 cfg-zhulong-01"),
                "兼容期配置仍写拼音，但出口必须是汉字：\n" + r);
    }

    @Test
    @DisplayName("smoke: 没角色发不了信（from 不许模型自己填）")
    void mailSendWithoutRoleMustRefuse() {
        String r = tools.mailSend("baize", "主题", "正文", null, "note", null, null, null, null);
        assertTrue(r.startsWith("❌ 发不了"), "没角色必须拒绝，不能默认成某个人：\n" + r);
        verifyNoInteractions(mailboxService);
    }

    @Test
    @DisplayName("smoke: 缺 threadId 直接拒，不猜线程")
    void mailThreadRequiresThreadId() {
        MailIdentity.set("zhulong", "cfg-zhulong-01");
        String r = tools.mailThread(null, null, null, null, null, null, null);
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

        String r = tools.mailStart(null, null, null, true, null, null);

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

        String r = tools.mailStart(null, null, null, null, true, null);

        assertTrue(r.contains("\"threads\":1"), "要给出可解析的计数：" + r);
        assertTrue(r.contains("\"type\":\"note\""), "type 必须带（dispatch/receipt/accept 优先级不同）：" + r);
        // 这条是硬要求：给了摘要就等于把正文漏给了前端
        org.junit.jupiter.api.Assertions.assertFalse(r.contains("片段"), "只读摘要不许带正文摘要：" + r);
        assertTrue(r.contains("只读"), "要写明它是只读的，别让人以为能拿它标已读：" + r);
    }

    // ── peek / advanceTo（2026-11-09 阁主 T261109015413-804）────────────────

    @Test
    @DisplayName("peek=true：正文照给，但游标原地不动，并给出下一步 advanceTo")
    void peekDoesNotAdvanceCursor() {
        MailIdentity.set("zhulong", "cfg-zhulong-01");
        when(mailboxService.cursorOf("zhulong", "T-PEEK")).thenReturn(41L);
        when(mailboxService.thread("zhulong", "T-PEEK", false, 10, true))
                .thenReturn(List.of(message(42L, "T-PEEK")));
        when(mailboxService.countThread("T-PEEK")).thenReturn(2L);

        String r = tools.mailThread("T-PEEK", null, null, null, null, true, null);

        assertTrue(r.contains("#42"), "peek 必须照常给正文：" + r);
        assertTrue(r.contains("游标**未推进**"), "peek 必须明说游标没动，否则调用方会以为读过了：" + r);
        assertTrue(r.contains("#41"), "要回显游标真实停在哪：" + r);
        assertTrue(r.contains("advanceTo=42"), "要给出下一步怎么确认推进：" + r);
        // 走的是 peek=true 那条路；要是内核用 false 读，上面的 stub 不会命中，正文就拿不到
        org.mockito.Mockito.verify(mailboxService, org.mockito.Mockito.never())
                .thread(org.mockito.Mockito.anyString(), org.mockito.Mockito.anyString(),
                        org.mockito.Mockito.anyBoolean(), org.mockito.Mockito.anyInt(),
                        org.mockito.Mockito.eq(false));
    }

    @Test
    @DisplayName("advanceTo：只推游标、不返回正文，并回显前后值与剩余条数")
    void advanceToMovesCursorWithoutBody() {
        MailIdentity.set("zhulong", "cfg-zhulong-01");
        when(mailboxService.cursorOf("zhulong", "T-ADV")).thenReturn(40L, 42L);
        when(mailboxService.advanceCursorTo("zhulong", "T-ADV", 42L)).thenReturn(1);
        when(mailboxService.countAfterCursor("zhulong", "T-ADV")).thenReturn(3L);

        String r = tools.mailThread("T-ADV", null, null, null, null, null, 42L);

        assertTrue(r.contains("#40 → #42"), "必须回显推进前后的实际值，不能只说「已推进」：" + r);
        assertTrue(r.contains("还有 3 封"), "要说明推进后还剩多少没读：" + r);
        org.mockito.Mockito.verify(mailboxService, org.mockito.Mockito.never())
                .thread(org.mockito.Mockito.anyString(), org.mockito.Mockito.anyString(),
                        org.mockito.Mockito.anyBoolean(), org.mockito.Mockito.anyInt(),
                        org.mockito.Mockito.anyBoolean());
    }

    @Test
    @DisplayName("advanceTo：目标不比游标新时幂等，如实说「未变」而不是报错")
    void advanceToIsIdempotent() {
        MailIdentity.set("zhulong", "cfg-zhulong-01");
        when(mailboxService.cursorOf("zhulong", "T-IDEM")).thenReturn(50L);
        when(mailboxService.advanceCursorTo("zhulong", "T-IDEM", 48L)).thenReturn(0);
        when(mailboxService.countAfterCursor("zhulong", "T-IDEM")).thenReturn(0L);

        String r = tools.mailThread("T-IDEM", null, null, null, null, null, 48L);

        assertTrue(r.contains("游标未变"), "重复推进不是错误，要说清而不是报失败：" + r);
        assertTrue(r.contains("#50"), "当前游标要摆出来：" + r);
    }

    @Test
    @DisplayName("advanceTo：跨线程的ID必须明拒，不能把别的线程的信标成已读")
    void advanceToRejectsCrossThreadId() {
        MailIdentity.set("zhulong", "cfg-zhulong-01");
        when(mailboxService.cursorOf("zhulong", "T-A")).thenReturn(0L);
        when(mailboxService.advanceCursorTo("zhulong", "T-A", 9L))
                .thenThrow(new IllegalArgumentException("#9 属于线程 T-B，不是 T-A —— 跨线程推进会把别的线程的信标成已读，拒绝"));

        String r = tools.mailThread("T-A", null, null, null, null, null, 9L);

        assertTrue(r.startsWith("❌"), "必须明拒，不能吞掉当成功：" + r);
        assertTrue(r.contains("T-B"), "要说清它其实属于哪个线程：" + r);
    }

    @Test
    @DisplayName("peek 与 advanceTo 互斥：不许静默选一个")
    void peekAndAdvanceToAreMutuallyExclusive() {
        MailIdentity.set("zhulong", "cfg-zhulong-01");

        String r = tools.mailThread("T-X", null, null, null, null, true, 42L);

        assertTrue(r.startsWith("❌"), r);
        assertTrue(r.contains("peek"), "要点名是哪个参数冲突了：" + r);
        assertTrue(r.contains("语义冲突"), r);
        verifyNoInteractions(mailboxService);
    }

    @Test
    @DisplayName("full 与 advanceTo 也互斥：回溯读正文与「只推游标」不能合并成一次")
    void fullAndAdvanceToAreAlsoMutuallyExclusive() {
        MailIdentity.set("zhulong", "cfg-zhulong-01");

        String r = tools.mailThread("T-X", true, null, null, null, null, 42L);

        assertTrue(r.startsWith("❌"), r);
        assertTrue(r.contains("full"), r);
        verifyNoInteractions(mailboxService);
    }

    // ── 授权链元数据回显（2026-11-09 阁主 T261109035247-364）────────────────

    @Test
    @DisplayName("mail_thread 回显授权链元数据：链 / 第几跳 / 自动或人工")
    void threadEchoesChainMetadata() {
        MailIdentity.set("zhulong", "cfg-zhulong-01");
        com.exphub.entity.MailMessage m = message(61L, "T-CHAIN");
        m.setChainId("CHAIN-A");
        m.setAutoFlag(true);
        m.setChainDepth(3);
        when(mailboxService.thread("zhulong", "T-CHAIN", false, 10, false)).thenReturn(List.of(m));
        when(mailboxService.countThread("T-CHAIN")).thenReturn(1L);
        when(mailboxService.threadSubject("T-CHAIN")).thenReturn("主题");

        String r = tools.mailThread("T-CHAIN", null, null, null, null, null, null);

        assertTrue(r.contains("链 CHAIN-A"), "必须回显链标识：" + r);
        assertTrue(r.contains("第 3 跳"), "必须回显跳数：" + r);
        assertTrue(r.contains("自动回合"), "必须回显这封是自动回合发出的：" + r);
    }

    @Test
    @DisplayName("mail_thread：没有元数据的历史消息一个字不多印（不传时行为完全一致）")
    void threadKeepsLegacyRendering() {
        MailIdentity.set("zhulong", "cfg-zhulong-01");
        when(mailboxService.thread("zhulong", "T-OLD", false, 10, false))
                .thenReturn(List.of(message(62L, "T-OLD")));
        when(mailboxService.countThread("T-OLD")).thenReturn(1L);
        when(mailboxService.threadSubject("T-OLD")).thenReturn("主题");

        String r = tools.mailThread("T-OLD", null, null, null, null, null, null);

        org.junit.jupiter.api.Assertions.assertFalse(r.contains(" · 链 "),
                "老消息不该多出授权链段，否则所有人的回执都被改了一遍：" + r);
        org.junit.jupiter.api.Assertions.assertFalse(r.contains("第 0 跳"),
                "默认值不该渲染成「第 0 跳」：" + r);
    }

    private static com.exphub.entity.MailMessage message(long id, String threadId) {
        com.exphub.entity.MailMessage m = new com.exphub.entity.MailMessage();
        m.setId(id);
        m.setThreadId(threadId);
        m.setMsgType("note");
        m.setFromRole("gezhu");
        m.setToRole("zhulong");
        m.setSubject("主题");
        m.setBody("正文");
        m.setCreatedAt(java.time.LocalDateTime.now());
        return m;
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
