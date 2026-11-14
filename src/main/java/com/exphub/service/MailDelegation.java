package com.exphub.service;

import java.util.LinkedHashSet;
import java.util.Set;

/**
 * 信箱代发（{@code onBehalfOf}）：青鸟阁派单信以「越哥」名义发出的身份前置（2026-11-14 阁主 W-20261114-09）。
 *
 * <h3>为什么不是「把 from_role 改成越哥」</h3>
 * <ol>
 *   <li><b>回信会死</b>：回信的收件人取父消息发件人（{@code MailboxService.sendDetailed} 的 inReplyTo 分支，
 *       且刻意不校验 ACTIVE 名单，防止老线程卡死）。父消息若 {@code from_role=越哥}，
 *       收件哨兵的回信就写成 {@code to_role=越哥} —— 越哥没有信箱，回执静默丢失。</li>
 *   <li><b>审计会断</b>：分不清「越哥亲手发的」与「阁主代发的」。代发必须留痕。</li>
 *   <li><b>历史会漂</b>：历史信的 {@code from_role} 已经写着阁主，「越哥」只能是新字段。</li>
 * </ol>
 * 所以真实发件人永远是阁主，{@code on_behalf_of} 只承担展示与追责。
 *
 * <h3>代发权的边界（写死的规则，别当成鉴权）</h3>
 * ExpHub 的入口鉴权是<b>共享 API Key</b>，角色来自客户端配置的 {@code x-exphub-role} 头（#1540 实测），
 * 服务端<b>没有</b>按角色的凭据。因此这里能做到的是：
 * <ul>
 *   <li><b>默认拒绝</b>：声明角色不是阁主 → 明拒，一封都不入库；</li>
 *   <li><b>第二道弱锁</b>：配置来源必须命中允许前缀（默认 {@code cfg-gezhu-}）——
 *       它同样是自报头，只能挡住「别的哨兵配错角色」这类真实误用，<b>挡不住改头的人</b>；</li>
 *   <li><b>双身份留痕</b>：真实发件人 + 代发对象 + 配置来源三样都入库，事后能追。</li>
 * </ul>
 * 要做到密码学意义上的身份证明，必须给阁主独立 API Key（凭证变更，需越哥批），不在这套代码里。
 * <p>⚠️ 口径（2026-11-14 阁主 W-20261114-09 验收第 1 条）：任何文档/注释/回执里<b>都不许</b>写成
 * 「鉴权已就位」「只有忆阁能代发」——这套规则挡的是配错角色/配错配置来源这类真实误用，挡不住改 http 头的人。
 */
public final class MailDelegation {

    /** 唯一允许发起代发的角色。 */
    public static final String DELEGATOR = "阁主";

    /** 目前唯一允许被代发的名义（越哥本人）。允许清单写死在代码里，不做成「任意名字」。 */
    public static final String PRINCIPAL = "越哥";

    /** 允许的代发对象（越哥）。刻意不给配置入口：多一个名字就多一条冒充面。 */
    private static final Set<String> ALLOWED_PRINCIPALS = Set.of(PRINCIPAL);

    private MailDelegation() {
    }

    public static boolean allowedPrincipal(String principal) {
        return principal != null && ALLOWED_PRINCIPALS.contains(principal.trim());
    }

    /**
     * 展示用发件人：有代发时写「越哥（经阁主代发）」，否则原样返回真实角色。
     *
     * <p>⚠️ 2026-11-14 阁主 W-20261114-09 验收第 2 条：展示串**先保留**「越哥（经阁主代发）」，
     * 不改裸显示 —— 裸「越哥」是越哥本人的产品要求，得他点头。括号里是真实发件人，
     * 回信才能回到能处理的人。所以本方法**任何分支都不许退化成裸代发对象**：
     * 真实发件人缺失时写明「发件人未记录」，而不是只印「越哥」。
     */
    public static String display(String realFrom, String onBehalfOf) {
        if (onBehalfOf == null || onBehalfOf.isBlank()) return realFrom;
        if (realFrom == null || realFrom.isBlank()) return onBehalfOf + "（代发·发件人未记录）";
        return onBehalfOf + "（经" + realFrom + "代发）";
    }

    /** 非阁主试图代发：明拒并说清判定依据，不静默改写成「以自己名义发」。 */
    public static String rejectNotDelegator(String from, String target, String configSource) {
        return "代发被拒（未发出任何信）：只有【" + DELEGATOR + "】能以【" + target + "】的名义发信"
                + "（按声明角色判定），而本次调用的声明角色是【" + from + "】"
                + (configSource == null || configSource.isBlank() ? "（没有配置来源）" : "（配置来源 " + configSource + "）")
                + "。声明角色来自客户端配置的 " + "x-exphub-role" + " 头，它是自报的、不是鉴权；"
                + "ExpHub 能做的是「默认拒绝 + 双身份留痕」，做不到密码学意义上的身份证明。"
                + "要真正隔离，必须给阁主独立 API Key（凭证变更，需要越哥定）。"
                + "在那之前请以自己名义发。";
    }

    /** 代发对象不在允许清单：明拒，不静默改成阁主或越哥。 */
    public static String rejectUnknownPrincipal(String raw) {
        return "代发被拒（未发出任何信）：代发对象只允许【" + PRINCIPAL + "】，"
                + "ExpHub 实际收到的是「" + raw + "」。这不是「任意名字都能代发」的通用通道 —— "
                + "允许清单写死在代码里，免得它变成冒充其它角色的入口。";
    }

    /** 代发缺配置来源：明拒。没有「哪份配置发的」，事后就分不清是谁干的。 */
    public static String rejectMissingConfigSource() {
        return "代发被拒（未发出任何信）：代发必须带配置来源（请求头 x-exphub-config-source）。"
                + "代发是审计动作，没有「这份配置来自哪台机器」，事后无法追责。";
    }

    /**
     * 配置来源不在允许前缀里：明拒并给出要改的配置项，不静默放行。
     *
     * <p>⚠️ 措辞口径（2026-11-14 阁主 W-20261114-09 验收第 1 条）：这里<b>不许</b>写
     * 「只有忆阁能代发」。它挡的是「别的哨兵配错角色头/配错配置来源」这类真实误用，
     * 配置来源是客户端自报头，改头的人照样能填 {@code cfg-gezhu-*}。写成「只有…能」就是把弱锁
     * 说成了鉴权，读的人会以为有隔离（见 {@link #rejectNotDelegator}）。
     */
    public static String rejectConfigSource(String configSource, String allowedPrefixes) {
        return "代发被拒（未发出任何信）：代发只接受配置来源前缀 " + allowedPrefixes + " 的调用，"
                + "而本次配置来源是「" + configSource + "」，不在允许前缀 " + allowedPrefixes + " 里。"
                + "若忆阁换了配置名，请把新前缀加进 exphub.mail.delegation.config-source-prefixes 再部署"
                + "（不在这里静默放行 —— 那等于把这道检查也拆了）。"
                + "⚠️ 这是**配置归属检查，不是鉴权**：配置来源是客户端自报头，改头的人照样能填 "
                + "cfg-gezhu-*。它挡的是配错配置这类误用，挡不住有意冒充。";
    }

    /** 把逗号分隔的前缀配置解析成集合（空项忽略、去空白、保留原大小写后按小写比较）。 */
    public static Set<String> parsePrefixes(String csv) {
        Set<String> out = new LinkedHashSet<>();
        if (csv == null) return out;
        for (String p : csv.split(",")) {
            String t = p.trim();
            if (!t.isEmpty()) out.add(t.toLowerCase());
        }
        return out;
    }
}
