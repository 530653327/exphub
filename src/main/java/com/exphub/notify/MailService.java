package com.exphub.notify;

import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 邮件提醒通道（2026-11-19，抄忆阁 3.3.96 / ExpHub #3323 的作业）。
 *
 * <p><b>为什么是它</b>：派单提交验收后，只有越哥在后台点「通过/驳回」才能往下走。
 * 原来这一步没有任何提醒，单子会一直挂在「待验收」，球在人手里却没人知道。
 *
 * <p><b>五条硬约束（与忆阁一致，改之前先看为什么）</b>：
 * <ol>
 *   <li><b>异步发送</b>：独立单线程 executor（{@code exphub-mail-sender}），不占 Spring 调度池
 *       （登录失败清理在用），也不阻塞 MCP 回合与后台请求；</li>
 *   <li><b>失败不外抛</b>：重试 2 次（默认 5s/15s）后放弃，只记 WARN ——
 *       <b>通知发不出去，绝不能把状态转移一起拖垮</b>（AGENTS.md 第 2 章）；</li>
 *   <li><b>缺配置 fail-soft</b>：启动不报错，发信时打一条节流日志「未配置，已跳过」，主流程照跑；</li>
 *   <li><b>去重 + 频控</b>：同一事件 10 分钟一封、每小时 20 封、每天 100 封；超限不丢，
 *       累计成「另有 N 条」写进下一封。**ExpHub 没有 Redis**，所以按单 JVM 内存记 —
 *       重启后计数归零，这个偏差可接受（重启本来就是低频动作）；</li>
 *   <li><b>凭证零回显</b>：日志只打主题与异常类名，收件人只打脱敏形式。</li>
 * </ol>
 */
@Service
public class MailService {

    private static final Logger log = LoggerFactory.getLogger(MailService.class);

    private static final DateTimeFormatter HOUR_FMT = DateTimeFormatter.ofPattern("yyyyMMddHH");
    private static final DateTimeFormatter TIME_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final java.util.regex.Pattern EMAIL_PATTERN =
            java.util.regex.Pattern.compile("[A-Za-z0-9._%+\\-]+@[A-Za-z0-9.\\-]+\\.[A-Za-z]{2,}");

    private static final int MAX_TITLE_CHARS = 120;
    private static final int MAX_BODY_CHARS = 4000;
    private static final int QUEUE_CAPACITY = 200;
    /** 去重表超过这个规模就顺手清一次过期项，避免长期运行无限长。 */
    private static final int DEDUP_CLEAN_THRESHOLD = 500;

    private final MailProperties props;
    private final MailTransport transport;
    private final Executor executor;

    /** eventKey → 过期时间戳（毫秒）。单 JVM 内存，重启归零。 */
    private final Map<String, Long> dedup = new ConcurrentHashMap<>();

    /** 小时/天计数与「被频控挤掉、合并进下一封」的条数。 */
    private final Object rateLock = new Object();
    private String rateHour = "";
    private String rateDay = "";
    private int hourCount;
    private int dayCount;
    private int pending;

    /** 「未配置」提示每分钟最多一条，避免每次提交都刷同一行。 */
    private final AtomicLong lastSkipLog = new AtomicLong();

    @Autowired
    public MailService(MailProperties props, MailTransport transport) {
        this(props, transport, newExecutor());
    }

    /**
     * 测试可注入的构造器：传 {@code Runnable::run} 就是同步执行（发信立即发生），
     * 传真实线程池时与生产一致。
     */
    public MailService(MailProperties props, MailTransport transport, Executor executor) {
        this.props = props;
        this.transport = transport;
        this.executor = executor;
    }

    /**
     * 发送结果。
     *
     * <p>{@code message} 必须说清「为什么没发出去」—— 「已排队」不等于「已送达」，
     * 调用方不许把它当成功通知传给用户。
     */
    public record Result(boolean ok, String message) {
    }

    /** 通道是否配置完整（只回布尔，绝不回显 host/user/pass/to）。 */
    public boolean isConfigured() {
        return props != null && props.ready();
    }

    /**
     * 启动时打一行「配没配」—— 2026-11-19 加。
     * 没有这行的话，「邮件没收到」时只能靠翻 env 文件判断是没配还是发失败，
     * 而成功率最高的误判就是「以为配了」。**只打 host 与脱敏收件人，不打 user/pass。**
     */
    @jakarta.annotation.PostConstruct
    void logChannelStatus() {
        if (isConfigured()) {
            log.info("邮件提醒通道已配置：host={}，收件人={}（派单进「待验收」会发提醒）",
                    props.getHost(), maskRecipients(props.getTo()));
        } else {
            log.info("邮件提醒通道未配置（exphub.mail.*）：派单进「待验收」不会发邮件，主流程不受影响");
        }
    }

    /**
     * 发一封提醒（对外唯一入口）。
     *
     * @param subject  主题，必填
     * @param body     正文，必填
     * @param eventKey 事件键：同一键在去重窗口内只发一封；为空时用「主题+正文」哈希兜底
     */
    public Result send(String subject, String body, String eventKey) {
        MailProperties cfg = props;
        if (cfg == null || !cfg.ready()) {
            logSkipUnconfigured();
            return new Result(false, "邮件通道未配置，已跳过（这次没有发出）。");
        }
        if (isBlank(subject) || isBlank(body)) {
            return new Result(false, "主题和正文都不能为空。");
        }

        String key = isBlank(eventKey) ? sha16(subject + "|" + body) : eventKey.trim();
        if (!acquireDedup(key, cfg)) {
            return new Result(false, "同一件事 " + Math.max(1, cfg.getDedupMinutes())
                    + " 分钟内已经发过一封，这次没有重复发。");
        }
        String scope = acquireRate(cfg);
        if (scope != null) {
            return new Result(false, "邮件提醒已触发「" + scope + "」上限（"
                    + ("每小时".equals(scope) ? cfg.getHourLimit() + " 封/小时" : cfg.getDayLimit() + " 封/天")
                    + "），这次没有发；已累计到下一次合并提醒。");
        }

        int merged = takePending();
        String subj = clip(subject, MAX_TITLE_CHARS);
        String text = buildBody(body, merged);
        try {
            executor.execute(() -> deliver(cfg, subj, text));
        } catch (RejectedExecutionException e) {
            log.warn("邮件发送队列已满，本次未入队");
            return new Result(false, "邮件发送队列已满，这次没有发出。");
        }
        String mergeNote = merged > 0 ? "（合并了另有 " + merged + " 条未推送）" : "";
        return new Result(true, "已排队发往越哥邮箱：" + mergeNote);
    }

    /** 供单测直接调用：跳过异步队列，执行一次「带重试的投递」。 */
    public boolean deliverNow(MailProperties cfg, String subject, String body) {
        return deliver(cfg, subject, body);
    }

    /**
     * 真正投递：首次 + 按配置间隔重试。任何异常都不外抛。
     *
     * @return true=送达；false=重试后仍失败
     */
    private boolean deliver(MailProperties cfg, String subject, String body) {
        long[] delays = parseDelays(cfg.getRetryDelaysMs());
        int attempts = delays.length + 1;
        for (int i = 0; i < attempts; i++) {
            if (i > 0 && !sleep(delays[i - 1])) {
                return false;
            }
            try {
                transport.send(cfg, subject, body);
                log.info("邮件提醒已送达：{}", clip(subject, 60));
                return true;
            } catch (Exception e) {
                // 异常消息可能带账号/服务器/收件地址，脱敏后再进日志
                log.warn("邮件提醒发送失败（第 {}/{} 次，原因 {}）", i + 1, attempts, safeReason(e, cfg.getPass()));
            }
        }
        log.error("邮件提醒投递失败：重试 {} 次（共 {} 次尝试）仍失败，收件人={}，主题={}",
                delays.length, attempts, maskRecipients(cfg.getTo()), clip(subject, 60));
        return false;
    }

    /** @return true=窗口内第一次，放行；false=重复事件 */
    private boolean acquireDedup(String key, MailProperties cfg) {
        long now = System.currentTimeMillis();
        long ttl = Math.max(1, cfg.getDedupMinutes()) * 60_000L;
        if (dedup.size() > DEDUP_CLEAN_THRESHOLD) {
            dedup.entrySet().removeIf(e -> e.getValue() <= now);
        }
        Long prev = dedup.putIfAbsent(key, now + ttl);
        if (prev == null) {
            return true;
        }
        if (prev <= now) {
            dedup.put(key, now + ttl);
            return true;
        }
        return false;
    }

    /**
     * 小时/天计数。先加再判超限 —— 到顶后请求根本不出网。
     *
     * @return null=放行；非空=命中的口径（「每小时」/「每天」，超限时累计一条 pending）
     */
    private String acquireRate(MailProperties cfg) {
        synchronized (rateLock) {
            String hour = LocalDateTime.now().format(HOUR_FMT);
            String day = LocalDate.now().toString();
            if (!hour.equals(rateHour)) {
                rateHour = hour;
                hourCount = 0;
            }
            if (!day.equals(rateDay)) {
                rateDay = day;
                dayCount = 0;
            }
            hourCount++;
            dayCount++;
            if (cfg.getHourLimit() > 0 && hourCount > cfg.getHourLimit()) {
                pending++;
                return "每小时";
            }
            if (cfg.getDayLimit() > 0 && dayCount > cfg.getDayLimit()) {
                pending++;
                return "每天";
            }
            return null;
        }
    }

    private int takePending() {
        synchronized (rateLock) {
            int n = pending;
            pending = 0;
            return n;
        }
    }

    /** 正文尾部补时间与来源；被频控合并的条数写清楚，不然越哥看到的是「提醒突然停了」。 */
    private static String buildBody(String body, int merged) {
        StringBuilder sb = new StringBuilder(body == null ? "" : body.trim());
        if (merged > 0) {
            sb.append("\n\n另有 ").append(merged).append(" 条提醒因频控合并到这一封（后台能看到全部单）。");
        }
        sb.append("\n\n—— 经验阁 ExpHub · ").append(LocalDateTime.now().format(TIME_FMT));
        return clip(sb.toString(), MAX_BODY_CHARS);
    }

    /** 收件人脱敏：{@code owner@example.com → o***@e***.com}；多个只标数量。 */
    static String maskRecipients(String raw) {
        String s = raw == null ? "" : raw.trim();
        if (s.isEmpty()) {
            return "";
        }
        Deque<String> parts = new ArrayDeque<>();
        for (String p : s.split("[,;\\s]+")) {
            if (!p.isEmpty()) {
                parts.add(maskOne(p));
            }
        }
        if (parts.isEmpty()) {
            return "";
        }
        return parts.size() == 1 ? parts.peekFirst() : parts.peekFirst() + " 等 " + parts.size() + " 个";
    }

    private static String maskOne(String addr) {
        int at = addr.lastIndexOf('@');
        if (at <= 0) {
            return addr.charAt(0) + "***";
        }
        String local = addr.substring(0, at);
        String domain = addr.substring(at + 1);
        String maskedLocal = local.isEmpty() ? "***" : local.charAt(0) + "***";
        int dot = domain.lastIndexOf('.');
        String maskedDomain = (dot > 0 && dot < domain.length() - 1)
                ? domain.charAt(0) + "***" + domain.substring(dot)
                : (domain.isEmpty() ? "***" : domain.charAt(0) + "***");
        return maskedLocal + "@" + maskedDomain;
    }

    /** 失败原因脱敏：抹掉授权码原值、邮箱地址与换行，再裁长度。 */
    static String safeReason(Throwable e, String secret) {
        String cls = e == null ? "Unknown" : e.getClass().getSimpleName();
        String msg = e == null || e.getMessage() == null ? "" : e.getMessage();
        if (secret != null && !secret.isBlank()) {
            msg = msg.replace(secret, "***");
        }
        msg = EMAIL_PATTERN.matcher(msg).replaceAll("***");
        msg = msg.replaceAll("[\\r\\n\\t]+", " ").trim();
        String out = msg.isEmpty() ? cls : cls + ": " + msg;
        return clip(out, 200);
    }

    /** 解析重试间隔；空/坏值回落到 5s、15s（最多 5 个，防止配错拖死线程）。 */
    public static long[] parseDelays(String raw) {
        if (raw == null || raw.isBlank()) {
            return new long[]{5000L, 15000L};
        }
        java.util.List<Long> out = new java.util.ArrayList<>();
        for (String part : raw.split(",")) {
            String p = part.trim();
            if (p.isEmpty()) {
                continue;
            }
            try {
                long v = Long.parseLong(p);
                if (v >= 0) {
                    out.add(v);
                }
            } catch (NumberFormatException ignore) {
                // 坏值跳过，不因一个配置笔误让整条通道不可用
            }
            if (out.size() >= 5) {
                break;
            }
        }
        if (out.isEmpty()) {
            return new long[]{5000L, 15000L};
        }
        long[] arr = new long[out.size()];
        for (int i = 0; i < out.size(); i++) {
            arr[i] = out.get(i);
        }
        return arr;
    }

    private static boolean sleep(long ms) {
        if (ms <= 0) {
            return true;
        }
        try {
            Thread.sleep(ms);
            return true;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    private void logSkipUnconfigured() {
        long now = System.currentTimeMillis();
        long last = lastSkipLog.get();
        if (now - last > 60_000L && lastSkipLog.compareAndSet(last, now)) {
            // 只说「未配置」，不打 host/user/pass/to 任何一个值
            log.info("邮件通道未配置（exphub.mail.*），本次跳过发信");
        }
    }

    private static String clip(String s, int max) {
        String v = s == null ? "" : s.trim();
        return v.length() <= max ? v : v.substring(0, max) + "…";
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }

    private static String sha16(String raw) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] d = md.digest(raw.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < 8; i++) {
                sb.append(String.format("%02x", d[i]));
            }
            return sb.toString();
        } catch (Exception e) {
            return Integer.toHexString(raw.hashCode());
        }
    }

    private static Executor newExecutor() {
        ThreadPoolExecutor pool = new ThreadPoolExecutor(
                1, 1, 0L, TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(QUEUE_CAPACITY),
                r -> {
                    Thread t = new Thread(r, "exphub-mail-sender");
                    t.setDaemon(true);
                    return t;
                },
                new ThreadPoolExecutor.AbortPolicy());
        pool.allowCoreThreadTimeOut(false);
        return pool;
    }

    @PreDestroy
    public void shutdown() {
        if (executor instanceof ExecutorService es) {
            es.shutdown();
            try {
                if (!es.awaitTermination(3, TimeUnit.SECONDS)) {
                    es.shutdownNow();
                }
            } catch (InterruptedException e) {
                es.shutdownNow();
                Thread.currentThread().interrupt();
            }
        }
    }
}
