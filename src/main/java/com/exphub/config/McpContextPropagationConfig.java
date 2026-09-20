package com.exphub.config;

import com.exphub.entity.AiAssistant;
import com.exphub.interceptor.ApiKeyInterceptor;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Configuration;
import reactor.core.scheduler.Schedulers;

/**
 * MCP 身份上下文「跨线程传播」配置
 *
 * <p><b>问题背景</b>：Spring AI 的 MCP server 内部使用 Reactor 的 {@code boundedElastic}
 * 线程池执行同步工具方法。而 API Key 鉴权发生在 Tomcat 请求线程（{@code nio-3099-exec-N}），
 * 身份的 ThreadLocal 无法传递到 {@code boundedElastic} 线程：
 * <ul>
 *   <li>工具执行时 {@code getCurrentAssistant()} 可能为 null（隔离校验被跳过）</li>
 *   <li>更糟的是读到<b>别的请求残留的身份</b> —— {@code InheritableThreadLocal} 只在
 *       「线程被创建」时继承一次，线程池线程复用后值不再更新，导致多 API Key 之间串号</li>
 * </ul>
 *
 * <p><b>解决</b>：注册 Reactor 调度钩子。任务被<b>调度</b>时仍在请求线程（ThreadLocal 持有
 * 正确身份），此刻捕获该身份；在任务<b>实际执行</b>的线程（boundedElastic）上恢复，
 * 执行完毕再还原。这样无论工具在哪个线程跑，读到的都是「发起该次调用」的身份。
 *
 * <p>仅依赖 Reactor 自带 API，无需引入第三方依赖。
 */
@Configuration
public class McpContextPropagationConfig {

    private static final Logger log = LoggerFactory.getLogger(McpContextPropagationConfig.class);

    private static final String HOOK_KEY = "exphub-assistant-context";

    @PostConstruct
    public void registerThreadLocalPropagation() {
        Schedulers.onScheduleHook(HOOK_KEY, runnable -> {
            // ① 调度时（请求线程）捕获身份
            final AiAssistant captured = ApiKeyInterceptor.getCurrentAssistant();
            if (captured == null) {
                return runnable;
            }
            // ② 执行时（boundedElastic 线程）恢复身份
            return () -> {
                final AiAssistant previous = ApiKeyInterceptor.getCurrentAssistant();
                ApiKeyInterceptor.setCurrentAssistant(captured);
                try {
                    runnable.run();
                } finally {
                    // ③ 执行完毕还原，避免污染线程池里的其他任务
                    if (previous != null) {
                        ApiKeyInterceptor.setCurrentAssistant(previous);
                    } else {
                        ApiKeyInterceptor.removeCurrentAssistant();
                    }
                }
            };
        });
        log.info("MCP 身份上下文跨线程传播已启用（Reactor onScheduleHook={}）", HOOK_KEY);
    }
}
