package com.exphub.mcp;

import com.exphub.service.CallLogService;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.reflect.MethodSignature;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.lang.reflect.Method;

/**
 * MCP 工具调用埋点切面。
 *
 * 为什么用 AOP 而不是在 25 个 @Tool 方法里逐个埋点：
 * 逐个改要动 25 处、以后新增工具极易漏；切面只此一处，新工具自动覆盖。
 *
 * 为什么切「ExpHubTools 的所有 public 方法」而不是 @annotation(Tool)：
 * 兼容性考虑——只要代理生效就能拦到，方法上没有 @Tool 注解时 resolveToolName 返回 null 直接跳过，
 * 不会误记内部方法。
 *
 * ⚠️ 两条铁律：
 * 1. 埋点异常绝不能影响业务——finally 里单独 try-catch 吞掉，工具该返回什么就返回什么。
 * 2. 埋点只写 action=TOOL 的独立行，不碰 ai_assistants.total_calls（业务方法已累加，避免重复计数）。
 */
@Aspect
@Component
public class ToolCallAspect {

    private static final Logger log = LoggerFactory.getLogger(ToolCallAspect.class);

    @Autowired
    private CallLogService callLogService;

    @Around("execution(public * com.exphub.mcp.ExpHubTools.*(..))")
    public Object aroundToolCall(ProceedingJoinPoint pjp) throws Throwable {
        long start = System.currentTimeMillis();
        String toolName = null;
        boolean success = true;
        try {
            toolName = resolveToolName(pjp);
            return pjp.proceed();
        } catch (Throwable e) {
            success = false;
            throw e;
        } finally {
            try {
                if (toolName != null) {
                    callLogService.logToolCall(toolName, success, System.currentTimeMillis() - start);
                }
            } catch (Exception ignored) {
                // 埋点失败不影响工具返回；只留 debug，避免刷 error 日志
                log.debug("ToolCallAspect: 埋点写入失败 tool={} err={}", toolName, ignored.getMessage());
            }
        }
    }

    /** 从方法上的 @Tool 注解取工具名；非工具方法返回 null（不记录） */
    private String resolveToolName(ProceedingJoinPoint pjp) {
        try {
            MethodSignature signature = (MethodSignature) pjp.getSignature();
            Method method = signature.getMethod();
            Tool tool = method.getAnnotation(Tool.class);
            if (tool != null && tool.name() != null && !tool.name().isEmpty()) {
                return tool.name();
            }
            // 代理场景下 method 可能是接口方法，回退到目标类同名方法
            Method target = pjp.getTarget().getClass().getMethod(method.getName(), method.getParameterTypes());
            Tool fallback = target.getAnnotation(Tool.class);
            return (fallback != null) ? fallback.name() : null;
        } catch (Exception e) {
            log.debug("ToolCallAspect: 解析工具名失败 {}", e.getMessage());
            return null;
        }
    }
}
