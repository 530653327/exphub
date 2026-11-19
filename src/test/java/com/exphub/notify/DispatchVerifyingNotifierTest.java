package com.exphub.notify;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 「派单进待验收 → 发邮件」的文案与边界（2026-11-19）。
 *
 * <p>为什么单独测文案：这封邮件是越哥唯一会看到的提醒，
 * 缺了「单号」他找不到单、缺了「结论」他得点进去才知道要判什么、
 * 缺了链接他得自己拼 URL —— 每一处都让「提醒」退化成「骚扰」。
 */
class DispatchVerifyingNotifierTest {

    private static DispatchVerifyingEvent event() {
        return new DispatchVerifyingEvent(
                "W-20261119-000021",
                "后台派单列表加翻页",
                "白泽",
                "白泽",
                "PC 每页 20、手机每页 10 已上线，749 条测试全绿。",
                "改了 DispatchService / 两个 Controller / 两个模板，加了 DispatchPaginationTest。",
                "W-20261119-000021-02",
                LocalDateTime.of(2026, 11, 19, 11, 40));
    }

    @Test
    @DisplayName("主题一眼能认出是哪张单、谁交的")
    void subjectIdentifiesOrder() {
        String s = DispatchVerifyingNotifier.subjectOf(event());

        assertTrue(s.startsWith("【待验收】"), "主题没有前缀，收件箱里分不出轻重：" + s);
        assertTrue(s.contains("W-20261119-000021"), "主题缺单号：" + s);
        assertTrue(s.contains("白泽"), "主题没写谁交的：" + s);
    }

    @Test
    @DisplayName("正文给全判断所需：单号 / 标题 / 指派 / 结论 / 内容 / 后台链接")
    void bodyCarriesEverythingNeededToJudge() {
        String b = DispatchVerifyingNotifier.bodyOf(event(), "https://cloudim.club/exphub");

        assertTrue(b.contains("W-20261119-000021"), "缺单号：" + b);
        assertTrue(b.contains("后台派单列表加翻页"), "缺标题：" + b);
        assertTrue(b.contains("指派：白泽"), "缺指派人：" + b);
        assertTrue(b.contains("749 条测试全绿"), "缺结论 —— 越哥要照它判断通过还是驳回：" + b);
        assertTrue(b.contains("DispatchPaginationTest"), "缺详细内容：" + b);
        assertTrue(b.contains("https://cloudim.club/exphub/dispatch/W-20261119-000021"),
                "缺后台直达链接，收件人还得自己拼 URL：" + b);
        assertTrue(b.contains("只能你在后台点"), "没提醒「这活只能人做」：" + b);
    }

    @Test
    @DisplayName("内容太长只带前 N 字并说明截断，不把整篇复盘搬进邮件")
    void longContentIsClipped() {
        String huge = "行".repeat(DispatchVerifyingNotifier.MAX_CONTENT_CHARS + 500);
        DispatchVerifyingEvent e = new DispatchVerifyingEvent(
                "W-1", "t", "白泽", "白泽", "c", huge, "W-1-01", LocalDateTime.now());

        String b = DispatchVerifyingNotifier.bodyOf(e, "https://cloudim.club/exphub");

        assertTrue(b.contains("前 " + DispatchVerifyingNotifier.MAX_CONTENT_CHARS + " 字"),
                "截断了却没说，收件人会以为内容就这些");
        assertTrue(b.length() < DispatchVerifyingNotifier.MAX_CONTENT_CHARS + 800,
                "正文没截住，邮件过长：" + b.length());
    }

    @Test
    @DisplayName("链接只接受 http/https：不合法就不拼，避免把半截地址发出去")
    void detailUrlIsSafe() {
        assertTrue(DispatchVerifyingNotifier.detailUrl("https://a.com/exphub/", "W-1")
                .equals("https://a.com/exphub/dispatch/W-1"), "末尾斜杠没处理干净");
        assertTrue(DispatchVerifyingNotifier.detailUrl("", "W-1").isEmpty());
        assertTrue(DispatchVerifyingNotifier.detailUrl("javascript:alert(1)", "W-1").isEmpty(),
                "非 http 协议也拼进去了");
        assertTrue(DispatchVerifyingNotifier.detailUrl("https://a.com", " ").isEmpty());
    }

    @Test
    @DisplayName("通道未配置时监听器安静跳过：不抛异常、不影响提交验收")
    void unconfiguredListenerDoesNotThrow() {
        MailProperties p = new MailProperties();   // 全空 = 未配置
        MailService mail = new MailService(p, (cfg, s, b) -> {
            throw new AssertionError("未配置时不该尝试投递");
        }, Runnable::run);
        DispatchVerifyingNotifier notifier = new DispatchVerifyingNotifier(mail, p);

        assertDoesNotThrow(() -> notifier.onVerifying(event()));
        assertFalse(mail.isConfigured());
    }

    @Test
    @DisplayName("发信抛异常也会被吞掉：通知失败不能把「提交验收」带崩")
    void listenerSwallowsTransportErrors() {
        MailProperties p = new MailProperties();
        p.setHost("smtp.example.com");
        p.setUser("bot@example.com");
        p.setPass("x");
        p.setTo("owner@example.com");
        p.setRetryDelaysMs("0,0");
        MailService mail = new MailService(p, (cfg, s, b) -> {
            throw new IllegalStateException("boom");
        }, Runnable::run);
        DispatchVerifyingNotifier notifier = new DispatchVerifyingNotifier(mail, p);

        assertDoesNotThrow(() -> notifier.onVerifying(event()));
    }

    @Test
    @DisplayName("只有「提交验收」发事件，且在同一次状态转移成功后 —— 别处多发就是重复提醒")
    void onlySubmitAcceptPublishesTheEvent() throws java.io.IOException {
        java.nio.file.Path src = java.nio.file.Path.of(
                "src/main/java/com/exphub/service/DispatchService.java");
        String s = java.nio.file.Files.readString(src, java.nio.charset.StandardCharsets.UTF_8);

        assertEquals(1, countOf(s, "publishVerifying(h, concl, body, it, now)"),
                "发布点不止一处：多一处就多一封邮件");

        int accept = s.indexOf("public Outcome submitAccept(");
        int publish = s.indexOf("publishVerifying(h, concl, body, it, now)");
        int nextMethod = s.indexOf("public Outcome terminate(", accept);
        assertTrue(accept > 0 && publish > accept && (nextMethod < 0 || publish < nextMethod),
                "事件不是在 submitAccept 里发的 —— 提醒会和真实状态（VERIFYING）脱节");
    }

    private static int countOf(String haystack, String needle) {
        int n = 0, i = 0;
        while ((i = haystack.indexOf(needle, i)) >= 0) {
            n++;
            i += needle.length();
        }
        return n;
    }
}
