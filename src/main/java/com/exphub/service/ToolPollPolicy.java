package com.exphub.service;

/**
 * 工具统计的「哨兵轮询排除」口径 —— **唯一出处**。
 *
 * <p>背景（阁主 W-20261115-17）：十几个信箱哨兵不停调 {@code mail_start(unreadOnly=true)}，
 * 7 天 19.8 万次，占全部工具调用的 83%，把其它工具的使用榜/成功率/趋势全淹了。
 * 越哥要的是「不要这个工具的统计」，但真实读信（{@code mail_start} 默认视图、
 * 面板翻页）仍然是有效调用，不能一并抹掉 —— 所以按<b>行</b>标记，而不是按工具名一刀切。
 *
 * <p><b>is_poll 的三种值</b>：
 * <ul>
 *   <li>{@code 1} = 哨兵轮询（{@code unreadOnly=true}），统计里排除；</li>
 *   <li>{@code 0} = 真实调用（含 {@code mail_start} 的普通读信），照常计入；</li>
 *   <li>{@code NULL} = 迁移前的历史行，无法区分轮询与真实读信，<b>不回填</b>。</li>
 * </ul>
 *
 * <p><b>为什么 NULL 也排除</b>：迁移后 7 天窗口里仍有 19 万行历史 {@code mail_start}，
 * 若只排除 {@code is_poll=1}，榜一还是它，验收「榜上不再出现 mail_start」不可能成立；
 * 而按工具名把它从榜上删掉又会连带丢掉真实读信。取「未标记为真实 = 不计入统计」，
 * 代价是历史真实读信也一并排除，所以回执必须把「历史未分类」单列出来，不混进「轮询」数字里。
 *
 * <p><b>硬约束</b>：所有聚合（使用榜、占比、趋势、总调用、成功率、平均耗时、最后调用、
 * 零调用差集）都必须拼 {@link #EXCLUDE_SQL}，页面与 {@code get_tool_stats} 共用
 * {@link #explain} 的同一句话。这里改口径，两处同时变。
 */
public final class ToolPollPolicy {

    /** 被按行排除轮询的工具名（已于 2026-11-16 随信箱功能下线，这里只为挡历史行） */
    public static final String MAIL_START = "mail_start";

    /**
     * 整体不计入统计的工具名（2026-11-19 越哥）。
     *
     * <p>与 {@link #MAIL_START} 的区别：mail_start 只能**按行**排（同一工具里还混着真实读信）；
     * dispatch_list 的每次调用都没有判断价值，所以是**整体**排除 —— 新调用由
     * {@link ToolTelemetryPolicy} 挡在写埋点之前，这里是挡开关上线前的**历史行**。
     */
    public static final String DISPATCH_LIST = "dispatch_list";

    /**
     * SQL 片段：**保留**「算进统计」的行。
     *
     * <p>三条口径合在一起：
     * <ul>
     *   <li>{@code mail_start}：只留 {@code is_poll=0}（真实读信），轮询与迁移前未分类都排掉；</li>
     *   <li>{@code dispatch_list}：整体排掉（历史行仍在表里，但新埋点已经不写了，
     *       这个数会随窗口推移自然降到 0）；</li>
     *   <li>其余工具与业务行（{@code tool_name IS NULL}）一律保留。</li>
     * </ul>
     *
     * <p>必须是编译期常量，MyBatis {@code @Select} 注解里才能拼接。
     *
     * <p>⚠️ 不能写成 {@code NOT (tool_name='mail_start' AND ...)}：业务行（SEARCH/CREATE…）
     * 的 {@code tool_name} 是 NULL，{@code NULL = 'mail_start'} 结果是 NULL，
     * 整条 {@code NOT(...)} 也是 NULL，WHERE 会把**全部业务行**一并过滤掉——
     * 仪表盘的业务动作统计会瞬间归零，而且不报错。所以用正向条件，
     * 先把 {@code tool_name IS NULL} 放最前面短路。
     */
    public static final String EXCLUDE_POLL_SQL =
            "(tool_name IS NULL"
                    + " OR tool_name NOT IN ('" + MAIL_START + "', '" + DISPATCH_LIST + "')"
                    + " OR (tool_name = '" + MAIL_START + "' AND is_poll = 0))";

    private ToolPollPolicy() {
    }

    /** 这个工具是否属于「只按行排除轮询」的工具（目前只有 mail_start） */
    public static boolean isPollExcludedTool(String toolName) {
        return MAIL_START.equals(toolName);
    }

    /** 这个工具是否**整体**不计入统计（目前只有 dispatch_list） */
    public static boolean isStatsExcludedTool(String toolName) {
        return DISPATCH_LIST.equals(toolName);
    }

    /**
     * 页面与 MCP 工具共用的口径说明。**同一句话只在这里生成** ——
     * 两处各写一份，迟早出现「页面说排除 19 万、工具说 0」。
     *
     * @param polls        窗口内明确标记为轮询（is_poll=1）的调用数
     * @param realCalls    窗口内明确标记为真实读信（is_poll=0）的调用数
     * @param unclassified 窗口内 deploy 前历史未分类（is_poll IS NULL）的调用数，也计入排除
     * @param dispatchCalls 窗口内 dispatch_list 的**历史**埋点数（2026-11-19 起不再写新行）
     * @param dispatchLastAt 窗口内 dispatch_list 最后一次调用时间（已格式化为 MM-dd HH:mm），没有则空
     * @param days         统计窗口天数
     */
    public static String explain(long polls, long realCalls, long unclassified,
                                 long dispatchCalls, String dispatchLastAt, int days) {
        StringBuilder sb = new StringBuilder();
        long excluded = polls + unclassified;
        sb.append("近 ").append(days).append(" 天已排除 mail_start 调用 ").append(excluded).append(" 次");
        if (unclassified > 0) {
            if (polls > 0) {
                sb.append("（其中哨兵轮询 ").append(polls).append(" 次、部署前未分类 ")
                  .append(unclassified).append(" 次；未分类不回填，无法区分当时是轮询还是读信）");
            } else {
                sb.append("（均为部署前未分类：迁移不回填，无法区分当时是轮询还是读信）");
            }
        }
        sb.append("；真实读信 ").append(realCalls).append(" 次仍计入。");

        // dispatch_list：整体不计统计。这个数是**历史行**，会随窗口推移归零 ——
        // 不写清这一点，读的人会以为「怎么还在排除」。
        sb.append(" dispatch_list 整体不计统计：");
        if (dispatchCalls > 0) {
            sb.append("窗口内历史埋点 ").append(dispatchCalls).append(" 次");
            if (dispatchLastAt != null && !dispatchLastAt.isBlank()) {
                sb.append("（最后一次 ").append(dispatchLastAt).append("）");
            }
            sb.append("；2026-11-19 起不再写新埋点，所以这个数会随时间降到 0，不是漏统计。");
        } else {
            sb.append("窗口内已无历史埋点（2026-11-19 起不再写新行）。");
        }
        return sb.toString();
    }
}
