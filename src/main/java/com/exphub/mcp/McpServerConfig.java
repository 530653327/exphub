package com.exphub.mcp;

import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.ai.tool.method.MethodToolCallbackProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * ExpHub MCP Server 配置
 * 通过 SSE 模式暴露工具给所有 AI 助手
 */
@Configuration
public class McpServerConfig {

    /**
     * 注册 MCP 工具
     */
    @Bean
    public ToolCallbackProvider toolCallbackProvider(ExpHubTools expHubTools) {
        return MethodToolCallbackProvider.builder()
                .toolObjects(expHubTools)
                .build();
    }
}
