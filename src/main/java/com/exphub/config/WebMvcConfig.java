package com.exphub.config;

import com.exphub.interceptor.ApiKeyInterceptor;
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

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(apiKeyInterceptor)
                .addPathPatterns("/api/**", "/mcp/**")
                .excludePathPatterns("/api/auth/**", "/api/templates/**", "/api/info");

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