package com.exphub.mcp;

import jakarta.servlet.*;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.util.ContentCachingRequestWrapper;

import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.stream.Collectors;

/**
 * 记录 MCP 请求体，用于排查 create/update 工具调用失败问题
 */
@Component
@Order(1)
public class McpRequestBodyLoggingFilter implements Filter {

    private static final Logger log = LoggerFactory.getLogger(McpRequestBodyLoggingFilter.class);

    @Override
    public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain)
            throws IOException, ServletException {
        
        HttpServletRequest httpReq = (HttpServletRequest) request;
        String uri = httpReq.getRequestURI();
        
        if (uri != null && uri.contains("/mcp/message")) {
            // 包装 request 以缓存 body
            CachedBodyRequestWrapper wrapper = new CachedBodyRequestWrapper(httpReq);
            String body = wrapper.getBody();
            
            // 记录请求体（截断过长内容）
            String bodyPreview = body.length() > 500 ? body.substring(0, 500) + "..." : body;
            log.info("=== MCP Message Request Body ({} bytes) ===\n{}", body.length(), bodyPreview);
            
            chain.doFilter(wrapper, response);
        } else {
            chain.doFilter(request, response);
        }
    }

    /**
     * 缓存请求体的 HttpServletRequestWrapper
     */
    static class CachedBodyRequestWrapper extends HttpServletRequestWrapper {
        private final byte[] cachedBody;

        public CachedBodyRequestWrapper(HttpServletRequest request) throws IOException {
            super(request);
            this.cachedBody = request.getInputStream().readAllBytes();
        }

        @Override
        public ServletInputStream getInputStream() {
            return new CachedBodyServletInputStream(this.cachedBody);
        }

        @Override
        public BufferedReader getReader() {
            return new BufferedReader(new InputStreamReader(
                    new ByteArrayInputStream(this.cachedBody), StandardCharsets.UTF_8));
        }

        public String getBody() {
            return new String(cachedBody, StandardCharsets.UTF_8);
        }
    }

    static class CachedBodyServletInputStream extends ServletInputStream {
        private final ByteArrayInputStream buffer;

        public CachedBodyServletInputStream(byte[] cachedBody) {
            this.buffer = new ByteArrayInputStream(cachedBody);
        }

        @Override
        public int read() throws IOException {
            return buffer.read();
        }

        @Override
        public boolean isFinished() {
            return buffer.available() == 0;
        }

        @Override
        public boolean isReady() {
            return true;
        }

        @Override
        public void setReadListener(ReadListener readListener) {
            // no-op
        }
    }
}
