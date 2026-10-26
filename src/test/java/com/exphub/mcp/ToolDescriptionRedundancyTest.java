package com.exphub.mcp;

import org.junit.jupiter.api.Test;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;

import java.lang.reflect.Method;
import java.lang.reflect.Parameter;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 工具描述里「可压缩的冗余」必须为 0 —— 守卫 #674 验收口径 B。
 *
 * <p><b>为什么要有这个守卫</b>：#674 原验收标准是「描述总字数降幅 ≥50%」，但上一轮已把最肥的
 * description 压过一轮（5813→4739），剩下的全是参数语义/枚举值/丢数据/副作用这些**删不得**的信息，
 * 再压就得拆安全网。越哥 2026-10-26 15:3x 拍板口径 B：验收从「降 50%」改成
 * <b>「可压缩的冗余 100% 清除」</b>。
 *
 * <p><b>⚠️ 第一版这个守卫是失效的（2026-10-26 16:0x 白泽实测揪出，烛龙确认）</b><br>
 * 第一版用「字面子串匹配 + 固定禁词表」，结果：
 * <ul>
 *   <li>禁词表写「这是首选方式」，线上实际文本是「这是补充进展/追加结论的<b>首选方式</b>」
 *       → <b>子串不匹配，直接放过</b></li>
 *   <li>禁词表只有「建议先/建议传/建议用/建议统一用」，<b>没有裸「建议」</b>
 *       → 「建议 keepRecent=10」<b>放过</b></li>
 *   <li>白名单逻辑是「描述里只要出现任一白名单词就跳过全部检查」
 *       → 一个正常词能让整条描述免检</li>
 * </ul>
 * 拿第一版的 19 条禁词表扫线上真实 tools/list → 命中 0 条，但实际残留 2 处。
 * <b>「机器判定清零」在真实数据上抓不到东西 = 空话。</b>
 *
 * <p><b>这一版的三个修法</b>：
 * <ol>
 *   <li><b>用正则而不是字面子串</b> —— 「首选方式」写成 {@code .*首选方式}，
 *       无论前面插了什么字都命中</li>
 *   <li><b>禁词表从「词」改成「模式 + 豁免」</b> —— 每条规则带自己的豁免正则，
 *       不再是「全文出现任一白名单词就全免检」</li>
 *   <li><b>加反测（guardItselfMustCatchThePatternsItMissedBefore）</b> —— 把第一版漏掉的那两句
 *       （「这是补充进展/追加结论的首选方式」「建议 keepRecent=10」）写成断言，
 *       <b>守卫自己必须能抓到它们</b>。否则守卫和被它检查的对象一样不可信。</li>
 * </ol>
 *
 * <p><b>「可压缩的冗余」= 四类</b>（都不是 #674 红线第 4 条要保留的三类信息）：
 * <ol>
 *   <li><b>教学话术</b> —— 替 AI 下结论的话（「建议先…」「务必」「这是…的首选方式」「最好」「用来」）</li>
 *   <li><b>举例</b> —— 「如：xxx」「例如：xxx」；枚举值清单本身不算举例（那是参数语义，必须留）</li>
 *   <li><b>重复机制说明</b> —— 在别的工具里已经讲过一遍的机制（归档不是删除 / 两者等价 等）</li>
 *   <li><b>schema 已体现的赘述</b> —— 「不传=不改」（required=false 已经表达了）</li>
 * </ol>
 *
 * <p><b>必须保留（守卫不许误伤）</b>：参数语义、默认值、区间、必填、会不会丢数据、有没有副作用、
 * 作用域语义（projectId=0 是移回全局）、枚举值清单、跨工具判断规则。
 *
 * <p><b>数据源说明</b>：本守卫用反射读注解，与 Spring AI 生成 tools/list 的数据源同源
 * （{@code @Tool.description} / {@code @ToolParam.description}）。
 * 线上真实载荷的交叉验证由 {@code tools/tool_desc_measure.py verify} 完成
 * （用线上 tools/list JSON 逐条比对源码提取值，41/41 必须完全一致）——
 * 单测只能证明「源码里没有」，加上这一步才能证明「发给模型的载荷里也没有」。
 */
class ToolDescriptionRedundancyTest {

    /**
     * 规则 = {匹配正则, 为什么该删}。
     *
     * <p>⚠️ 写正则的三个坑（第一版就踩了）：
     * <ol>
     *   <li><b>不要用 {@code .*} 包裹</b> —— {@code find()} 本身就是子串搜索，
     *       加了 {@code .*} 反而会跨行跨句乱匹配（「建议 ≤1500 字」会被后面的字拖住）。
     *       规则越短越准。</li>
     *   <li><b>「建议」要用零宽否定预查</b> —— {@code 建议(?!\s*[≤<]?\s*\d)}
     *       表示「建议后面不能紧跟数字」：这样「建议 ≤1500 字」「建议 30 字内」
     *       （字数上限，属副作用信息）放行，「建议 keepRecent=10」「建议以小标题开头」命中。</li>
     *   <li><b>白名单不能是「全文出现任一词就全免检」</b> —— 第一版就是那样，
     *       一个正常词能让整条描述免检。现在豁免写在规则自身的正则里。</li>
     * </ol>
     */
    private static final String[][] RULES = {
            // —— 教学话术（替 AI 下结论）——
            {"建议先", "教学话术：替 AI 决定调用顺序"},
            {"建议传", "教学话术：替 AI 决定要不要传"},
            {"建议用", "教学话术：替 AI 决定用哪个工具"},
            {"建议统一用", "教学话术"},
            {"建议(?!\\s*[≤<]?\\s*\\d)", "教学话术：裸「建议」（「建议 ≤N 字」「建议 N 字内」是字数上限，属副作用，放行）"},
            {"务必", "教学话术"},
            {"请注意", "教学话术"},
            {"最好", "教学话术"},
            {"这是给", "教学话术"},
            {"让 AI 知道", "教学话术"},
            {"不要靠", "教学话术"},
            {"不必再", "教学话术"},
            {"不要当 bug 报给用户", "教学话术"},
            // 「首选方式」：不限定前缀，才能抓住「这是补充进展/追加结论的首选方式」
            {"首选方式", "教学话术：替 AI 决定该用哪个工具"},
            // —— 举例 ——
            // ⚠️ 2026-10-26 17:5x 修（阁主实跑发现「守卫绑了冒号、漏掉无冒号形态」）：
            //   原来只有 {"例如："} 和 {"如："} 两条 —— **带冒号**。
            //   线上真实载荷里还有 15 处**无冒号**形态：
            //     「按模板类型过滤，如 bug_fix、config_guide」「和哪条重复（如「与 ID:377…」）」
            //     「章节号，如'1'、'14'」「如 7 表示读取 v7」「如 since=today」…
            //   「线上载荷已无可压缩冗余」这句话当时不成立 —— 守卫只认自己写的那几个字面量。
            //
            //   现在改成**形态匹配**：「如/例如」后面跟冒号、空白或引号一律命中。
            //   负向前瞻 (?<!例) 排除「例如」被「如」这条重复命中。
            //   排除「诸如」「譬如」这类词（前面不是例）。
            {"(?<!例)(?<!譬)如[：:\\s「『（(\"']", "举例：无冒号形态也算（清单应在别处，不在描述里举例）"},
            {"例如[：:\\s「『（(\"']", "举例：清单应在别处，不在描述里举例"},
            // —— 重复机制说明 ——
            {"归档只是搬位置、不是删除", "重复机制：archive_old_versions 已讲过"},
            {"两者都传时以本参数为准", "重复机制：同段已说明等价"},
            // —— schema 已体现的赘述 ——
            {"不传=不改", "赘述：required=false 已经表达了"},
    };

    /**
     * 第一版守卫漏掉、但**必须能抓到**的两句。
     * 守卫自己必须先证明它抓得住，否则「机器判定清零」就是空话。
     */
    private static final String[] MUST_CATCH = {
            "在经验正文末尾追加一段内容。这是补充进展/追加结论的首选方式：服务端直接拼接。",
            "把超出保留窗口的历史版本搬进冷存档。用 list_archived_versions 回查。建议 keepRecent=10。",
            // ⚠️ 2026-10-26 17:5x 补：无冒号形态的举例（阁主实跑从线上载荷揪出 15 处）
            "可选。按模板类型过滤，如 bug_fix、config_guide",
            "可选。标记说明：为什么打、和哪条重复（如「与 ID:377 结论重复」）",
            "章节号，如'1'、'14'",
            "可选。要读取的特定版本号，如 7 表示读取 v7 的历史内容。",
            "可选。项目名或别名（如 回金算 / huijinsuan）。不传只搜全局",
            "可选。判断依据，例如「两条命令完全相同，后者多了 systemd 部分」。",
            "整理记忆用 since 只看最近一段（如 since=today）。",
    };

    /** 合法用法：这些是**必要信息**（副作用/字数上限/参数用途），守卫必须放行 */
    private static final String[] ALLOWED_SAMPLES = {
            "建议 ≤1500 字",
            "用于分段读取超长历史版本",
            "用于模糊搜索",
            "用于排序，不是判决依据",
            // ⚠️ 2026-10-26 17:5x 补：这些含「如」但**不是举例**，守卫不能误伤
            "⚠️ 传当前版本号（v58 就是当前版）会明确告知「这就是当前正文」",
            "如果 ExpHub 不可用会直接报错",
    };

    @Test
    void guardItselfMustCatchThePatternsItMissedBefore() {
        List<String> missed = new ArrayList<>();
        for (String sample : MUST_CATCH) {
            if (scan(sample).isEmpty()) missed.add(sample);
        }
        assertTrue(missed.isEmpty(),
                "守卫抓不住自己声明要抓的样本 —— 守卫已失效，等于没有守卫：\n  " + String.join("\n  ", missed));
    }

    /** 反过来：必要信息不能被误伤（压过头比不压更贵） */
    @Test
    void guardMustNotFlagNecessaryInformation() {
        List<String> wrong = new ArrayList<>();
        for (String sample : ALLOWED_SAMPLES) {
            List<String> hits = scan(sample);
            if (!hits.isEmpty()) wrong.add(sample + " → " + String.join(" / ", hits));
        }
        assertTrue(wrong.isEmpty(),
                "守卫误伤了必要信息（参数语义/副作用/字数上限）：\n  " + String.join("\n  ", wrong));
    }

    @Test
    void toolDescriptionsMustNotContainCompressibleRedundancy() {
        List<String> hits = new ArrayList<>();
        int toolCount = 0;
        int paramCount = 0;

        for (Method m : ExpHubTools.class.getDeclaredMethods()) {
            Tool tool = m.getAnnotation(Tool.class);
            if (tool == null) continue;
            toolCount++;
            collect(tool.name(), "description", tool.description(), hits);

            for (Parameter p : m.getParameters()) {
                ToolParam tp = p.getAnnotation(ToolParam.class);
                if (tp == null) continue;
                paramCount++;
                collect(tool.name() + "." + p.getName(), "param", tp.description(), hits);
            }
        }

        assertTrue(toolCount >= 40, "反射到的 @Tool 数量异常（" + toolCount + "），守卫本身可能失效");
        assertTrue(paramCount >= 100, "反射到的 @ToolParam 数量异常（" + paramCount + "），守卫本身可能失效");
        assertTrue(hits.isEmpty(),
                "工具描述里还有可压缩的冗余（#674 口径 B 要求 100% 清除）：\n  " + String.join("\n  ", hits));
    }

    /** 三类信息一个都不能少 —— 压过头比不压更贵（#674 红线第 4 条） */
    @Test
    void descriptionsMustKeepLossAndSideEffectSemantics() {
        Tool tool = null;
        for (Method m : ExpHubTools.class.getDeclaredMethods()) {
            Tool t = m.getAnnotation(Tool.class);
            if (t != null && "update_experience".equals(t.name())) { tool = t; break; }
        }
        assertTrue(tool != null, "找不到 update_experience");
        String d = tool.description();
        assertTrue(d.contains("清空"), "必须保留 clearFields 清空语义：" + d);
        assertTrue(d.contains("严禁") || d.contains("🔒"), "必须保留敏感信息禁令：" + d);
        assertTrue(d.contains("patch_experience") && d.contains("append_experience"),
                "必须保留「改一段用 patch / 追加用 append」的作用域语义（选错入口会丢内容）：" + d);
    }

    /** 单条上限：#674 写「单条设上限（建议 ≤200 字），超的重写不硬砍」 */
    @Test
    void singleToolDescriptionMustNotExceed200Chars() {
        for (Method m : ExpHubTools.class.getDeclaredMethods()) {
            Tool tool = m.getAnnotation(Tool.class);
            if (tool == null) continue;
            int len = tool.description() == null ? 0 : tool.description().length();
            assertTrue(len <= 200,
                    tool.name() + " 的 description 有 " + len + " 字，超过 200 上限 —— 先重写再谈压缩");
        }
    }

    /** 扫一段文本，返回命中的规则说明 */
    private List<String> scan(String text) {
        List<String> hits = new ArrayList<>();
        if (text == null || text.isBlank()) return hits;
        for (String[] rule : RULES) {
            Matcher m = Pattern.compile(rule[0]).matcher(text);
            if (!m.find()) continue;
            hits.add(rule[1] + "（规则 " + rule[0] + "）：" + excerpt(text, m.start(), m.end()));
        }
        return hits;
    }

    private void collect(String where, String kind, String text, List<String> hits) {
        for (String h : scan(text)) {
            hits.add(where + " [" + kind + "] " + h);
        }
    }

    private String excerpt(String text, int from, int to) {
        int a = Math.max(0, from - 20);
        int b = Math.min(text.length(), to + 20);
        return "…" + text.substring(a, b) + "…";
    }
}
