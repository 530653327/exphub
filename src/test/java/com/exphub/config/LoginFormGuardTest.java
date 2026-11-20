package com.exphub.config;

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

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 登录表单防重复提交守卫（派单 W-20261120-000020）。
 *
 * <p>根因回顾：Spring Security 6 登录成功后会 {@code ChangeSessionIdAuthenticationStrategy}
 * 换 JSESSIONID + {@code CsrfAuthenticationStrategy} 轮换 CSRF token。双击/连击登录按钮时，
 * 第二个 POST 携带已被轮换的旧 token，被 CsrfFilter 在认证前拒为 403，转发到已 permitAll 的
 * /error 后显示 Whitelabel Error Page —— 这就是「重新登录后 403」的真实形态。</p>
 *
 * <p>修法是两端登录表单在第一次 submit 后置标志并禁用按钮。登录页没有任何测试覆盖，
 * 模板语法错一个字符就是线上 500，且防重复逻辑被误删会让 403 复现而无人察觉。
 * 这里用真渲染（不启 Spring、不连库）同时兜住「能渲染」和「防重复逻辑在」两件事。</p>
 */
class LoginFormGuardTest {

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

    private static String render(String template) {
        MockServletContext servletContext = new MockServletContext();
        MockHttpServletRequest request = new MockHttpServletRequest(servletContext);
        request.setContextPath("/exphub");
        request.setRequestURI("/exphub/login");
        MockHttpServletResponse response = new MockHttpServletResponse();
        JakartaServletWebApplication webApp = JakartaServletWebApplication.buildApplication(servletContext);
        WebContext ctx = new WebContext(webApp.buildExchange(request, response), Locale.SIMPLIFIED_CHINESE);
        ctx.setVariable("contextPath", "/exphub");
        ctx.setVariable("active", "login");
        try {
            return engine().process(template, ctx);
        } catch (RuntimeException e) {
            throw new AssertionError(template + " 渲染失败，登录页会 500。原始异常：" + e.getMessage(), e);
        }
    }

    private static String src(String path) throws Exception {
        return Files.readString(Path.of(path), StandardCharsets.UTF_8);
    }

    @Test
    @DisplayName("PC 登录页：能渲染，且表单带 __loginSent 防重复提交")
    void pcLoginHasSubmitGuard() {
        String html = render("login");
        assertTrue(html.contains("name=\"username\"") && html.contains("name=\"password\""),
                "登录表单字段没渲染出来");
        assertTrue(html.contains("window.__loginSent"),
                "PC 登录页丢了防重复提交标志，双击会再次 POST 触发 CSRF 403");
        assertTrue(html.contains("b.disabled=true"),
                "必须在首次提交后禁用按钮，否则连击仍可发起第二个 POST");
    }

    @Test
    @DisplayName("移动登录页：能渲染，且表单带同样的防重复提交")
    void mobileLoginHasSubmitGuard() {
        String html = render("m/login");
        assertTrue(html.contains("name=\"username\"") && html.contains("name=\"password\""),
                "移动登录表单字段没渲染出来");
        assertTrue(html.contains("window.__loginSent"),
                "移动登录页丢了防重复提交标志（移动端更易误触双击）");
        assertTrue(html.contains("b.disabled=true"),
                "移动端必须在首次提交后禁用按钮");
    }

    @Test
    @DisplayName("两个登录表单都提交到 /login（CSRF 失效路径未被改到别的端点）")
    void bothSubmitToLogin() throws Exception {
        String pc = src("src/main/resources/templates/login.html");
        String mobile = src("src/main/resources/templates/m/login.html");
        assertTrue(pc.contains("th:action=\"@{/login}\"") && pc.contains("method=\"post\""),
                "PC 登录表单 action 变了，防重复守卫失去意义");
        assertTrue(mobile.contains("th:action=\"@{/login}\"") && mobile.contains("method=\"post\""),
                "移动登录表单 action 变了");
        // 防重复逻辑必须是「拦第二次」，不是简单地隐藏按钮（隐藏后回车仍可提交）
        assertFalse(pc.contains("__loginSent=false"),
                "PC：__loginSent 不能被重置为 false，否则连击仍能重复提交");
        assertFalse(mobile.contains("__loginSent=false"),
                "移动：__loginSent 不能被重置为 false");
    }
}
