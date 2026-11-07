package com.exphub.config;

import com.exphub.service.LoginAttemptService;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.authentication.LockedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.authentication.AuthenticationFailureHandler;
import org.springframework.stereotype.Component;

import java.io.IOException;

/**
 * 登录失败处理（2026-11-07 · 防爆破第四层）。
 *
 * <p>职责拆得很清楚：
 * <ul>
 *   <li>已经锁定的账号（{@link LockedException}）→ <b>不计数</b>，直接跳 {@code ?locked}。
 *       否则锁定期内继续尝试会不断延长锁定，攻击者能用这一手把管理员永久锁死。</li>
 *   <li>密码错 / 用户不存在 → 记一次失败，把「还剩几次」带回去。</li>
 * </ul>
 *
 * <p><b>为什么不区分「用户名不存在」和「密码错」</b>：Spring Security 默认
 * {@code hideUserNotFoundExceptions=true}，两者都会被包装成 {@code BadCredentialsException}，
 * 提示语统一成「用户名或密码错误」。这是对的 —— 区分开等于告诉攻击者「这个用户名存在」。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class LoginFailureHandler implements AuthenticationFailureHandler {

    private final LoginAttemptService attemptService;

    @Override
    public void onAuthenticationFailure(HttpServletRequest request, HttpServletResponse response,
                                        AuthenticationException exception) throws IOException, ServletException {
        String username = request.getParameter("username");

        if (isLockedException(exception)) {
            long sec = attemptService.lockedSeconds(username);
            // 兜底：remaining 拿不到时按整段锁定时长显示，别显示「0 分钟」
            long minutes = sec > 0 ? (sec + 59) / 60 : LOCK_MINUTES;
            log.warn("登录被拒（账号锁定中）：user={} ip={} 剩余 {} 秒", username, clientIp(request), sec);
            response.sendRedirect(request.getContextPath() + "/login?locked=" + minutes);
            return;
        }

        int remaining = attemptService.recordFailure(username);
        log.warn("登录失败：user={} ip={} 异常={} 剩余机会={}",
                username, clientIp(request), exception.getClass().getSimpleName(), remaining);

        if (remaining <= 0) {
            response.sendRedirect(request.getContextPath() + "/login?locked=" + LOCK_MINUTES);
        } else {
            response.sendRedirect(request.getContextPath() + "/login?error&remaining=" + remaining);
        }
    }

    /** 锁定时长（分钟），只用于提示文案；真值在 {@code LoginAttemptService#LOCK_DURATION} */
    private static final int LOCK_MINUTES = 15;

    /**
     * 判断是否为锁定异常。
     *
     * <p>要顺着 cause 链找：UserDetailsService 里抛的异常会被
     * {@code DaoAuthenticationProvider} 包成 {@code InternalAuthenticationServiceException}。
     * 现在虽然改走 {@code accountLocked} 让 Spring 抛正牌 {@code LockedException}，
     * 这里仍保留解包，避免以后有人在 UserService 里又改回手抛时静默失效。
     */
    private static boolean isLockedException(AuthenticationException exception) {
        Throwable t = exception;
        for (int depth = 0; t != null && depth < 5; depth++) {
            if (t instanceof LockedException) return true;
            t = t.getCause();
        }
        return false;
    }

    /** 取真实客户端 IP（nginx 反代会带 X-Forwarded-For，只取第一段） */
    private static String clientIp(HttpServletRequest request) {
        String xff = request.getHeader("X-Forwarded-For");
        if (xff != null && !xff.isEmpty()) {
            int comma = xff.indexOf(',');
            return comma > 0 ? xff.substring(0, comma).trim() : xff.trim();
        }
        return request.getRemoteAddr();
    }
}
