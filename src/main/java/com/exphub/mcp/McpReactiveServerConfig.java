package com.exphub.mcp;

import com.exphub.entity.AiAssistant;
import com.exphub.interceptor.ApiKeyInterceptor;
import com.exphub.mapper.AiAssistantMapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.modelcontextprotocol.server.transport.WebFluxSseServerTransportProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.web.embedded.netty.NettyReactiveWebServerFactory;
import org.springframework.boot.web.server.WebServer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.DependsOn;
import org.springframework.http.HttpStatus;
import org.springframework.http.server.reactive.HttpHandler;
import org.springframework.web.reactive.function.server.HandlerFilterFunction;
import org.springframework.web.reactive.function.server.RouterFunction;
import org.springframework.web.reactive.function.server.RouterFunctions;
import org.springframework.web.reactive.function.server.ServerResponse;

/**
 * MCP WebFlux 独立服务器配置
 * <p>
 * 在单独端口 (3098) 启动 Netty 服务器，仅处理 MCP SSE 请求。
 * 使用非阻塞 WebFlux 传输，从根本上避免 Tomcat 线程池耗尽问题。
 * <p>
 * 主应用（Thymeleaf 页面、REST API、登录）仍在端口 3099 通过 WebMvc 运行。
 */
@Configuration
public class McpReactiveServerConfig {

    private static final Logger log = LoggerFactory.getLogger(McpReactiveServerConfig.class);

    private final AiAssistantMapper assistantMapper;

    public McpReactiveServerConfig(AiAssistantMapper assistantMapper) {
        this.assistantMapper = assistantMapper;
    }

    /**
     * WebFlux SSE 传输层（非阻塞）
     * <p>
     * baseUrl = /exphub —— 与 Nginx 代理前缀一致，确保 MCP 客户端
     * 收到的 SSE 事件中 Endpoint 字段包含正确的消息端点公共路径。
     */
    @Bean
    public WebFluxSseServerTransportProvider webFluxSseServerTransportProvider(
            ObjectMapper objectMapper) {
        return new WebFluxSseServerTransportProvider(
                objectMapper,
                "/exphub",              // baseUrl — Nginx 代理前缀
                "/mcp/message",         // sseMessageEndpoint — 客户端 POST 消息到此路径
                "/mcp/sse"              // sseEndpoint — 客户端 SSE 连接到此路径
        );
    }

    /**
     * MCP 专用 Netty 服务器工厂（端口 3098）
     */
    @Bean
    public NettyReactiveWebServerFactory mcpReactiveWebServerFactory() {
        return new NettyReactiveWebServerFactory(3098);
    }

    /**
     * 启动独立的 Netty 服务器，包裹 MCP SSE 路由并附加 API Key 鉴权。
     * <p>
     * 通过 @DependsOn("mcpSyncServer") 确保自动配置的 McpSyncServer
     * 已先完成 transport 的初始化（工具注册等），然后包装其路由函数。
     */
    @Bean
    @DependsOn("mcpSyncServer")
    public WebServer mcpWebServer(
            WebFluxSseServerTransportProvider transport,
            NettyReactiveWebServerFactory factory,
            ObjectMapper objectMapper) {

        @SuppressWarnings("unchecked")
        RouterFunction<ServerResponse> securedRouter = ((RouterFunction<ServerResponse>) transport.getRouterFunction())
                .filter(authFilter(objectMapper));

        HttpHandler httpHandler = RouterFunctions.toHttpHandler(securedRouter);
        WebServer webServer = factory.getWebServer(httpHandler);
        webServer.start();

        log.info("MCP WebFlux server started on port 3098 (non-blocking SSE)");
        return webServer;
    }

    /**
     * MCP API Key 鉴权过滤器
     * <p>
     * 从请求头读取 authorization-key，验证后设置 ThreadLocal，
     * 确保 ExpHubTools.getCaller() 能正常获取助手信息。
     */
    private HandlerFilterFunction<ServerResponse, ServerResponse> authFilter(ObjectMapper objectMapper) {
        return (request, handler) -> {
            String apiKey = request.headers().firstHeader("authorization-key");

            if (apiKey == null || apiKey.isEmpty()) {
                log.warn("MCP WebFlux: missing API Key header");
                return ServerResponse.status(HttpStatus.UNAUTHORIZED)
                        .bodyValue("{\"code\":401,\"message\":\"API Key缺失\"}");
            }

            try {
                AiAssistant assistant = assistantMapper.selectOne(
                        new com.baomidou.mybatisplus.core.conditions.query.QueryWrapper<AiAssistant>()
                                .eq("api_key", apiKey)
                );

                if (assistant == null) {
                    log.warn("MCP WebFlux: invalid API Key");
                    return ServerResponse.status(HttpStatus.UNAUTHORIZED)
                            .bodyValue("{\"code\":401,\"message\":\"API Key无效\"}");
                }

                if (!assistant.getEnabled()) {
                    log.warn("MCP WebFlux: disabled API Key, assistant={}", assistant.getAssistantId());
                    return ServerResponse.status(HttpStatus.FORBIDDEN)
                            .bodyValue("{\"code\":403,\"message\":\"API Key已禁用\"}");
                }

                ApiKeyInterceptor.setCurrentAssistant(assistant);
                log.debug("MCP WebFlux: authorized, assistantId={}, path={}, thread={}",
                        assistant.getAssistantId(), request.path(), Thread.currentThread().getName());

                // 【关键修复】SSE 模式下，工具不在「处理 POST /mcp/message 的线程」上执行，
                // 而是在「SSE 长连接的流线程」上执行（POST 只投递消息并立即返回 202）。
                // 若在 POST 响应完成时就 removeCurrentAssistant()，当 POST 线程恰好与
                // SSE 流线程相同（Netty epoll 线程池复用，随机）时，会把 SSE 流线程上的
                // 身份一并清掉，导致工具执行时 getCurrentAssistant() 为 null →
                // 报「无法识别调用者身份」。此即该问题的偶发根因。
                // 因此：身份只能在 SSE 长连接真正关闭时清理。
                boolean isSseEndpoint = request.path().endsWith("/sse");
                if (isSseEndpoint) {
                    return handler.handle(request)
                            .doFinally(signalType -> ApiKeyInterceptor.removeCurrentAssistant());
                }
                // POST /mcp/message：只设置、不清理，保证 SSE 流线程上的身份存活
                return handler.handle(request);

            } catch (Exception e) {
                log.error("MCP WebFlux: auth filter error", e);
                return ServerResponse.status(HttpStatus.INTERNAL_SERVER_ERROR)
                        .bodyValue("{\"code\":500,\"message\":\"认证服务异常\"}");
            }
        };
    }
}
