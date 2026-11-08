package com.exphub.util;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 写入口凭据拦截（2026-11-08，阁主 #81 判据 / #1313 第 4 条）。
 *
 * <p><b>为什么必须有这一层</b>：原来只有 `create_experience` / `update_experience` 的**工具描述文字**
 * 写着「严禁含密钥」，代码里零检测。真发生过生产 Key 与数据库口令明文入库，并扩散到
 * `doc_versions` 快照与信箱消息表 —— 清理了，机制没补。
 *
 * <p><b>过线标准（阁主 #81 原话口径）</b>：真凭据全拦 + 说明性文本零误伤。
 * 5 类前缀直接拒；`password=` / `token=` 只有当**值的形态**像真凭据才拒：
 * ① 长度 ≥8 ② 含数字或符号（非纯字母）③ 不是中文、不含尖括号/占位词/代码表达式符号。
 * 不加文档白名单、不做上下文语义判断。
 *
 * <p><b>掩码纪律</b>：拒绝回执只给「字段 + 行号 + 类别 + 前 2 后 1 的掩码片段」。
 * 绝不能把原值回显 —— 否则拦下的凭据又被写进 call_logs 和模型上下文，等于换个地方再漏一次。
 *
 * <p><b>为什么放在 util 而不是 Spring Bean</b>：服务层已有大量单测用 `new MailboxService()`
 * + `ReflectionTestUtils` 手工装配，再加一个 @Autowired 字段会让这些测试全 NPE。
 * 纯静态无依赖，任何写入口都能直接调。
 */
public final class SensitiveContentGuard {

    private SensitiveContentGuard() {}

    private static final class Rule {
        final String kind;
        final Pattern pattern;
        Rule(String kind, String regex) {
            this.kind = kind;
            this.pattern = Pattern.compile(regex);
        }
    }

    /** 5 类高置信前缀：命中即拒。 */
    private static final List<Rule> DIRECT = List.of(
            new Rule("OPENAI_KEY", "sk-[A-Za-z0-9_-]{8,}"),
            new Rule("SLACK_TOKEN", "xox[baprs]-[A-Za-z0-9-]{8,}"),
            new Rule("AWS_AKID", "AKIA[0-9A-Z]{16}"),
            new Rule("PRIVATE_KEY", "-----BEGIN (?:[A-Z0-9 ]+ )?PRIVATE KEY-----"),
            new Rule("JDBC_CREDS", "jdbc:[a-z0-9]+://[^\\s:/@]+:[^\\s/@]+@")
    );

    /** 键只认小写：代码里的 `var TOKEN = ...` 是变量赋值，不是凭据配置。 */
    private static final Pattern KV = Pattern.compile(
            "\\b(password|token)\\s*[:=]\\s*([^\\s,;\"'`}\\]]+)");

    private static final Pattern CJK = Pattern.compile("[\\u4e00-\\u9fff]");
    private static final Pattern PLACEHOLDER = Pattern.compile(
            "(?i)([<>]|\\$\\{|\\{\\{|xxx|your|你的|占位|示例|placeholder|sample|example|fake|dummy|redacted|masked|secret|test|demo|abc123|123456|changeme|\\*{3,})");
    /** 含这些字符的是代码表达式，不是凭据值（JWT / 域名由前缀规则另行覆盖）。 */
    private static final Pattern CODE_CHARS = Pattern.compile("[.(){};/\\\\]");
    /** 前缀规则也要排除文档里常见的占位值，如 `sk-placeholder...`。只判值本身词形，不判上下文。 */
    private static final Pattern DIRECT_PLACEHOLDER = Pattern.compile(
            "(?i)(placeholder|your|xxx|here|changeme)");

    public static final class Hit {
        public final String field;
        public final int line;
        public final String kind;
        public final String masked;
        Hit(String field, int line, String kind, String masked) {
            this.field = field;
            this.line = line;
            this.kind = kind;
            this.masked = masked;
        }
        public String describe() {
            return "· " + field + " 第 " + line + " 行：" + kind + "，片段 " + masked;
        }
    }

    /** 扫一个字段，返回所有命中（不抛异常，便于测试与批量对账）。 */
    public static List<Hit> scan(String field, String text) {
        List<Hit> hits = new ArrayList<>();
        if (text == null || text.isEmpty()) return hits;
        for (Rule rule : DIRECT) {
            Matcher m = rule.pattern.matcher(text);
            while (m.find()) {
                if (DIRECT_PLACEHOLDER.matcher(m.group()).find()) continue;
                hits.add(new Hit(field, lineOf(text, m.start()), rule.kind, mask(m.group())));
            }
        }
        Matcher kv = KV.matcher(text);
        while (kv.find()) {
            String raw = kv.group(2);
            if (!looksLikeSecret(raw)) continue;
            hits.add(new Hit(field, lineOf(text, kv.start()),
                    "KV_" + kv.group(1).toUpperCase(), mask(raw)));
        }
        return hits;
    }

    /**
     * 批量校验一批字段；有命中就抛 {@link IllegalArgumentException}（工具层的 catch 会原样回给模型）。
     * 必须在任何写库动作之前调用。
     */
    public static void check(Map<String, String> fields) {
        if (fields == null || fields.isEmpty()) return;
        List<Hit> all = new ArrayList<>();
        for (Map.Entry<String, String> e : fields.entrySet()) {
            all.addAll(scan(e.getKey(), e.getValue()));
        }
        if (all.isEmpty()) return;
        StringBuilder sb = new StringBuilder();
        sb.append("疑似凭据，已拒绝写入（未写入任何数据）：\n");
        for (Hit h : all) sb.append(h.describe()).append('\n');
        sb.append("请把凭据换成环境变量引用（如 ${XXX}）或占位写法后再提交；确属误判请联系越哥，不要绕过校验。");
        throw new IllegalArgumentException(sb.toString());
    }

    /** 单字段便捷入口。 */
    public static void check(String field, String text) {
        Map<String, String> one = new LinkedHashMap<>();
        one.put(field, text);
        check(one);
    }

    private static boolean looksLikeSecret(String raw) {
        String v = raw.replaceAll("^[\"'`),;}]+|[\"'`),;}]+$", "");
        if (v.length() < 8) return false;
        if (CJK.matcher(v).find()) return false;
        if (PLACEHOLDER.matcher(v).find()) return false;
        if (CODE_CHARS.matcher(v).find()) return false;
        // 纯字母不算（password / database 这类说明词）
        return Pattern.compile("[^A-Za-z]").matcher(v).find();
    }

    private static int lineOf(String text, int pos) {
        int line = 1;
        for (int i = 0; i < pos && i < text.length(); i++) {
            if (text.charAt(i) == '\n') line++;
        }
        return line;
    }

    /** 前 2 + 后 1，中间不保留；长度一并给出，方便人判断是不是误判。 */
    public static String mask(String raw) {
        int n = raw.length();
        if (n <= 4) return "***(长度 " + n + ")";
        return raw.substring(0, 2) + "***" + raw.charAt(n - 1) + "(长度 " + n + ")";
    }
}
