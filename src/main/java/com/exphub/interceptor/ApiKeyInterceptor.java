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

    /**
     * 供 WebFlux MCP 服务器设置当前助手信息（非阻塞模式不使用 HandlerInterceptor）
     */
    public static void setCurrentAssistant(AiAssistant assistant) {
        currentAssistantHolder.set(assistant);
    }

    /**
     * 移除当前助手信息（WebFlux 过滤器 finally 中调用）
     */
    public static void removeCurrentAssistant() {
        currentAssistantHolder.remove();
    }

    @Autowired
    private AiAssistantMapper assistantMapper;

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {
        String uri = request.getRequestURI();
        String contextPath = request.getContextPath();
        log.debug("ApiKeyInterceptor.preHandle: uri={}, contextPath={}, method={}", uri, contextPath, request.getMethod());

        // getRequestURI() 含 context-path（本机是 /exphub），而 WebMvc 的 addPathPatterns
        // 匹配的是去掉 context-path 之后的路径。MCP 判断必须用后者：否则新的无状态端点
        // /exphub/mcp/http 会被当成普通后台请求，落到末尾的 403「API调用已关闭」。
        String path = (contextPath != null && !contextPath.isEmpty() && uri.startsWith(contextPath))
                ? uri.substring(contextPath.length()) : uri;

        // 放行认证接口和信息服务
        if (uri.startsWith("/api/auth/") || uri.equals("/api/info")) {
            return true;
        }

        // MCP 请求必须验证 API Key，不能被 Session 登录状态短路
        if (path.startsWith("/mcp/") || path.equals("/mcp")
                || uri.endsWith("/mcp/sse") || uri.contains("/mcp/message")) {
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

        // /api/** 路径：仅允许 Session 登录用户（后台管理），拒绝外部 AI 助手的 API Key 调用
        // AI 助手必须通过 MCP 协议（/mcp/**）调用，不应直接使用 REST API
        log.warn("preHandle: /api/** request rejected - no session login, uri={}, method={}", uri, request.getMethod());
        response.setStatus(403);
        response.setContentType("application/json;charset=UTF-8");
        response.getWriter().write("{\"code\":403,\"message\":\"API调用已关闭，请通过MCP协议连接ExpHub\"}");
        return false;
    }

    /**
     * 验证 API Key 并设置 CURRENT_ASSISTANT
     */
    private boolean validateApiKey(HttpServletRequest request, HttpServletResponse response) throws Exception {
        String apiKey = request.getHeader("authorization-key");
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

        // 协作信箱：角色与配置来源来自客户端配置里写死的请求头。
        // 注意这不是鉴权 —— 网关只认上面这把共享 Key，角色谁都能声称（#1324 C118）。
        // 放这里读是为了拿到「每次请求的真实 header」；工具层只从 ThreadLocal 取，
        // 不接受模型作为参数传入。
        com.exphub.mcp.MailIdentity.set(
                request.getHeader(com.exphub.mcp.MailIdentity.HEADER_ROLE),
                request.getHeader(com.exphub.mcp.MailIdentity.HEADER_CONFIG_SOURCE));

        // 2026-11-09 #679：不打印原始角色头（兼容期可能是拼音），出口/日志一律汉字；
        // 规范正名由工具层归一并在回执里自证，这里保留 assistantId + configSource 定位配置。
        log.info("validateApiKey: assistant set, assistantId={}, assistantName={}, configSource={}",
            assistant.getAssistantId(), assistant.getAssistantName(),
            com.exphub.mcp.MailIdentity.configSource());
        return true;
    }

    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response, Object handler, Exception ex) throws Exception {
        currentAssistantHolder.remove();
        com.exphub.mcp.MailIdentity.clear();
    }

    /**
     * 获取当前请求的助手信息（可在 MCP boundedElastic 子线程中安全调用）
     */
    public static AiAssistant getCurrentAssistant() {
        return currentAssistantHolder.get();
    }
}