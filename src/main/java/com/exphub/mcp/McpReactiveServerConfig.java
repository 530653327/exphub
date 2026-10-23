package com.exphub.mcp;

import com.exphub.entity.AiAssistant;
import com.exphub.interceptor.ApiKeyInterceptor;
import com.exphub.mapper.AiAssistantMapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.modelcontextprotocol.server.transport.WebFluxSseServerTransportProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.web.embedded.netty.NettyReactiveWebServerFactory;
import org.springframework.boot.web.server.WebServer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.DependsOn;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.core.io.buffer.DataBufferFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.server.reactive.HttpHandler;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.http.server.reactive.ServerHttpResponseDecorator;
import org.springframework.web.reactive.function.server.HandlerFilterFunction;
import org.springframework.web.reactive.function.server.HandlerStrategies;
import org.springframework.web.reactive.function.server.RouterFunction;
import org.springframework.web.reactive.function.server.RouterFunctions;
import org.springframework.web.reactive.function.server.ServerResponse;
import org.reactivestreams.Publisher;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.nio.charset.StandardCharsets;
import java.time.Duration;

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

    /**
     * SSE 心跳间隔（秒）。必须小于 Nginx keepalive_timeout（当前 65s）以及
     * 中间网络设备（运营商 / NAT / 防火墙）的空闲超时，否则连接仍会被掐断。
     * 2026-10-09 之前完全无心跳，导致客户端日均重连 350+ 次。
     */
    @Value("${exphub.mcp.sse-heartbeat-seconds:30}")
    private int sseHeartbeatSeconds;

    /**
     * MCP 单次请求体上限（字节）。默认 5MB，与 Nginx client_max_body_size 留出余量。
     * 之前未显式配置时，RouterFunctions 使用 WebFlux 默认的 256KB，
     * 超过会抛 DataBufferLimitException（HTTP 500）。
     */
    @Value("${exphub.mcp.max-request-bytes:5242880}")
    private int maxRequestBytes;

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

        HttpHandler httpHandler = RouterFunctions.toHttpHandler(securedRouter,
                HandlerStrategies.builder()
                        .codecs(c -> c.defaultCodecs().maxInMemorySize(maxRequestBytes))
                        .build());
        // 包装一层：给 SSE 端点注入心跳，避免空闲连接被 Nginx / 中间设备掐断
        WebServer webServer = factory.getWebServer(withSseHeartbeat(httpHandler));
        webServer.start();

        log.info("MCP WebFlux server started on port 3098 (non-blocking SSE, heartbeat={}s)",
                sseHeartbeatSeconds);
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
                // 注：SSE 心跳在更底层的 HttpHandler 层注入（见 withSseHeartbeat）。
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

    /**
     * 包装 HttpHandler：仅对 SSE 端点注入心跳，其余请求原样透传。
     *
     * <p><b>为什么在这一层做：</b>SDK 的 {@code WebFluxSseServerTransportProvider}
     * 内部用 {@code ServerResponse.body(Flux, ServerSentEvent.class)} 构建 SSE 响应，
     * 而 {@code ServerResponse} 是只写的——没有 {@code bodyToFlux} 之类的读取方法，
     * 因此在 RouterFunction 的 filter 里根本拿不到响应体。唯一可靠的注入点是更底层的
     * 出站数据流：{@code ServerHttpResponseDecorator}。
     */
    private HttpHandler withSseHeartbeat(HttpHandler delegate) {
        return (request, response) -> {
            if (!request.getPath().value().endsWith("/sse")) {
                return delegate.handle(request, response);
            }
            return delegate.handle(request, new SseHeartbeatResponse(response, sseHeartbeatSeconds));
        };
    }

    /**
     * SSE 响应装饰器——在出站数据流中周期性插入心跳行。
     *
     * <p><b>为什么需要心跳：</b>MCP 的 SSE 长连接在两次工具调用之间会长时间静默，
     * 静默连接会被 Nginx（keepalive_timeout 65s）以及运营商 / NAT / 防火墙按各自的
     * 空闲超时掐断，客户端只能不断重连。实测（2026-10-09）11 天累计新建 SSE 连接
     * 3877 次、日均 350+ 次重连，用户体感即为「偶尔连不上」。
     *
     * <p><b>为什么用 comment：</b>SSE 规范规定以 ':' 开头的行是注释、客户端必须忽略，
     * 因此不会干扰 MCP 的任何解析逻辑，是最安全的保活方式。
     *
     * <p><b>为什么覆写 writeAndFlushWith：</b>SSE 由
     * {@code ServerSentEventHttpMessageWriter} 写出，走的是 writeAndFlushWith
     * （每个事件单独 flush），而不是 writeWith。
     */
    private static final class SseHeartbeatResponse extends ServerHttpResponseDecorator {

        /** SSE 注释行：以 ':' 开头，客户端必须忽略。 */
        private static final byte[] KEEPALIVE = ": keepalive\n\n".getBytes(StandardCharsets.UTF_8);

        private final int heartbeatSeconds;

        private SseHeartbeatResponse(ServerHttpResponse delegate, int heartbeatSeconds) {
            super(delegate);
            this.heartbeatSeconds = heartbeatSeconds;
        }

        @Override
        public Mono<Void> writeAndFlushWith(
                Publisher<? extends Publisher<? extends DataBuffer>> body) {

            DataBufferFactory factory = getDelegate().bufferFactory();

            // 每个心跳是一个独立的 flush 组，保证被立即推送到客户端
            Flux<Publisher<? extends DataBuffer>> heartbeat = Flux
                    .interval(Duration.ofSeconds(heartbeatSeconds))
                    .map(tick -> (Publisher<? extends DataBuffer>) Mono.just(factory.wrap(KEEPALIVE)));

            Flux<Publisher<? extends DataBuffer>> source = Flux.from(body)
                    .map(group -> (Publisher<? extends DataBuffer>) group);

            // 客户端断开 → 整个响应流被 cancel → interval 随之释放
            return super.writeAndFlushWith(source.mergeWith(heartbeat));
        }
    }
}
