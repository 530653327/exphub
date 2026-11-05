package com.exphub.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.ai.tool.definition.ToolDefinition;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 无状态 MCP 端点的协议层测试。
 *
 * 这里不测 HTTP（鉴权、Nginx 在线上验），只钉死「重启无感」依赖的协议契约：
 * 不建会话、工具清单与 SSE 同源、工具报错不升级成传输错误。
 */
class McpStatelessProtocolTest {

    private final ObjectMapper mapper = new ObjectMapper();
    private McpStatelessProtocol protocol;

    private static final String SCHEMA = "{\"type\":\"object\",\"properties\":{\"id\":{\"type\":\"integer\"}},\"required\":[]}";

    @BeforeEach
    void setUp() {
        ToolCallback echo = callback("echo", "回显工具", text -> "hello:" + text);
        ToolCallback boom = callback("boom", "会抛异常", text -> {
            throw new IllegalStateException("模拟工具内部报错");
        });
        protocol = new McpStatelessProtocol((ToolCallbackProvider) () -> new ToolCallback[]{echo, boom}, mapper);
    }

    @Test
    @DisplayName("initialize 回显协议版本，且响应体不带任何会话标识")
    void initializeHasNoSession() throws Exception {
        JsonNode r = result(protocol.handle(req(1, "initialize",
                "{\"protocolVersion\":\"2025-06-18\",\"capabilities\":{},\"clientInfo\":{\"name\":\"probe\",\"version\":\"1\"}}")));
        assertEquals("2025-06-18", r.path("protocolVersion").asText());
        assertTrue(r.path("capabilities").has("tools"), "必须声明 tools 能力，否则客户端不会调 tools/list");
        assertEquals("exphub", r.path("serverInfo").path("name").asText());
        assertFalse(r.toString().contains("sessionId"), "响应体不许出现会话 ID（无状态是重启无感的前提）");
        assertFalse(r.toString().contains("Mcp-Session"), "响应体不许出现会话相关字段");
    }

    @Test
    @DisplayName("两次 initialize 之间没有任何状态依赖：直接 tools/call 也照常工作")
    void noHandshakeStateRequired() throws Exception {
        // 模拟「服务重启后客户端没有重新握手」，上来就调工具
        JsonNode r = result(protocol.handle(req(7, "tools/call",
                "{\"name\":\"echo\",\"arguments\":{\"id\":1}}")));
        assertEquals("hello:{\"id\":1}", r.path("content").get(0).path("text").asText());
        assertFalse(r.path("isError").asBoolean());
    }

    @Test
    @DisplayName("tools/list 的 inputSchema 必须是对象，不能是 JSON 字符串")
    void toolsListSchemaIsObject() throws Exception {
        JsonNode r = result(protocol.handle(req(2, "tools/list", "{}")));
        JsonNode tools = r.path("tools");
        assertEquals(2, tools.size());
        assertEquals("echo", tools.get(0).path("name").asText());
        assertTrue(tools.get(0).path("inputSchema").isObject(), "MCP 要求 inputSchema 是对象");
        assertEquals("object", tools.get(0).path("inputSchema").path("type").asText());
    }

    @Test
    @DisplayName("工具返回带引号的 JSON 串时还原成纯文本，不带多余引号")
    void unwrapsJsonStringResult() {
        assertEquals("纯文本", McpStatelessProtocol.unwrap("\"纯文本\"", mapper));
        assertEquals("带\n换行", McpStatelessProtocol.unwrap("\"带\\n换行\"", mapper),
                "JSON 转义要还原成真实换行，不是留着反斜杠 n");
        assertEquals("本来就是纯文本", McpStatelessProtocol.unwrap("本来就是纯文本", mapper));
        assertEquals("", McpStatelessProtocol.unwrap(null, mapper));
    }

    @Test
    @DisplayName("未知工具 / 工具抛异常 → isError=true 的结果，不是 JSON-RPC 传输错误")
    void toolErrorsStayInResult() throws Exception {
        JsonNode unknown = result(protocol.handle(req(3, "tools/call",
                "{\"name\":\"不存在的工具\",\"arguments\":{}}")));
        assertTrue(unknown.path("isError").asBoolean());
        assertTrue(unknown.path("content").get(0).path("text").asText().contains("未知工具"));

        JsonNode failed = result(protocol.handle(req(4, "tools/call",
                "{\"name\":\"boom\",\"arguments\":{}}")));
        assertTrue(failed.path("isError").asBoolean());
        assertTrue(failed.path("content").get(0).path("text").asText().contains("模拟工具内部报错"));
    }

    @Test
    @DisplayName("通知（无 id）不回响应；ping 回空 result")
    void notificationsAndPing() throws Exception {
        assertNull(protocol.handle("{\"jsonrpc\":\"2.0\",\"method\":\"notifications/initialized\"}"));
        JsonNode ping = result(protocol.handle(req(5, "ping", "{}")));
        assertTrue(ping.isObject());
        assertEquals(0, ping.size());
    }

    @Test
    @DisplayName("未知方法返回 -32601；解析失败返回 -32700")
    void protocolErrors() throws Exception {
        JsonNode noMethod = mapper.readTree(protocol.handle(req(6, "不存在的方法", "{}")));
        assertEquals(-32601, noMethod.path("error").path("code").asInt());
        JsonNode bad = mapper.readTree(protocol.handle("这不是 JSON"));
        assertEquals(-32700, bad.path("error").path("code").asInt());
    }

    @Test
    @DisplayName("批量请求：返回数组，批内通知被过滤")
    void batchRequest() throws Exception {
        String batch = "[{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"ping\",\"params\":{}},"
                + "{\"jsonrpc\":\"2.0\",\"method\":\"notifications/initialized\"},"
                + "{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"ping\",\"params\":{}}]";
        JsonNode r = mapper.readTree(protocol.handle(batch));
        assertTrue(r.isArray());
        assertEquals(2, r.size(), "通知不产生响应");
        assertEquals(1, r.get(0).path("id").asInt());
        assertEquals(2, r.get(1).path("id").asInt());
    }

    // ---------- helpers ----------

    private static String req(int id, String method, String params) {
        return "{\"jsonrpc\":\"2.0\",\"id\":" + id + ",\"method\":\"" + method
                + "\",\"params\":" + params + "}";
    }

    private JsonNode result(String response) throws Exception {
        JsonNode envelope = mapper.readTree(response);
        assertFalse(envelope.has("error"), "不该是 JSON-RPC 错误: " + response);
        return envelope.path("result");
    }

    private static ToolCallback callback(String name, String desc, java.util.function.Function<String, String> body) {
        return new ToolCallback() {
            @Override
            public ToolDefinition getToolDefinition() {
                return new ToolDefinition() {
                    @Override public String name() { return name; }
                    @Override public String description() { return desc; }
                    @Override public String inputSchema() { return SCHEMA; }
                };
            }

            @Override
            public String call(String toolInput) {
                // 模拟 Spring AI 把 String 返回值序列化成 JSON 串的行为
                return "\"" + body.apply(toolInput).replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
            }
        };
    }
}
