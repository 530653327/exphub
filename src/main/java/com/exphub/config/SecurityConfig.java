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

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        http
            .authorizeHttpRequests(authz -> authz
                .requestMatchers("/login", "/").permitAll()
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
                .failureUrl("/login?error")
                .permitAll()
            )
            .logout(logout -> logout
                .logoutUrl("/logout")
                .logoutSuccessUrl("/login?logout")
                .permitAll()
            )
            .httpBasic(basic -> basic.disable());
        return http.build();
    }
}
