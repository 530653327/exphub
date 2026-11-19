package com.exphub.notify;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import java.time.format.DateTimeFormatter;

/**
 * 派单进「待验收」时给越哥发邮件（2026-11-19）。
 *
 * <p><b>为什么这一步必须有提醒</b>：VERIFYING → DONE 只能越哥在后台点，MCP 侧没有任何验收动作
 * （设计如此，闸门在人手里）。没有提醒时，单子会一直挂在「待验收」而你并不知情 ——
 * 哨兵以为交完了，实际球停在一个没人看见的地方。
 *
 * <p><b>三条边界</b>：
 * <ol>
 *   <li>只在 {@code AFTER_COMMIT} 发：事务回滚不发信，发信也不参与事务；</li>
 *   <li>任何异常都吞掉并降级 WARN —— 通知失败绝不能影响「提交验收」本身；</li>
 *   <li>未配置邮件通道 = 静默跳过（MailService 里每分钟最多打一条提示），主流程照跑。</li>
 * </ol>
 */
@Component
public class DispatchVerifyingNotifier {

    private static final Logger log = LoggerFactory.getLogger(DispatchVerifyingNotifier.class);

    /** 正文里「内容」段最多带多少字 —— 邮件是提醒，不是把整篇复盘搬过去。 */
    static final int MAX_CONTENT_CHARS = 1500;
    static final int MAX_TITLE_CHARS = 120;

    private static final DateTimeFormatter TIME_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

    private final MailService mail;
    private final MailProperties props;

    public DispatchVerifyingNotifier(MailService mail, MailProperties props) {
        this.mail = mail;
        this.props = props;
    }

    /**
     * 事务提交后发提醒；{@code fallbackExecution=true} 保证「没有事务上下文直接调用」时也发
     * （单测与将来可能的非事务入口都走得到这里）。
     */
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void onVerifying(DispatchVerifyingEvent e) {
        if (e == null) {
            return;
        }
        try {
            String subject = subjectOf(e);
            String body = bodyOf(e, props == null ? "" : props.getBaseUrl());
            // 去重键用 itemNo：同一轮只发一封；驳回后重新提交是新的一轮，会再提醒
            MailService.Result r = mail.send(subject, body, "dispatch-verifying:" + e.itemNo());
            if (!r.ok()) {
                // 「没发出去」不是错误，不该刷 ERROR —— 未配置、去重、频控都走这一条
                log.info("派单 {} 的验收提醒未发出：{}", e.orderNo(), r.message());
            }
        } catch (Exception ex) {
            log.warn("派单 {} 的验收提醒发送异常，已忽略：{}", e.orderNo(), ex.getClass().getSimpleName());
        }
    }

    /** 主题：一眼看出是哪张单、谁交的、要人做什么。 */
    static String subjectOf(DispatchVerifyingEvent e) {
        String title = clip(e.title(), 60);
        String who = isBlank(e.assignee()) ? "" : "（" + e.assignee() + "）";
        return clip("【待验收】" + e.orderNo() + " " + title + who, MAX_TITLE_CHARS);
    }

    /**
     * 正文全部是纯文本。顺序按「越哥读邮件时的判断顺序」：
     * 哪张单 → 谁交的 → 他为什么觉得做完了（结论）→ 细节 → 去哪儿点。
     */
    static String bodyOf(DispatchVerifyingEvent e, String baseUrl) {
        StringBuilder sb = new StringBuilder();
        sb.append("单号：").append(nvl(e.orderNo())).append('\n');
        sb.append("标题：").append(nvl(e.title())).append('\n');
        sb.append("指派：").append(nvl(e.assignee()));
        if (!isBlank(e.accepter())) {
            sb.append("｜接单：").append(e.accepter());
        }
        sb.append('\n');
        if (e.at() != null) {
            sb.append("提交时间：").append(e.at().format(TIME_FMT)).append('\n');
        }
        sb.append("\n结论：\n").append(nvl(e.conclusion())).append('\n');
        if (!isBlank(e.content())) {
            sb.append("\n内容（前 ").append(MAX_CONTENT_CHARS).append(" 字）：\n")
              .append(clip(e.content(), MAX_CONTENT_CHARS)).append('\n');
        }
        String url = detailUrl(baseUrl, e.orderNo());
        if (!url.isEmpty()) {
            sb.append("\n去后台处理：").append(url).append('\n');
        }
        sb.append("\n审核 / 验收通过 / 驳回 / 取消都只能你在后台点，AI 没有这些动作。\n");
        return sb.toString();
    }

    /** 只接受 http/https，且 baseUrl 为空时不拼出半截链接。 */
    static String detailUrl(String baseUrl, String orderNo) {
        String base = baseUrl == null ? "" : baseUrl.trim();
        if (base.isEmpty() || isBlank(orderNo)) {
            return "";
        }
        while (base.endsWith("/")) {
            base = base.substring(0, base.length() - 1);
        }
        String low = base.toLowerCase(java.util.Locale.ROOT);
        if (!low.startsWith("http://") && !low.startsWith("https://")) {
            return "";
        }
        return base + "/dispatch/" + orderNo.trim();
    }

    private static String clip(String s, int max) {
        String v = s == null ? "" : s.trim();
        return v.length() <= max ? v : v.substring(0, max) + "…";
    }

    private static String nvl(String s) {
        return s == null ? "" : s.trim();
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }
}
