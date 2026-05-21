package com.exphub.interceptor;

import com.exphub.entity.AiAssistant;
import com.exphub.entity.User;
import com.exphub.mapper.AiAssistantMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;

/**
 * API Key 拦截器
 * 验证所有 /api/* 请求的 API Key
 */
@Component
public class ApiKeyInterceptor implements HandlerInterceptor {

    private static final Logger log = LoggerFactory.getLogger(ApiKeyInterceptor.class);

    public static final String ASSISTANT_ATTR = "CURRENT_ASSISTANT";

    /**
     * 用 InheritableThreadLocal 存储当前 AI 助手，避免 boundedElastic 线程中访问已回收的 request
     */
    private static final InheritableThreadLocal<AiAssistant> currentAssistantHolder = new InheritableThreadLocal<>();

    @Autowired
    private AiAssistantMapper assistantMapper;

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {
        String uri = request.getRequestURI();
        String contextPath = request.getContextPath();
        log.debug("ApiKeyInterceptor.preHandle: uri={}, contextPath={}, method={}", uri, contextPath, request.getMethod());
        
        // 放行认证接口和信息服务
        if (uri.startsWith("/api/auth/") || uri.equals("/api/info")) {
            return true;
        }

        // MCP 请求必须验证 API Key，不能被 Session 登录状态短路
        if (uri.startsWith("/mcp/") || uri.endsWith("/mcp/sse") || uri.contains("/mcp/message")) {
            log.info("MCP request detected: uri={}, checking API Key", uri);
            return validateApiKey(request, response);
        }

        // 检查后台管理登录状态（Session中有登录用户）
        HttpSession session = request.getSession(false);
        User loginUser = session != null ? (User) session.getAttribute("user") : null;
        
        // 已登录用户可以操作所有 /api/assistants 接口（后台管理）
        if (loginUser != null) {
            return true;
        }

        // 放行后台管理接口（无Session但可能是特定接口）
        if (request.getMethod().equals("POST") || request.getMethod().equals("PUT") || request.getMethod().equals("DELETE")) {
            if (uri.equals("/api/assistants") || uri.matches("/api/assistants/\\d+")) {
                return true;
            }
        }

        // API 请求也走统一的 API Key 验证
        return validateApiKey(request, response);
    }

    /**
     * 验证 API Key 并设置 CURRENT_ASSISTANT
     */
    private boolean validateApiKey(HttpServletRequest request, HttpServletResponse response) throws Exception {
        String apiKey = request.getHeader("X-API-Key");
        log.info("validateApiKey: apiKey header present={}", apiKey != null && !apiKey.isEmpty());
        
        if (apiKey == null || apiKey.isEmpty()) {
            log.warn("validateApiKey: no API Key header, uri={}", request.getRequestURI());
            response.setStatus(401);
            response.setContentType("application/json;charset=UTF-8");
            response.getWriter().write("{\"code\":401,\"message\":\"API Key缺失\"}");
            return false;
        }

        AiAssistant assistant = assistantMapper.selectOne(
            new com.baomidou.mybatisplus.core.conditions.query.QueryWrapper<AiAssistant>()
                .eq("api_key", apiKey)
        );

        if (assistant == null) {
            log.warn("validateApiKey: invalid API Key");
            response.setStatus(401);
            response.setContentType("application/json;charset=UTF-8");
            response.getWriter().write("{\"code\":401,\"message\":\"API Key无效\"}");
            return false;
        }

        if (!assistant.getEnabled()) {
            log.warn("validateApiKey: disabled API Key, assistant={}", assistant.getAssistantId());
            response.setStatus(403);
            response.setContentType("application/json;charset=UTF-8");
            response.getWriter().write("{\"code\":403,\"message\":\"API Key已禁用\"}");
            return false;
        }

        // 将助手信息存入 InheritableThreadLocal（关键！boundedElastic 线程需要）
        currentAssistantHolder.set(assistant);
        request.setAttribute(ASSISTANT_ATTR, assistant);
        log.info("validateApiKey: assistant set, assistantId={}, assistantName={}", 
            assistant.getAssistantId(), assistant.getAssistantName());
        return true;
    }

    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response, Object handler, Exception ex) throws Exception {
        currentAssistantHolder.remove();
    }

    /**
     * 获取当前请求的助手信息（可在 MCP boundedElastic 子线程中安全调用）
     */
    public static AiAssistant getCurrentAssistant() {
        return currentAssistantHolder.get();
    }
}