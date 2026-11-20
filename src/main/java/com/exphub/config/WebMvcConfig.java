package com.exphub.config;

import com.exphub.interceptor.ApiKeyInterceptor;
import com.exphub.interceptor.DispatchReadTokenInterceptor;
import com.exphub.interceptor.PortalAuthInterceptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
public class WebMvcConfig implements WebMvcConfigurer {

    @Autowired
    private ApiKeyInterceptor apiKeyInterceptor;

    @Autowired
    private PortalAuthInterceptor portalAuthInterceptor;

    @Autowired
    private DispatchReadTokenInterceptor dispatchReadTokenInterceptor;

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(apiKeyInterceptor)
                .addPathPatterns("/api/**", "/mcp/**")
                .excludePathPatterns("/api/auth/**", "/api/templates/**", "/api/info", "/api/share/**",
                        // W-20261120-000025：派单只读 JSON 接口用专用只读 token，不复用 MCP Key。
                        // 不排除的话会被这里的「无 Session 登录一律 403」规则挡住忆阁后端调用。
                        "/api/dispatch/**");

        // 派单只读 JSON 接口：专用只读 token 鉴权（与 MCP Key 隔离）
        registry.addInterceptor(dispatchReadTokenInterceptor)
                .addPathPatterns("/api/dispatch/**");

        // 门户 API 鉴权
        registry.addInterceptor(portalAuthInterceptor)
                .addPathPatterns("/portal/api/**");
    }

    @Override
    public void addCorsMappings(CorsRegistry registry) {
        // 门户前端跨域支持
        registry.addMapping("/portal/api/**")
                .allowedOriginPatterns("*")
                .allowedMethods("GET", "POST", "PUT", "DELETE", "OPTIONS")
                .allowedHeaders("*")
                .allowCredentials(true);
    }
}