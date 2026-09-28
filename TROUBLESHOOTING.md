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

---
---

# 二、MCP 身份串号（多 API Key 隔离失效）

**问题时间**：2026-09-20
**问题描述**：非归属方 API Key 能读到归属方的项目与经验（list 和 get 都能），且**随调用顺序变化**，看起来像随机故障。

## 现象

用 Key A 建的项目，用 Key B 也能 `list_projects` / `get_project` 看到；换个调用顺序有时又正常。

## 根本原因

- API Key 鉴权发生在 **Tomcat 线程**（`nio-3099-exec-N`），身份写入 `InheritableThreadLocal`
- 但 Spring AI 的 MCP server 内部使用 **Reactor `boundedElastic` 线程池**执行同步工具方法
- **`InheritableThreadLocal` 只在「线程被创建」时从父线程继承一次** —— 池化线程复用后值**永不更新**，
  于是工具永远读到「创建该线程那一刻」的身份 → **固定串号**（比读到 null 更危险：null 会报错，串号会安静地泄漏数据）

## 排查口诀（关键）

在业务代码里打印**业务线程名 + 读到的身份**，与鉴权日志的线程名对比，一眼看穿：

```
[nio-3099-exec-1] validateApiKey: assistant set, assistantId=assistant-058822bb     ← 鉴权（Tomcat 线程）
[boundedElastic-1] currentKey: thread=boundedElastic-1, assistant=assistant-058822bb  ← 业务执行！
[nio-3099-exec-8] validateApiKey: assistant set, assistantId=assistant-81aa1a1d      ← 换 Key 后鉴权
[boundedElastic-1] currentKey: ...assistant=assistant-058822bb                       ← 仍读到上一个 ✗
```

## 解决方案

`config/McpContextPropagationConfig` 注册 Reactor 调度钩子 —— **调度时捕获、执行时恢复、完毕还原**：

```java
Schedulers.onScheduleHook("exphub-assistant-context", runnable -> {
    // ① 任务被「调度」时（仍在请求线程，ThreadLocal 持有正确身份）→ 捕获
    final AiAssistant captured = ApiKeyInterceptor.getCurrentAssistant();
    if (captured == null) return runnable;
    // ② 任务「实际执行」时（boundedElastic 线程）→ 恢复
    return () -> {
        final AiAssistant previous = ApiKeyInterceptor.getCurrentAssistant();
        ApiKeyInterceptor.setCurrentAssistant(captured);
        try { runnable.run(); }
        finally { /* ③ 还原，避免污染线程池里的其他任务 */ }
    };
});
```

## 经验总结

1. **`InheritableThreadLocal` + 线程池 = 永不更新**，必然串号（不是偶尔）
2. **换 Web 容器解决不了**（Servlet ↔ Netty 都无效）—— 工具终究跑在框架内部的 Reactor 线程上
3. 跨线程上下文传递优先用框架的调度钩子（Reactor `Schedulers.onScheduleHook`、TTL 的 `TtlExecutors`），不要让 `ThreadLocal` 裸奔

---

# 三、应用假死（进程活着但所有请求超时）

**问题时间**：2026-09-27
**问题描述**：exphub 连不上。进程在、端口在监听，但所有请求超时或被 RST。

## 现象

1. 进程存活、`3099` 端口正常监听（`netstat` 可见）
2. 但**所有 HTTP 请求超时**（curl 返回 `HTTP 000`）或立即被重置
3. **应用日志停在几天前**（不再产生新日志）
4. Nginx `error.log`：
   `recv() failed (104: Connection reset by peer) while reading response header from upstream, upstream: "http://127.0.0.1:3099/exphub/mcp/sse"`

## 根本原因

**MCP 使用了 Servlet(WebMvc) transport** → **每个 SSE 长连接占用一个 Tomcat 工作线程且长期不释放** →
客户端重连 / 连接堆积耗尽线程池（默认 200 个）→ 整个应用假死。

## 定位方法

```bash
PID=$(pgrep -f exphub-1.0.0.jar)
# 线程状态分布
/opt/jdk-21.0.2/bin/jstack $PID | grep -oE 'java.lang.Thread.State: [A-Z_]+' | sort | uniq -c | sort -rn
# Tomcat 工作线程数（若 = 200 即上限，说明耗尽）
/opt/jdk-21.0.2/bin/jstack $PID | grep -c 'http-nio-3099-exec'
# SSE 相关栈帧数量
/opt/jdk-21.0.2/bin/jstack $PID | grep -cE 'SseEmitter|WebMvcSse|SseServerTransport'
```

本次实测结果：`http-nio-3099-exec` = **200/200**、线程状态 **204 WAITING**、SSE 相关帧 **400**。

## 解决方案

**恢复架构：MCP 必须使用独立 WebFlux(Netty:3098)，不要用 Servlet。**

| 项 | 配置 |
|---|---|
| pom | `spring-ai-starter-mcp-server-webflux` |
| 服务 | `McpReactiveServerConfig`：独立 Netty 3098 + API Key 鉴权 filter |
| application.yml | 排除 `McpWebFluxServerAutoConfiguration`（手动搭建该服务器） |
| Nginx | `location /exphub/mcp/` → `http://127.0.0.1:3098/mcp/`（关闭 buffering） |
| 额外 | **不需要** `/mcp/` 独立 location（WebFlux 版返回的 endpoint 已含 `/exphub` 前缀） |

## 经验总结

1. **SSE（长连接）+ Servlet 线程模型 = 线程池耗尽** —— 这是设计层面的不匹配，调线程数只是拖延，治本要换非阻塞模型
2. **应急恢复**：重启进程（清空线程池）可立即恢复；但**必须同时修架构**，否则会复发
3. Servlet 版还有个副作用：SSE 的 endpoint 事件返回路径**不含 context-path**（`/mcp/message`），
   必须额外加 `/mcp/` 反代 location 才能用 —— 这也是判断当前跑的是哪个版本的线索
4. 判断「服务是否真挂了」的顺序：**进程 → 端口 → 应用本地 curl → 日志时间戳 → jstack**，
   不要只看进程在就以为服务正常
