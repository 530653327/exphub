package com.exphub.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.exphub.entity.AiAssistant;
import com.exphub.mapper.AiAssistantMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

@Service
public class AiAssistantService {

    @Autowired
    private AiAssistantMapper assistantMapper;

    // 创建助手（自动生成 API Key 和 assistantId）
    @Transactional
    public AiAssistant create(AiAssistant assistant) {
        // 自动生成 assistantId
        if (assistant.getAssistantId() == null || assistant.getAssistantId().isEmpty()) {
            assistant.setAssistantId("assistant-" + UUID.randomUUID().toString().replace("-", "").substring(0, 8));
        }
        assistant.setApiKey(generateApiKey());
        assistant.setApiKeySecret(UUID.randomUUID().toString().replace("-", "").substring(0, 32));
        assistant.setEnabled(true);
        assistant.setTotalCalls(0);
        assistant.setSuccessCalls(0);
        assistant.setFailCalls(0);
        assistantMapper.insert(assistant);
        return assistant;
    }

    // 切换启用/禁用状态
    @Transactional
    public AiAssistant toggleEnabled(Long id) {
        AiAssistant assistant = assistantMapper.selectById(id);
        if (assistant == null) return null;
        assistant.setEnabled(!assistant.getEnabled());
        assistantMapper.updateById(assistant);
        return assistant;
    }

    // 分页列表
    public Page<AiAssistant> list(int page, int size) {
        Page<AiAssistant> p = new Page<>(page, size);
        return assistantMapper.selectPage(p, new QueryWrapper<AiAssistant>().orderByDesc("created_at"));
    }

    // 按ID查询
    public AiAssistant getById(Long id) {
        return assistantMapper.selectById(id);
    }

    // 按 API Key 查询
    public AiAssistant getByApiKey(String apiKey) {
        return assistantMapper.selectOne(
            new QueryWrapper<AiAssistant>().eq("api_key", apiKey)
        );
    }

    // 更新助手
    @Transactional
    public AiAssistant update(Long id, AiAssistant updateData) {
        AiAssistant assistant = assistantMapper.selectById(id);
        if (assistant == null) return null;
        if (updateData.getAssistantName() != null) assistant.setAssistantName(updateData.getAssistantName());
        if (updateData.getDescription() != null) assistant.setDescription(updateData.getDescription());
        if (updateData.getEnabled() != null) assistant.setEnabled(updateData.getEnabled());
        assistantMapper.updateById(assistant);
        return assistant;
    }

    // 重置 API Key
    @Transactional
    public AiAssistant resetApiKey(Long id) {
        AiAssistant assistant = assistantMapper.selectById(id);
        if (assistant == null) return null;
        assistant.setApiKey(generateApiKey());
        assistant.setApiKeySecret(UUID.randomUUID().toString().replace("-", "").substring(0, 32));
        assistantMapper.updateById(assistant);
        return assistant;
    }

    // 删除助手
    @Transactional
    public boolean delete(Long id) {
        return assistantMapper.deleteById(id) > 0;
    }

    // 助手总数
    public long count() {
        return assistantMapper.selectCount(null);
    }

    // 生成 API Key
    private String generateApiKey() {
        return "exp-" + UUID.randomUUID().toString().replace("-", "");
    }

    // 屏蔽 API Key（用于显示）
    public String maskApiKey(String apiKey) {
        if (apiKey == null || apiKey.length() < 12) return "****";
        return apiKey.substring(0, 8) + "..." + apiKey.substring(apiKey.length() - 4);
    }
}