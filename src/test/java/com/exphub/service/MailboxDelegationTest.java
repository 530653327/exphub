package com.exphub.service;

import com.exphub.entity.MailMessage;
import com.exphub.mapper.MailCursorMapper;
import com.exphub.mapper.MailMessageMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * 信箱代发（{@code onBehalfOf}）：青鸟阁派单信以「越哥」名义发出（2026-11-14 阁主 W-20261114-09）。
 *
 * <p>这单的核心不是改字符串，是<b>回答「凭什么只有阁主能代发」并把它落成可测的拒绝</b>：
 * <ol>
 *   <li>真实发件人永远是阁主 —— 否则回信取父消息发件人会写到「越哥」这个没有信箱的角色上，静默死信；</li>
 *   <li>非阁主代发 → 明拒，一封都不入库（不静默改成「以自己名义发」）；</li>
 *   <li>代发对象只允许「越哥」，不是「任意名字都能代发」的通用通道；</li>
 *   <li>必须有配置来源且命中允许前缀（第二道弱锁，挡误用、挡不住改头的人 —— 这点不美化）；</li>
 *   <li>不传时老行为逐字段不变。</li>
 * </ol>
 */
class MailboxDelegationTest {

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
        ReflectionTestUtils.setField(svc, "roleCatalog", MailRoleFixtures.catalog(
                Set.of("烛龙", "阁主", "白泽"),
                Map.of("zhulong", "烛龙", "gezhu", "阁主", "baize", "白泽"),
                Map.of()));
        when(msgMapper.insert(any(MailMessage.class))).thenAnswer(inv -> {
            ((MailMessage) inv.getArgument(0)).setId(99L);
            return 1;
        });
    }

    private MailMessage captured() {
        ArgumentCaptor<MailMessage> cap = ArgumentCaptor.forClass(MailMessage.class);
        verify(msgMapper, atLeastOnce()).insert(cap.capture());
        return cap.getValue();
    }

    @Test
    @DisplayName("阁主 + 越哥 + 忆阁配置：代发入库，真实发件人仍是阁主")
    void delegatorStoresOnBehalfOfButKeepsRealSender() {
        svc.sendDetailed("gezhu", "zhulong", "派单", "正文", null, "dispatch", null,
                "cfg-gezhu-memhaven-prod", null, null, null, "越哥");

        MailMessage saved = captured();
        assertEquals("阁主", saved.getFromRole(), "真实发件人必须是阁主：回信路由与审计都按它");
        assertEquals("越哥", saved.getOnBehalfOf());
        assertEquals("越哥（经阁主代发）",
                MailDelegation.display(saved.getFromRole(), saved.getOnBehalfOf()));
    }

    @Test
    @DisplayName("不传 onBehalfOf：老调用逐字段不变，库里是 NULL（历史语义）")
    void legacyCallStoresNull() {
        svc.send("gezhu", "zhulong", "通知", "正文", null, "note", null, "cfg-gezhu-memhaven-prod");

        assertNull(captured().getOnBehalfOf(), "不传代发时不许凭空写值");
    }

    @Test
    @DisplayName("非阁主代发：明拒且一封都不入库（不静默改成以自己名义发）")
    void nonDelegatorIsRejected() {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> svc.sendDetailed("baize", "zhulong", "派单", "正文", null, "dispatch", null,
                        "cfg-gezhu-memhaven-prod", null, null, null, "越哥"));

        assertTrue(e.getMessage().contains("只有【阁主】"), "要说清凭什么：" + e.getMessage());
        assertTrue(e.getMessage().contains("声明角色是【白泽】"), "要回显实际声明角色：" + e.getMessage());
        assertTrue(e.getMessage().contains("未发出任何信"), "不许让人以为发出去了：" + e.getMessage());
        verify(msgMapper, never()).insert(any(MailMessage.class));
    }

    @Test
    @DisplayName("代发对象不是越哥：明拒（不是「任意名字都能代发」的通道）")
    void unknownPrincipalIsRejected() {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> svc.sendDetailed("gezhu", "zhulong", "派单", "正文", null, "dispatch", null,
                        "cfg-gezhu-memhaven-prod", null, null, null, "张三"));

        assertTrue(e.getMessage().contains("只允许【越哥】"), e.getMessage());
        assertTrue(e.getMessage().contains("张三"), "要回显实际收到的值：" + e.getMessage());
        verify(msgMapper, never()).insert(any(MailMessage.class));
    }

    @Test
    @DisplayName("配置来源不是忆阁（cfg-baize-*）：明拒，不静默放行")
    void wrongConfigSourceIsRejected() {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> svc.sendDetailed("gezhu", "zhulong", "派单", "正文", null, "dispatch", null,
                        "cfg-baize-watch-mac", null, null, null, "越哥"));

        assertTrue(e.getMessage().contains("不在允许前缀"), e.getMessage());
        assertTrue(e.getMessage().contains("cfg-baize-watch-mac"), "要回显实际配置来源：" + e.getMessage());
        verify(msgMapper, never()).insert(any(MailMessage.class));
    }

    @Test
    @DisplayName("代发没有配置来源：明拒（代发是审计动作，没有来源无法追责）")
    void missingConfigSourceIsRejected() {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> svc.sendDetailed("gezhu", "zhulong", "派单", "正文", null, "dispatch", null,
                        null, null, null, null, "越哥"));

        assertTrue(e.getMessage().contains("配置来源"), e.getMessage());
        verify(msgMapper, never()).insert(any(MailMessage.class));
    }

    @Test
    @DisplayName("幂等键复用：库里那封没代发、这次要代发 → 明拒（不许回放冒充）")
    void replayWithDifferentDelegationIsRejected() {
        MailMessage exist = new MailMessage();
        exist.setId(77L);
        exist.setThreadId("T-1");
        exist.setFromRole("阁主");
        exist.setToRole("烛龙");
        exist.setSubject("派单");
        exist.setOnBehalfOf(null);
        when(msgMapper.selectOne(any())).thenReturn(exist);

        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> svc.sendDetailed("gezhu", "zhulong", "派单", "正文", null, "dispatch", "CM-1",
                        "cfg-gezhu-memhaven-prod", null, null, null, "越哥"));

        assertTrue(e.getMessage().contains("代发"), "要说清是代发语义不同：" + e.getMessage());
        assertTrue(e.getMessage().contains("换键"), e.getMessage());
        verify(msgMapper, never()).insert(any(MailMessage.class));
    }

    @Test
    @DisplayName("展示助手：无代发原样返回；有代发写「越哥（经阁主代发）」")
    void displayHelper() {
        assertEquals("阁主", MailDelegation.display("阁主", null));
        assertEquals("阁主", MailDelegation.display("阁主", "  "));
        assertEquals("越哥（经阁主代发）", MailDelegation.display("阁主", "越哥"));
    }

    @Test
    @DisplayName("允许前缀解析：逗号分隔、去空白、空项忽略")
    void prefixParsing() {
        assertEquals(Set.of("cfg-gezhu-", "cfg-yuege-"),
                MailDelegation.parsePrefixes(" cfg-gezhu- , ,cfg-yuege- "));
        assertTrue(MailDelegation.parsePrefixes(null).isEmpty());
    }
}
