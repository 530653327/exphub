package com.exphub.notify;

import jakarta.mail.internet.MimeMessage;
import org.springframework.mail.javamail.JavaMailSenderImpl;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.stereotype.Component;

import java.util.Properties;

/**
 * SMTP 实现（2026-11-19，抄忆阁 3.3.96 的 {@code SmtpMailTransport}）。
 *
 * <p><b>端口约定</b>：465 走隐式 SSL，其余端口（常见 587）走 STARTTLS —— 163/QQ 邮箱两种都支持，
 * 拿不准时按 465 配。
 *
 * <p><b>凭证安全</b>：本类不打日志；异常原样抛出，由 {@link MailService} 脱敏后只记类名。
 */
@Component
public class SmtpMailTransport implements MailTransport {

    @Override
    public void send(MailProperties cfg, String subject, String body) throws Exception {
        JavaMailSenderImpl sender = buildSender(cfg);
        MimeMessage msg = sender.createMimeMessage();
        // 纯文本 + UTF-8：主题/正文里的中文由 jakarta.mail 自动编码
        MimeMessageHelper helper = new MimeMessageHelper(msg, false, "UTF-8");
        String fromName = cfg.getFromName() == null ? "" : cfg.getFromName().trim();
        if (fromName.isEmpty()) {
            helper.setFrom(cfg.getUser());
        } else {
            helper.setFrom(cfg.getUser(), fromName);
        }
        helper.setTo(splitAddresses(cfg.getTo()));
        helper.setSubject(subject);
        helper.setText(body == null ? "" : body, false);
        sender.send(msg);
    }

    /**
     * 按配置建 sender。抽出来是为了单测能验「465 走 SSL / 其它端口走 STARTTLS」与超时，
     * 不必真连 SMTP。
     */
    JavaMailSenderImpl buildSender(MailProperties cfg) {
        JavaMailSenderImpl sender = new JavaMailSenderImpl();
        sender.setHost(cfg.getHost());
        sender.setPort(cfg.getPort() <= 0 ? 465 : cfg.getPort());
        sender.setUsername(cfg.getUser());
        sender.setPassword(cfg.getPass());
        sender.setDefaultEncoding("UTF-8");

        Properties p = sender.getJavaMailProperties();
        p.put("mail.smtp.auth", "true");
        p.put("mail.smtp.connectiontimeout", String.valueOf(Math.max(1000, cfg.getConnectTimeoutMs())));
        p.put("mail.smtp.timeout", String.valueOf(Math.max(1000, cfg.getReadTimeoutMs())));
        p.put("mail.smtp.writetimeout", String.valueOf(Math.max(1000, cfg.getReadTimeoutMs())));
        if (sender.getPort() == 465) {
            p.put("mail.smtp.ssl.enable", "true");
        } else {
            p.put("mail.smtp.starttls.enable", "true");
        }
        return sender;
    }

    /** 收件人支持逗号分隔；空白项直接丢，避免一个空项导致整封发送失败。 */
    static String[] splitAddresses(String raw) {
        if (raw == null || raw.isBlank()) {
            return new String[0];
        }
        return java.util.Arrays.stream(raw.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .toArray(String[]::new);
    }
}
