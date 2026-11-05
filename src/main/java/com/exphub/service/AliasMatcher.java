package com.exphub.service;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * L0-1a：别名判定的三分支定稿规则（#678 §5.3，取代旧的「整串等值」）。
 *
 * <table border="1">
 *   <caption>三种关系</caption>
 *   <tr><th>分支</th><th>关系</th><th>条件</th></tr>
 *   <tr><td>①</td><td>项 == token</td><td>df(token) &lt; 30%，不限长度</td></tr>
 *   <tr><td>②</td><td>项 ⊃ token</td><td><b>仅含 CJK 的 token</b>；两侧边界；df(token) &lt; 30%</td></tr>
 *   <tr><td>③</td><td>token ⊃ 项</td><td>两侧边界（起止/空白/CJK）；df(项) &lt; 30%</td></tr>
 * </table>
 *
 * <p>两条护栏都是结构性的，不靠调阈值：② 对纯 ASCII token 一律拒绝（#678 §5.1；
 * 否则 {@code api} 会命中 {@code xy-api}），df 未知一律「缺数不放行」。
 * 长度只留 {@code >= 2} 的下限 —— 用长度当 df 的代理正是 #678 §1 批评过的做法。</p>
 *
 * <p>本类是 {@code tools/search_regression.py} 中 {@code detect_by_rule} 的 Java 同源实现；
 * B 组 7 条验算在 {@code AliasMatcherTest} 里逐条复刻，防误召与防漏召同时验。</p>
 */
public final class AliasMatcher {

    /** #678 §5.3：「任何别名项长度 &lt; 2 不参与」。旧的 3 字门槛是拿长度当 df 代理，已撤。 */
    public static final int MIN_ITEM_LEN = 2;
    public static final double DF_MAX_PCT = 30.0;

    private AliasMatcher() {
    }

    public enum Branch {
        EQ("①"), ITEM_CONTAINS_TOKEN("②"), TOKEN_CONTAINS_ITEM("③");
        final String label;

        Branch(String label) {
            this.label = label;
        }
    }

    public static final class Verdict {
        public final boolean hit;
        public final Branch branch;
        public final String reason;

        private Verdict(boolean hit, Branch branch, String reason) {
            this.hit = hit;
            this.branch = branch;
            this.reason = reason;
        }

        static Verdict miss(String why) {
            return new Verdict(false, null, why);
        }

        static Verdict hit(Branch b, String why) {
            return new Verdict(true, b, why);
        }

        @Override
        public String toString() {
            return (branch != null ? branch.label : "-") + " " + (hit ? "命中" : "拒绝") + "：" + reason;
        }
    }

    /** 单个「别名项 × 查询 token」的判定。 */
    public static Verdict judge(String item, String token, DfTable df) {
        String a = norm(item);
        String t = norm(token);
        if (a.length() < MIN_ITEM_LEN) return Verdict.miss("别名项长度 < 2");
        if (t.length() < MIN_ITEM_LEN) return Verdict.miss("token 长度 < 2");

        Branch branch;
        String term;
        boolean boundary;
        if (a.equals(t)) {
            branch = Branch.EQ;
            term = t;
            boundary = true;
        } else if (a.contains(t)) {
            branch = Branch.ITEM_CONTAINS_TOKEN;
            term = t;
            if (!hasCjk(t)) {
                return Verdict.miss("② 纯 ASCII token 结构性拒绝（#678 §5.1）");
            }
            boundary = boundaryBothSides(a, t, false);
        } else if (t.contains(a)) {
            branch = Branch.TOKEN_CONTAINS_ITEM;
            term = a;
            boundary = boundaryBothSides(t, a, true);
        } else {
            return Verdict.miss("无包含关系");
        }
        if (!boundary) return Verdict.miss("两侧不是边界");

        Double pct = df.pct(term);
        if (pct == null) return Verdict.miss("df(" + term + ")=UNKNOWN → 缺数不放行");
        if (pct >= DF_MAX_PCT) {
            return Verdict.miss(String.format(Locale.ROOT, "df(%s)=%.1f%% >= 30%%", term, pct));
        }
        return Verdict.hit(branch,
                String.format(Locale.ROOT, "%s 边界成立，df(%s)=%.1f%% < 30%%", branch.label, term, pct));
    }

    /** 返回第一个命中的别名项原文（日志里要能看出命中的是哪一项）；无命中返回 null。 */
    public static String firstMatch(List<String> items, List<String> tokens, DfTable df) {
        for (String item : items) {
            for (String token : tokens) {
                if (judge(item, token, df).hit) return item;
            }
        }
        return null;
    }

    public static boolean anyMatch(List<String> items, List<String> tokens, DfTable df) {
        return firstMatch(items, tokens, df) != null;
    }

    /** 查询词切分：与回归脚本一致，空白/逗号/顿号/分号都是分隔符。 */
    public static List<String> tokens(String query) {
        List<String> out = new ArrayList<>();
        if (query == null) return out;
        for (String raw : query.split("[\\s,，、;；]+")) {
            String s = raw.trim();
            if (!s.isEmpty()) out.add(s);
        }
        return out;
    }

    /** 候选片：别名逐项 + 项目编码 + 项目名（与回归脚本 detect_by_rule 的 items 顺序一致）。 */
    public static List<String> items(String aliases, String name, String projectCode) {
        List<String> out = new ArrayList<>();
        if (aliases != null) {
            for (String raw : aliases.split("[,，、;；]")) {
                String s = raw.trim();
                if (!s.isEmpty()) out.add(s);
            }
        }
        if (projectCode != null && !projectCode.isBlank()) out.add(projectCode.trim());
        if (name != null && !name.isBlank()) out.add(name.trim());
        return out;
    }

    /**
     * needle 在 hay 里每一次出现，两侧是否都算边界。
     *
     * @param strictCjkNeighbor true = ③（只认起止/空白/CJK）；false = ②（非 ASCII 字母数字即可，
     *                          所以 {@code -} 在这里恰好是边界，#678 §5.1 勘误）
     */
    static boolean boundaryBothSides(String hay, String needle, boolean strictCjkNeighbor) {
        if (needle.isEmpty()) return false;
        int from = 0;
        while (true) {
            int i = hay.indexOf(needle, from);
            if (i < 0) return false;
            boolean leftOk = i == 0 || isBound(hay.charAt(i - 1), strictCjkNeighbor);
            int end = i + needle.length();
            boolean rightOk = end >= hay.length() || isBound(hay.charAt(end), strictCjkNeighbor);
            if (leftOk && rightOk) return true;
            from = i + 1;
        }
    }

    private static boolean isBound(char b, boolean strictCjkNeighbor) {
        if (Character.isWhitespace(b)) return true;
        if (strictCjkNeighbor) return isCjk(b);
        return !(b >= 'A' && b <= 'Z') && !(b >= 'a' && b <= 'z') && !(b >= '0' && b <= '9');
    }

    static boolean isCjk(char c) {
        return c >= '\u4e00' && c <= '\u9fff';
    }

    static boolean hasCjk(String s) {
        for (int i = 0; i < s.length(); i++) {
            if (isCjk(s.charAt(i))) return true;
        }
        return false;
    }

    /** 归一：全角转半角（NFKC）+ 去首尾空白 + 小写。与 ProjectService 原归一一致。 */
    static String norm(String s) {
        return s == null ? ""
                : Normalizer.normalize(s, Normalizer.Form.NFKC).trim().toLowerCase(Locale.ROOT);
    }
}
