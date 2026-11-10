package com.exphub.mcp;

import com.exphub.mapper.MailCursorMapper;
import com.exphub.mapper.MailMessageMapper;
import com.exphub.service.MailRoleFixtures;
import com.exphub.service.MailboxService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

/**
 * {@code mail_start(roles=true)}：按需查全量名单的独立入口
 * （2026-11-09 阁主 T261109054529-699）。
 *
 * <p>为什么要单独测工具层：名单在拒绝回执里超过阈值时只报计数，
 * 真正能被调用方拿去用（给人看）的就是这个入口。它要是读信封、推游标，
 * 或者干脆不返回名单，那条「按需查」的路就是摆设。
 */
class MailRoleDirectoryToolTest {

    private ExpHubTools tools;
    private MailMessageMapper msgMapper;
    private MailCursorMapper cursorMapper;

    @BeforeEach
    void setUp() {
        MailboxService svc = new MailboxService();
        msgMapper = mock(MailMessageMapper.class);
        cursorMapper = mock(MailCursorMapper.class);
        ReflectionTestUtils.setField(svc, "msgMapper", msgMapper);
        ReflectionTestUtils.setField(svc, "cursorMapper", cursorMapper);
        ReflectionTestUtils.setField(svc, "roleCatalog", MailRoleFixtures.catalog(
                Set.of("烛龙", "阁主", "白泽"),
                Map.of("zhulong", "烛龙", "gezhu", "阁主", "baize", "白泽"),
                Map.of()));

        tools = new ExpHubTools();
        ReflectionTestUtils.setField(tools, "mailboxService", svc);
        MailIdentity.clear();
    }

    @AfterEach
    void clear() {
        MailIdentity.clear();
    }

    @Test
    @DisplayName("roles=true 只回名单：不读信封、不碰游标、不需要身份头")
    void rolesOnlyReturnsDirectoryWithoutTouchingMail() {
        String r = tools.mailStart(null, null, null, null, null, true, null, null, null);

        assertTrue(r.contains("当前可投递角色：3 个（只含 status=ACTIVE）"), r);
        assertTrue(r.contains("- 烛龙"), r);
        assertTrue(r.contains("不得依据本名单自动改写收件人并重发"), r);
        assertFalse(r.contains("未读"), "查名单不该顺带读信封：" + r);
        assertFalse(r.contains("📬"), "查名单不该返回线程列表：" + r);
        verifyNoInteractions(msgMapper, cursorMapper);
    }
}
