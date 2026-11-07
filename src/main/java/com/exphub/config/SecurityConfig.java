package com.exphub.config;

import com.exphub.service.UserService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;

@Configuration
@EnableWebSecurity
public class SecurityConfig {

    @Autowired
    private UserService userService;

    @Autowired
    private LoginSuccessHandler loginSuccessHandler;

    @Autowired
    private LoginFailureHandler loginFailureHandler;

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        http
            .authorizeHttpRequests(authz -> authz
                .requestMatchers("/login", "/").permitAll()
                // 移动壳登录页 + PWA 三件套：manifest/sw/offline 必须免登录，
                // 否则浏览器拉 manifest 被 302 到登录页，安装条件不成立（PWA 直接装不上）
                .requestMatchers("/m/login", "/manifest.json", "/sw.js", "/offline.html").permitAll()
                .requestMatchers("/css/**", "/js/**", "/images/**").permitAll()
                // MCP 用 authorization-key 请求头鉴权（ApiKeyInterceptor），不依赖 session
                .requestMatchers("/mcp/**").permitAll()
                // 公开 API：登录注册、版本信息、分享、模板列表（与 WebMvcConfig 的
                // ApiKeyInterceptor excludePathPatterns 保持一致）
                .requestMatchers("/api/auth/**", "/api/info", "/api/share/**", "/api/templates/**").permitAll()
                // 其余后台 API 必须 Session 登录
                // ⚠️ 历史上这里写的是 /api/** permitAll，与 ApiKeyInterceptor「要求 Session」的口径不一致，
                // 靠拦截器兜住了；现在两层口径统一为「需登录」，避免将来拦截器放行时出现裸奔
                .requestMatchers("/api/**").authenticated()
                .requestMatchers("/share/**").permitAll()
                .requestMatchers("/portal/**").permitAll()
                .requestMatchers("/health").permitAll()
                .requestMatchers("/error").permitAll()
                .anyRequest().authenticated()
            )
            // CSRF 保护（2026-10-24 启用）
            // 豁免说明：
            //  - /mcp/**：MCP 客户端用 authorization-key 请求头鉴权，不带 cookie，无法被 CSRF 利用
            //  - /portal/api/**：门户用 portal token 鉴权，同样不依赖 session cookie
            // 其余后台表单与 /api/** 写操作一律校验 token；页面通过 static/js/csrf.js 全局注入
            .csrf(csrf -> csrf
                .ignoringRequestMatchers("/mcp/**", "/mcp", "/portal/api/**")
            )
            .formLogin(form -> form
                .loginPage("/login")
                .loginProcessingUrl("/login")
                .successHandler(loginSuccessHandler)
                // 2026-11-07：改成自定义 failureHandler 以接入失败计数与锁定。
                // 原来的 failureUrl("/login?error") 只给一句「用户名或密码错误」，
                // 既不计数也不会锁，等于对爆破完全无感。
                .failureHandler(loginFailureHandler)
                .permitAll()
            )
            .logout(logout -> logout
                .logoutUrl("/logout")
                .logoutSuccessUrl("/login?logout")
                .permitAll()
            )
            // 2026-11-08 移动壳：未登录访问 /m/** 要落到移动登录页，而不是 PC 登录页。
            // 必须写在 formLogin 之后 —— FormLoginConfigurer 会把 entryPoint 改成 loginPage，
            // 写在前面的 exceptionsHandling 会被它覆盖掉（这类「配置顺序」问题不报错，只不生效）。
            .exceptionHandling(ex -> {
                java.util.LinkedHashMap<org.springframework.security.web.util.matcher.RequestMatcher,
                        org.springframework.security.web.AuthenticationEntryPoint> entries =
                        new java.util.LinkedHashMap<>();
                entries.put(request -> {
                    String p = request.getServletPath();
                    return p != null && (p.equals("/m") || p.startsWith("/m/"));
                }, new org.springframework.security.web.authentication.LoginUrlAuthenticationEntryPoint("/m/login"));
                org.springframework.security.web.authentication.DelegatingAuthenticationEntryPoint delegating =
                        new org.springframework.security.web.authentication.DelegatingAuthenticationEntryPoint(entries);
                delegating.setDefaultEntryPoint(
                        new org.springframework.security.web.authentication.LoginUrlAuthenticationEntryPoint("/login"));
                ex.authenticationEntryPoint(delegating);
            })
            .httpBasic(basic -> basic.disable());
        return http.build();
    }
}
