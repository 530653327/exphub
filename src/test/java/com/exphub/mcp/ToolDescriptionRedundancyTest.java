package com.exphub.mcp;

import org.junit.jupiter.api.Test;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;

import java.lang.reflect.Method;
import java.lang.reflect.Parameter;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 工具描述里「可压缩的冗余」必须为 0 —— 守卫 #674 验收口径 B。
 *
 * <p><b>为什么要有这个守卫</b>：#674 原验收标准是「描述总字数降幅 ≥50%」，但上一轮已把最肥的
 * description 压过一轮（5813→4739），剩下的全是参数语义/枚举值/丢数据/副作用这些**删不得**的信息，
 * 再压就得拆安全网。越哥 2026-10-26 15:3x 拍板口径 B：验收从「降 50%」改成
 * <b>「可压缩的冗余 100% 清除」</b>。
 *
 * <p><b>「可压缩的冗余」= 以下四类</b>（这三类都不是 #674 红线第 4 条要保留的信息）：
 * <ol>
 *   <li><b>教学话术</b> —— 替 AI 下结论的话（「建议先…」「务必」「这是给…看的」「最好」「用来」）</li>
 *   <li><b>举例</b> —— 「如：xxx」「例如：xxx」；枚举值清单本身不算举例（那是参数语义，必须留）</li>
 *   <li><b>重复机制说明</b> —— 在别的工具里已经讲过一遍的机制（归档不是删除 / 两者等价 等）</li>
 *   <li><b>schema 已体现的赘述</b> —— 「不传=不改」（required=false 已经表达了）</li>
 * </ol>
 *
 * <p><b>必须保留（守卫不许误伤）</b>：参数语义、默认值、区间、必填、会不会丢数据、有没有副作用、
 * 作用域语义（projectId=0 是移回全局）、枚举值清单、跨工具判断规则。
 *
 * <p><b>怎么用</b>：加新工具或改描述时，若命中本守卫 → 说明又写进了教学话术/举例，删掉即可。
 * 守卫只认「明确可判」的短语，不做语义推断，避免误报（此前项目里出现过守卫自身误报、
 * 靠剥注释才通过的先例，见 AGENTS.md）。
 */
class ToolDescriptionRedundancyTest {

    /** 明确可判的冗余短语 → 为什么该删 */
    private static final String[][] FORBIDDEN = {
            // —— 教学话术（替 AI 下结论）——
            {"建议先", "教学话术：替 AI 决定调用顺序"},
            {"建议传", "教学话术：替 AI 决定要不要传"},
            {"建议用", "教学话术：替 AI 决定用哪个工具"},
            {"建议统一用", "教学话术"},
            {"务必", "教学话术"},
            {"请注意", "教学话术"},
            {"最好", "教学话术"},
            {"这是给", "教学话术"},
            {"让 AI 知道", "教学话术"},
            {"不要靠", "教学话术"},
            {"不必再", "教学话术"},
            {"不要当 bug 报给用户", "教学话术"},
            {"这是首选方式", "教学话术"},
            // —— 举例 ——
            {"例如：", "举例：清单应在别处，不在描述里举例"},
            {"如：", "举例"},
            // —— 重复机制说明 ——
            {"归档只是搬位置、不是删除", "重复机制：archive_old_versions 已讲过"},
            {"与 `authorName` 等价，两者都传时以本参数为准", "重复机制：同段已说明等价"},
            // —— schema 已体现的赘述 ——
            {"不传=不改", "赘述：required=false 已经表达了"},
    };

    /** 合法用法白名单：这些词本身不是冗余，只是要看清上下文 */
    private static final String[] ALLOWED = {
            "建议 ≤",          // 字数上限提示（副作用类）
            "用于分段读取",    // 参数用途
            "用于模糊搜索",    // 参数用途
            "用于排序",        // 参数用途
    };

    @Test
    void toolDescriptionsMustNotContainCompressibleRedundancy() {
        List<String> hits = new ArrayList<>();
        int toolCount = 0;
        int paramCount = 0;

        for (Method m : ExpHubTools.class.getDeclaredMethods()) {
            Tool tool = m.getAnnotation(Tool.class);
            if (tool == null) continue;
            toolCount++;
            check(tool.name(), "description", tool.description(), hits);

            for (Parameter p : m.getParameters()) {
                ToolParam tp = p.getAnnotation(ToolParam.class);
                if (tp == null) continue;
                paramCount++;
                check(tool.name() + "." + p.getName(), "param", tp.description(), hits);
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
        // 用一个真实工具做样本：update_experience 必须说清「清空」「不递增版本」「敏感信息」
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

    private void check(String where, String kind, String text, List<String> hits) {
        if (text == null || text.isBlank()) return;
        for (String[] rule : FORBIDDEN) {
            String phrase = rule[0];
            if (!text.contains(phrase)) continue;
            // 白名单：某些短语在特定上下文是合法的（如「建议 ≤1500 字」是字数上限提示）
            boolean allowed = false;
            for (String a : ALLOWED) {
                if (text.contains(a)) { allowed = true; break; }
            }
            if (allowed) continue;
            hits.add(where + " [" + kind + "] 含「" + phrase + "」—— " + rule[1] + "："
                    + excerpt(text, phrase));
        }
    }

    private String excerpt(String text, String phrase) {
        int i = text.indexOf(phrase);
        int from = Math.max(0, i - 20);
        int to = Math.min(text.length(), i + phrase.length() + 20);
        return "…" + text.substring(from, to) + "…";
    }
}
