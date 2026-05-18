package com.exphub.controller;

import com.exphub.common.R;
import com.exphub.entity.User;
import com.exphub.mapper.UserMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.web.bind.annotation.*;

import javax.servlet.http.HttpSession;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;

@RestController
@RequestMapping("/api/auth")
public class AuthController {

    @Autowired
    private UserMapper userMapper;

    @PostMapping("/login")
    public R<Map<String, Object>> login(@RequestBody Map<String, String> body, HttpSession session) {
        String username = body.get("username");
        String password = body.get("password");

        User user = userMapper.selectOne(
            new com.baomidou.mybatisplus.core.conditions.query.QueryWrapper<User>()
                .eq("username", username)
        );

        if (user == null) {
            return R.fail("用户名或密码错误");
        }

        BCryptPasswordEncoder encoder = new BCryptPasswordEncoder();
        if (!encoder.matches(password, user.getPassword())) {
            return R.fail("用户名或密码错误");
        }

        // 更新最后登录时间
        user.setLastLogin(LocalDateTime.now());
        userMapper.updateById(user);

        // 存入 session
        session.setAttribute("user", user);

        Map<String, Object> userData = new HashMap<>();
        userData.put("id", user.getId());
        userData.put("username", user.getUsername());
        userData.put("displayName", user.getDisplayName());
        userData.put("role", user.getRole());
        Map<String, Object> data = new HashMap<>();
        data.put("user", userData);

        return R.ok(data);
    }

    @PostMapping("/logout")
    public R<Void> logout(HttpSession session) {
        session.invalidate();
        return R.ok();
    }

    @GetMapping("/verify")
    public R<Map<String, Object>> verify(HttpSession session) {
        User user = (User) session.getAttribute("user");
        if (user == null) {
            return R.fail(401, "未登录");
        }
        Map<String, Object> userData = new HashMap<>();
        userData.put("id", user.getId());
        userData.put("username", user.getUsername());
        userData.put("displayName", user.getDisplayName());
        userData.put("role", user.getRole());
        Map<String, Object> data = new HashMap<>();
        data.put("user", userData);
        return R.ok(data);
    }
}