package com.exphub.interceptor;

import com.exphub.entity.PublicUser;
import com.exphub.service.PublicUserService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * 门户 API 鉴权拦截器
 * 验证 /portal/api/** 请求的 Bearer Token
 */
@Component
public class PortalAuthInterceptor implements HandlerInterceptor {

    private static final Logger log = LoggerFactory.getLogger(PortalAuthInterceptor.class);
    public static final String PORTAL_USER_ATTR = "PORTAL_USER";

    private static final InheritableThreadLocal<PublicUser> currentPortalUser = new InheritableThreadLocal<>();

    @Autowired
    private PublicUserService publicUserService;

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {
        String uri = request.getRequestURI();

        // 注册和登录接口放行（兼容有无 context path）
        if (uri.endsWith("/portal/api/register") || uri.endsWith("/portal/api/login")) {
            return true;
        }

        // 验证 Authorization: Bearer <token>
        String authHeader = request.getHeader("Authorization");
        if (authHeader == null || !authHeader.startsWith("Bearer ")) {
            response.setStatus(401);
            response.setContentType("application/json;charset=UTF-8");
            response.getWriter().write("{\"code\":401,\"message\":\"未登录\"}");
            return false;
        }

        String token = authHeader.substring(7);
        PublicUser user = publicUserService.getByToken(token);
        if (user == null) {
            response.setStatus(401);
            response.setContentType("application/json;charset=UTF-8");
            response.getWriter().write("{\"code\":401,\"message\":\"登录已过期，请重新登录\"}");
            return false;
        }

        currentPortalUser.set(user);
        request.setAttribute(PORTAL_USER_ATTR, user);
        return true;
    }

    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response, Object handler, Exception ex) throws Exception {
        currentPortalUser.remove();
    }

    public static PublicUser getCurrentPortalUser() {
        return currentPortalUser.get();
    }
}
