package com.exphub.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.stereotype.Component;

/**
 * 无状态 MCP（Streamable HTTP）协议处理。
 *
 * <p><b>为什么要有它：</b>现有 {@code /mcp/sse} 是「一条 SSE 长连接 = 一个内存会话」，
 * 服务一重启会话全丢，客户端拿着旧 sessionId 请求只会拿到 404，而客户端不会自己重新握手
 * —— 于是每次部署完，所有设备都得手动重连一次 MCP。
 *
 * <p><b>本实现的关键：不建会话。</b>每个 HTTP POST 都是独立的一问一答，
 * 不生成也不返回 {@code Mcp-Session-Id}，服务端不保存任何连接状态。
 * 因此服务重启后，客户端的<strong>下一个请求直接成功</strong>，无需重新 initialize。
 *
 * <p><b>与 SSE 端点同源：</b>工具定义和调用都走同一个 {@link ToolCallbackProvider} bean，
 * 不另写一份注册表。新增 / 改名工具时两边同步变化，不会出现「SSE 有、HTTP 没有」。
 * 旧 SSE 端点保持不动，未切换的设备继续可用。
 */
@Component
public class McpStatelessProtocol {

    private static final Logger log = LoggerFactory.getLogger(McpStatelessProtocol.class);

    private static final String DEFAULT_PROTOCOL_VERSION = "2025-03-26";

    private final ToolCallbackProvider toolCallbackProvider;
    private final ObjectMapper mapper;

    public McpStatelessProtocol(ToolCallbackProvider toolCallbackProvider, ObjectMapper mapper) {
        this.toolCallbackProvider = toolCallbackProvider;
        this.mapper = mapper;
    }

    /**
     * 处理一条（或一批）JSON-RPC 消息。
     *
     * @return 响应 JSON；纯通知（无 id）返回 {@code null}，由 Controller 回 202 Accepted。
     *         任何情况下都不会抛出协议级异常——工具内部报错按 MCP 规范转成 isError 结果。
     */
    public String handle(String body) {
        JsonNode root;
        try {
            root = mapper.readTree(body == null ? "" : body);
        } catch (Exception e) {
            log.warn("MCP stateless: parse error: {}", e.getMessage());
            return error(null, -32700, "Parse error: " + e.getMessage());
        }

        if (root.isArray()) {
            ArrayNode out = mapper.createArrayNode();
            for (JsonNode one : root) {
                if (!one.hasNonNull("id")) continue; // 批里的通知也不回
                out.add(readTreeOrNull(handleOne(one)));
            }
            return out.isEmpty() ? null : out.toString();
        }
        if (!root.hasNonNull("id")) {
            return null; // notifications/initialized 等：协议规定不回响应
        }
        return handleOne(root);
    }

    private String handleOne(JsonNode req) {
        JsonNode id = req.get("id");
        String method = req.path("method").asText("");
        JsonNode params = req.path("params");

        try {
            return ok(id, dispatch(method, params));
        } catch (UnknownMethodException e) {
            return error(id, -32601, e.getMessage());
        } catch (Exception e) {
            log.error("MCP stateless: dispatch failed, method={}", method, e);
            return error(id, -32603, "Internal error: " + e.getClass().getSimpleName()
                    + " - " + e.getMessage());
        }
    }

    private ObjectNode dispatch(String method, JsonNode params) throws Exception {
        ObjectNode r = mapper.createObjectNode();
        switch (method) {
            case "initialize" -> {
                // 回显客户端版本：服务端不强制升级、也不假装支持更新的版本。
                // 没有会话概念，所以绝不返回 Mcp-Session-Id。
                r.put("protocolVersion", params.path("protocolVersion").asText(DEFAULT_PROTOCOL_VERSION));
                ObjectNode caps = mapper.createObjectNode();
                ObjectNode tools = mapper.createObjectNode();
                tools.put("listChanged", false);
                caps.set("tools", tools);
                r.set("capabilities", caps);
                ObjectNode info = mapper.createObjectNode();
                info.put("name", "exphub");
                info.put("version", "1.0.0");
                r.set("serverInfo", info);
            }
            case "ping" -> {
                // 空 result 即可
            }
            case "tools/list" -> r.set("tools", listTools());
            case "tools/call" -> callTool(params).fields()
                    .forEachRemaining(e -> r.set(e.getKey(), e.getValue()));
            // 有些客户端会顺手探测资源/提示词，给空列表比回 Method not found 更省事
            case "resources/list" -> r.set("resources", mapper.createArrayNode());
            case "prompts/list" -> r.set("prompts", mapper.createArrayNode());
            case "resources/templates/list" -> r.set("resourceTemplates", mapper.createArrayNode());
            default -> throw new UnknownMethodException("Method not found: " + method);
        }
        return r;
    }

    private ArrayNode listTools() throws Exception {
        ArrayNode tools = mapper.createArrayNode();
        for (ToolCallback cb : toolCallbackProvider.getToolCallbacks()) {
            ObjectNode t = mapper.createObjectNode();
            t.put("name", cb.getToolDefinition().name());
            t.put("description", cb.getToolDefinition().description());
            String schema = cb.getToolDefinition().inputSchema();
            // inputSchema 是 JSON Schema 字符串，MCP 要求作为对象返回，不能塞成字符串
            t.set("inputSchema", schema == null || schema.isBlank()
                    ? mapper.createObjectNode() : mapper.readTree(schema));
            tools.add(t);
        }
        return tools;
    }

    private ObjectNode callTool(JsonNode params) {
        ObjectNode r = mapper.createObjectNode();
        ArrayNode content = mapper.createArrayNode();
        String name = params.path("name").asText("");
        JsonNode args = params.path("arguments");
        String argJson = args.isMissingNode() || args.isNull() ? "{}" : args.toString();

        ToolCallback cb = null;
        for (ToolCallback candidate : toolCallbackProvider.getToolCallbacks()) {
            if (candidate.getToolDefinition().name().equals(name)) {
                cb = candidate;
                break;
            }
        }
        if (cb == null) {
            ObjectNode item = mapper.createObjectNode();
            item.put("type", "text");
            item.put("text", "❌ 未知工具：「" + name + "」。用 tools/list 看当前可用工具。");
            content.add(item);
            r.set("content", content);
            r.put("isError", true);
            return r;
        }

        try {
            String raw = cb.call(argJson);
            ObjectNode item = mapper.createObjectNode();
            item.put("type", "text");
            item.put("text", unwrap(raw));
            content.add(item);
            r.set("content", content);
            r.put("isError", false);
        } catch (Exception e) {
            // 工具内部异常按 MCP 规范走 result.isError，不是 JSON-RPC error：
            // 这样客户端能把错误当正文显示，而不是把整次调用判成传输失败
            log.warn("MCP stateless: tool {} failed: {}", name, e.toString());
            ObjectNode item = mapper.createObjectNode();
            item.put("type", "text");
            item.put("text", "调用失败: " + e.getClass().getName() + " - " + e.getMessage());
            content.add(item);
            r.set("content", content);
            r.put("isError", true);
        }
        return r;
    }

    /** Spring AI 把 String 返回值序列化成带引号的 JSON 串，这里还原成纯文本 */
    static String unwrap(String raw, ObjectMapper mapper) {
        if (raw == null) return "";
        String t = raw.trim();
        if (t.length() >= 2 && t.startsWith("\"") && t.endsWith("\"")) {
            try {
                return mapper.readValue(t, String.class);
            } catch (Exception ignored) {
                // 不是合法 JSON 字符串就原样返回
            }
        }
        return raw;
    }

    private String unwrap(String raw) {
        return unwrap(raw, mapper);
    }

    private String ok(JsonNode id, ObjectNode result) {
        ObjectNode envelope = mapper.createObjectNode();
        envelope.put("jsonrpc", "2.0");
        envelope.set("id", id);
        envelope.set("result", result);
        return envelope.toString();
    }

    private String error(JsonNode id, int code, String message) {
        ObjectNode envelope = mapper.createObjectNode();
        envelope.put("jsonrpc", "2.0");
        if (id == null) envelope.putNull("id");
        else envelope.set("id", id);
        ObjectNode err = mapper.createObjectNode();
        err.put("code", code);
        err.put("message", message);
        envelope.set("error", err);
        return envelope.toString();
    }

    /** 把单条响应解析回 JsonNode，供批量请求组装（handleOne 保证返回合法 JSON） */
    private JsonNode readTreeOrNull(String json) {
        try {
            return mapper.readTree(json);
        } catch (Exception e) {
            return mapper.createObjectNode();
        }
    }

    private static final class UnknownMethodException extends RuntimeException {
        UnknownMethodException(String message) {
            super(message);
        }
    }
}
