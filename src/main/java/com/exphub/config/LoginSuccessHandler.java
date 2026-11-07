package com.exphub.config;

import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.exphub.entity.User;
import com.exphub.mapper.UserMapper;
import com.exphub.service.LoginAttemptService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.core.Authentication;
import org.springframework.security.web.authentication.AuthenticationSuccessHandler;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.time.LocalDateTime;

/**
 * 登录成功处理。
 *
 * <p>2026-11-07 补了两件事：
 * <ol>
 *   <li><b>清除失败计数</b> —— 不清的话，{@code admin} 昨天输错 4 次、今天再错 1 次就会被锁，
 *       用户会觉得「我刚刚才输对过，怎么还锁我」。</li>
 *   <li><b>真正写 {@code users.last_login}</b> —— 这个字段以前从来没被写过（一直是 NULL），
 *       等于放弃了一条最基础的审计线索。</li>
 * </ol>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class LoginSuccessHandler implements AuthenticationSuccessHandler {

    private final UserMapper userMapper;
    private final LoginAttemptService attemptService;

    @Override
    public void onAuthenticationSuccess(HttpServletRequest request, HttpServletResponse response,
                                        Authentication authentication) throws IOException {
        String username = authentication.getName();

        User user = userMapper.selectOne(
            new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<User>()
                .eq(User::getUsername, username)
        );

        // 登录成功 = 这是本人 → 失败计数归零
        attemptService.clear(username);

        if (user != null) {
            // ⚠️ users 表带 ON UPDATE CURRENT_TIMESTAMP（项目红线，见 #748 第四节第 8 条）：
            // 只 set last_login 时，MySQL 会顺手把 updated_at 刷成当前时刻，全库「什么时候改过」
            // 就废了。必须显式写回自身 —— 显式赋值不触发 ON UPDATE，是必要的空操作。
            userMapper.update(null, new UpdateWrapper<User>()
                .eq("id", user.getId())
                .set("last_login", LocalDateTime.now())
                .setSql("updated_at = updated_at"));

            HttpSession session = request.getSession();
            session.setAttribute("user", user);
        } else {
            log.error("登录成功但库里查不到用户 {}，无法写入 last_login", username);
        }

        log.info("登录成功：user={} ip={}", username, clientIp(request));
        response.sendRedirect(request.getContextPath() + "/dashboard");
    }

    private static String clientIp(HttpServletRequest request) {
        String xff = request.getHeader("X-Forwarded-For");
        if (xff != null && !xff.isEmpty()) {
            int comma = xff.indexOf(',');
            return comma > 0 ? xff.substring(0, comma).trim() : xff.trim();
        }
        return request.getRemoteAddr();
    }
}
