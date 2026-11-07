package com.exphub.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.exphub.entity.User;
import com.exphub.mapper.UserMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;

import java.util.Collections;

@Service
@RequiredArgsConstructor
public class UserService implements UserDetailsService {

    private final UserMapper userMapper;
    private final LoginAttemptService attemptService;

    /**
     * 加载用户并做锁定检查（2026-11-07 防爆破第四层）。
     *
     * <p><b>为什么用 {@code accountLocked} 标志而不是在这里直接 throw LockedException</b>
     * （这条踩过一次，别再改回去）：{@code DaoAuthenticationProvider.retrieveUser} 里，
     * 本方法抛出的任何非 {@code UsernameNotFoundException} 都会被包成
     * {@code InternalAuthenticationServiceException}，到了 failureHandler 那里
     * {@code instanceof LockedException} 恒为 false，自定义处理器就再也认不出「锁定」。
     * 走 {@code accountLocked(true)}，由 Spring 的
     * {@code AccountStatusUserDetailsChecker} 在 preAuthenticationChecks 阶段
     * 抛<b>正牌</b> {@code LockedException}，才能被处理器正确识别。
     *
     * <p><b>顺序很关键</b>：先查库确认用户存在，再判锁定。反过来的话，
     * 对一个不存在的用户名也会返回「已锁定」，等于多给攻击者一个信号。
     */
    @Override
    public UserDetails loadUserByUsername(String username) throws UsernameNotFoundException {
        LambdaQueryWrapper<User> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(User::getUsername, username);
        User user = userMapper.selectOne(wrapper);

        if (user == null) {
            throw new UsernameNotFoundException("User not found: " + username);
        }

        boolean locked = attemptService.isLocked(username);

        return org.springframework.security.core.userdetails.User.builder()
            .username(user.getUsername())
            .password(user.getPassword())
            .authorities(Collections.singletonList(new SimpleGrantedAuthority("ROLE_" + user.getRole())))
            .accountLocked(locked)
            .build();
    }
}