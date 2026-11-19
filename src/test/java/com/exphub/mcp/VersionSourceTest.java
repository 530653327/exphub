package com.exphub.mcp;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 版本号的单一来源守卫。
 *
 * <p>health、SSE 的 serverInfo、无状态端点的 serverInfo 三处都会对外报版本。
 * 以前各写各的数字，改一次要找三个地方，漏一个就出现「同一个服务报两个版本」。
 * 这里钉死：三处全部读 {@code exphub.version}，且不允许代码里再硬编码版本号。
 *
 * <p><b>2026-11-19 改法（重要）</b>：原来这条测试把版本号**字面值**写进断言
 * （{@code yml.contains("version: 2.5.6")}），结果每次发版都要改测试 ——
 * 2.5.7 那次就因为「改完 yml 没重跑测试」让主干红着推上去。
 * 现在改成结构性断言：只要求「恰好一条 {@code version: x.y.z}」「占位符都引用它」，
 * 具体是几号与测试无关。
 */
class VersionSourceTest {

    private static String read(String path) throws IOException {
        return Files.readString(Path.of(path), StandardCharsets.UTF_8);
    }

    /** 形如 {@code version: 2.5.8} 的独立配置行（不含占位符、不含注释）。 */
    private static final Pattern PRODUCT_VERSION_LINE =
            Pattern.compile("(?m)^ {2}version: (\\d+\\.\\d+\\.\\d+)$");

    @Test
    @DisplayName("产品版本在 application.yml 只定义一次，且是 x.y.z 字面值")
    void productVersionIsSingleSourced() throws IOException {
        String yml = read("src/main/resources/application.yml");
        Matcher m = PRODUCT_VERSION_LINE.matcher(yml);
        int count = 0;
        String version = null;
        while (m.find()) {
            count++;
            version = m.group(1);
        }
        assertEquals(1, count, "exphub.version 必须恰好定义一条（否则两处会各报各的版本）: 命中 " + count + " 条");
        assertNotNull(version, "application.yml 里找不到 exphub.version");
    }

    @Test
    @DisplayName("SSE 的 serverInfo 通过占位符引用同一个版本")
    void sseServerInfoReferencesSameVersion() throws IOException {
        String yml = read("src/main/resources/application.yml");
        assertTrue(yml.contains("version: ${exphub.version:"),
                "spring.ai.mcp.server.version 必须引用 exphub.version，别再写死数字");
    }

    @Test
    @DisplayName("health 与无状态端点都读 exphub.version，不硬编码版本")
    void bothEndpointsReadSameProperty() throws IOException {
        String health = read("src/main/java/com/exphub/controller/HealthController.java");
        String stateless = read("src/main/java/com/exphub/mcp/McpStatelessProtocol.java");
        assertTrue(health.contains("@Value(\"${exphub.version:"), "health 读 exphub.version");
        assertTrue(stateless.contains("@Value(\"${exphub.version:"), "无状态端点读 exphub.version");
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
}
