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

    // 登录由 Spring Security 处理，不在这里

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
