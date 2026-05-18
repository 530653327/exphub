package com.exphub.interceptor;

import com.exphub.entity.AiAssistant;
import com.exphub.mapper.AiAssistantMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

/**
 * API Key 拦截器
 * 验证所有 /api/* 请求的 API Key
 */
@Component
public class ApiKeyInterceptor implements HandlerInterceptor {

    public static final ThreadLocal<AiAssistant> CURRENT_ASSISTANT = new ThreadLocal<>();

    @Autowired
    private AiAssistantMapper assistantMapper;

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {
        // 放行认证接口和静态资源
        String uri = request.getRequestURI();
        if (uri.startsWith("/api/auth/")) {
            return true;
        }

        // 已登录用户（后台页面操作）跳过 API Key 验证
        if (request.getSession().getAttribute("user") != null) {
            return true;
        }

        String apiKey = request.getHeader("X-API-Key");
        if (apiKey == null || apiKey.isEmpty()) {
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
            response.setStatus(401);
            response.setContentType("application/json;charset=UTF-8");
            response.getWriter().write("{\"code\":401,\"message\":\"API Key无效\"}");
            return false;
        }

        if (!assistant.getEnabled()) {
            response.setStatus(403);
            response.setContentType("application/json;charset=UTF-8");
            response.getWriter().write("{\"code\":403,\"message\":\"API Key已禁用\"}");
            return false;
        }

        // 将助手信息存入 ThreadLocal
        CURRENT_ASSISTANT.set(assistant);
        return true;
    }

    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response, Object handler, Exception ex) throws Exception {
        CURRENT_ASSISTANT.remove();
    }
}