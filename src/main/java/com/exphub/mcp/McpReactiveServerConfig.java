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
 * MCP WebFlux 独立服务器配置（端口 3098）
 *
 * <p><b>⚠️ 为什么必须用 WebFlux 而不是 WebMvc(Servlet)：</b>
 * Servlet 版下每个 SSE 长连接会占用一个 Tomcat 工作线程且长期不释放，
 * 客户端重连 / 连接堆积会耗尽线程池（默认 200 个），导致整个应用假死
 * （表现为：进程在、端口在监听，但所有请求超时或被 RST）。
 * <b>2026-09-27 就发生过该故障</b>：`http-nio-3099-exec` 200/200 全部 WAITING、
 * 栈里 400 个 SSE 帧。WebFlux 的非阻塞模型不存在此问题，故保持本方案。
 *
 * <p><b>关于身份（API Key）传递：</b>本类只负责把 assistant 写入 ThreadLocal；
 * 由于工具实际在 Reactor 线程执行，跨线程传播由
 * {@code config/McpContextPropagationConfig}（Reactor onScheduleHook）负责。
 * 两者职责分离，不要在这里再引入线程池相关的 hack。
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
     * 收到的 SSE 事件中 endpoint 字段包含正确的公共路径。
     */
    @Bean
    public WebFluxSseServerTransportProvider webFluxSseServerTransportProvider(
            ObjectMapper objectMapper) {
        return new WebFluxSseServerTransportProvider(
                objectMapper,
                "/exphub",              // baseUrl — Nginx 代理前缀
                "/mcp/message",         // sseMessageEndpoint
                "/mcp/sse"              // sseEndpoint
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

                // 【重要】只在 SSE 长连接真正关闭时清理身份。
                // POST /mcp/message 只投递消息并立即返回，若此时 remove，
                // 会把 SSE 流线程上的身份一并清掉（详见 AGENTS.md 第 4 章）。
                boolean isSseEndpoint = request.path().endsWith("/sse");
                if (isSseEndpoint) {
                    return handler.handle(request)
                            .doFinally(signalType -> ApiKeyInterceptor.removeCurrentAssistant());
                }
                return handler.handle(request);

            } catch (Exception e) {
                log.error("MCP WebFlux: auth filter error", e);
                return ServerResponse.status(HttpStatus.INTERNAL_SERVER_ERROR)
                        .bodyValue("{\"code\":500,\"message\":\"认证服务异常\"}");
            }
        };
    }
}
