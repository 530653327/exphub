package com.exphub.mcp;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 版本号的单一来源守卫（2026-11-05 起产品版本 = 2.2.1）。
 *
 * health、SSE 的 serverInfo、无状态端点的 serverInfo 三处都会对外报版本。
 * 以前各写各的数字，改一次要找三个地方，漏一个就出现「同一个服务报两个版本」。
 * 这里钉死：三处全部读 exphub.version，且不允许代码里再硬编码旧版本号。
 */
class VersionSourceTest {

    private static String read(String path) throws IOException {
        return Files.readString(Path.of(path), StandardCharsets.UTF_8);
    }

    @Test
    @DisplayName("产品版本是 2.2.1，且只在 application.yml 定义一次")
    void productVersionIsSingleSourced() throws IOException {
        String yml = read("src/main/resources/application.yml");
        assertTrue(yml.contains("version: 2.2.1"), "application.yml 里必须定义 exphub.version: 2.2.1");
        assertEquals(1, countOf(yml, "version: 2.2.1"), "产品版本只应在一处写字面值");
    }

    @Test
    @DisplayName("SSE 的 serverInfo 通过占位符引用同一个版本")
    void sseServerInfoReferencesSameVersion() throws IOException {
        String yml = read("src/main/resources/application.yml");
        assertTrue(yml.contains("version: ${exphub.version:2.2.1}"),
                "spring.ai.mcp.server.version 必须引用 exphub.version，别再写死数字");
    }

    @Test
    @DisplayName("health 与无状态端点都读 exphub.version，不硬编码旧版本")
    void bothEndpointsReadSameProperty() throws IOException {
        String health = read("src/main/java/com/exphub/controller/HealthController.java");
        String stateless = read("src/main/java/com/exphub/mcp/McpStatelessProtocol.java");
        assertTrue(health.contains("@Value(\"${exphub.version:2.2.1}\")"), "health 读 exphub.version");
        assertTrue(stateless.contains("@Value(\"${exphub.version:2.2.1}\")"), "无状态端点读 exphub.version");
        assertFalse(stateless.contains("info.put(\"version\", \"1.0.0\")"),
                "serverInfo 不许再硬编码 1.0.0");
    }

    @Test
    @DisplayName("Maven artifact 版本别乱改：它决定 jar 文件名，被启动脚本写死")
    void artifactVersionChangeNeedsDeployScriptSync() throws IOException {
        String pom = read("pom.xml");
        String artifactVersion = pom.replaceFirst("(?s).*?<artifactId>exphub</artifactId>\\s*<version>([^<]+)</version>.*", "$1");
        assertEquals("1.0.0", artifactVersion,
                "改 pom 的 <version> 会把产物改名成 exphub-<新版本>.jar；"
                        + "systemd service、deploy.sh、Dockerfile、start.sh 里全写死了 exphub-1.0.0.jar，"
                        + "不同步就起不来。产品版本号请改 application.yml 的 exphub.version，别动这里。");

        String[] pinned = {
                "deploy/exphub.service", "deploy.sh", "Dockerfile", "start.sh"
        };
        for (String f : pinned) {
            assertTrue(read(f).contains("exphub-1.0.0.jar"),
                    f + " 里的 jar 名与 pom artifact 版本必须对得上");
        }
    }

    private static int countOf(String s, String sub) {
        int n = 0, i = 0;
        while ((i = s.indexOf(sub, i)) >= 0) { n++; i += sub.length(); }
        return n;
    }
}
