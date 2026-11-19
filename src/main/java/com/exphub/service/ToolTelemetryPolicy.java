package com.exphub.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 「哪些工具**不写逐次埋点**」的口径 —— 唯一出处（2026-11-19 越哥）。
 *
 * <p><b>为什么要有这个开关</b>：{@code dispatch_list} 是哨兵每回合开工第一步，
 * 近 7 天调了 <b>45,120 次</b>（占「排除历史信箱轮询后」全部工具调用的 82%），
 * 其它工具在榜上被压成 1% 以下的噪音，「成功率 / 占比 / 趋势」这些对比彻底失去意义。
 * 越哥原话：**不需要统计埋点了**。
 *
 * <p><b>为什么是「不写行」而不是「写了再排除」</b>：信箱轮询那套（{@link ToolPollPolicy}）
 * 必须保留行，因为同一工具里还混着「真实读信」这种有价值的调用，只能按行分。
 * {@code dispatch_list} 没有这种混合语义 —— 它的每一次调用都不携带判断价值，
 * 留着 4.5 万行 / 周只是让表变大、让聚合扫描变慢。**没有信息量的行，最好一开始就别写。**
 *
 * <p><b>三处必须同时生效</b>（少一处就会出现自相矛盾的回执）：
 * <ol>
 *   <li>{@code ToolCallAspect}：不写 {@code call_logs} 行；</li>
 *   <li>{@code ToolPollPolicy.EXCLUDE_POLL_SQL}：**历史行**（开关上线前的埋点）也不计入任何聚合；</li>
 *   <li>看板 / {@code get_tool_stats}：不把它算进「从未被调用」清单 —— 否则一个每回合都调的工具
 *       会被列成「精简候选」，那是彻底的误导。</li>
 * </ol>
 *
 * <p>配置：{@code exphub.tool-stats.skip-telemetry}（逗号分隔），默认 {@code dispatch_list}；
 * 改成空串 = 恢复该工具的埋点。
 */
@Component
public class ToolTelemetryPolicy {

    /** 默认不写埋点的工具（与 application.yml 的默认值一致，改一处要两处一起改）。 */
    public static final String DEFAULT_SKIP = "dispatch_list";

    private final List<String> skipped;

    public ToolTelemetryPolicy(@Value("${exphub.tool-stats.skip-telemetry:" + DEFAULT_SKIP + "}") String raw) {
        this.skipped = parse(raw);
    }

    /**
     * 纯函数解析：逗号分隔、去空白、丢空项。
     *
     * <p>{@code null} = 「没有这个配置项」→ 用默认名单（{@link #DEFAULT_SKIP}）；
     * <b>空串是有意的</b>：{@code skip-telemetry: ""} 表示「谁都别跳过」= 恢复埋点。
     * 这两种情况必须区分 —— 把它们混成一个，就会出现「想恢复埋点却恢复不了」或反之。
     */
    static List<String> parse(String raw) {
        if (raw == null) {
            return List.of(DEFAULT_SKIP);
        }
        if (raw.isBlank()) {
            return List.of();
        }
        List<String> out = new ArrayList<>();
        for (String part : raw.split(",")) {
            String p = part.trim();
            if (!p.isEmpty() && !out.contains(p)) {
                out.add(p);
            }
        }
        return Collections.unmodifiableList(out);
    }

    /** 这个工具的调用是否**不写**埋点（写行这一层就跳过，不是写了再排除）。 */
    public boolean isSkipped(String toolName) {
        return toolName != null && skipped.contains(toolName);
    }

    /** 当前不写埋点的工具清单（只读，供回执/页面说明）。 */
    public List<String> skipped() {
        return skipped;
    }
}
