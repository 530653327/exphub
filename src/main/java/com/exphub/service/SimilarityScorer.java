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
    /**
     * 判定为「疑似字面重复」所需的最小共享行数。
     *
     * <p>取 2：只有 1 行相同时几乎一定是残留套话（如单独一句「更新于 2026-04-21」），
     * 不足以当证据。行数只是第一道门，真正的判据是 {@link #MIN_LINE_COVERAGE}。
     */
    public static final int MIN_SHARED_LINES = 2;

    /**
     * 判定为「疑似字面重复」所需的最小覆盖率（共享行 / 较短那篇的行数）。
     *
     * <p>为什么必须有覆盖率这一道（2026-10-25 23:50 实测）：只看行数会把
     * 「同模板不同实例」判成重复 —— 水晶鹿与蝴蝶仙子共用 12 行提示词骨架，
     * 但它们是两只不同的宠物。加了覆盖率后，这类对降到 27%，而真重复都在 48% 以上。
     *
     * <p>取 0.40 是在真实数据上选的最大间隔：真重复最低 48%、骨架最高 27%，中间没有样本。
     */
    public static final double MIN_LINE_COVERAGE = 0.40;

    /**
     * 判定为「疑似字面重复」所需的**共享行平均长度**。
     *
     * <p>为什么光有覆盖率不够（2026-10-25 23:56 写测试时发现）：
     * 覆盖率对小文档不公平 —— 骨架行数固定，文档越短骨架占比越高。
     * 合成一个 20 行的宠物提示词，骨架 10 行就占 45%，**刚过 0.40 阈值**，
     * 于是「不同宠物」会被判成重复。真实库里宠物文档有 57 行去模板正文，
     * 骨架只占 21% 才侥幸躲过 —— 换句话说，**这判据当时在全库上是对的，靠的是巧合**。
     *
     * <p>加的第二道门是共享行的**平均长度**：骨架是短标签（`death-死亡[6帧]`、
     * `适用工具：PixelLab`），真内容行更长。实测中位数：
     * <pre>
     *   #159↔#160  39 字  → 真重复
     *   #63↔#67   23 字  → 真重复
     *   #74↔#85   15 字  → 骨架
     *   #75↔#79   12 字  → 骨架
     * </pre>
     * 取 20 字落在这条缝里。两道门是 **AND**：覆盖率够**且**共享行够长，才算重复。
     */
    public static final int MIN_SHARED_LINE_LEN = 20;

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
     * @param lineMode        true = 正文走的「去模板整行」口径；false = 回退 2-gram 口径
     * @param lineCoverage    「较短那篇有多少比例的行出现在对方里」，仅 lineMode 时有意义
     * @param sharedAllLines  未剔模板时的原始共享行数，用于展示「骨架重合 vs 内容重合」的差异
     * @param sharedAvgLen    共享行的平均字数：骨架行短（约 15 字）、真正的内容行长（约 23~39 字）
     */
    public record Score(double total, double tag, double title, double content,
                        int sharedLines, int linesA, int linesB, boolean lineMode,
                        double lineCoverage, int sharedAllLines, int sharedAvgLen) {

        /** 可读理由：人判断一对候选值不值得看，靠的就是这几行 */
        public String reason() {
            StringBuilder sb = new StringBuilder();
            sb.append("标签交集 ").append(fmt(tag));
            sb.append("；标题重合 ").append(fmt(title));
            if (lineMode) {
                sb.append("；正文行重合 ").append(sharedLines).append(" 行（").append(linesA)
                  .append(" 行 vs ").append(linesB).append(" 行，模板行已剔除；平均 ")
                  .append(sharedAvgLen).append(" 字，覆盖较短那篇 ")
                  .append(pct(lineCoverage)).append("）");
            } else {
                sb.append("；正文 2-gram 重合 ").append(fmt(content)).append("（行太少，回退 2-gram 口径）");
            }
            return sb.toString();
        }

        /**
         * 判定层级：这一对「像的是什么」。
         *
         * <p><b>为什么用「覆盖率」而不是「共享行数」</b>（2026-10-25 23:50 实测修正）：
         * 一开始只判「共享 ≥2 行」，结果 #74↔#85（水晶鹿 vs 蝴蝶仙子，**两只不同的宠物**）
         * 因为共用一套提示词模板的骨架行而被判成「疑似字面重复」——那 12 行是
         * `death-死亡[6帧]` / `适用工具：PixelLab` / `更新时间：2026-04-21` 这类骨架，不是内容。
         *
         * <p>换覆盖率后干净分开（实测全库）：
         * <pre>
         *   #63↔#67   地图设计 vs 地图3提示词   40 行  覆盖 83%  → 真重复
         *   #159↔#160 两个 expert 专家          21 行  覆盖 51%  → 真重复
         *   #59↔#71   亲密系统旧版 vs 新版      22 行  覆盖 48%  → 真重复
         *   #74↔#85   水晶鹿 vs 蝴蝶仙子        12 行  覆盖 27%  → 骨架，不是重复
         *   #75↔#79   水系 vs 电系特效           2 行  覆盖  4%  → 骨架，不是重复
         * </pre>
         * 「重复」的本质就是<b>较短的那篇几乎整篇出现在另一篇里</b> —— 这正是覆盖率的定义，
         * 比「共享了几行」有依据，也更好向人解释。
         */
        public Kind kind() {
            if (!lineMode) return Kind.UNKNOWN;
            boolean enough = sharedLines >= MIN_SHARED_LINES
                    && lineCoverage >= MIN_LINE_COVERAGE
                    && sharedAvgLen >= MIN_SHARED_LINE_LEN;
            return enough ? Kind.DUPLICATE : Kind.SERIES;
        }

        /**
         * 给人看的一句话定性：比分数本身更能指导动作。
         *
         * <p>回答的是「我该判 duplicate 还是 superseded」——这是队列里每条都要做的决定，
         * 只看分数根本判不出来。
         */
        public String diagnosis() {
            return switch (kind()) {
                case DUPLICATE -> "**疑似字面重复**（正文 " + sharedLines + " 行完全一致，平均 "
                        + sharedAvgLen + " 字，覆盖较短那篇 " + pct(lineCoverage)
                        + "）→ 优先判 duplicate 或 superseded";
                case SERIES -> sharedLines > 0
                        ? "**共用骨架**（只重 " + sharedLines + " 行、平均仅 " + sharedAvgLen
                          + " 字、覆盖 " + pct(lineCoverage)
                          + "，是同一模板的不同实例）→ 别判 duplicate"
                        : "**同话题演进链**（正文共享仅覆盖 " + pct(lineCoverage)
                          + "，不够算重复）→ 大概率该判 superseded（后者是前者的新阶段），不该判 duplicate";
                case UNKNOWN -> "**信息不足**（行太少，走 2-gram 兜底）→ 建议人工读一眼再定";
            };
        }

        public static String fmt(double v) {
            return String.format(java.util.Locale.ROOT, "%.2f", v);
        }

        public static String pct(double v) {
            return String.format(java.util.Locale.ROOT, "%.0f%%", v * 100);
        }
    }

    /**
     * 候选对的定性分类。
     *
     * <p><b>为什么必须有这个（2026-10-25 23:38 越哥实测发现）</b>：
     * 原来只用一个总分排序，结果「正文 0 行重合」的演进链拿 0.62 排第一，
     * 而「正文 33 行完全一致」的真重复只拿 0.51 排第 16 —— **排序是反的**。
     *
     * <p>根因：`标签 1.00 + 标题 0.75` 就能凑出 0.59，正文完全不重也能过阈值。
     * 三个信号的含义根本不是一回事：
     * <ul>
     *   <li>标签 = <b>项目归属</b>（同一批记录的 tags 常常完全相同，信息量最低）</li>
     *   <li>标题 = <b>话题</b>（「待办：新增 X」和「实现 X」必然像，但内容是两回事）</li>
     *   <li>正文 = <b>内容</b>（只有它才能证明「这俩是同一份东西」）</li>
     * </ul>
     *
     * <p>所以不把三种东西硬揉进一个数字，而是<b>先分层、层内再比分数</b>。
     * 分数保持原样（不让已有候选的分数发生漂移），只影响排序与提示文案。
     */
    public enum Kind {
        /** 正文共享行覆盖较短那篇 ≥40%：最像字面重复，最该先看 */
        DUPLICATE,
        /** 正文共享覆盖不足：同话题的不同阶段记录（或只是共用模板骨架） */
        SERIES,
        /** 长段落型文档，没有行边界可比，信息不足 */
        UNKNOWN
    }

    /**
     * 一对文档的相似度。
     *
     * <p>权重取 0.34 / 0.33 / 0.33：三个信号各自独立、没有任何一个被验证过「应该更重」，
     * 等权是最不容易过拟合的选择。同项目 +0.02、同分类 +0.01 只做微弱倾向，
     * 不足以把一对无关文档顶过阈值。
     *
     * <p>⚠️ **2026-10-25 23:38 更新认知**：等权本身没错，错在**只用一个数字排序**。
     * 实测「标签 1.00 + 标题 0.75」即使正文完全不重也能拿 0.59 —— 于是演进链排在真重复前面。
     * 现在的处理不是改权重（那会让所有历史分数漂移、也让「为什么像」变得难解释），
     * 而是保留分数、增加 {@link Kind} 分层，由调用方**先分层再比分数**。
     * 分数仍然忠实反映「三路信号的加权相似度」，只是不再单独承担排序职责。
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
        // 覆盖率的分子分母都用「参与比较的那一套行」（去模板后），与 shared 同源
        double coverage = lineMode ? containment(ca, cb) : 0;
        // 原始共享行（不剔模板）：用于回执里对比「骨架重合 vs 内容重合」
        int sharedAll = lineMode ? intersectionSize(a.lines(), b.lines()) : 0;
        // 共享行的平均字数：区分「骨架」（短标签）与「内容」（长句）的关键
        int avgLen = 0;
        if (lineMode && shared > 0) {
            int totalChars = 0;
            Set<String> smaller = ca.size() <= cb.size() ? ca : cb;
            Set<String> bigger = smaller == ca ? cb : ca;
            for (String ln : smaller) if (bigger.contains(ln)) totalChars += ln.length();
            avgLen = totalChars / shared;
        }
        return new Score(total, tag, title, content, shared, ca.size(), cb.size(), lineMode,
                coverage, sharedAll, avgLen);
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
