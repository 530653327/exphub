package com.exphub.controller;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.exphub.common.R;
import com.exphub.entity.PublicUser;
import com.exphub.mapper.DocMapper;
import com.exphub.mapper.PublicUserMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 移动端「用户」模块的只读查询接口（2026-11-08）。
 *
 * <h3>为什么不复用 /portal/api/**</h3>
 * 那是门户侧「登录用户看自己」的视角，鉴权来源是 portalToken，只能看自己那一条。
 * 这里是管理员视角，要看所有人。两者鉴权口径完全不同，混在同一个路径下迟早把越权写进去。
 *
 * <h3>为什么不放在 /api/assistants 旁边</h3>
 * 助手列表直接复用现成的 {@code GET /api/assistants} —— 它已经做了 apiKey 掩码，
 * 且一个接口就带齐了名称/描述/权限位/调用统计/最后调用时间。
 * <b>但绝对不要用 {@code GET /api/assistants/{id}} 做详情</b>：那个返回完整实体，
 * 含 apiKey 与 apiKeySecret 明文，移动端一旦接了就是把密钥画到屏幕上。
 *
 * <h3>两条写死的约束（以后有人想「顺手」加字段之前先看这里）</h3>
 * <ol>
 *   <li>绝不返回 password / apiKey 明文 / portalToken。apiKey 只给掩码。</li>
 *   <li>本类只读，不提供禁用、删除、重置 Key 这类写操作 —— 手机上误触一次
 *       就把某个用户的 Key 废掉，代价与收益不成比例。要做得单独设计二次确认。</li>
 * </ol>
 */
@RestController
@RequestMapping("/api/mobile")
public class MobileAdminController {

    @Autowired
    private PublicUserMapper publicUserMapper;

    @Autowired
    private DocMapper docMapper;

    /**
     * 门户注册用户列表。
     *
     * @param keyword 按邮箱或昵称模糊匹配
     */
    @GetMapping("/users")
    public R<Map<String, Object>> users(
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(required = false) String keyword) {

        int p = Math.max(1, page);
        int s = (size < 1 || size > 50) ? 20 : size;

        QueryWrapper<PublicUser> wrapper = new QueryWrapper<>();
        if (keyword != null && !keyword.trim().isEmpty()) {
            String kw = keyword.trim();
            wrapper.and(w -> w.like("email", kw).or().like("display_name", kw));
        }
        wrapper.orderByDesc("created_at");

        Page<PublicUser> result = publicUserMapper.selectPage(new Page<>(p, s), wrapper);

        // 文档数：一次 group by 拿全量，避免逐个用户 count（N+1）
        Map<String, Long> countByKey = new HashMap<>();
        try {
            for (Map<String, Object> row : docMapper.countDocsByApiKey()) {
                Object k = row.get("apiKey");
                Object c = row.get("cnt");
                if (k != null) {
                    countByKey.put(String.valueOf(k), c == null ? 0L : Long.parseLong(String.valueOf(c)));
                }
            }
        } catch (Exception ignored) {
            // 统计失败不能让整个用户列表打不开：少的只是一个数字，不是主体信息
        }

        List<Map<String, Object>> list = new ArrayList<>();
        for (PublicUser u : result.getRecords()) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", u.getId());
            m.put("email", u.getEmail());
            m.put("displayName", u.getDisplayName());
            m.put("apiKey", maskKey(u.getApiKey()));
            m.put("enabled", u.getEnabled());
            m.put("lastLoginAt", u.getLastLoginAt());
            m.put("createdAt", u.getCreatedAt());
            m.put("docCount", countByKey.getOrDefault(u.getApiKey(), 0L));
            list.add(m);
        }

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("total", result.getTotal());
        data.put("page", result.getCurrent());
        data.put("size", result.getSize());
        data.put("pages", result.getPages());
        data.put("list", list);
        data.put("summary", summary());
        return R.ok(data);
    }

    /**
     * 顶部的三个数：总用户 / 今日新增 / 7 日内登录过。
     *
     * <p>用 last_login_at 而不是 updated_at 判活跃：用户改个昵称也会把 updated_at 顶到今天，
     * 那根本不代表他还在用。
     */
    private Map<String, Object> summary() {
        Map<String, Object> s = new LinkedHashMap<>();
        try {
            s.put("total", publicUserMapper.selectCount(null));

            LocalDateTime todayStart = LocalDateTime.now().toLocalDate().atStartOfDay();
            s.put("todayNew", publicUserMapper.selectCount(
                    new QueryWrapper<PublicUser>().ge("created_at", todayStart)));

            s.put("active7d", publicUserMapper.selectCount(
                    new QueryWrapper<PublicUser>().ge("last_login_at", LocalDateTime.now().minusDays(7))));

            s.put("enabled", publicUserMapper.selectCount(
                    new QueryWrapper<PublicUser>().eq("enabled", true)));
        } catch (Exception e) {
            // 统计出问题就返回空值，列表照常能用
            s.put("total", null);
        }
        return s;
    }

    /** 只露前 8 后 4，短 Key 全打码。明文永远不出这个类。 */
    private static String maskKey(String key) {
        if (key == null || key.isEmpty()) return "—";
        if (key.length() < 12) return "****";
        return key.substring(0, 8) + "..." + key.substring(key.length() - 4);
    }
}
