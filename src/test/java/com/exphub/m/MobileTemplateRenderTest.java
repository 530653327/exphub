package com.exphub.m;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.mock.web.MockServletContext;
import org.thymeleaf.context.WebContext;
import org.thymeleaf.spring6.SpringTemplateEngine;
import org.thymeleaf.templatemode.TemplateMode;
import org.thymeleaf.templateresolver.ClassLoaderTemplateResolver;
import org.thymeleaf.web.servlet.JakartaServletWebApplication;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertFalse;

/**
 * 移动端模板「能渲染」守卫（2026-11-08 加）。
 *
 * <h3>为什么必须存在这个测试</h3>
 * 2026-11-08 第一批移动页上线后，手机上打开是「一片深灰、什么都没有」，而 HTTP 状态是 <b>200</b>。
 * 根因：模板里写了 {@code ${#httpServletRequest.contextPath}}，本项目的 Thymeleaf 3.1 下该对象为 null，
 * 取属性直接抛 {@code TemplateProcessingException}。而 Thymeleaf 是边渲染边输出，
 * 异常发生时响应已提交，Spring 连错误页都渲染不了（日志原话：
 * {@code Cannot render error page ... as the response has already been committed}），
 * 浏览器收到的是半截 HTML —— 只剩 head 里的背景色。监控、状态码、curl 全部看不见。
 *
 * <p>所以这里不做「对源码文本断言」，而是<b>真的把模板渲染一遍</b>：
 * 模板只要有一个表达式炸，这条测试就红，而不是等人在手机上看着空白页去猜。
 *
 * <p>本类只依赖 thymeleaf + spring-mock，不启动 Spring 容器，也不连数据库 ——
 * 前面的 {@code @WebMvcTest} 方案因 MyBatis mapper 要 sqlSessionFactory 起不来，故改用纯引擎渲染。
 */
class MobileTemplateRenderTest {

    private static final String CTX_PATH = "/exphub";

    /** 五个移动页 + 各自需要的模型变量。新增移动页必须加进来，否则它永远没人渲染过。 */
    private static final List<String> TEMPLATES = List.of(
            "m/login", "m/dashboard", "m/pending", "m/tools", "m/users", "m/me"
    );

    private static SpringTemplateEngine engine() {
        ClassLoaderTemplateResolver resolver = new ClassLoaderTemplateResolver();
        resolver.setPrefix("templates/");
        resolver.setSuffix(".html");
        resolver.setTemplateMode(TemplateMode.HTML);
        resolver.setCharacterEncoding("UTF-8");
        resolver.setCacheable(false);

        SpringTemplateEngine engine = new SpringTemplateEngine();
        engine.setTemplateResolver(resolver);
        return engine;
    }

    private static WebContext context() {
        MockServletContext servletContext = new MockServletContext();
        MockHttpServletRequest request = new MockHttpServletRequest(servletContext);
        request.setContextPath(CTX_PATH);
        request.setRequestURI(CTX_PATH + "/m/dashboard");
        MockHttpServletResponse response = new MockHttpServletResponse();

        // Thymeleaf 3.1 起 WebContext 只接受 IWebExchange（不再直接吃 Servlet 三件套）
        JakartaServletWebApplication webApp = JakartaServletWebApplication.buildApplication(servletContext);
        WebContext ctx = new WebContext(webApp.buildExchange(request, response), Locale.SIMPLIFIED_CHINESE);
        // 与 MobileController 往 Model 里放的字段保持一致
        ctx.setVariable("title", "看板");
        ctx.setVariable("me", "管理员");
        ctx.setVariable("username", "admin");
        ctx.setVariable("lastLogin", "2026-11-08 02:00");
        ctx.setVariable("activeTab", "dashboard");
        // _csrf 在测试里没有 Security 上下文，模板按 null 分支走（这正是要覆盖的分支之一）
        ctx.setVariable("_csrf", null);
        return ctx;
    }

    @Test
    @DisplayName("五个移动页都能渲染完成，且 contextPath 被 @{/} 正确拼上")
    void allMobileTemplatesRender() {
        SpringTemplateEngine engine = engine();

        for (String t : TEMPLATES) {
            String html;
            try {
                html = engine.process(t, context());
            } catch (RuntimeException e) {
                throw new AssertionError("模板 " + t + " 渲染失败 —— 线上表现会是「200 + 半截白/灰页」，"
                        + "因为响应已提交时错误页也渲染不出来。原始异常：" + e.getMessage(), e);
            }

            assertTrue(html.contains("<!DOCTYPE html>"), t + " 输出不是完整 HTML：\n" + head(html));
            // @{/} 在 contextPath=/exphub 下应产出 /exphub/ 前缀
            assertTrue(html.contains(CTX_PATH), t + " 里没有出现 contextPath " + CTX_PATH
                    + "（@{/} 没生效？）");
            assertTrue(html.contains("tabbar"), t + " 缺底部导航（layout 的 tabbar 片段没被 include）");
        }
    }

    @Test
    @DisplayName("dashboard 渲染后 JS 与数据接口都在，不能只剩一个空壳")
    void dashboardHasWorkingScript() {
        String html = engine().process("m/dashboard", context());

        assertTrue(html.contains("id=\"refreshBtn\""), "刷新按钮不存在 —— 脚本会在 addEventListener 处抛错，"
                + "后面的 load() 根本不会跑（线上表现就是没有任何 /api 请求）");
        assertTrue(html.contains("/api/stats/overview"), "看板脚本里没有 overview 接口调用");
        assertTrue(html.contains("/api/stats/call-trend"), "看板脚本里没有趋势接口调用");
        assertTrue(html.contains("id=\"hotList\""), "热门列表容器不存在");
        // contextPath 拼接：模板里是 CTX + '/api/...'，CTX 不能带尾斜杠否则出现 //api
        assertFalse(html.contains("//api/"), "contextPath 拼接出现双斜杠：CTX 末尾的 / 没被去掉");
    }

    @Test
    @DisplayName("pending 页渲染后要带列表接口、CSRF 与二次确认脚本")
    void pendingHasWorkingScript() {
        String html = engine().process("m/pending", context());

        // 列表接口：没有它这一页就是个空壳
        assertTrue(html.contains("/api/docs/pending"), "待删队列没有调用 /api/docs/pending");
        // 详情接口：手机上删之前必须能看正文
        assertTrue(html.contains("/api/docs/' + id"), "看正文没走 /api/docs/{id}");
        // 删除仍然走 Session 登录的 PageController 端点，不是新开的 REST 删除口子
        assertTrue(html.contains("/docs/' + id + '/delete"), "删除没走 /docs/{id}/delete");
        // CSRF：删除是写操作，没有 token 会被 Spring Security 403 拦掉
        assertTrue(html.contains("name=\"_csrf\""), "页面缺 _csrf meta，删除会被 403 拦掉");
        assertTrue(html.contains("getElementById('sentinel')"), "分页哨兵不存在，长列表加载不了更多");
    }

    @Test
    @DisplayName("tools 页渲染后要带统计与日志两个接口，不能只剩标题")
    void toolsHasWorkingScript() {
        String html = engine().process("m/tools", context());

        assertTrue(html.contains("/api/tool-stats/summary"), "埋点页没有调用工具统计接口");
        assertTrue(html.contains("/api/logs"), "埋点页没有调用调用日志接口");
        // 三个数据区必须各有容器，否则 JS 拿到数据也没地方放
        assertTrue(html.contains("id=\"ranking\""), "工具使用榜容器不存在");
        assertTrue(html.contains("id=\"zeroList\""), "零调用工具容器不存在");
        assertTrue(html.contains("id=\"logs\""), "调用日志容器不存在");
        assertTrue(html.contains("id=\"trend\""), "趋势图容器不存在");
        assertTrue(html.contains("id=\"failOnlyBtn\""), "缺少「只看失败」开关");
    }

    @Test
    @DisplayName("users 页两类用户各走各的接口，且助手不能走会返回明文 Key 的详情接口")
    void usersHasWorkingScript() {
        String html = engine().process("m/users", context());

        // 门户用户走新建的只读接口
        assertTrue(html.contains("/api/mobile/users"), "门户用户没走 /api/mobile/users");
        // 助手列表复用现成接口（它已经做了 apiKey 掩码）
        assertTrue(html.contains("/api/assistants?page=1"), "助手列表没走 /api/assistants");

        // 安全红线：/api/assistants/{id} 返回完整实体，含 apiKey 与 apiKeySecret 明文。
        // 移动端一旦调它，密钥就直接画在屏幕上了 —— 这条断言就是防这个。
        assertFalse(html.contains("/api/assistants/' +"),
                "助手详情不能调 /api/assistants/{id}：它返回明文 apiKey 与 apiKeySecret");
        assertFalse(html.contains("apiKeySecret"),
                "模板里不应该出现 apiKeySecret 这个词，出现就说明有人把密钥字段接进来了");

        assertTrue(html.contains("id=\"userList\""), "门户用户列表容器不存在");
        assertTrue(html.contains("id=\"assistantList\""), "助手列表容器不存在");
        assertTrue(html.contains("id=\"userSearch\""), "缺少用户搜索框");
    }

    @Test
    @DisplayName("底部导航必须五项齐全（少一项等于那个入口没了）")
    void tabbarHasAllFiveTabs() {
        String html = engine().process("m/dashboard", context());

        for (String href : List.of("/m/dashboard", "/m/pending", "/m/tools", "/m/users", "/m/me")) {
            assertTrue(html.contains(href), "底部导航缺入口：" + href);
        }
    }

    @Test
    @DisplayName("模板里禁止再出现 #httpServletRequest / #request / #session（本项目 Thymeleaf 3.1 下为 null）")
    void noServletObjectsInTemplates() throws IOException {
        Path dir = Path.of("src/main/resources/templates/m");
        assertTrue(Files.isDirectory(dir), "找不到移动端模板目录：" + dir.toAbsolutePath());

        try (var files = Files.list(dir)) {
            for (Path p : files.filter(f -> f.toString().endsWith(".html")).toList()) {
                String src = Files.readString(p, StandardCharsets.UTF_8);
                for (String bad : List.of("#httpServletRequest", "#httpSession", "${#request.", "${#session.")) {
                    assertFalse(src.contains(bad), p.getFileName() + " 里出现 " + bad
                            + " —— 本项目 Thymeleaf 3.1 下该对象为 null，会在渲染中途抛异常，"
                            + "导致「200 状态码 + 半截页面」。取 contextPath 请用 @{/}。");
                }
            }
        }
    }

    private static String head(String s) {
        return s.length() > 400 ? s.substring(0, 400) : s;
    }
}
