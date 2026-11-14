package com.exphub.mcp;

import com.exphub.service.CallLogService;
import com.exphub.service.ToolPollPolicy;
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
        Integer isPoll = null;
        boolean success = true;
        try {
            toolName = resolveToolName(pjp);
            // 必须在 proceed 之前取参数：这里只读，发出去的仍是调用方原值
            isPoll = safeResolvePollFlag(toolName, pjp);
            return pjp.proceed();
        } catch (Throwable e) {
            success = false;
            throw e;
        } finally {
            try {
                if (toolName != null) {
                    callLogService.logToolCall(toolName, success, System.currentTimeMillis() - start, isPoll);
                }
            } catch (Exception ignored) {
                // 埋点失败不影响工具返回；只留 debug，避免刷 error 日志
                log.debug("ToolCallAspect: 埋点写入失败 tool={} err={}", toolName, ignored.getMessage());
            }
        }
    }

    /**
     * 解析 is_poll。**任何异常都不能上抛**——埋点是副作用，解析失败只落 NULL。
     *
     * @return 1=哨兵轮询（mail_start 且 unreadOnly=true）；0=真实调用（其余一切）；
     *         null=mail_start 但参数名解析失败（与历史行同语义：统计排除）
     */
    private Integer safeResolvePollFlag(String toolName, ProceedingJoinPoint pjp) {
        if (!ToolPollPolicy.isPollExcludedTool(toolName)) {
            return 0;
        }
        try {
            MethodSignature signature = (MethodSignature) pjp.getSignature();
            Integer flag = resolvePollFlag(toolName, signature.getParameterNames(), pjp.getArgs());
            if (flag == null) {
                log.debug("ToolCallAspect: mail_start 的 unreadOnly 参数解析失败，is_poll 落 NULL（该行不计入统计）");
            }
            return flag;
        } catch (Exception e) {
            log.debug("ToolCallAspect: 解析轮询标记失败 tool={} err={}", toolName, e.getMessage());
            return null;
        }
    }

    /** 纯函数版本，供单测直接钉「哪些入参算轮询」；不依赖切面上下文 */
    static Integer resolvePollFlag(String toolName, String[] parameterNames, Object[] args) {
        if (toolName == null) {
            return null;
        }
        if (!ToolPollPolicy.isPollExcludedTool(toolName)) {
            return 0;
        }
        if (parameterNames == null || args == null || parameterNames.length != args.length) {
            return null;
        }
        for (int i = 0; i < parameterNames.length; i++) {
            if ("unreadOnly".equals(parameterNames[i])) {
                return Boolean.TRUE.equals(args[i]) ? 1 : 0;
            }
        }
        return null;
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
