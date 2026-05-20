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
            .securityMatcher("/**")  // 只匹配所有路径
            .userDetailsService(userService)
            .authorizeHttpRequests(authorize -> authorize
                // MCP 和 API 完全放行
                .requestMatchers("/mcp/**").permitAll()
                .requestMatchers("/api/**").permitAll()
                // 静态资源放行
                .requestMatchers("/css/**", "/js/**", "/images/**").permitAll()
                // 登录页面放行
                .requestMatchers("/login", "/").permitAll()
                // 后台管理页面需要认证
                .requestMatchers("/docs/**", "/assistants/**", "/stats/**", "/templates/**").authenticated()
                .anyRequest().authenticated()
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
            .csrf(csrf -> csrf.disable())
            // 禁用 HTTP Basic 认证，避免自动弹出登录框
            .httpBasic(httpBasic -> httpBasic.disable());

        return http.build();
    }
}
