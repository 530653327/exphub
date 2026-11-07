package com.exphub.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 登录失败锁定（2026-11-07 防爆破第四层）。
 *
 * <p>这些测试都<b>真的执行代码</b>，不是读源码文本断言 —— 见 #748 第八节第 1 条：
 * 「620 条测试里真正执行线上代码的没几条」。
 *
 * <p>时钟被做成可注入就是为了这里：不用真等 15 分钟就能验「锁定到期」和「窗口过期」。
 */
class LoginAttemptServiceTest {

    /** 可推进的测试时钟 */
    static class MutableClock extends Clock {
        private long millis;
        MutableClock(long start) { this.millis = start; }
        void advance(Duration d) { this.millis += d.toMillis(); }
        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public Instant instant() { return Instant.ofEpochMilli(millis); }
        @Override public long millis() { return millis; }
    }

    private LoginAttemptService newService(MutableClock clock) {
        return new LoginAttemptService(clock);
    }

    @Test
    @DisplayName("5 次内不锁，并按次数递减剩余机会")
    void notLockedBeforeFiveAttempts() {
        MutableClock clock = new MutableClock(1_700_000_000_000L);
        LoginAttemptService svc = newService(clock);

        assertEquals(4, svc.recordFailure("admin"));
        assertEquals(3, svc.recordFailure("admin"));
        assertEquals(2, svc.recordFailure("admin"));
        assertEquals(1, svc.recordFailure("admin"));
        assertFalse(svc.isLocked("admin"), "4 次失败还不该锁");
    }

    @Test
    @DisplayName("第 5 次失败立刻锁定")
    void lockedOnFifthAttempt() {
        MutableClock clock = new MutableClock(1_700_000_000_000L);
        LoginAttemptService svc = newService(clock);

        for (int i = 0; i < 4; i++) svc.recordFailure("admin");
        assertEquals(0, svc.recordFailure("admin"), "第 5 次返回 0 = 已锁定");
        assertTrue(svc.isLocked("admin"));
        assertTrue(svc.lockedSeconds("admin") > 800 && svc.lockedSeconds("admin") <= 900,
                "剩余秒数应接近 15 分钟，实际 " + svc.lockedSeconds("admin"));
    }

    @Test
    @DisplayName("锁定期内继续失败不会延长锁定")
    void attemptsDuringLockDoNotExtend() {
        MutableClock clock = new MutableClock(1_700_000_000_000L);
        LoginAttemptService svc = newService(clock);

        for (int i = 0; i < 5; i++) svc.recordFailure("admin");
        long before = svc.lockedSeconds("admin");

        clock.advance(Duration.ofMinutes(1));
        for (int i = 0; i < 10; i++) svc.recordFailure("admin");

        long after = svc.lockedSeconds("admin");
        assertTrue(after < before, "锁定不会被延长：1 分钟后剩余时间应更少（" + before + " → " + after + "）");
        assertTrue(after > 0, "但仍在锁定中");
    }

    @Test
    @DisplayName("15 分钟后自动解锁")
    void unlockAfterLockDuration() {
        MutableClock clock = new MutableClock(1_700_000_000_000L);
        LoginAttemptService svc = newService(clock);

        for (int i = 0; i < 5; i++) svc.recordFailure("admin");
        assertTrue(svc.isLocked("admin"));

        clock.advance(Duration.ofMinutes(15).plusSeconds(1));
        assertFalse(svc.isLocked("admin"), "过了 15 分钟应自动解锁");
        assertEquals(0, svc.lockedSeconds("admin"));
    }

    @Test
    @DisplayName("登录成功清除计数")
    void clearOnSuccess() {
        MutableClock clock = new MutableClock(1_700_000_000_000L);
        LoginAttemptService svc = newService(clock);

        svc.recordFailure("admin");
        svc.recordFailure("admin");
        svc.recordFailure("admin");
        svc.recordFailure("admin");
        svc.clear("admin");

        assertEquals(4, svc.recordFailure("admin"), "清除后应重新从 1 计数（剩 4 次）");
    }

    @Test
    @DisplayName("大小写与首尾空格视为同一账号")
    void usernameIsNormalized() {
        MutableClock clock = new MutableClock(1_700_000_000_000L);
        LoginAttemptService svc = newService(clock);

        svc.recordFailure("Admin");
        svc.recordFailure(" admin ");
        svc.recordFailure("ADMIN");
        svc.recordFailure("aDmIn");
        assertEquals(0, svc.recordFailure("admin"),
                "换大小写绕不过限额 —— 归一化失败的话这里会是 1");
        assertTrue(svc.isLocked("Admin"));
    }

    @Test
    @DisplayName("超出窗口的旧失败不计入（不会三年后突然被锁）")
    void oldFailuresOutsideWindowDoNotCount() {
        MutableClock clock = new MutableClock(1_700_000_000_000L);
        LoginAttemptService svc = newService(clock);

        for (int i = 0; i < 4; i++) svc.recordFailure("admin");
        clock.advance(Duration.ofMinutes(16));   // 窗口(15min)已过

        assertEquals(4, svc.recordFailure("admin"),
                "窗口外的 4 次应作废，这次算第 1 次失败（剩 4 次）");
        assertFalse(svc.isLocked("admin"));
    }

    @Test
    @DisplayName("过期记录会被清理，不会无限堆积")
    void evictExpiredRemovesStaleEntries() {
        MutableClock clock = new MutableClock(1_700_000_000_000L);
        LoginAttemptService svc = newService(clock);

        for (int i = 0; i < 100; i++) svc.recordFailure("user" + i);
        assertEquals(100, svc.trackedCount());

        clock.advance(Duration.ofMinutes(31));
        svc.evictExpired();

        assertEquals(0, svc.trackedCount(), "全部过期后应清空");
    }

    @Test
    @DisplayName("空用户名不记也不锁（防止脏参数把 Map 塞满）")
    void blankUsernameIgnored() {
        MutableClock clock = new MutableClock(1_700_000_000_000L);
        LoginAttemptService svc = newService(clock);

        svc.recordFailure(null);
        svc.recordFailure("");
        svc.recordFailure("   ");

        assertEquals(0, svc.trackedCount());
        assertFalse(svc.isLocked(""));
    }
}
