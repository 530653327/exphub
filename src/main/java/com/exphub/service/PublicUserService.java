package com.exphub.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.exphub.entity.AiAssistant;
import com.exphub.entity.PublicUser;
import com.exphub.mapper.AiAssistantMapper;
import com.exphub.mapper.PublicUserMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.UUID;

@Service
public class PublicUserService {

    private static final Logger log = LoggerFactory.getLogger(PublicUserService.class);

    @Autowired
    private PublicUserMapper publicUserMapper;

    @Autowired
    private AiAssistantMapper assistantMapper;

    @Autowired
    private PasswordEncoder passwordEncoder;

    /**
     * 邮箱注册：创建公开用户 + 自动生成 AI 助手（API Key）
     */
    @Transactional
    public PublicUser register(String email, String password) {
        // 检查邮箱是否已注册
        LambdaQueryWrapper<PublicUser> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(PublicUser::getEmail, email);
        if (publicUserMapper.selectOne(wrapper) != null) {
            throw new RuntimeException("该邮箱已注册");
        }

        // 生成 API Key 和 API Key Secret
        String apiKey = "exp-" + UUID.randomUUID().toString().replace("-", "");
        String apiKeySecret = UUID.randomUUID().toString().replace("-", "").substring(0, 32);

        // 创建公开用户
        PublicUser user = new PublicUser();
        user.setEmail(email);
        user.setPassword(passwordEncoder.encode(password));
        user.setApiKey(apiKey);
        user.setDisplayName(email.split("@")[0]);
        user.setEnabled(true);
        publicUserMapper.insert(user);

        // 同步创建 AI 助手记录（与 MCP 鉴权体系对接）
        AiAssistant assistant = new AiAssistant();
        assistant.setAssistantId("user-" + user.getId());
        assistant.setAssistantName(email.split("@")[0]);
        assistant.setDescription("公开用户 " + email);
        assistant.setApiKey(apiKey);
        assistant.setApiKeySecret(apiKeySecret);
        assistant.setEnabled(true);
        assistant.setCanCreate(true);
        assistant.setCanUpdate(true);
        assistant.setCanSearch(true);
        assistant.setTotalCalls(0);
        assistant.setSuccessCalls(0);
        assistant.setFailCalls(0);
        assistantMapper.insert(assistant);

        log.info("Public user registered: email={}, apiKey={}", email, apiKey);
        return user;
    }

    /**
     * 邮箱登录：验证密码，生成 portal_token
     */
    @Transactional
    public String login(String email, String password) {
        LambdaQueryWrapper<PublicUser> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(PublicUser::getEmail, email);
        PublicUser user = publicUserMapper.selectOne(wrapper);

        if (user == null) {
            throw new RuntimeException("邮箱或密码错误");
        }
        if (!user.getEnabled()) {
            throw new RuntimeException("账号已被禁用");
        }
        if (!passwordEncoder.matches(password, user.getPassword())) {
            throw new RuntimeException("邮箱或密码错误");
        }

        // 生成 portal token
        String token = UUID.randomUUID().toString().replace("-", "");
        user.setPortalToken(token);
        user.setLastLoginAt(LocalDateTime.now());
        publicUserMapper.updateById(user);

        log.info("Public user logged in: email={}", email);
        return token;
    }

    /**
     * 通过 portal_token 获取用户信息
     */
    public PublicUser getByToken(String token) {
        if (token == null || token.isEmpty()) return null;
        LambdaQueryWrapper<PublicUser> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(PublicUser::getPortalToken, token);
        return publicUserMapper.selectOne(wrapper);
    }

    /**
     * 重置 API Key
     */
    @Transactional
    public String resetApiKey(Long userId) {
        PublicUser user = publicUserMapper.selectById(userId);
        if (user == null) throw new RuntimeException("用户不存在");

        String oldApiKey = user.getApiKey();
        String newApiKey = "exp-" + UUID.randomUUID().toString().replace("-", "");
        String newApiKeySecret = UUID.randomUUID().toString().replace("-", "").substring(0, 32);

        // 更新 public_users
        user.setApiKey(newApiKey);
        publicUserMapper.updateById(user);

        // 更新对应的 ai_assistants
        LambdaQueryWrapper<AiAssistant> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(AiAssistant::getApiKey, oldApiKey);
        AiAssistant assistant = assistantMapper.selectOne(wrapper);
        if (assistant != null) {
            assistant.setApiKey(newApiKey);
            assistant.setApiKeySecret(newApiKeySecret);
            assistantMapper.updateById(assistant);
        }

        log.info("API Key reset: userId={}, oldKey={}, newKey={}", userId, oldApiKey, newApiKey);
        return newApiKey;
    }

    /**
     * 退出登录：清除 portal_token
     */
    @Transactional
    public void logout(Long userId) {
        PublicUser user = publicUserMapper.selectById(userId);
        if (user != null) {
            user.setPortalToken(null);
            publicUserMapper.updateById(user);
        }
    }

    /**
     * 获取用户的 AI 助手信息
     */
    public AiAssistant getAssistant(Long userId) {
        PublicUser user = publicUserMapper.selectById(userId);
        if (user == null) return null;
        LambdaQueryWrapper<AiAssistant> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(AiAssistant::getApiKey, user.getApiKey());
        return assistantMapper.selectOne(wrapper);
    }
}
