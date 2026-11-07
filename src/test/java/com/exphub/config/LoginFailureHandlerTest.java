package com.exphub.config;

import com.exphub.service.LoginAttemptService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.InternalAuthenticationServiceException;
import org.springframework.security.authentication.LockedException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 登录失败处理器行为测试（2026-11-07）。
 *
 * <p>为什么必须测这个：失败处理器是「5 次锁 15 分钟」唯一面向用户的一环。
 * 逻辑写错（比如重定向参数拼错）时，后端其实锁了但页面什么都不说，
 * 用户只会觉得「密码明明输了怎么没反应」——这种问题生产上极难发现。
 */
class LoginFailureHandlerTest {

    private LoginAttemptService attemptService;
    private LoginFailureHandler handler;

    @BeforeEach
    void setUp() {
        attemptService = new LoginAttemptService();
        handler = new LoginFailureHandler(attemptService);
    }

    private MockHttpServletResponse fail(String username, org.springframework.security.core.AuthenticationException ex) throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/exphub/login");
        request.setContextPath("/exphub");
        request.setParameter("username", username);
        request.addHeader("X-Forwarded-For", "203.0.113.7, 10.0.0.1");
        MockHttpServletResponse response = new MockHttpServletResponse();
        handler.onAuthenticationFailure(request, response, ex);
        return response;
    }

    @Test
    @DisplayName("第 1~4 次失败：跳 error 并带上剩余次数")
    void redirectWithRemaining() throws Exception {
        for (int i = 1; i <= 4; i++) {
            MockHttpServletResponse r = fail("admin", new BadCredentialsException("bad"));
            String loc = r.getRedirectedUrl();
            assertEquals("/exphub/login?error&remaining=" + (5 - i), loc, "第 " + i + " 次失败");
        }
    }

    @Test
    @DisplayName("第 5 次失败：跳 locked=15")
    void fifthFailureLocks() throws Exception {
        for (int i = 1; i <= 4; i++) {
            fail("admin", new BadCredentialsException("bad"));
        }
        MockHttpServletResponse r = fail("admin", new BadCredentialsException("bad"));
        assertEquals("/exphub/login?locked=15", r.getRedirectedUrl());
        assertTrue(attemptService.isLocked("admin"), "第 5 次失败后必须处于锁定状态");
    }

    @Test
    @DisplayName("锁定期内再尝试：LockedException 不计新失败、不延长锁定，跳 locked")
    void lockedExceptionDoesNotExtend() throws Exception {
        for (int i = 1; i <= 5; i++) {
            fail("admin", new BadCredentialsException("bad"));
        }
        long before = attemptService.lockedSeconds("admin");

        MockHttpServletResponse r = fail("admin", new LockedException("locked"));
        assertEquals("/exphub/login?locked=15", r.getRedirectedUrl());
        long after = attemptService.lockedSeconds("admin");

        // 允许 1 秒误差，但绝不能被延长
        assertTrue(after <= before, "锁定期内尝试不得延长锁定：" + before + " -> " + after);
    }

    @Test
    @DisplayName("被包成 InternalAuthenticationServiceException 的锁定也要认出来")
    void unwrapsWrappedLockedException() throws Exception {
        MockHttpServletResponse r = fail("admin",
            new InternalAuthenticationServiceException("boom", new LockedException("locked")));
        assertTrue(r.getRedirectedUrl().startsWith("/exphub/login?locked="),
            "应当识别出包装在 cause 里的 LockedException，实际=" + r.getRedirectedUrl());
    }

    @Test
    @DisplayName("大小写/空格变体共用同一份计数（不能靠换写法绕过限额）")
    void usernameCaseInsensitive() throws Exception {
        fail("admin", new BadCredentialsException("bad"));
        fail("ADMIN", new BadCredentialsException("bad"));
        fail("Admin", new BadCredentialsException("bad"));
        fail(" admin ", new BadCredentialsException("bad"));
        MockHttpServletResponse r = fail("aDmIn", new BadCredentialsException("bad"));
        assertEquals("/exphub/login?locked=15", r.getRedirectedUrl());
    }
}