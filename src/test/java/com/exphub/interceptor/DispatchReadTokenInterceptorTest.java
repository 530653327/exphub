package com.exphub.interceptor;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 派单只读接口鉴权守卫（2026-11-20 W-20261120-000025）。
 */
class DispatchReadTokenInterceptorTest {

    @Test
    @DisplayName("带正确 token 放行")
    void correctTokenPasses() throws Exception {
        DispatchReadTokenInterceptor it = new DispatchReadTokenInterceptor("secret-abc");
        MockHttpServletRequest req = new MockHttpServletRequest();
        req.addHeader(DispatchReadTokenInterceptor.TOKEN_HEADER, "secret-abc");
        assertTrue(it.preHandle(req, new MockHttpServletResponse(), new Object()));
    }

    @Test
    @DisplayName("不带 token / token 错 / token 未配置 一律 401（fail-closed）")
    void missingOrWrongTokenRejected() throws Exception {
        DispatchReadTokenInterceptor it = new DispatchReadTokenInterceptor("secret-abc");
        MockHttpServletResponse noToken = new MockHttpServletResponse();
        assertFalse(it.preHandle(new MockHttpServletRequest(), noToken, new Object()));
        assertEquals(401, noToken.getStatus());

        MockHttpServletRequest wrong = new MockHttpServletRequest();
        wrong.addHeader(DispatchReadTokenInterceptor.TOKEN_HEADER, "wrong");
        MockHttpServletResponse wrongResp = new MockHttpServletResponse();
        assertFalse(it.preHandle(wrong, wrongResp, new Object()));
        assertEquals(401, wrongResp.getStatus());

        // token 未配置（空）：即使带任意头也 401，绝不「没配就放行」
        DispatchReadTokenInterceptor unconfigured = new DispatchReadTokenInterceptor("");
        MockHttpServletRequest any = new MockHttpServletRequest();
        any.addHeader(DispatchReadTokenInterceptor.TOKEN_HEADER, "anything");
        MockHttpServletResponse unresp = new MockHttpServletResponse();
        assertFalse(unconfigured.preHandle(any, unresp, new Object()));
        assertEquals(401, unresp.getStatus());
    }

    @Test
    @DisplayName("用专用头，不复用 MCP 的 authorization-key")
    void usesDedicatedHeader() {
        assertEquals("X-Dispatch-Token", DispatchReadTokenInterceptor.TOKEN_HEADER);
        assertNotEquals("authorization-key", DispatchReadTokenInterceptor.TOKEN_HEADER);
    }

    @Test
    @DisplayName("WebMvcConfig：/api/dispatch/** 从 apiKey 拦截排除，且挂了专用 token 拦截")
    void webMvcWiring() throws Exception {
        String src = new String(Files.readAllBytes(
                Path.of("src/main/java/com/exphub/config/WebMvcConfig.java")), StandardCharsets.UTF_8)
                .replaceAll("(?m)//.*$", "");
        assertTrue(src.contains("\"/api/dispatch/**\""),
                "WebMvcConfig 没排除 /api/dispatch/**，忆阁调用会被 apiKey 拦截器的「无 Session 403」挡住");
        assertTrue(src.contains("dispatchReadTokenInterceptor"),
                "WebMvcConfig 没挂专用 token 拦截器");
        assertTrue(src.contains("addInterceptor(dispatchReadTokenInterceptor)"),
                "专用 token 拦截器没注册到路径");
    }

    @Test
    @DisplayName("SecurityConfig：/api/dispatch/** 必须 permitAll（否则被 authenticated() 挡成 302 登录页）")
    void securityConfigPermitsDispatchApi() throws Exception {
        String src = new String(Files.readAllBytes(
                Path.of("src/main/java/com/exphub/config/SecurityConfig.java")), StandardCharsets.UTF_8)
                .replaceAll("(?m)//.*$", "");
        assertTrue(src.contains("\"/api/dispatch/**\").permitAll()")
                        || src.contains("\"/api/dispatch/**\").permitAll"),
                "SecurityConfig 没把 /api/dispatch/** permitAll —— 请求会在 token 拦截器之前被 302 到登录页");
    }

    @Test
    @DisplayName("两个只读接口只有 @GetMapping，没有任何写方法注解")
    void controllerIsReadOnly() throws Exception {
        String src = new String(Files.readAllBytes(
                Path.of("src/main/java/com/exphub/controller/DispatchApiController.java")), StandardCharsets.UTF_8)
                .replaceAll("(?s)/\\*.*?\\*/", "").replaceAll("(?m)//.*$", "");
        assertEquals(2, countOccurrences(src, "@GetMapping"), "应该正好两个 GET 接口");
        for (String write : new String[]{"@PostMapping", "@PutMapping", "@DeleteMapping", "@PatchMapping"}) {
            assertFalse(src.contains(write), "只读接口里出现了写方法 " + write);
        }
    }

    private static int countOccurrences(String s, String sub) {
        int n = 0, i = 0;
        while ((i = s.indexOf(sub, i)) >= 0) { n++; i += sub.length(); }
        return n;
    }
}
