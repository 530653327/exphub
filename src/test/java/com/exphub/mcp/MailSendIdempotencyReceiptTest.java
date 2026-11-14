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

import java.time.LocalDateTime;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * mail_send 幂等命中的回执（2026-11-14 阁主 T261114082605-521）。
 *
 * <p>现场：同 clientMsgId 二次发送，服务端回放旧信，工具层渲染出与「真发一封」逐字相同的
 * 「✅ 已发给…」—— 忆阁据此把委托单推进到 running，收件人信箱里却没有任何新信。
 *
 * <p>这里钉两件事：① 回放与新建在回执上必须可区分；② 键被复用到「不是同一封信」时必须明拒，
 * 不许拿别人的/别的用途的旧信当成功返回。
 */
class MailSendIdempotencyReceiptTest {

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
        ReflectionTestUtils.setField(svc, "roleCatalog", com.exphub.service.MailRoleFixtures.catalog(
                Set.of("烛龙", "阁主", "白泽"),
                Map.of("zhulong", "烛龙", "gezhu", "阁主", "baize", "白泽"),
                Map.of()));

        when(msgMapper.insert(any(MailMessage.class))).thenAnswer(inv -> {
            MailMessage m = inv.getArgument(0);
            m.setId(99L);
            return 1;
        });

        tools = new ExpHubTools();
        ReflectionTestUtils.setField(tools, "mailboxService", svc);
        MailIdentity.set("gezhu", "cfg-gezhu-test");
    }

    @AfterEach
    void clear() {
        MailIdentity.clear();
    }

    private MailMessage oldLetter() {
        MailMessage exist = new MailMessage();
        exist.setId(77L);
        exist.setThreadId("T-OLD");
        exist.setFromRole("阁主");
        exist.setToRole("烛龙");
        exist.setMsgType("note");
        exist.setSubject("通知");
        exist.setBody("正文");
        exist.setClientMsgId("CM-1");
        exist.setCreatedAt(LocalDateTime.of(2026, 11, 14, 0, 26));
        when(msgMapper.selectOne(any())).thenReturn(exist);
        when(msgMapper.selectById(77L)).thenReturn(exist);
        return exist;
    }

    @Test
    @DisplayName("幂等命中：回执首句写「没有新建消息」，不渲染成 ✅ 已发出")
    void replayMustNotLookLikeFreshSend() {
        oldLetter();

        String r = tools.mailSend("zhulong", "通知", "正文", null, "note", "CM-1",
                null, null, null);

        assertFalse(r.contains("✅ 已以"), "回放不许印「已发给」，那正是事故现场：" + r);
        assertTrue(r.startsWith("⚠️ 幂等命中"), r);
        assertTrue(r.contains("没有新建消息"), r);
        assertTrue(r.contains("消息 #77"), "要报出原消息 id，调用方能核对：" + r);
        assertTrue(r.contains("原发送时间 11-14 00:26"), r);
        assertTrue(r.contains("首次那次已经成功"), "内容一致时要说清楚这是重试：" + r);
        assertTrue(r.contains("别把它当成「刚发出去」"), r);
        verify(msgMapper, never()).insert(any(MailMessage.class));
    }

    @Test
    @DisplayName("同键但内容不同：点出主题/正文差异，并劝换新键（不是重试，是键被复用）")
    void replayWithDifferentContentIsFlaggedAsKeyReuse() {
        oldLetter();

        String r = tools.mailSend("zhulong", "W-20261114-02 发版", "另一份正文内容", null, "dispatch", "CM-1",
                null, null, null);

        assertTrue(r.contains("不是同一封信"), r);
        assertTrue(r.contains("主题不同"), "主题差异必须逐项说出来：" + r);
        assertTrue(r.contains("正文不同"), r);
        assertTrue(r.contains("换一个没被用过的 clientMsgId"), r);
        verify(msgMapper, never()).insert(any(MailMessage.class));
    }

    @Test
    @DisplayName("同键换线程/回复目标：明拒，不许拿旧信冒充回信")
    void inReplyToMismatchRejected() {
        oldLetter(); // 库里那封是「新开线程」，这次却想当回信发

        String r = tools.mailSend("zhulong", "通知", "正文", 50L, "receipt", "CM-1",
                null, null, null);

        assertTrue(r.startsWith("❌"), r);
        assertTrue(r.contains("只能重放同一封信"), r);
        assertTrue(r.contains("换新键"), r);
        verify(msgMapper, never()).insert(any(MailMessage.class));
    }

    @Test
    @DisplayName("同键换收件人：明拒（调用方以为发给 A，实际返回的是发给 B 的旧信）")
    void recipientMismatchRejected() {
        MailMessage exist = oldLetter();
        exist.setToRole("白泽");

        String r = tools.mailSend("zhulong", "通知", "正文", null, "dispatch", "CM-1",
                null, null, null);

        assertTrue(r.startsWith("❌"), r);
        assertTrue(r.contains("改收件人必须换键重发"), r);
        verify(msgMapper, never()).insert(any(MailMessage.class));
    }

    @Test
    @DisplayName("同键撞别人发的信：明拒（键全局唯一，不同发件人不能共用）")
    void crossSenderCollisionRejected() {
        MailMessage exist = oldLetter();
        exist.setFromRole("白泽");

        String r = tools.mailSend("zhulong", "通知", "正文", null, "note", "CM-1",
                null, null, null);

        assertTrue(r.startsWith("❌"), r);
        assertTrue(r.contains("不同发件人不能共用同一个键"), r);
        verify(msgMapper, never()).insert(any(MailMessage.class));
    }

    @Test
    @DisplayName("没有幂等键的正常发信仍然从 ✅ 开始（不许把主路径改坏）")
    void freshSendStillRendersSuccess() {
        String r = tools.mailSend("zhulong", "通知", "正文", null, "note", null,
                null, null, null);

        assertTrue(r.startsWith("✅ 已以【阁主】"), r);
        assertTrue(r.contains("消息 #99"), r);
        assertFalse(r.contains("幂等命中"), r);
        verify(msgMapper, times(1)).insert(any(MailMessage.class));
    }
}
