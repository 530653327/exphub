package com.exphub.service;

import com.exphub.entity.MailMessage;
import com.exphub.mapper.MailCursorMapper;
import com.exphub.mapper.MailMessageMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * 授权链元数据（2026-11-09 阁主 T261109035247-364）。
 *
 * <p>这单的硬约束是「<b>不传时行为完全不变</b>」——忆阁和哨兵阁都在用 mail_send。
 * 所以本测试既验「传了存得对」，也验「不传时存的就是默认值（chainId=null / auto=false / depth=0）」。
 *
 * <p>另一条是 AGENTS 9c：<b>非法值明拒，不静默截断/归零</b>。
 * 截断 chainId 会让两条不同的链折成同一个标识，而「第几跳」是按链算的。
 */
class MailboxChainMetadataTest {

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
        // 开新线程要校验收件人；配置表是单一数据源，这里直接注进去，不读 yml
        ReflectionTestUtils.setField(svc, "knownRolesConfig", "zhulong,gezhu,baize");
        // MyBatis 的 insert 会回填自增主键；mock 也得回填，否则 send() 推游标时拿到 null id
        when(msgMapper.insert(any(MailMessage.class))).thenAnswer(inv -> {
            ((MailMessage) inv.getArgument(0)).setId(99L);
            return 1;
        });
    }

    @Test
    @DisplayName("不传三个参数：入库值就是默认值，老调用零改动")
    void legacyCallStoresDefaults() {
        svc.send("gezhu", "zhulong", "主题", "正文", null, "note", null, "cfg-test");

        MailMessage saved = captured();
        assertNull(saved.getChainId(), "老调用不该凭空产生链标识");
        assertEquals(Boolean.FALSE, saved.getAutoFlag(), "老调用必须是人工（false）");
        assertEquals(0, saved.getChainDepth(), "老调用必须是链根（0）");
    }

    @Test
    @DisplayName("传了元数据：三个字段按原值入库")
    void chainMetadataIsStored() {
        svc.send("gezhu", "zhulong", "主题", "正文", null, "dispatch", null, "cfg-test",
                "CHAIN-A", true, 2);

        MailMessage saved = captured();
        assertEquals("CHAIN-A", saved.getChainId());
        assertEquals(Boolean.TRUE, saved.getAutoFlag());
        assertEquals(2, saved.getChainDepth());
    }

    @Test
    @DisplayName("只传 chainId：auto 默认 false、depth 默认 0（回执里必须写明用了默认）")
    void partialMetadataUsesDefaults() {
        svc.send("gezhu", "zhulong", "主题", "正文", null, "note", null, "cfg-test",
                "CHAIN-B", null, null);

        MailMessage saved = captured();
        assertEquals("CHAIN-B", saved.getChainId());
        assertEquals(Boolean.FALSE, saved.getAutoFlag());
        assertEquals(0, saved.getChainDepth());
    }

    @Test
    @DisplayName("depth 为负：明拒且一封都不入库（不静默改成 0）")
    void negativeDepthIsRejected() {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> svc.send("gezhu", "zhulong", "主题", "正文", null, "note", null, "cfg-test",
                        "CHAIN-C", false, -1));

        assertTrue(e.getMessage().contains("非负整数"), e.getMessage());
        assertTrue(e.getMessage().contains("-1"), "要把实际收到的值说清：" + e.getMessage());
        verify(msgMapper, never()).insert(any(MailMessage.class));
    }

    @Test
    @DisplayName("chainId 空白：明拒，不折成「没传」")
    void blankChainIdIsRejected() {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> svc.send("gezhu", "zhulong", "主题", "正文", null, "note", null, "cfg-test",
                        "   ", false, 0));

        assertTrue(e.getMessage().contains("空白"), e.getMessage());
        verify(msgMapper, never()).insert(any(MailMessage.class));
    }

    @Test
    @DisplayName("chainId 65 字符：明拒且不截断（截断会把两条链折成同一个标识）")
    void tooLongChainIdIsRejected() {
        String tooLong = "x".repeat(65);

        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> svc.send("gezhu", "zhulong", "主题", "正文", null, "note", null, "cfg-test",
                        tooLong, false, 0));

        assertTrue(e.getMessage().contains("上限 64"), e.getMessage());
        assertTrue(e.getMessage().contains("65"), "要报出实际长度：" + e.getMessage());
        verify(msgMapper, never()).insert(any(MailMessage.class));
    }

    @Test
    @DisplayName("chainId 刚好 64 字符：放行（边界是闭区间，别把上限当成 63）")
    void maxLengthChainIdIsAccepted() {
        String max = "y".repeat(64);

        svc.send("gezhu", "zhulong", "主题", "正文", null, "note", null, "cfg-test",
                max, false, 0);

        assertEquals(max, captured().getChainId());
    }

    private MailMessage captured() {
        ArgumentCaptor<MailMessage> cap = ArgumentCaptor.forClass(MailMessage.class);
        verify(msgMapper, times(1)).insert(cap.capture());
        return cap.getValue();
    }
}
