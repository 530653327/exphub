package com.exphub.mcp;

import com.exphub.entity.MailMessage;
import com.exphub.mapper.MailCursorMapper;
import com.exphub.mapper.MailMessageMapper;
import com.exphub.service.MailboxService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * mail_send 的授权链元数据：校验在服务端、回执必须自证入库值
 * （2026-11-09 阁主 T261109035247-364）。
 *
 * <p>这里用<b>真的 MailboxService + mock mapper</b>，而不是 mock 整个 service ——
 * 否则校验（depth 非负、chainId 上限）会被 mock 掉，测试只能证明「参数传进去了」，
 * 证明不了「非法值真的被拒」。
 *
 * <p>回执口径按 AGENTS 第 10 章：报<b>回读出来的值</b>，不把入参复述当结果。
 */
class MailSendChainMetadataReceiptTest {

    private ExpHubTools tools;
    private MailMessageMapper msgMapper;
    private MailCursorMapper cursorMapper;
    private final AtomicReference<MailMessage> stored = new AtomicReference<>();

    @BeforeEach
    void setUp() {
        MailboxService svc = new MailboxService();
        msgMapper = mock(MailMessageMapper.class);
        cursorMapper = mock(MailCursorMapper.class);
        ReflectionTestUtils.setField(svc, "msgMapper", msgMapper);
        ReflectionTestUtils.setField(svc, "cursorMapper", cursorMapper);
        ReflectionTestUtils.setField(svc, "knownRolesConfig", "zhulong,gezhu,baize");

        // 模拟入库：assign id，并把这条消息当成「库里读回来的那份」
        when(msgMapper.insert(any(MailMessage.class))).thenAnswer(inv -> {
            MailMessage m = inv.getArgument(0);
            m.setId(99L);
            stored.set(m);
            return 1;
        });
        when(msgMapper.selectById(99L)).thenAnswer(inv -> stored.get());

        tools = new ExpHubTools();
        ReflectionTestUtils.setField(tools, "mailboxService", svc);
        MailIdentity.set("gezhu", "cfg-gezhu-test");
    }

    @AfterEach
    void clear() {
        MailIdentity.clear();
    }

    @Test
    @DisplayName("不传三个参数：回执与改动前一致，且入库就是默认值（老调用零改动）")
    void legacyReceiptUnchanged() {
        String r = tools.mailSend("zhulong", "通知", "正文", null, "note", null, null, null, null);

        assertFalse(r.contains("授权链元数据"), "没传元数据就不该多出这一段：" + r);
        assertNull(stored.get().getChainId());
        assertEquals(Boolean.FALSE, stored.get().getAutoFlag());
        assertEquals(0, stored.get().getChainDepth());
    }

    @Test
    @DisplayName("回执报回读值：chainId / auto / depth 三个都要摆出来")
    void receiptShowsStoredValues() {
        String r = tools.mailSend("zhulong", "派单", "正文", null, "dispatch", null,
                "CHAIN-A", true, 2);

        assertTrue(r.contains("入库后回读"), "要说明这是回读值，不是复述入参：" + r);
        assertTrue(r.contains("chainId: CHAIN-A"), r);
        assertTrue(r.contains("auto: true"), r);
        assertTrue(r.contains("depth: 2"), r);
        assertFalse(r.contains("与本次传入不一致"), r);
    }

    @Test
    @DisplayName("只传 chainId：回执必须写「depth/auto 用了默认值」，不许静默补默认")
    void receiptDeclaresDefaults() {
        String r = tools.mailSend("zhulong", "通知", "正文", null, "note", null,
                "CHAIN-B", null, null);

        assertTrue(r.contains("depth 未传，入库用默认 0"), r);
        assertTrue(r.contains("auto 未传，入库用默认 false"), r);
    }

    @Test
    @DisplayName("depth 为负：回执是 ❌，且一封都不写库")
    void negativeDepthRejectedAtToolBoundary() {
        String r = tools.mailSend("zhulong", "通知", "正文", null, "note", null,
                "CHAIN-C", false, -1);

        assertTrue(r.startsWith("❌"), "非法值必须明拒：" + r);
        assertTrue(r.contains("非负整数"), r);
        verify(msgMapper, never()).insert(any(MailMessage.class));
    }

    @Test
    @DisplayName("chainId 超长：回执是 ❌ 并报实际长度，不截断")
    void tooLongChainIdRejectedAtToolBoundary() {
        String r = tools.mailSend("zhulong", "通知", "正文", null, "note", null,
                "z".repeat(70), false, 0);

        assertTrue(r.startsWith("❌"), r);
        assertTrue(r.contains("上限 64"), r);
        assertTrue(r.contains("70"), "要把实际收到多少字说清楚：" + r);
        verify(msgMapper, never()).insert(any(MailMessage.class));
    }

    @Test
    @DisplayName("depth>0 但没 chainId：按原样入库，但回执要点明「没有可追溯的根」")
    void depthWithoutChainIsStoredButFlagged() {
        String r = tools.mailSend("zhulong", "通知", "正文", null, "note", null,
                null, true, 3);

        assertTrue(r.contains("depth>0 但 chainId 为空"), "缺链根的元数据必须点出来：" + r);
        assertEquals(3, stored.get().getChainDepth());
        assertNull(stored.get().getChainId());
    }

    @Test
    @DisplayName("clientMsgId 幂等命中：回执写清「库里是首次入库的值」，不假装覆盖成功")
    void idempotentHitExplainsWhyValuesDiffer() {
        MailMessage exist = new MailMessage();
        exist.setId(77L);
        exist.setThreadId("T-OLD");
        exist.setFromRole("gezhu");
        exist.setToRole("zhulong");
        exist.setMsgType("note");
        exist.setSubject("通知");
        exist.setChainId("CHAIN-OLD");
        exist.setAutoFlag(false);
        exist.setChainDepth(1);
        when(msgMapper.selectOne(any())).thenReturn(exist);
        when(msgMapper.selectById(77L)).thenReturn(exist);

        String r = tools.mailSend("zhulong", "通知", "正文", null, "note", "CM-1",
                "CHAIN-NEW", true, 5);

        // 回执头仍是 #77（幂等命中，没有新建）
        assertTrue(r.contains("消息 #77"), r);
        assertTrue(r.contains("chainId: CHAIN-OLD"), "必须报库里真实值：" + r);
        assertTrue(r.contains("与本次传入不一致"), "差异必须解释，不能静默：" + r);
        assertTrue(r.contains("以首次入库为准"), r);
        verify(msgMapper, never()).insert(any(MailMessage.class));
    }
}
