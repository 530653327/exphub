package com.exphub.mcp;

import com.exphub.interceptor.ApiKeyInterceptor;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * 无状态 MCP 入口：{@code POST /mcp/http}（公网 {@code /exphub/mcp/http}）。
 *
 * <p><b>与旧 SSE 端点的关系：</b>并存，不替换。旧端点 {@code /mcp/sse} 继续供尚未切换的设备使用；
 * 新端点没有会话概念，部署重启后不需要任何重连动作。
 *
 * <p><b>鉴权：</b>复用现有的 {@link ApiKeyInterceptor}（拦截 {@code /mcp/**}，
 * 校验 authorization-key 并注入 CURRENT_ASSISTANT，afterCompletion 清理）。
 * 这里再查一次身份是双保险：万一以后拦截器路径配置变了，也不能让工具在匿名身份下裸跑
 * ——工具里的项目隔离全靠 CURRENT_ASSISTANT，丢了就越权。
 *
 * <p><b>不设 Mcp-Session-Id：</b>这是「重启无感」的关键。客户端看到没有会话 ID，
 * 后续请求就不会带旧会话号，服务端也就不存在「会话过期」这回事。
 */
@RestController
public class McpStatelessController {

    private final McpStatelessProtocol protocol;

    public McpStatelessController(McpStatelessProtocol protocol) {
        this.protocol = protocol;
    }

    @PostMapping("/mcp/http")
    public ResponseEntity<String> handle(@RequestBody(required = false) String body) {
        if (ApiKeyInterceptor.getCurrentAssistant() == null) {
            return ResponseEntity.status(401)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body("{\"code\":401,\"message\":\"API Key缺失或无效\"}");
        }

        String response = protocol.handle(body);
        if (response == null) {
            // 通知类消息（notifications/initialized 等）按规范不回响应体
            return ResponseEntity.accepted().build();
        }
        return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_JSON)
                .body(response);
    }
}
