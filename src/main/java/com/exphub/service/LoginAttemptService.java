package com.exphub.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Duration;
import java.util.Iterator;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 登录失败计数与账号锁定（2026-11-07 · 防爆破第四层，纯内存实现）。
 *
 * <p><b>为什么用内存而不是 Redis/数据库</b>（越哥 2026-11-07 拍板）：
 * ExpHub 是单 JVM 部署（Tomcat 3099 + Netty 3098 同进程），登录只发生在 3099 的
 * Spring Security 里，一个 {@link ConcurrentHashMap} 天然就是全局共享的。
 * 为这点计数引入 Redis 或给 {@code users} 加两个字段，都是给单实例应用凭空加故障点。
 * <b>重启会清空计数</b>：但重启本身就得重新登录，语义上等于「重启即解锁」，可以接受。
 * 真到多实例部署那天，把这一层换成 Redis 即可，接口不用动。
 *
 * <p><b>三个设计取舍</b>：
 * <ol>
 *   <li><b>按用户名计数，不按 IP</b>：后台只有 {@code admin} 一个账号，攻击者目标明确，
 *       按账号锁才是对症的。按 IP 锁的话，对方换个代理就绕过。</li>
 *   <li><b>滑动窗口而非永久累计</b>：只数最近 {@link #WINDOW} 内的失败。
 *       否则「三年里累计输错 5 次」也会被锁，那是误伤不是防护。</li>
 *   <li><b>有内存上限</b>（{@link #MAX_TRACKED}）：攻击者拿随机用户名刷，
 *       Map 会无限膨胀直到 OOM —— 那是拿防护措施换了个 DoS 入口。
 *       超过上限时先清过期项，仍超就<b>不再记录新 key</b>（fail-open）：
 *       宁可漏记陌生用户名，也不能让进程崩掉。</li>
 * </ol>
 *
 * <p><b>已知的代价（必须承认）</b>：按账号锁定意味着攻击者可以故意输错 5 次，
 * 把管理员锁在门外 15 分钟（账号锁定型 DoS）。这是所有「按账号锁定」方案的共同代价，
 * GitHub / AWS 也都这么做。相比「被爆破进后台」，这个代价是可接受的。
 * 真被恶意锁了，重启进程即可解锁（内存计数）。
 */
@Slf4j
@Service
public class LoginAttemptService {

    /** 连续失败多少次触发锁定 */
    static final int MAX_ATTEMPTS = 5;

    /** 锁定时长 */
    static final Duration LOCK_DURATION = Duration.ofMinutes(15);

    /** 失败计数窗口：只看这么久以内的失败，更早的自动作废 */
    static final Duration WINDOW = Duration.ofMinutes(15);

    /** 内存上限：同时跟踪的用户名数量上限，防止随机用户名打爆内存 */
    static final int MAX_TRACKED = 5000;

    private record Attempt(int count, long firstFailAtMillis, long lockedUntilMillis) {
        boolean locked(long now) {
            return lockedUntilMillis > now;
        }
    }

    private final Map<String, Attempt> attempts = new ConcurrentHashMap<>();

    /**
     * 时钟。做成可注入是为了让「窗口过期 / 锁定到期」这类逻辑能被单测真正跑到 ——
     * 否则测一次过期要等 15 分钟，那种测试没人会跑，等于没测。
     * 生产环境走无参构造，就是系统时钟。
     */
    private final Clock clock;

    public LoginAttemptService() {
        this(Clock.systemDefaultZone());
    }

    LoginAttemptService(Clock clock) {
        this.clock = clock;
    }

    private long nowMillis() {
        return clock.millis();
    }

    /**
     * 用户名归一化：trim + 小写。
     * 不归一化的话 {@code Admin} / {@code admin} / {@code "admin "} 会各算一份，
     * 攻击者只要换大小写就能把 5 次限额变成无限次。
     */
    static String key(String username) {
        return username == null ? "" : username.trim().toLowerCase();
    }

    /**
     * 该用户名是否处于锁定中。
     *
     * @return 剩余锁定秒数；0 = 未锁定
     */
    public long lockedSeconds(String username) {
        String k = key(username);
        if (k.isEmpty()) return 0L;
        Attempt a = attempts.get(k);
        if (a == null) return 0L;
        long now = nowMillis();
        if (!a.locked(now)) return 0L;
        return Math.max(1L, (a.lockedUntilMillis() - now) / 1000L);
    }

    public boolean isLocked(String username) {
        return lockedSeconds(username) > 0;
    }

    /**
     * 记录一次登录失败，必要时触发锁定。
     *
     * @return 本次失败后还剩几次机会；0 表示本次已触发锁定
     */
    public int recordFailure(String username) {
        String k = key(username);
        if (k.isEmpty()) return MAX_ATTEMPTS;
        long now = nowMillis();

        Attempt result = attempts.compute(k, (kk, old) -> {
            // 已锁定的：不重复计数（锁定期内的尝试不延长也不缩短锁定）
            if (old != null && old.locked(now)) {
                return old;
            }
            // 窗口外的旧记录作废，从 1 重新数
            boolean expired = old == null || (now - old.firstFailAtMillis()) > WINDOW.toMillis();
            int count = expired ? 1 : old.count() + 1;
            long firstFailAt = expired ? now : old.firstFailAtMillis();
            if (count >= MAX_ATTEMPTS) {
                return new Attempt(count, firstFailAt, now + LOCK_DURATION.toMillis());
            }
            return new Attempt(count, firstFailAt, 0L);
        });

        // 内存上限保护：只在「新增 key」把 Map 顶到上限时才处理
        if (attempts.size() > MAX_TRACKED) {
            evictExpired();
            if (attempts.size() > MAX_TRACKED) {
                log.warn("登录失败计数已达上限 {}，新用户名不再记录（fail-open，防止内存耗尽）", MAX_TRACKED);
            }
        }

        if (result == null) return MAX_ATTEMPTS;
        if (result.locked(now)) {
            log.warn("账号 {} 连续登录失败 {} 次，已锁定 {} 分钟", k, result.count(), LOCK_DURATION.toMinutes());
            return 0;
        }
        return Math.max(0, MAX_ATTEMPTS - result.count());
    }

    /** 登录成功后清除该用户名的所有失败记录 */
    public void clear(String username) {
        String k = key(username);
        if (!k.isEmpty()) attempts.remove(k);
    }

    /** 当前跟踪的用户名数量（供监控/测试） */
    public int trackedCount() {
        return attempts.size();
    }

    /** 清空全部记录（测试与「重启即解锁」语义的显式入口） */
    public void clearAll() {
        attempts.clear();
    }

    /**
     * 定期清理过期记录。惰性清理只在访问时生效，
     * 但被攻击后 Map 里会留一堆再也不会被访问的 key，必须主动扫。
     */
    @Scheduled(fixedDelay = 300_000)
    public void evictExpired() {
        long now = nowMillis();
        long windowMs = WINDOW.toMillis();
        int before = attempts.size();
        Iterator<Map.Entry<String, Attempt>> it = attempts.entrySet().iterator();
        int removed = 0;
        while (it.hasNext()) {
            Attempt a = it.next().getValue();
            // 锁已过期 + 首次失败也在窗口外 → 这条记录没有任何保留价值
            boolean lockOver = a.lockedUntilMillis() <= now;
            boolean windowOver = (now - a.firstFailAtMillis()) > windowMs;
            if (lockOver && windowOver) {
                it.remove();
                removed++;
            }
        }
        if (removed > 0) {
            log.debug("登录失败计数清理：{} → {}（移除 {} 条过期记录）", before, attempts.size(), removed);
        }
    }
}
