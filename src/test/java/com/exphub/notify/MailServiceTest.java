package com.exphub.notify;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 邮件通道的四条硬约束（2026-11-19，抄忆阁 3.3.96 的作业）。
 *
 * <p>这里刻意不连 SMTP：{@link MailTransport} 抽出来就是为了能用假实现验
 * 「未配置跳过 / 去重 / 重试 / 失败不外抛 / 频控」—— 这些行为在真 SMTP 上根本测不出来，
 * 而它们恰好是「通知发不出去会不会拖垮派单」的全部答案。
 */
class MailServiceTest {

    /** 记录每次投递；可配置「前 N 次失败」。 */
    private static final class FakeTransport implements MailTransport {
        final List<String> subjects = new ArrayList<>();
        final AtomicInteger calls = new AtomicInteger();
        int failFirstN;

        FakeTransport(int failFirstN) {
            this.failFirstN = failFirstN;
        }

        @Override
        public void send(MailProperties cfg, String subject, String body) throws Exception {
            int n = calls.incrementAndGet();
            if (n <= failFirstN) {
                throw new IllegalStateException("smtp down " + n);
            }
            subjects.add(subject);
        }
    }

    private static MailProperties configured() {
        MailProperties p = new MailProperties();
        p.setHost("smtp.example.com");
        p.setUser("bot@example.com");
        p.setPass("secret-授权码");
        p.setTo("owner@example.com");
        p.setRetryDelaysMs("0,0");
        return p;
    }

    /** 用同步 executor（Runnable::run），否则测试要等线程池。 */
    private static MailService service(MailProperties p, MailTransport t) {
        return new MailService(p, t, Runnable::run);
    }

    @Test
    @DisplayName("未配置不发信：返回可读原因，且完全不出网（fail-soft，不是启动失败）")
    void unconfiguredSkipsWithoutTouchingTransport() {
        MailProperties p = new MailProperties();
        p.setHost("");
        FakeTransport t = new FakeTransport(0);

        MailService.Result r = service(p, t).send("主题", "正文", "k1");

        assertFalse(r.ok());
        assertTrue(r.message().contains("未配置"), "没说清为什么没发：" + r.message());
        assertEquals(0, t.calls.get(), "未配置却真的去连 SMTP 了");
    }

    @Test
    @DisplayName("同一事件键只发一封；换一个键才再发")
    void duplicateEventKeySendsOnce() {
        MailProperties p = configured();
        FakeTransport t = new FakeTransport(0);
        MailService mail = service(p, t);

        assertTrue(mail.send("待验收 A", "正文", "dispatch-verifying:W-1-01").ok());
        MailService.Result dup = mail.send("待验收 A", "正文", "dispatch-verifying:W-1-01");
        assertFalse(dup.ok(), "同一轮重复触发不该再发一封");
        assertTrue(dup.message().contains("已经发过"), "去重没说明原因：" + dup.message());
        assertEquals(1, t.calls.get());

        assertTrue(mail.send("待验收 A", "正文", "dispatch-verifying:W-1-02").ok(), "新的一轮应该再提醒");
        assertEquals(2, t.calls.get());
    }

    @Test
    @DisplayName("失败重试两次后成功：共 3 次尝试，最终算送达")
    void retriesThenSucceeds() {
        MailProperties p = configured();
        FakeTransport t = new FakeTransport(2);   // 前两次失败，第三次成功

        assertTrue(service(p, t).deliverNow(p, "主题", "正文"), "重试后本该成功");
        assertEquals(3, t.calls.get(), "重试次数不对（应当 1 次首发 + 2 次重试）");
    }

    @Test
    @DisplayName("三次全失败也不外抛：返回 false，由调用方决定怎么降级")
    void totalFailureNeverThrows() {
        MailProperties p = configured();
        FakeTransport t = new FakeTransport(99);

        boolean delivered = service(p, t).deliverNow(p, "主题", "正文");

        assertFalse(delivered);
        assertEquals(3, t.calls.get(), "应当尝试 3 次后放弃");
    }

    @Test
    @DisplayName("频控到顶时拒发，但说清是哪条上限 —— 且不会悄悄丢（累计进下一封）")
    void rateLimitRefusesWithReason() {
        MailProperties p = configured();
        p.setHourLimit(1);
        p.setDayLimit(0);   // 0 = 不限
        FakeTransport t = new FakeTransport(0);
        MailService mail = service(p, t);

        assertTrue(mail.send("第一封", "正文", "k1").ok());
        MailService.Result second = mail.send("第二封", "正文", "k2");

        assertFalse(second.ok());
        assertTrue(second.message().contains("每小时"), "没说是哪条上限：" + second.message());
        assertEquals(1, t.calls.get());
    }

    @Test
    @DisplayName("凭证不进日志：失败原因里的授权码与邮箱都被抹掉")
    void secretsAreScrubbedFromReasons() {
        String reason = MailService.safeReason(
                new IllegalStateException("auth failed for bot@example.com pwd=secret-授权码"),
                "secret-授权码");

        assertFalse(reason.contains("secret-授权码"), "授权码进日志了：" + reason);
        assertFalse(reason.contains("bot@example.com"), "完整邮箱进日志了：" + reason);
        assertTrue(reason.contains("***"), "该脱敏的地方没有 ***：" + reason);
    }

    @Test
    @DisplayName("收件人日志只留脱敏形式")
    void recipientIsMasked() {
        assertEquals("o***@e***.com", MailService.maskRecipients("owner@example.com"));
        assertTrue(MailService.maskRecipients("a@x.com,b@y.com").contains("等 2 个"));
        assertEquals("", MailService.maskRecipients(""));
    }

    @Test
    @DisplayName("重试间隔解析：坏值跳过、空值回落默认，不让一个配置笔误拖死发信")
    void parseDelaysIsForgiving() {
        assertEquals(2, MailService.parseDelays("").length);
        assertEquals(2, MailService.parseDelays("abc,,").length, "全是坏值时必须回落默认");
        long[] parsed = MailService.parseDelays("0, 5000");
        assertEquals(0L, parsed[0]);
        assertEquals(5000L, parsed[1]);
    }
}
