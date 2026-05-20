# MCP 连接问题排查记录

**问题时间**：2026-05-19  
**问题描述**：部署后 MCP SSE 端点连接失败，CodeBuddy 连接时重定向到登录页

---

## 问题现象

1. 访问 `/mcp/sse` 被 Spring Security 重定向到 `/login` 页面
2. 即使添加了静态资源放行，问题依然存在
3. MCP SSE 端点返回 404

---

## 根本原因

### 原因一：Spring Security 配置问题

`SecurityConfig` 中配置了 `securityMatcher("/**")` 匹配所有请求，且 `/error` 端点未在 `permitAll()` 列表中。

### 原因二：配置属性名错误

`application.yml` 中使用了错误的 MCP 配置属性名：

```yaml
# 错误写法
spring:
  ai:
    mcp:
      server:
        sse:
          sse-endpoint: /mcp/sse          # ❌ 错误
          sse-message-endpoint: /mcp/message  # ❌ 错误

# 正确写法
spring:
  ai:
    mcp:
      server:
        sse-endpoint: /mcp/sse            # ✅ 正确
        sse-message-endpoint: /mcp/message   # ✅ 正确
```

---

## 解决方案

### 1. 修复 SecurityConfig.java

```java
@Bean
public SecurityFilterChain adminFilterChain(HttpSecurity http) throws Exception {
    http
        .securityMatcher("/**")
        .authorizeHttpRequests(authorize -> authorize
            .requestMatchers("/login", "/").permitAll()
            .requestMatchers("/css/**", "/js/**", "/images/**").permitAll()
            .requestMatchers("/mcp/**", "/api/**").permitAll()  // 添加 MCP 放行
            .requestMatchers("/error").permitAll()              // 添加 error 放行
            .anyRequest().authenticated()
        )
        // ... 其他配置
        .csrf(csrf -> csrf.disable());
    return http.build();
}
```

### 2. 修复 application.yml

```yaml
spring:
  ai:
    mcp:
      server:
        name: exphub
        version: 1.0.0
        type: SYNC
        stdio: false
        sse-endpoint: /mcp/sse
        sse-message-endpoint: /mcp/message
```

---

## 调试过程

1. **检查 SecurityConfig**：发现 `/mcp/**` 未在 permitAll 列表中
2. **添加 MCP 路径放行**：仍然失败，因为 `/error` 端点也被拦截
3. **添加 /error 放行**：SSE 端点可以访问，但返回 404
4. **检查 application.yml**：发现配置属性名错误
5. **修正属性名**：使用正确的 `sse-endpoint` 和 `sse-message-endpoint`
6. **本地测试通过**：SSE 端点正常返回 eventstream

---

## 经验总结

1. **Spring Security 配置要点**：
   - `securityMatcher("/**")` 会拦截所有请求，包括 `/error`
   - 开放 API（如 MCP、SSE）必须显式添加到 `permitAll()`
   - MCP SSE 协议需要 `/error` 端点也能访问

2. **Spring AI MCP 配置要点**：
   - 配置属性是 `sse-endpoint` 和 `sse-message-endpoint`（不是嵌套的 `sse.sse-*`）
   - 端点路径不需要前缀（如 `/mcp/sse`，而不是 `/exphub/mcp/sse`）
   - Spring AI MCP 1.0.0 版本兼容 Spring Boot 3.2.x

3. **Nginx 反向代理要点**：
   - 配置 `proxy_set_header Host $Host;` 不能带端口号
   - 如果带 `$server_port`，Spring 会重定向到 `http://host:443`

4. **Spring Boot 3.x 额外配置**：
   - 添加 `server.forward-headers-strategy=native`
   - 确保代理头正确传递

---

## 相关文件

- `src/main/java/com/exphub/config/SecurityConfig.java`
- `src/main/resources/application.yml`
- `src/main/java/com/exphub/mcp/McpServerConfig.java`
