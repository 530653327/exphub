package com.exphub.service;

import com.exphub.mapper.MailMessageMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * 收件人校验（2026-11-08，#1313 第 1 条，阁主 #51 拍板）。
 *
 * <p><b>要防的是哪一种错</b>：不是恶意，是「传了显示名」。#45 那封信就是这么消失的 ——
 * `to` 写成了「烛龙」，服务端只做 `trim()` 后入库，读信时按角色代号精确匹配，
 * 于是那封信存在、但谁也匹配不上。发件方全程无感，靠收件方反馈才知道发丢了。
 *
 * <p><b>口径</b>：可用代号 = 配置基准表 ∪ 库里出现过的 role。只认配置会导致新助手必须先发版
 * 才能收信；只认库里的会导致新助手第一次发信就把自己拒掉。两边并集才没有死结。
 */
class MailboxRecipientValidationTest {

    private MailboxService svc;
    private MailMessageMapper mapper;

    @BeforeEach
    void setUp() {
        svc = new MailboxService();
        mapper = mock(MailMessageMapper.class);
        // 库里出现过的：一个换了大小写的老代号、一个配置表里没有的老代号
        when(mapper.distinctRoles()).thenReturn(List.of("Baize", "legacy_guy"));
        ReflectionTestUtils.setField(svc, "msgMapper", mapper);
        ReflectionTestUtils.setField(svc, "knownRolesConfig", "zhulong, gezhu");
    }

    @Test
    @DisplayName("配置表里的代号可发，且大小写不敏感")
    void configRolesAreAccepted() {
        assertTrue(svc.isKnownRole("zhulong"));
        assertTrue(svc.isKnownRole("zhulong"));
        assertTrue(svc.isKnownRole("ZhuLong"), "大小写不该成为发信失败的理由");
        assertTrue(svc.isKnownRole("  gezhu  "), "两边空白要容忍，否则手工拷来的值会失败");
    }

    @Test
    @DisplayName("库里出现过、但配置没写的代号也可发（否则新助手第一次发信就被自己拒掉）")
    void historicalRolesAreAccepted() {
        assertTrue(svc.isKnownRole("baize"), "库里存的是 Baize，比较要归一化");
        assertTrue(svc.isKnownRole("legacy_guy"), "老代号即使从配置表删掉，也不能让老线程突然发不出去");
    }

    @Test
    @DisplayName("拼错的英文代号：拒绝，并把可用代号列出来")
    void unknownAsciiRoleIsRejectedWithList() {
        assertFalse(svc.isKnownRole("zhulnog"));
        String msg = svc.unknownRecipientMessage("zhulnog");
        assertTrue(msg.contains("不是已知角色代号"), msg);
        assertTrue(msg.contains("zhulong"), "必须列可用代号，否则对方只能猜：" + msg);
        assertTrue(msg.contains("gezhu"), msg);
        assertFalse(msg.contains("显示名"), "纯英文不该被说成显示名：" + msg);
    }

    @Test
    @DisplayName("中文显示名：单独一句说清「代号是小写英文」，不能只说角色不存在")
    void chineseDisplayNameGetsItsOwnMessage() {
        assertFalse(svc.isKnownRole("烛龙"));
        String msg = svc.unknownRecipientMessage("烛龙");
        // 只说「不存在」的话，对方下次还会传「烛龙」——这句才是真正止损的
        assertTrue(msg.contains("显示名"), "中文必须被识别成显示名：" + msg);
        assertTrue(msg.contains("小写英文"), "要告诉它正确形态：" + msg);
        assertTrue(msg.contains("烛龙"), "要回显它传的原值，让它看到自己写错了：" + msg);
        assertTrue(msg.contains("zhulong"), "还要给出它大概想要的那个代号：" + msg);
    }

    @Test
    @DisplayName("拒绝文案必须写明「只在开新线程时校」——否则会让人以为回信也会被拦")
    void messageExplainsScope() {
        String msg = svc.unknownRecipientMessage("nobody");
        assertTrue(msg.contains("开新线程"), msg);
        assertTrue(msg.contains("回信"), msg);
    }

    @Test
    @DisplayName("配置表为空时不能把所有人都拒掉：库里存在过的代号仍然可发")
    void emptyConfigDoesNotRejectEveryone() {
        ReflectionTestUtils.setField(svc, "knownRolesConfig", "");
        assertTrue(svc.isKnownRole("legacy_guy"), "配置漏了不该等于全员禁言");
        assertFalse(svc.knownRoles().isEmpty(), "并集不能为空，否则拒绝文案会列出空列表");
    }

    @Test
    @DisplayName("查库失败时退化成只认配置表，不能阻断发信")
    void dbFailureFallsBackToConfig() {
        MailMessageMapper broken = mock(MailMessageMapper.class);
        when(broken.distinctRoles()).thenThrow(new RuntimeException("db down"));
        MailboxService s = new MailboxService();
        ReflectionTestUtils.setField(s, "msgMapper", broken);
        ReflectionTestUtils.setField(s, "knownRolesConfig", "zhulong");
        assertTrue(s.isKnownRole("zhulong"));
        assertFalse(s.isKnownRole("legacy_guy"));
    }
}
