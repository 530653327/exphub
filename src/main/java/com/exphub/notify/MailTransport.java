package com.exphub.notify;

/**
 * 邮件发送的唯一出口（2026-11-19，抄忆阁 3.3.96）。
 *
 * <p><b>为什么抽一层接口</b>：真发信要连 SMTP，单测里不可能真连。把「拼邮件 + 发出去」
 * 收在接口后面，{@link MailService} 的重试、去重、频控就能用假实现完整验证。
 *
 * <p>实现类**不许**在异常信息里带授权码。
 */
public interface MailTransport {

    /**
     * 发一封纯文本邮件。
     *
     * @param cfg     通道配置（host/port/user/pass/to）
     * @param subject 主题（已含【】前缀、长度已裁剪）
     * @param body    正文
     * @throws Exception 任意发送失败；重试与降级由 {@link MailService} 负责
     */
    void send(MailProperties cfg, String subject, String body) throws Exception;
}
