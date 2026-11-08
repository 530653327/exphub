package com.exphub.service;

import com.exphub.entity.MailMessage;
import com.exphub.mapper.MailCursorMapper;
import com.exphub.mapper.MailMessageMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * 回信的收件人（2026-11-09 阁主 T261109053740-701 P9 第 3 条，白泽查出来的漏）。
 *
 * <p><b>旧行为</b>：回信（带 inReplyTo）既不校验收件人、也不按父消息推导，{@code to} 按入参入库。
 * 于是 to 写错的回信<b>照样入库、线程里看得见</b>，而对方收件箱里没有 —— 发件方还以为送到了。
 *
 * <p><b>新行为</b>：收件人取父消息的发件人；传了 to 且与父消息发件人不一致 → <b>明拒</b>
 * （不静默改：调用方以为发给了 A，实际给了 B，比报错更危险）。
 *
 * <p>2026-11-09 #679：收件人一律归一成汉字正名，拼音（gezhu）与错拼（gezu）都算一致。
 */
class MailReplyRecipientTest {

    private MailboxService svc;
    private MailMessageMapper msgMapper;
    private MailRoleCatalog catalog;

    @BeforeEach
    void setUp() {
        svc = new MailboxService();
        msgMapper = mock(MailMessageMapper.class);
        MailCursorMapper cursorMapper = mock(MailCursorMapper.class);
        catalog = MailRoleFixtures.catalog(Set.of("烛龙", "阁主", "白泽"),
                Map.of("zhulong", "烛龙", "gezhu", "阁主", "gezu", "阁主",
                        "baize", "白泽", "zhuolong", "烛龙"), Map.of());
        ReflectionTestUtils.setField(svc, "msgMapper", msgMapper);
        ReflectionTestUtils.setField(svc, "cursorMapper", cursorMapper);
        ReflectionTestUtils.setField(svc, "roleCatalog", catalog);
        when(msgMapper.insert(any(MailMessage.class))).thenAnswer(inv -> {
            MailMessage m = inv.getArgument(0);
            m.setId(100L);
            return 1;
        });
    }

    @Test
    @DisplayName("回信不传 to：收件人取父消息的发件人（并归一成汉字）")
    void replyTakesRecipientFromParentSender() {
        when(msgMapper.selectById(50L)).thenReturn(parent("gezhu"));

        MailMessage m = svc.send("zhulong", null, "回执", "正文", 50L, "receipt", null, "cfg-test");

        assertEquals("阁主", m.getToRole());
        assertEquals("烛龙", m.getFromRole());
        assertEquals("T-parent", m.getThreadId());
        verify(msgMapper).insert(any(MailMessage.class));
    }

    @Test
    @DisplayName("回信传了 to，且与父消息发件人一致（含拼音/错拼/大小写）：放行")
    void replyWithMatchingToIsAccepted() {
        when(msgMapper.selectById(50L)).thenReturn(parent("阁主"));

        assertEquals("阁主",
                svc.send("烛龙", "Gezhu", "回执", "正文", 50L, "receipt", null, "cfg").getToRole());
        assertEquals("阁主",
                svc.send("烛龙", "gezu", "回执", "正文", 50L, "receipt", null, "cfg").getToRole(),
                "别名归一到父消息发件人就算一致");
    }

    @Test
    @DisplayName("回信传了 to 但和父消息发件人不一致：明拒，一封都不写库")
    void replyWithWrongToIsRejectedInsteadOfMisrouted() {
        when(msgMapper.selectById(50L)).thenReturn(parent("阁主"));

        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> svc.send("烛龙", "白泽", "回执", "正文", 50L, "receipt", null, "cfg"));

        assertTrue(e.getMessage().contains("回信的收件人不能自己指定"), e.getMessage());
        assertTrue(e.getMessage().contains("阁主"), "要写明真正的收件人：" + e.getMessage());
        assertTrue(e.getMessage().contains("白泽"), "要回显他传的那个：" + e.getMessage());
        assertTrue(e.getMessage().contains("新开线程"), "要给出可执行出口：" + e.getMessage());
        verify(msgMapper, never()).insert(any(MailMessage.class));
    }

    @Test
    @DisplayName("父消息发件人是历史别名/拼音：归一到汉字后入库")
    void parentSenderAliasIsCanonicalised() {
        when(msgMapper.selectById(50L)).thenReturn(parent("zhuolong"));

        MailMessage m = svc.send("gezhu", "", "回执", "正文", 50L, "receipt", null, "cfg");
        assertEquals("烛龙", m.getToRole());
    }

    @Test
    @DisplayName("父消息发件人已停用/不在清单：回信仍然放行（不能让老线程永久卡死）")
    void replyToLegacySenderIsNotBlockedByWhitelist() {
        when(msgMapper.selectById(50L)).thenReturn(parent("legacy_guy"));

        MailMessage m = svc.send("阁主", null, "回执", "正文", 50L, "receipt", null, "cfg");
        assertEquals("legacy_guy", m.getToRole(),
                "回信不校验 ACTIVE 名单：拦掉会让历史线程永久卡死（比投错更伤）");
    }

    @Test
    @DisplayName("父消息不存在：明拒，不新建孤儿线程")
    void missingParentIsRejected() {
        when(msgMapper.selectById(404L)).thenReturn(null);
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> svc.send("阁主", "烛龙", "回执", "正文", 404L, "receipt", null, "cfg"));
        assertTrue(e.getMessage().contains("父消息不存在"), e.getMessage());
    }

    @Test
    @DisplayName("开新线程不传 to：明拒并说明「回信可以留空」")
    void newThreadWithoutRecipientIsRejected() {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> svc.send("阁主", "  ", "通知", "正文", null, "note", null, "cfg"));
        assertTrue(e.getMessage().contains("开新线程必须传 to"), e.getMessage());
        assertTrue(e.getMessage().contains("回信"), e.getMessage());
    }

    @Test
    @DisplayName("开新线程：待复核/停用值不是合法来源")
    void newThreadRejectsNonActiveRole() {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> svc.send("阁主", "legacy_guy", "通知", "正文", null, "note", null, "cfg"));
        assertTrue(e.getMessage().startsWith("收件人无效"), e.getMessage());
        verify(msgMapper, never()).insert(any(MailMessage.class));
    }

    @Test
    @DisplayName("清单读不到：说「无法校验」，不许说成「收件人不存在」")
    void unavailableCatalogIsNotReportedAsUnknownRecipient() {
        when(MailRoleFixtures.roleMapperOf(catalog).selectActive())
                .thenThrow(new RuntimeException("Table 'mail_roles' doesn't exist"));
        catalog.setForceReloadThrottleMs(0L);

        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> svc.send("阁主", "烛龙", "通知", "正文", null, "note", null, "cfg"));
        assertTrue(e.getMessage().contains("无法校验"), e.getMessage());
        assertFalse(e.getMessage().contains("收件人无效"),
                "服务端故障不能显示成用户写错（#1459）：" + e.getMessage());
    }

    private static MailMessage parent(String from) {
        MailMessage p = new MailMessage();
        p.setId(50L);
        p.setThreadId("T-parent");
        p.setFromRole(from);
        return p;
    }
}
