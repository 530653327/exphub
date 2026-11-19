package com.exphub.notify;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 邮件提醒通道配置（2026-11-19，抄忆阁 3.3.96 的作业）。
 *
 * <p><b>为什么不配 {@code spring.mail.*}</b>：那套自动配置在 host 为空时可能在建 bean 阶段就炸，
 * 而本通道的硬要求是「越哥没填凭证时整站照样能启动」。所以这里自己承载配置，
 * 由 {@link SmtpMailTransport} 现建 {@link org.springframework.mail.javamail.JavaMailSenderImpl}。
 *
 * <p><b>凭证（{@code pass}）三条规矩</b>：不进日志、不进仓库、不进任何接口响应。
 * 真值只写在服务器 {@code /etc/exphub/.env}（systemd EnvironmentFile），
 * 本文件里只有占位符。
 */
@Data
@Component
@ConfigurationProperties(prefix = "exphub.mail")
public class MailProperties {

    /** 总开关。false = 一律不发（但启动照常）。 */
    private boolean enabled = true;

    private String host = "";
    private int port = 465;
    private String user = "";

    /** 授权码。{@code @ToString.Exclude} 防 {@code toString()} 把它带进日志。 */
    @lombok.ToString.Exclude
    private String pass = "";

    /** 收件人，逗号分隔。 */
    private String to = "";

    private String fromName = "经验阁 ExpHub";

    /** 邮件里的「去后台处理」链接前缀，不带末尾斜杠。 */
    private String baseUrl = "https://cloudim.club/exphub";

    /** 同一事件 N 分钟内只发一封（防止重试/重复触发把同一件事发两遍）。 */
    private int dedupMinutes = 10;

    /** 频控：每小时 / 每天最多几封；<=0 表示不限。超限的累计成「另有 N 条」进下一封。 */
    private int hourLimit = 20;
    private int dayLimit = 100;

    private int connectTimeoutMs = 5000;
    private int readTimeoutMs = 10000;

    /** 失败重试间隔（毫秒，逗号分隔），默认 5s、15s，共 3 次尝试。 */
    private String retryDelaysMs = "5000,15000";

    /** 四项齐全才算「配置好了」；缺任一项 = 未配置，发信时跳过并说明。 */
    public boolean complete() {
        return notBlank(host) && notBlank(user) && notBlank(pass) && notBlank(to);
    }

    public boolean ready() {
        return enabled && complete();
    }

    private static boolean notBlank(String s) {
        return s != null && !s.isBlank();
    }
}
