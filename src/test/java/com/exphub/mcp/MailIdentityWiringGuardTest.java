package com.exphub.mcp;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 守卫：新增的 ThreadLocal 身份必须**两条链路都接上**。
 *
 * <p>2026-11-06 的事故：信箱身份头只写进了 MVC 拦截器（{@code ApiKeyInterceptor}），
 * 但 3098 是 WebFlux(Netty) 独立服务器，根本不走拦截器；而忆阁正是走 3098 的 SSE。
 * 结果：包到了网卡、应用层读成空，{@code mail_start} 永远回「未声明角色」。
 * 同一个坑还有第二层：即使读到了，工具在 {@code boundedElastic} 线程执行，
 * ThreadLocal 不传播仍然读成 null。
 *
 * <p><b>这是源码守卫，不是行为测试</b> —— 它只能防「漏改」，不能证明运行期正确。
 * 运行期由线上 SSE 链路真调一次 {@code mail_start} 验（见 #1237 A9 ⑤）。
 * 写它的理由：#672 记录过「工具在、模型不知道」全靠人工发现，这类结构约束
 * 一旦漏掉，下次还是同一个 bug。
 */
class MailIdentityWiringGuardTest {

    @Test
    @DisplayName("WebFlux(3098) 鉴权过滤器必须读信箱身份头")
    void webFluxAuthFilterMustReadMailHeaders() throws Exception {
        String src = read("src/main/java/com/exphub/mcp/McpReactiveServerConfig.java");
        assertTrue(src.contains("MailIdentity.HEADER_ROLE"),
                "3098 的 authFilter 没读 x-exphub-role —— SSE 链路会永远 role=null");
        assertTrue(src.contains("MailIdentity.HEADER_CONFIG_SOURCE"),
                "3098 的 authFilter 没读 x-exphub-config-source");
        assertTrue(src.contains("MailIdentity.set("),
                "3098 的 authFilter 没把身份写进 ThreadLocal");
    }

    @Test
    @DisplayName("Reactor 跨线程传播必须带上信箱身份")
    void reactivePropagationMustCarryMailIdentity() throws Exception {
        String src = read("src/main/java/com/exphub/config/McpContextPropagationConfig.java");
        assertTrue(src.contains("MailIdentity.role()"),
                "传播钩子没捕获角色：工具在 boundedElastic 线程会读成 null");
        assertTrue(src.contains("MailIdentity.set("),
                "传播钩子没恢复角色");
    }

    private String read(String relative) throws Exception {
        return Files.readString(Path.of(relative));
    }
}
