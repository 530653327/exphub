package com.exphub.service;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * D1 施工包 4：文档相似度打分（纯静态、无 Spring 依赖，便于单测）。
 *
 * <p>为什么不用「正文 2-gram 重合度」当主口径 —— 这是拿线上 563 篇真实数据量出来的结论：
 * <pre>
 *   同模板兄弟（百炼宠物 19 篇动画提示词，各是不同的宠物）  2-gram 重合 0.79~0.84
 *   同模板兄弟（expert-manager 创建的两个专家）            2-gram 重合 0.76
 *   真候选（待办「新增 X」→「实现 X」）                    2-gram 重合 0.34
 * </pre>
 * 2-gram 完全被模板句淹没：**越像同一个模板，分数越高**，而模板兄弟恰恰是最不该进整理队列的东西。
 * 换成「整行重合 + 剔除模板行」后，同样的数据变成：
 * <pre>
 *   同模板兄弟（宠物）      行重合 0.00，总分 0.45（低于阈值，不入队）
 *   真候选（待办→实现）     行重合 0.00，总分 0.62（词不同但主题同一，仍能识别）
 * </pre>
 *
 * <p>所以正文取「去模板行后的整行重合」：模板行 = 在**同一作用域**内出现超过
 * {@link #TEMPLATE_DF_K} 篇文档的行。宠物 A 与宠物 B 的模板行被剔除后，剩下的
 * 只有「白虎/野猪」这类真正区分它们的内容 —— 一行都不重，自然不会误报。
 *
 * <p>正文行数太少（长段落型文档）时整行口径会退化成「0 或 1」，此时回退 2-gram 口径，
 * 并在回执里注明走了哪条路 —— 双路径必须自证走位（红线第 4 条）。
 */
public final class SimilarityScorer {

    private SimilarityScorer() {}

    // ===== 口径常量（改这几个数前，先看类注释里的实测数据）=====
    public static final int TITLE_NGRAM = 2;
    public static final int CAP_TITLE_GRAMS = 300;
    public static final int CAP_CONTENT_GRAMS = 2500;
    public static final int MIN_LINE_LEN = 8;
    /** 同一作用域内出现 > K 篇的行视为模板行 */
    public static final int TEMPLATE_DF_K = 2;
    /**
     * 原文行数少于这个数，就认为行口径不可靠，回退 2-gram。
     *
     * <p>⚠️ 这里判的是<b>剔除模板行之前</b>的行数，不是之后 —— 一开始写成「剔除后剩几行」，
     * 单测立刻打回：一份 5 行的文档，4 行是模板、1 行是独有内容，剔除后只剩 1 行 → 被判「行太少」
     * → 回退 2-gram → 模板兄弟的假阳性又回来了（实测 0.87）。<br>
     * 「大部分是模板」不是缺陷，那正是该用整行口径的文档；只有<b>整篇没有行边界</b>（长段落型）
     * 才是真的没法按行比。
     */
    public static final int MIN_LINES_FOR_LINE_MODE = 3;
    /** 正文短于这个长度不参与比较（没有足够信息可判） */
    public static final int MIN_CONTENT_LEN = 50;

    public static final double W_TAG = 0.34;
    public static final double W_TITLE = 0.33;
    public static final double W_CONTENT = 0.33;
    public static final double BONUS_SAME_PROJECT = 0.02;
    public static final double BONUS_SAME_CATEGORY = 0.01;

    /** 归一化：小写 + 去掉空白与标点。中文按字符切分即可，不需要分词器 */
    public static String norm(String s) {
        if (s == null || s.isEmpty()) return "";
        StringBuilder sb = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (Character.isLetterOrDigit(c)) {
                sb.append(Character.toLowerCase(c));
            }
        }
        return sb.toString();
    }

    private static Set<String> ngrams(String text, int n, int cap) {
        Set<String> out = new LinkedHashSet<>();
        if (text == null || text.length() < n) return out;
        for (int i = 0; i + n <= text.length(); i++) {
            out.add(text.substring(i, i + n));
            if (out.size() >= cap) break;
        }
        return out;
    }

    /** 标题 2-gram（插入序截断，保证同一文本每次切出来的集合一致） */
    public static Set<String> titleGrams(String title) {
        return ngrams(norm(title), TITLE_NGRAM, CAP_TITLE_GRAMS);
    }

    /** 正文 2-gram，仅在「整行口径不可靠」时兜底用 */
    public static Set<String> contentGrams(String content) {
        return ngrams(norm(content), TITLE_NGRAM, CAP_CONTENT_GRAMS);
    }

    /**
     * 正文切行：去掉 markdown 前缀与分隔符，长度不足 {@link #MIN_LINE_LEN} 的行不要。
     * 去前缀是必须的 —— 「## 环境信息」与「### 环境信息」是同一行内容。
     */
    public static Set<String> lines(String content) {
        Set<String> out = new LinkedHashSet<>();
        if (content == null || content.isEmpty()) return out;
        for (String raw : content.split("\n")) {
            String s = stripLinePrefix(raw);
            if (s.length() >= MIN_LINE_LEN) out.add(s);
        }
        return out;
    }

    /** 剥掉行首的 markdown 标记（#、-、*、>、|、1.）与表头分隔行 */
    static String stripLinePrefix(String raw) {
        String s = raw == null ? "" : raw.trim();
        int i = 0;
        while (i < s.length()) {
            char c = s.charAt(i);
            if (c == '#' || c == '-' || c == '*' || c == '>' || c == '|' || c == '+') {
                i++;
            } else if (Character.isDigit(c)) {
                int j = i;
                while (j < s.length() && Character.isDigit(s.charAt(j))) j++;
                if (j < s.length() && (s.charAt(j) == '.' || s.charAt(j) == '、' || s.charAt(j) == ')')) {
                    i = j + 1;
                } else {
                    break;
                }
            } else if (Character.isWhitespace(c)) {
                i++;
            } else {
                break;
            }
        }
        String body = s.substring(Math.min(i, s.length()));
        StringBuilder sb = new StringBuilder(body.length());
        for (int k = 0; k < body.length(); k++) {
            char c = body.charAt(k);
            if (!Character.isWhitespace(c)) sb.append(c);
        }
        return sb.toString();
    }

    /**
     * 行去模板：df 超过阈值的行视为模板样板，剔除。
     * 剔除后剩太少（长段落型文档）时**回退为原始行集**，由调用方通过
     * {@link #isLineModeReliable} 判断并注明。
     */
    public static Set<String> distinctLines(Set<String> lines, Map<String, Integer> df) {
        Set<String> out = new LinkedHashSet<>();
        for (String ln : lines) {
            Integer c = df.get(ln);
            if (c == null || c <= TEMPLATE_DF_K) out.add(ln);
        }
        return out;
    }

    /**
     * 能按行比就算可靠 —— 判据是<b>原文行数</b>（见 {@link #MIN_LINES_FOR_LINE_MODE}），
     * 剔除模板后剩 0 行也没关系：剩 0 行说明这篇全是模板，那它和别的模板文档本来就只在模板上相似。
     */
    public static boolean isLineModeReliable(Set<String> originalLines) {
        return originalLines != null && originalLines.size() >= MIN_LINES_FOR_LINE_MODE;
    }

    /** 标签/别名切分：逗号、分号、空白都算分隔符 */
    public static Set<String> tokens(String raw) {
        Set<String> out = new LinkedHashSet<>();
        if (raw == null || raw.isBlank()) return out;
        for (String t : raw.split("[,，;；\\s]+")) {
            String v = t.trim().toLowerCase();
            if (!v.isEmpty() && !"-".equals(v) && !"无".equals(v)) out.add(v);
        }
        return out;
    }

    public static double jaccard(Set<String> a, Set<String> b) {
        if (a == null || b == null || a.isEmpty() || b.isEmpty()) return 0;
        int inter = 0;
        Set<String> small = a.size() <= b.size() ? a : b;
        Set<String> big = small == a ? b : a;
        for (String s : small) if (big.contains(s)) inter++;
        int union = a.size() + b.size() - inter;
        return union == 0 ? 0 : (double) inter / union;
    }

    public static int intersectionSize(Set<String> a, Set<String> b) {
        if (a == null || b == null || a.isEmpty() || b.isEmpty()) return 0;
        Set<String> small = a.size() <= b.size() ? a : b;
        Set<String> big = small == a ? b : a;
        int inter = 0;
        for (String s : small) if (big.contains(s)) inter++;
        return inter;
    }

    /** Dice = 2|A∩B| / (|A|+|B|)，对长短差异比 Jaccard 温和 */
    public static double dice(Set<String> a, Set<String> b) {
        if (a == null || b == null || a.isEmpty() || b.isEmpty()) return 0;
        int inter = intersectionSize(a, b);
        return 2.0 * inter / (a.size() + b.size());
    }

    /** 覆盖率 = |A∩B| / min(|A|,|B|)，衡量「短的被长的包含」 */
    public static double containment(Set<String> a, Set<String> b) {
        if (a == null || b == null || a.isEmpty() || b.isEmpty()) return 0;
        int inter = intersectionSize(a, b);
        return (double) inter / Math.min(a.size(), b.size());
    }

    /** 文档快照（scorer 不认 Doc 实体，避免把 MyBatis 实体拖进纯函数） */
    public record Sketch(Long id,
                         String title,
                         Set<String> tags,
                         Set<String> aliases,
                         String category,
                         Long projectId,
                         Set<String> titleGrams,
                         Set<String> contentGrams,
                         Set<String> lines,
                         Set<String> distinctLines,
                         boolean lineModeReliable,
                         int contentLen) {

        /** 构造快照；distinct 相关字段由作用域索引在统计完行 df 后回填 */
        public static Sketch of(Long id, String title, String tags, String aliases,
                                String category, Long projectId, String content) {
            String body = content == null ? "" : content;
            // 必须写全类名：record 自己就有同名访问器，裸调用会被解析成无参版本
            return new Sketch(id,
                    title == null ? "" : title,
                    SimilarityScorer.tokens(tags),
                    SimilarityScorer.tokens(aliases),
                    category,
                    projectId,
                    SimilarityScorer.titleGrams(title),
                    SimilarityScorer.contentGrams(body),
                    SimilarityScorer.lines(body),
                    Set.of(),
                    false,
                    body.length());
        }

        public Sketch withDistinctLines(Set<String> distinct, boolean reliable) {
            return new Sketch(id, title, tags, aliases, category, projectId,
                    titleGrams, contentGrams, lines, distinct, reliable, contentLen);
        }
    }

    /**
     * 打分结果。
     *
     * @param lineMode true = 正文走的「去模板整行」口径；false = 回退 2-gram 口径
     */
    public record Score(double total, double tag, double title, double content,
                        int sharedLines, int linesA, int linesB, boolean lineMode) {

        /** 可读理由：人判断一对候选值不值得看，靠的就是这几行 */
        public String reason() {
            StringBuilder sb = new StringBuilder();
            sb.append("标签交集 ").append(fmt(tag));
            sb.append("；标题重合 ").append(fmt(title));
            if (lineMode) {
                sb.append("；正文行重合 ").append(sharedLines).append(" 行（").append(linesA)
                  .append(" 行 vs ").append(linesB).append(" 行，模板行已剔除）");
            } else {
                sb.append("；正文 2-gram 重合 ").append(fmt(content)).append("（行太少，回退 2-gram 口径）");
            }
            return sb.toString();
        }

        public static String fmt(double v) {
            return String.format(java.util.Locale.ROOT, "%.2f", v);
        }
    }

    /**
     * 一对文档的相似度。
     *
     * <p>权重取 0.34 / 0.33 / 0.33：三个信号各自独立、没有一个被验证过「应该更重」，
     * 等权是最不容易过拟合的选择。同项目 +0.02、同分类 +0.01 只做微弱倾向，
     * 不足以把一对无关文档顶过阈值。
     */
    public static Score score(Sketch a, Sketch b) {
        double tag = Math.max(jaccard(a.tags(), b.tags()), 0.9 * jaccard(a.aliases(), b.aliases()));
        double title = dice(a.titleGrams(), b.titleGrams());

        boolean lineMode = a.lineModeReliable() && b.lineModeReliable();
        Set<String> ca;
        Set<String> cb;
        if (lineMode) {
            // 剔除模板后为空的，说明这篇全是模板 —— 那就拿全文行去比，
            // 别让「全是模板」被当成「没有内容可比」而静默变成 0 分
            ca = a.distinctLines().isEmpty() ? a.lines() : a.distinctLines();
            cb = b.distinctLines().isEmpty() ? b.lines() : b.distinctLines();
        } else {
            ca = a.contentGrams();
            cb = b.contentGrams();
        }
        double content = 0.6 * dice(ca, cb) + 0.4 * containment(ca, cb);

        double total = W_TAG * tag + W_TITLE * title + W_CONTENT * content;
        if (a.projectId() != null && a.projectId().equals(b.projectId())) {
            total += BONUS_SAME_PROJECT;
        }
        if (a.category() != null && !a.category().isBlank() && a.category().equals(b.category())) {
            total += BONUS_SAME_CATEGORY;
        }
        if (total > 1.0) total = 1.0;

        int shared = lineMode ? intersectionSize(ca, cb) : 0;
        return new Score(total, tag, title, content, shared, ca.size(), cb.size(), lineMode);
    }

    /** 统计一组文档的行频次（作用域内） */
    public static Map<String, Integer> lineDf(Collection<Sketch> sketches) {
        Map<String, Integer> df = new LinkedHashMap<>();
        for (Sketch s : sketches) {
            for (String ln : s.lines()) {
                df.merge(ln, 1, Integer::sum);
            }
        }
        return df;
    }

    /** 给一批快照回填去模板行（先统 df，再逐条剔除） */
    public static List<Sketch> applyDistinctLines(List<Sketch> sketches) {
        Map<String, Integer> df = lineDf(sketches);
        List<Sketch> out = new ArrayList<>(sketches.size());
        for (Sketch s : sketches) {
            Set<String> dist = distinctLines(s.lines(), df);
            out.add(s.withDistinctLines(dist, isLineModeReliable(s.lines())));
        }
        return out;
    }
}
