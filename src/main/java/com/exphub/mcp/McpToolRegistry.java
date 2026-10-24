package com.exphub.mcp;

import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.stereotype.Component;

import java.lang.reflect.Method;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * MCP 工具注册表。
 *
 * 启动时扫描 {@link ExpHubTools} 上所有 {@code @Tool} 方法，得到「系统里到底有哪些工具」。
 *
 * 为什么需要它：工具使用统计只看 call_logs 时，无法区分「这个工具没被调用过」
 * 和「这个工具根本不存在/已改名」。有了注册表，看板才能把 0 调用的工具也列出来
 * （这正是判断能否精简工具的依据），且新增工具后自动出现，不需要改看板代码。
 */
@Component
public class McpToolRegistry {

    private static final Logger log = LoggerFactory.getLogger(McpToolRegistry.class);

    /** 工具名 -> 描述（LinkedHashMap 保持声明顺序，看板展示稳定） */
    private final Map<String, String> tools = new LinkedHashMap<>();

    @PostConstruct
    public void scan() {
        // 注意：这里用 ExpHubTools.class（原始类），不要用注入的 bean——
        // bean 可能被 AOP 代理，Class 上取不到注解
        for (Method method : ExpHubTools.class.getDeclaredMethods()) {
            Tool tool = method.getAnnotation(Tool.class);
            if (tool != null && tool.name() != null && !tool.name().isEmpty()) {
                tools.put(tool.name(), tool.description() == null ? "" : tool.description());
            }
        }
        log.info("McpToolRegistry: 已注册 {} 个 MCP 工具 {}", tools.size(), tools.keySet());
    }

    public Map<String, String> getTools() {
        return Collections.unmodifiableMap(tools);
    }

    public int size() {
        return tools.size();
    }
}
