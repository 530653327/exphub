package com.exphub.mcp;

/**
 * 当前请求的「声明角色 + 配置来源」。
 *
 * <p><b>这不是鉴权。</b>网关只认 API Key（共享一把），角色和配置来源来自客户端配置里
 * 写死的请求头，服务端无从核对「你是不是真的烛龙」。它只做两件事：
 * 给信件贴收件人标签、串信时指路去改哪份配置。
 *
 * <p>为什么不走工具参数：那样等于让模型临场自报，模型可以忘、可以编，
 * 还会在 stateless 会话里存不住。放请求头里，由客户端配置层固定注入，
 * 模型碰不到（#1324 C118/C119）。
 */
public final class MailIdentity {

    /**
     * 客户端配置里写死的角色，如「烛龙」/ 白泽。
     *
     * <p>2026-11-09 起<b>正名是汉字</b>；拼音（zhulong / baize / gezhu）仍兼容，
     * 服务端入口统一归一到汉字再入库/输出（阁主 T261109054938-679）。
     */
    public static final String HEADER_ROLE = "x-exphub-role";

    /**
     * 配置来源标识，如 cfg-zhulong-mac-01。
     *
     * <p>⚠️ 必须人工保证唯一：九份配置如果是从模板复制出来的，实例 ID 一样，
     * 「哪份配置配错了」这条就查不出来，等于把 Key 尾号那个坑原地重挖（#1324 C120）。
     */
    public static final String HEADER_CONFIG_SOURCE = "x-exphub-config-source";

    private static final ThreadLocal<String> ROLE = new ThreadLocal<>();
    private static final ThreadLocal<String> CONFIG_SOURCE = new ThreadLocal<>();

    private MailIdentity() {
    }

    public static void set(String role, String configSource) {
        if (role == null || role.isBlank()) {
            ROLE.remove();
        } else {
            ROLE.set(role.trim());
        }
        if (configSource == null || configSource.isBlank()) {
            CONFIG_SOURCE.remove();
        } else {
            CONFIG_SOURCE.set(configSource.trim());
        }
    }

    public static String role() {
        return ROLE.get();
    }

    public static String configSource() {
        return CONFIG_SOURCE.get();
    }

    public static void clear() {
        ROLE.remove();
        CONFIG_SOURCE.remove();
    }
}
