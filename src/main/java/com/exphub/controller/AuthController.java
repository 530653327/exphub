package com.exphub.controller;

import com.exphub.common.R;
import com.exphub.entity.User;
import com.exphub.mapper.UserMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.web.bind.annotation.*;

import jakarta.servlet.http.HttpSession;
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

    @PostMapping("/change-password")
    public R<Void> changePassword(@RequestBody Map<String, String> params, HttpSession session) {
        User sessionUser = (User) session.getAttribute("user");
        if (sessionUser == null) {
            return R.fail(401, "请先登录");
        }

        String oldPassword = params.get("oldPassword");
        String newPassword = params.get("newPassword");
        String confirmPassword = params.get("confirmPassword");

        if (oldPassword == null || oldPassword.isEmpty()) {
            return R.fail(400, "请输入原密码");
        }
        if (newPassword == null || newPassword.isEmpty()) {
            return R.fail(400, "请输入新密码");
        }
        if (newPassword.length() < 6) {
            return R.fail(400, "新密码长度不能少于6位");
        }
        if (!newPassword.equals(confirmPassword)) {
            return R.fail(400, "两次输入的新密码不一致");
        }

        // 验证原密码
        BCryptPasswordEncoder encoder = new BCryptPasswordEncoder();
        User dbUser = userMapper.selectById(sessionUser.getId());
        if (dbUser == null || !encoder.matches(oldPassword, dbUser.getPassword())) {
            return R.fail(400, "原密码错误");
        }

        // 更新密码
        dbUser.setPassword(encoder.encode(newPassword));
        userMapper.updateById(dbUser);

        // 清除 session 要求重新登录
        session.invalidate();

        return R.ok(null, "密码修改成功，请重新登录");
    }
}
