package com.exphub.interceptor;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * 派单只读 JSON 接口的专用鉴权（2026-11-20 W-20261120-000025）。
 *
 * <p><b>为什么不复用 MCP 那把 Key</b>：MCP Key 能调所有写工具（建单 / 推状态 / 改文档），
 * 而这两个接口只给忆阁后端读流水。共用一把 Key 等于把「只读」口子挂在能写全库的凭证上 ——
 * 一旦泄露，拿到的人能改写状态。专用只读 token 只能读，没有对应任何写能力。
 *
 * <p><b>为什么不靠回环地址放行</b>：忆阁是独立进程，可能不跟 ExpHub 同机 / 同网络命名空间
 * （#3365 阁主三条契约第 2 条）。回环判断会把合法调用挡掉，或者反向把同机的任意进程放行。
 *
 * <p><b>fail-closed</b>：token 未配置（空）时一律 401，不因为「没配就当开发模式放行」——
 * 静默放开等于没有鉴权。token 从配置读，不硬编码、不打进日志。
 */
@Component
public class DispatchReadTokenInterceptor implements HandlerInterceptor {

    /** 忆阁后端调用时把 token 放这个头；不要用 authorization-key（那是 MCP 的）。 */
    public static final String TOKEN_HEADER = "X-Dispatch-Token";

    /** 配置值（已 trim）。空 = 未配置 = 所有请求 401。 */
    private final String expected;

    public DispatchReadTokenInterceptor(
            @Value("${exphub.dispatch.read-api-token:}") String token) {
        this.expected = token == null ? "" : token.trim();
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler)
            throws Exception {
        String got = request.getHeader(TOKEN_HEADER);
        // 常量时间比较没必要（这是只读展示数据、不是认证会话），但拒绝原因不区分「没配」和「token 错」——
        // 对外都回 401，不泄露「服务端到底配没配」。
        boolean ok = !expected.isEmpty() && got != null && expected.equals(got.trim());
        if (!ok) {
            response.setStatus(401);
            response.setContentType("application/json;charset=UTF-8");
            response.getWriter().write("{\"code\":401,\"message\":\"dispatch read token missing or invalid\"}");
            return false;
        }
        return true;
    }
}
