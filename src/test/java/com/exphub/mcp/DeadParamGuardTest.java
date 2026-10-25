package com.exphub.mcp;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.lang.reflect.Method;
import java.lang.reflect.Parameter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 死参数守卫：声明了却没在方法体里用上的 @ToolParam 参数。
 *
 * <p><b>为什么需要这个测试</b>（2026-10-25 一天内踩了两次）：
 * <ul>
 *   <li>早上 `update_experience(authorName)` 是死参数 —— 传了不生效</li>
 *   <li>晚上 `mark_for_deletion(updatedName)` 又是死参数，白泽查源码 + 阁主实测才发现</li>
 * </ul>
 * 两次都是同一个模式：<b>参数声明了、说明写了、甚至承诺了「两者都传时以本参数为准」，
 * 但方法体里一行都没用</b>。模型会照说明去传，用户以为署名生效了，实际全记成 Key 绑定的名字。
 *
 * <p>害处不在「少一个功能」，而在<b>承诺与实现不一致导致调用方按错误预期行事</b>。
 * 而且这种缺陷**编译不报错、运行不报错、回执也不报错**，只能靠人肉读源码发现。
 *
 * <p>扫全文件时发现不止一个：`mark_for_deletion` / `set_flags` / `update_experience_status`
 * / `cancel_deletion` / `clear_superseded` 五个全都是 —— 如果只按被指出的那一个修，
 * 剩下四个会继续坏。**这正是「修了一条要回头问还有哪条」的又一次实证。**
 */
class DeadParamGuardTest {

    private static final Path SRC =
            Path.of("src/main/java/com/exphub/mcp/ExpHubTools.java");

    private static final Pattern TOOL = Pattern.compile("@Tool\\(name = \"([A-Za-z_0-9]+)\"");

    /** 每个 @Tool 方法：工具名 + 参数名列表 + 方法体 */
    private record Tool(String name, List<String> params, String body, String signature) {}

    private static List<Tool> parseTools() throws IOException {
        String src = Files.readString(SRC, StandardCharsets.UTF_8);
        List<Tool> out = new ArrayList<>();
        Matcher m = TOOL.matcher(src);
        while (m.find()) {
            String name = m.group(1);
            int brace = src.indexOf('{', m.end());
            if (brace < 0) continue;
            String signature = src.substring(m.end(), brace);

            // 大括号配平，找到方法体范围
            int depth = 0;
            int i = brace;
            while (i < src.length()) {
                char c = src.charAt(i);
                if (c == '{') depth++;
                else if (c == '}') {
                    depth--;
                    if (depth == 0) break;
                }
                i++;
            }
            String body = src.substring(brace + 1, i);

            List<String> params = new ArrayList<>();
            Matcher pm = Pattern.compile("@ToolParam[^)]*\\)\\s*(?:String|Long|Integer|Double|Boolean)\\s+(\\w+)")
                    .matcher(signature);
            while (pm.find()) params.add(pm.group(1));
            out.add(new Tool(name, params, body, signature));
        }
        return out;
    }

    /** 参数在方法体里至少要被读一次（`>` 排除赋值：`x = null` 这种不算用了） */
    private static boolean isUsed(String param, String body) {
        // 赋值给别的变量也算用了：`String by = updatedName;`
        return Pattern.compile("\\b" + Pattern.quote(param) + "\\b").matcher(body).find();
    }

    @Test
    void noToolParamIsDeclaredButUnused() throws IOException {
        List<Tool> tools = parseTools();
        assertTrue(tools.size() > 30, "解析到的工具数异常（" + tools.size() + "），可能正则失配");

        List<String> dead = new ArrayList<>();
        for (Tool t : tools) {
            for (String p : t.params()) {
                // updatedName / authorName 是历史踩坑点，必须确保真被用上；
                // 其他参数也一起查，防止新工具重犯
                if (!isUsed(p, t.body())) {
                    dead.add(t.name() + "(" + p + ")");
                }
            }
        }
        assertTrue(dead.isEmpty(),
                "以下参数声明了但方法体里没用上（模型会照说明传，用户以为生效了）：\n  "
                        + String.join("\n  ", dead)
                        + "\n\n修法：要么接上（updatedName 走 resolveAuthor(updatedName != null ? updatedName : authorName, assistant)），"
                        + "要么从签名和说明里删掉。不许留「声明了但不管」的中间态。");
    }

    /**
     * 「只认 authorName」的旧写法必须绝迹 —— 这正是本次 bug 的形态。
     *
     * <p>典型坏写法（`mark_for_deletion` / `set_flags` 原来都是这样）：
     * <pre>
     *   String by = (authorName != null &amp;&amp; !authorName.trim().isEmpty())
     *           ? authorName.trim()
     *           : (assistant != null ? assistant.getAssistantName() : "unknown");
     * </pre>
     * 参数说明写着「两者都传时以 updatedName 为准」，代码里却根本不看 updatedName ——
     * 调用方传了等于没传。
     *
     * <p>修法统一为：
     * {@code resolveAuthor(updatedName != null ? updatedName : authorName, assistant)}。
     *
     * <p>⚠️ 不要断言「所有带 authorName 的工具都必须走 resolveAuthor」——
     * `create_experience` 的 `authorName` 是**文档作者（作品归属）**，语义完全不同，
     * 它就该直接 setAuthorName，不该走操作人署名。想当然会让测试变成假警报。
     */
    @Test
    void noToolIgnoresUpdatedNameWhenAuthorNameGiven() throws IOException {
        List<Tool> tools = parseTools();
        List<String> offenders = new ArrayList<>();
        for (Tool t : tools) {
            boolean hasUpdated = t.params().contains("updatedName");
            if (!hasUpdated) continue;   // 没有这个参数就谈不上忽略

            String body = t.body();
            // 坏形态：从 authorName 起算，但整段里没出现 updatedName
            boolean authorOnlyPattern = Pattern
                    .compile("authorName\\s*!=\\s*null[\\s\\S]{0,120}?authorName\\.trim\\(\\)")
                    .matcher(body).find();
            if (authorOnlyPattern && !body.contains("updatedName")) {
                offenders.add(t.name());
            }
        }
        assertTrue(offenders.isEmpty(),
                "这些工具声明了 updatedName 却只认 authorName（传了等于没传）：" + offenders
                        + "\n统一改成 resolveAuthor(updatedName != null ? updatedName : authorName, assistant)");
    }

    /** 声明了 updatedName 的工具，其参数说明不许出现「以本参数为准」这类无法兑现的承诺 */
    @Test
    void promiseInDescriptionMatchesImplementation() throws IOException {
        List<Tool> tools = parseTools();
        List<String> liars = new ArrayList<>();
        for (Tool t : tools) {
            String sig = t.signature();
            boolean promises = sig.contains("以本参数为准") || sig.contains("与 `authorName` 等价");
            if (!promises) continue;
            // 承诺了就必须真的读它（第一个测试已覆盖死参数，这里再钉一次承诺兑现）
            if (!isUsed("updatedName", t.body())) liars.add(t.name());
        }
        assertTrue(liars.isEmpty(),
                "这些工具的参数说明承诺了「以 updatedName 为准」，但方法体没实现：" + liars);
    }

    /** 反射兜底：确认 resolveAuthor 本身的行为契约没被改坏 */
    @Test
    void resolveAuthorPrefersExplicitName() throws Exception {
        Method m = ExpHubTools.class.getDeclaredMethod("resolveAuthor", String.class,
                Class.forName("com.exphub.entity.AiAssistant"));
        m.setAccessible(true);

        // 显式传名字 → 用传的（哪怕 Key 上绑的是别的名字）
        assertTrue("烛龙".equals(m.invoke(null, "烛龙", null)));
        // 传空串 / 空格 → 视同没传
        assertTrue("unknown".equals(m.invoke(null, "", null)));
        assertTrue("unknown".equals(m.invoke(null, "   ", null)));
        // 没传也没 Key → unknown，绝不能返回 null（否则库里写出 NULL 署名）
        assertTrue("unknown".equals(m.invoke(null, null, null)));
        // 前后空格要被 trim 掉，否则「烛龙 」和「烛龙」在库里是两个名字
        assertTrue("烛龙".equals(m.invoke(null, "  烛龙  ", null)));
    }
}
