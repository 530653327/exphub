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

    /**
     * 「整篇复制」允许的最大独有行占比（两侧都要低于它）。
     *
     * <p>⚠️ <b>这个门目前没有正样本</b>（2026-10-26 01:40 全库 7 对 ①层样本里 0 对真重复）。
     * 它取 0.30 是「复制品天然允许 10~20% 的尾部差异」这一常识，不是量出来的。
     * 将来若真重复被错判成 {@link Shape#PARALLEL}，应当重跑全库重新量，不要只改这个数字。
     */
    public static final double MAX_OWN_SHARE_FOR_TRUE_DUP = 0.30;

    /**
     * 判「母集-子集」的子集侧独有行占比上限（> 它就不算被包含）。
     *
     * <p>全库 7 对 ①层样本实测：母集-子集那 4 对是 0.17 / 0.18 / 0.20 / 0.30，
     * 并列那 3 对是 0.43 / 0.475 / 0.49 —— 阈值 0.35 落在这条缝里。
     */
    public static final double MAX_OWN_SHARE_FOR_SUBSET = 0.35;

    /**
     * ②层里判「可能是母集-子集」的<b>行数比</b>门槛：大的一侧 ≥ 小的 <b>4 倍</b>。
     *
     * <p><b>为什么②层也要判形态（2026-10-26 02:2x 越哥实测指出）</b>：
     * #514（120 行）↔ #542（5 行）落在②层，回执只写「只有标签/标题像…拿不准判 distinct」——
     * 结论碰巧对（别判），<b>理由却是错的</b>：它是典型的 24:1 母集-子集，
     * 而形态检测当时只在①层做。**建议对了、理由错了，下次换个数据就会把错的理由用错地方。**
     *
     * <p>⚠️ 这是<b>弱信号</b>（只看行数、不看内容），所以文案必须写成「很可能是」而不是「是」，
     * 且动作仍要求人先读一眼母档有没有把这些行收进去。阈值 4 是常识值，没有正样本量过。
     */
    public static final int MIN_LINES_RATIO_FOR_SUBSET = 4;

    private static final java.util.regex.Pattern DATE_FULL =
            java.util.regex.Pattern.compile("(\\d{4})[-/年](\\d{1,2})[-/月](\\d{1,2})日?");
    private static final java.util.regex.Pattern DATE_MD =
            java.util.regex.Pattern.compile("(?<!\\d)(\\d{1,2})[-/月](\\d{1,2})日?(?!\\d)");
    private static final java.util.regex.Pattern NUM_TOKEN =
            java.util.regex.Pattern.compile("([\\p{IsHan}A-Za-z]{0,2})(\\d{1,3})");

    /** 标题里的日期标记，统一成 MM-DD；同一天的不同写法（10月23日 / 10-23 / 2026-10-23）视为同一个 */
    static java.util.Set<String> dateMarks(String title) {
        java.util.Set<String> out = new java.util.LinkedHashSet<>();
        if (title == null || title.isBlank()) return out;
        java.util.regex.Matcher f = DATE_FULL.matcher(title);
        StringBuilder rest = new StringBuilder();
        int last = 0;
        while (f.find()) {
            out.add(pad(f.group(2)) + "-" + pad(f.group(3)));
            rest.append(title, last, f.start()).append(' ');
            last = f.end();
        }
        rest.append(title.substring(last));
        java.util.regex.Matcher m = DATE_MD.matcher(rest);
        while (m.find()) {
            int mm = Integer.parseInt(m.group(1)), dd = Integer.parseInt(m.group(2));
            if (mm < 1 || mm > 12 || dd < 1 || dd > 31) continue;
            // ⚠️ 2026-10-26 02:38 实测踩到：标题「…约 1030kcal，脂肪一顿用掉 3/4 碗…」里的 3/4
            // 被当成 03-04，回执写成「日期不同（10-23 / 03-04 vs 10-24）」—— 噪声进了证据。
            // 收紧两条：① 只认标题**开头**的日期（日志类命名习惯：日期在当前 12 个字符内）
            // ② 紧跟量词/单位的（3/4 碗、2/3 杯、1/2 天）不是日期
            if (m.start() > 12) continue;
            int end = m.end();
            if (end < rest.length() && "碗杯个份克千卡次分时天日人片勺".indexOf(rest.charAt(end)) >= 0) continue;
            out.add(pad(m.group(1)) + "-" + pad(m.group(2)));
        }
        return out;
    }

    /** 标题里的编号标记（地图1 / 地图2 / v3 / 第2版）—— 已剔掉日期部分，避免 10-23 被拆成 10 和 23 */
    static java.util.Set<String> numberMarks(String title) {
        java.util.Set<String> out = new java.util.LinkedHashSet<>();
        if (title == null || title.isBlank()) return out;
        String stripped = DATE_FULL.matcher(title).replaceAll(" ");
        stripped = DATE_MD.matcher(stripped).replaceAll(" ");
        java.util.regex.Matcher m = NUM_TOKEN.matcher(stripped);
        while (m.find()) {
            String prefix = m.group(1).trim();
            // 纯数字（前面没有任何字）不当编号：那是页码、数量之类，分不清谁属于谁
            if (prefix.isEmpty()) continue;
            out.add(prefix + Integer.parseInt(m.group(2)));
        }
        return out;
    }

    private static String pad(String v) {
        return v.length() >= 2 ? v : "0" + v;
    }

    /**
     * 这一对是不是<b>并列的两份独立记录</b>（不是母集-子集）—— 只靠标题里的结构信号判。
     *
     * <p><b>为什么必须补这个排除项（2026-10-26 02:32 越哥实测，`[ ]` 第七版理由）</b>：
     * 上一版刚加的「行数差 ≥4:1 → 很可能是母集-子集」把
     * #548「10-23午餐实录」7 行 ↔ #558「10-24 全天饮食实录」54 行（约 8:1）
     * 判成了母集，后面直接跟「把这 7 行并回母档、再清子页」——
     * <b>那是两天的独立记录，照做就是把 10-23 的午餐并进 10-24 的记录里，日期都串了。</b>
     *
     * <p><b>行数比只说明「长短悬殊」，说明不了「谁属于谁」</b>。
     * 结构能查出来的只有日期与编号；主体是否相同（两只宠物 / 两个专家）
     * 只能靠人看标题 —— 所以回执里必须把这三项**一起**列成排除清单，而不是只报行数比。
     *
     * @return 检测到的差异（写进回执的人话），没检测到返回 null（= 仍需人工排除，不是「确认是母集」）
     */
    public static String siblingReason(String titleA, String titleB) {
        if (titleA == null || titleB == null) return null;
        java.util.Set<String> da = dateMarks(titleA), db = dateMarks(titleB);
        if (!da.isEmpty() && !db.isEmpty() && java.util.Collections.disjoint(da, db)) {
            return "标题里的日期不同（" + String.join(" / ", da) + " vs " + String.join(" / ", db) + "）";
        }
        java.util.Set<String> na = numberMarks(titleA), nb = numberMarks(titleB);
        if (!na.isEmpty() && !nb.isEmpty() && java.util.Collections.disjoint(na, nb)) {
            return "标题里的编号不同（" + String.join(" / ", na) + " vs " + String.join(" / ", nb) + "）";
        }
        return null;
    }

    /** 三项排除清单 —— 只报行数比的回执是不够的，必须把「哪些不同就算并列」摊开写 */
    public static final String SIBLING_EXCLUSIONS =
            "先按顺序排除三项：① **日期不同**（如 10-23 vs 10-24）② **编号不同**（如 地图1 vs 地图2、v2 vs v3）"
            + "③ **主体不同**（同一套模板写出来的两个不同对象 —— 看标题写的是谁、哪一件，"
            + "**别照抄这几个例子去对号入座**）—— "
            + "**任何一项不同 = 并列记录，判 distinct，谁也别并、谁也别清**";

    /**
     * 标题像不像「母档的补充页」（补充 / 续篇 / 第 N 部分 / 附记）。
     *
     * <p><b>为什么单看标题就够（2026-10-26 02:42 越哥实测 #514↔#542）</b>：
     * #542 标题是「史越个人档案**补充**：家庭、饮食偏好与饮茶习惯」——
     * 它本来就是给 #514 补料的，正确动作是<b>把那 5 行并进 #514</b>，
     * 而不是「确认 #514 已包含它」（0 行重合 = 一行都没包含，那个确认必然失败）。
     *
     * <p>⚠️ 只看「补充 / 续篇 / 第N部分 / 附记」这类**结构性命名**，不看内容语义——
     * 判不了就返回 false，回执退回「拿不准判 distinct」。
     */
    public static boolean isSupplementTitle(String title) {
        if (title == null || title.isBlank()) return false;
        return SUPPLEMENT_P.matcher(title).find();
    }

    private static final java.util.regex.Pattern SUPPLEMENT_P = java.util.regex.Pattern.compile(
            "补充|补遗|补记|追加|附记|附录|续篇|（续）|\\(续\\)|第[一二三四五六七八九十\\d]{1,3}部分");

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
                         String templateType,
                         Set<String> titleGrams,
                         Set<String> contentGrams,
                         Set<String> lines,
                         Set<String> distinctLines,
                         boolean lineModeReliable,
                         int contentLen) {

        /** 构造快照；distinct 相关字段由作用域索引在统计完行 df 后回填 */
        public static Sketch of(Long id, String title, String tags, String aliases,
                                String category, Long projectId, String content) {
            return of(id, title, tags, aliases, category, projectId, null, content);
        }

        /** 带 templateType 的构造（进度类记录要据此降级） */
        public static Sketch of(Long id, String title, String tags, String aliases,
                                String category, Long projectId, String templateType, String content) {
            String body = content == null ? "" : content;
            // 必须写全类名：record 自己就有同名访问器，裸调用会被解析成无参版本
            return new Sketch(id,
                    title == null ? "" : title,
                    SimilarityScorer.tokens(tags),
                    SimilarityScorer.tokens(aliases),
                    category,
                    projectId,
                    templateType,
                    SimilarityScorer.titleGrams(title),
                    SimilarityScorer.contentGrams(body),
                    SimilarityScorer.lines(body),
                    Set.of(),
                    false,
                    body.length());
        }

        public Sketch withDistinctLines(Set<String> distinct, boolean reliable) {
            return new Sketch(id, title, tags, aliases, category, projectId, templateType,
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
     * @param progressPair    两侧都是「进度/日志类」记录 —— 这类天然是演进链，单独降一级
     */
    public record Score(double total, double tag, double title, double content,
                        int sharedLines, int linesA, int linesB, boolean lineMode,
                        double lineCoverage, int sharedAllLines, int sharedAvgLen,
                        boolean progressPair, String titleA, String titleB) {

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
            // 进度类优先判断：两侧都是进度/待办记录时，即使正文重合也降级到最后。
            // 原因见 Kind.PROGRESS 的注释 —— 这类记录的「像」是工作流决定的，不是内容重复。
            if (progressPair) return Kind.PROGRESS;
            if (!lineMode) return Kind.UNKNOWN;
            boolean enough = sharedLines >= MIN_SHARED_LINES
                    && lineCoverage >= MIN_LINE_COVERAGE
                    && sharedAvgLen >= MIN_SHARED_LINE_LEN;
            return enough ? Kind.DUPLICATE : Kind.SERIES;
        }

        /** A 侧独有的行数（参与了比较的那一套行里，对方没有的） */
        public int uniqueA() { return Math.max(0, linesA - sharedLines); }

        /** B 侧独有的行数 */
        public int uniqueB() { return Math.max(0, linesB - sharedLines); }

        /** A 侧独有行占 A 的比例 */
        public double ownShareA() { return linesA == 0 ? 0 : (double) uniqueA() / linesA; }

        /** B 侧独有行占 B 的比例 */
        public double ownShareB() { return linesB == 0 ? 0 : (double) uniqueB() / linesB; }

        /**
         * 这一对「重合得像什么」的三种形态 —— 只看覆盖率分不出来，必须看<b>两侧各自剩多少独有行</b>。
         *
         * <p>为什么必须分（2026-10-26 01:35 越哥实测指出）：①层（覆盖 ≥40% 且共享行够长）
         * 里同时装着三种东西，而它们该做的动作完全不同：
         * <pre>
         *   #159↔#160 前端开发专家 / 系统测试工程师  独有 50% / 49%  → 同模板实例，判 duplicate 等于用一个角色取代另一个
         *   #63↔#67   地图设计 / 地图3提示词        独有 80% / 17%  → 母集-子集，子文档不是复制品
         *   #514↔#545 个人档案 / 减脂体重记录       独有 97% / 43%  → 仅 4 行相同，短的那篇恰好被包住
         * </pre>
         * 全库 7 对 ①层样本里，没有一对是「两侧独有都很少」的真复制。
         *
         * <p>⚠️ <b>形态只决定回执给什么建议，不把任何一对踢出①层</b> ——
         * 覆盖率仍然表示「重合多不多」，是不是真重复得靠人看独有行数定。
         */
        public Shape shape() {
            if (linesA == 0 || linesB == 0) return Shape.UNKNOWN;
            double oa = ownShareA(), ob = ownShareB();
            if (Math.max(oa, ob) <= MAX_OWN_SHARE_FOR_TRUE_DUP) return Shape.TRUE_DUP;
            if (Math.min(oa, ob) <= MAX_OWN_SHARE_FOR_SUBSET) return Shape.SUBSET;
            return Shape.PARALLEL;
        }

        /**
         * 给人看的一句话定性：比分数本身更能指导动作。
         *
         * <p>回答的是「我该判 duplicate 还是 superseded」——这是队列里每条都要做的决定，
         * 只看分数根本判不出来。
         *
         * <p>⚠️ <b>这是回执文案的唯一来源</b>：{@code ExpHubTools} 的分层标签与
         * {@link #advice} 共用同一段文字。此前两处各写一份，结果文档改了工具没改，
         * 工具还在建议「大概率该判 superseded」，而实际命中的是同模板的两个宠物
         * （#77 灵狐 ↔ #83 神龙，0 行重合）—— 判 superseded 等于用一只宠物取代另一只。
         * <b>一条建议只能有一个出处。</b>
         */
        public String diagnosis() {
            return advice(kind(), sharedLines, lineCoverage, sharedAvgLen, linesA, linesB, titleA, titleB);
        }

        public static String fmt(double v) {
            return String.format(java.util.Locale.ROOT, "%.2f", v);
        }

        /**
         * 母集-子集的<b>统一动作</b> —— 全系统只此一份，①层与②层都调它。
         *
         * <p><b>为什么必须单独立出来（2026-10-26 02:2x 越哥实测指出，第 N 次同类问题）</b>：
         * 上一轮把①层的建议从「优先判 duplicate」改成「别判 duplicate，先看是不是 superseded」，
         * 挡住了 duplicate 这个方向，<b>却把 superseded 这个方向放开了</b> ——
         * 而 superseded 对母集-子集正是会丢内容的那个动作：
         *
         * <pre>
         *   #63 母档 195 行 ↔ #67 子页 47 行（子页独有 8 行 / 17%）
         *   #65 独有 8 行（20%）、#68 独有 18 行（30%）
         * </pre>
         * 判 superseded（子页 ← 母档）之后，那 8~18 行永久藏在「⛔ 已被 #63 取代」后面，
         * 下一个 AI 读到就跳过 —— <b>这不是收敛，是信息丢失</b>。
         * 同一个隐患换了个出口，比缺一条建议严重。
         *
         * @param kidLines 子页（被包含那一侧）的独有行数 —— 就是要保住的那几行
         */
        /**
         * 同一套判法的<b>短版</b>（用于处理说明那种一行一条的清单，放不下长版）。
         *
         * <p>与 {@link #subsetAction(int)} 同在这个类里、紧邻放置，就是为了**改的时候一眼看到另一份** ——
         * 散到 {@code ExpHubTools} 里各写一份，就会重演「文档改了工具没改」。
         */
        public static final String SUBSET_RULE_SHORT =
                "一侧几乎整篇包含另一侧（母集-子集）→ **两个都别判**：duplicate 会用母档取代子页、"
                + "superseded 会把子页独有行埋在「已被取代」后面；**并回母档再清，并不了判 distinct**";

        public static String subsetAction(int kidLines) {
            return "**别判 duplicate，也别判 superseded** —— superseded 只写指针，"
                    + "小那侧独有的 " + kidLines + " 行会永久藏在「⛔ 已被 #N 取代」标记后面，"
                    + "下一个读到的 AI 直接跳过，**那是信息丢失不是收敛**。"
                    + "正确动作：**先把这 " + kidLines + " 行并回母档，确认母档已含它们，再清子页**；"
                    + "并不了 / 不确定母档是否已含，就判 **distinct**（两份都留着，别为了「干净」丢掉细节）";
        }

        public static String pct(double v) {
            return String.format(java.util.Locale.ROOT, "%.0f%%", v * 100);
        }
    }

    /**
     * ①层（正文重合度高）里混着的三种形态 —— 只看覆盖率分不出来，靠<b>两侧各剩多少独有行</b>分。
     *
     * <p><b>为什么必须有（2026-10-26 01:35 越哥实测指出）</b>：①层的回执原来一律写
     * 「优先判 duplicate」，可这一层装着三种东西，动作完全不同：
     *
     * <pre>
     *   真重复     两侧独有都很少   —— 整篇复制            → duplicate
     *   母集-子集   一侧独有很少     —— 子文档 ⊂ 母文档     → 别判 duplicate（先确认 superseded / distinct）
     *   同模板实例 两侧各有约一半独有 —— 不同实体/不同角色   → distinct（判 duplicate 等于用一个取代另一个）
     * </pre>
     *
     * <p>⚠️ 形态<b>只决定回执给什么建议，不改分层、不踢候选</b>。
     */
    public enum Shape {
        /** 两侧独有行都很少：整篇复制 */
        TRUE_DUP,
        /** 一侧几乎整篇被另一侧包含：母集-子集 */
        SUBSET,
        /** 两侧各有大量独有行：同一模板的两个实例，或同一话题的两份并列记录 */
        PARALLEL,
        /** 行口径不可用（长段落型），分不出形态 */
        UNKNOWN
    }

    /**
     * 回执建议文案的<b>唯一出处</b>。
     *
     * <p>{@link Score#diagnosis()} 与 {@code ExpHubTools} 的候选列表回执都调这里 ——
     * 同一句话有两份实现必然漂移（2026-10-26 晚上已经踩到第 N 次：文档改成
     * 「演进链要先排除母集-子集」，工具还在写「大概率该判 superseded」）。
     *
     * @param sharedLines   去模板后两侧完全一致的行数
     * @param lineCoverage  共享行 / 较短那篇的行数
     * @param sharedAvgLen  共享行的平均字数
     * @param linesA/linesB 两侧参与比较的行数（用来算独有行，判断 {@link Shape}）
     */
    public static String advice(Kind kind, int sharedLines, double lineCoverage,
                                int sharedAvgLen, int linesA, int linesB) {
        return advice(kind, sharedLines, lineCoverage, sharedAvgLen, linesA, linesB, null, null);
    }

    /**
     * @param titleA/titleB 两侧标题 —— 只有它能提供「日期 / 编号 / 主体」这类<b>结构身份信号</b>，
     *                      而行数比恰恰判断不了「谁属于谁」（2026-10-26 02:32 越哥实测）。
     *                      传 null 表示拿不到标题：此时<b>不许</b>下「母集-子集」的结论，只能给排除清单。
     */
    public static String advice(Kind kind, int sharedLines, double lineCoverage,
                                int sharedAvgLen, int linesA, int linesB,
                                String titleA, String titleB) {
        if (kind == null) return "";
        int ua = Math.max(0, linesA - sharedLines);
        int ub = Math.max(0, linesB - sharedLines);
        double oa = linesA == 0 ? 0 : (double) ua / linesA;
        double ob = linesB == 0 ? 0 : (double) ub / linesB;
        Shape shape;
        if (linesA == 0 || linesB == 0) shape = Shape.UNKNOWN;
        else if (Math.max(oa, ob) <= MAX_OWN_SHARE_FOR_TRUE_DUP) shape = Shape.TRUE_DUP;
        else if (Math.min(oa, ob) <= MAX_OWN_SHARE_FOR_SUBSET) shape = Shape.SUBSET;
        else shape = Shape.PARALLEL;
        String cov = Score.pct(lineCoverage);
        return switch (kind) {
            case DUPLICATE -> "**正文重合度高**（共享 " + sharedLines + " 行，平均 " + sharedAvgLen
                    + " 字，覆盖较短那篇 " + cov + "）\n"
                    + "　⚠️ 重合高 ≠ 就是重复：这一层混着三种形态，用独有行数分辨 —— "
                    + "A 独有 " + ua + " 行（占 " + Score.pct(oa) + "）、"
                    + "B 独有 " + ub + " 行（占 " + Score.pct(ob) + "）。\n"
                    + switch (shape) {
                        case TRUE_DUP -> "　→ 两侧独有都很少（整篇复制）：**可以判 duplicate**";
                        case SUBSET -> {
                            String sib = siblingReason(titleA, titleB);
                            if (sib != null) {
                                yield "　→ 一侧几乎整篇在另一侧里，**但 " + sib + "** —— "
                                        + "这是两份独立记录，**不是母集-子集**，别拿「被包含」当「该被取代」。\n"
                                        + "　　**别判 duplicate，也别判 superseded**，判 **distinct**："
                                        + "谁也别并、谁也别清（照母集-子集办就是把一份并进另一份）。";
                            }
                            int kidLines = oa <= ob ? ua : ub;
                            double kidShare = Math.min(oa, ob);
                            yield "　→ 一侧几乎整篇在另一侧里（母集-子集）："
                                    + (oa <= ob ? "A" : "B") + " 侧独有 " + kidLines
                                    + " 行（占 " + Score.pct(kidShare) + "），像另一侧的子页。"
                                    + (kidShare >= MAX_OWN_SHARE_FOR_TRUE_DUP
                                       ? "⚠️ 这个占比已贴近「真重复」上限 " + Score.pct(MAX_OWN_SHARE_FOR_TRUE_DUP)
                                         + "，属边界样本 —— 先打开两边看那 " + kidLines
                                         + " 行到底是新增内容还是改写。" : "")
                                    + "\n　　"
                                    + Score.subsetAction(kidLines);
                        }
                        case PARALLEL -> "　→ 两侧各有约一半独有行（同一套模板写出来的两个对象 / 两份并列记录）："
                                + "**别判 duplicate** —— 判了等于用其中一份取代另一份，一般判 distinct；"
                                + "只有确认后者是前者同一件事的新阶段、且两侧不是不同日期/编号/主体，才 superseded"
                                + "（确认不了就 distinct —— 别为了「收敛」硬判）";
                        case UNKNOWN -> "　→ 行口径不可用，形态分不出，人工读一眼再定";
                    };
            case SERIES -> {
                int big = Math.max(linesA, linesB), small = Math.min(linesA, linesB);
                boolean ratioSubset = small > 0 && big >= small * MIN_LINES_RATIO_FOR_SUBSET;
                String ratio = "两侧行数 " + linesA + " : " + linesB
                        + (ratioSubset ? "（约 " + Math.round((double) big / small) + ":1）" : "");
                if (sharedLines > 0) {
                    yield "**共用骨架**（" + ratio + "，只重 " + sharedLines + " 行、平均仅 "
                            + sharedAvgLen + " 字、覆盖 " + cov + "，两侧还各有 " + ua + " / " + ub
                            + " 行独有）→ 同一模板的不同实例：**别判 duplicate**，一般判 distinct；"
                            + "只有确认一侧确实是另一侧的新阶段才 superseded";
                }

                // ⚠️ 0 行重合是**最强的反向证据**，优先级高于行数比（2026-10-26 02:42 越哥实测）：
                // 上一版写「0 行重合 …… 短的那篇可能整篇被长的那篇包住」—— 两句互斥。
                // 真被包住，重合度该接近 100%；0 行重合说明短的那篇一个字都没出现过。
                // 结果是把 #514↔#542（标题明说是「补充」页）引向「确认长篇已包含它」——
                // 那个确认必然失败，于是永远只能判 distinct，把一个本该合并的补充页钉在队列里。
                String sib = siblingReason(titleA, titleB);
                if (sib != null) {
                    yield "**" + ratio + " —— 长短悬殊，但 " + sib + "**："
                            + "这是两份独立记录，**不是母集-子集**（行数比只说明长短，说明不了谁属于谁）。\n　　"
                            + "**判 distinct**：谁也别并、谁也别清 —— 照母集-子集办"
                            + "就是把一份的内容并进另一份里，等于把日期/编号弄混。";
                }
                if (ratioSubset) {
                    boolean aIsShort = linesA <= linesB;
                    if (isSupplementTitle(aIsShort ? titleA : titleB)) {
                        yield "**" + ratio + "，但正文 0 行重合 —— 短的那篇一个字都没出现在长的那篇里**，"
                                + "而标题明说它是**补充页**。所以：\n　　"
                                + "❌ **不要去找「长的那篇已包含它」的证据**（0 行重合，一定找不到，"
                                + "也别按母集-子集办 —— 那套的前提是「已包含」）；\n　　"
                                + "✅ **把这 " + small + " 行并进长那篇的对应章节**，并完打开长的那篇读一遍、"
                                + "确认真读到了，再把短的那篇标为被取代 / 清掉；"
                                + "拿不准该并到哪一章就判 **distinct**（两份都留着，别丢）";
                    }
                    yield "**" + ratio + "，但正文 0 行重合 —— 一行交集都没有**，所以"
                            + "**不可能是「短的那篇整篇被包住」**（真被包住的话重合度该接近 100%）。"
                            + "两份都不包含对方。\n　　"
                            + "两种可能，选一个：① 同一条记录的两半 → **把短的那篇并进长的那篇**，"
                            + "并完确认再清；② 两份独立记录 → 判 **distinct**（谁也别并、谁也别清）。"
                            + "\n　　" + SimilarityScorer.SIBLING_EXCLUSIONS + "；都排除不掉又拿不准 → distinct";
                }
                yield "**只有标签/标题像**（" + ratio + "，正文 0 行重合 —— "
                        + "没有任何内容证据）：可能是同话题演进链，也可能是同一套模板写出来的两个不同对象"
                        + "（**别照抄示例去猜类型，看标题和正文各写了什么**）。"
                        + "⚠️ **没内容证据时不要判 superseded** —— 那等于用其中一份取代另一份。"
                        + "先排除母集-子集与同模板实例，确认是同一件事的新阶段才 superseded；拿不准判 distinct";
            }
            case UNKNOWN -> "**信息不足**（行太少，走 2-gram 兜底）→ 建议人工读一眼再定";
            case PROGRESS -> "**两侧都是进度/日志记录**（template_type 属进度类）→ "
                    + "这类天然是同一件事的分阶段记录，**一般不用判**；"
                    + "除非确认是同一条写了两遍，否则忽略";
        };
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
        UNKNOWN,
        /**
         * 两侧都是「进度 / 待办」类记录。
         *
         * <p><b>为什么单独一层（2026-10-26 00:2x 阁主判完 5 条候选后的结论）</b>：
         * 阁主逐对读正文后判了 5 条全 `distinct`，其中 #13–#16 全是
         * `#464/#470/#471/#477/#478/#479` 这一簇，而它们<b>全部是 {@code project_progress}</b>：
         * 「要做成什么样」「做了什么」「上线后踩到什么」「这活归谁」——
         * 两两都是**不同角色**的记录，标签交集恒为 1.00，必然高分。
         *
         * <p>这类记录的「像」是<b>工作流形态决定的，不是内容重复</b>：
         * 标题带阶段词/日期、标签同一批、正文每次全新。放进查重队列长期会有一大半是
         * 「同一件事的分阶段日记」，人工判到手软，而且判也是白判。
         *
         * <p><b>为什么不直接排除</b>：排除会永久隐藏这一对 —— 万一真的是同一条写了两遍，
         * 就再也发现不了。降级到最后既保住了信息，又不会占前排名额，
         * 符合「只标记不删除」的精神。
         */
        PROGRESS
    }

    /** 视为「进度 / 日志」类的 template_type：这类记录之间天然是演进链 */
    public static final Set<String> PROGRESS_TEMPLATE_TYPES =
            Set.of("project_progress", "todo_list");

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
        // 两侧都是进度/日志类 → 这一对天然是同一件事的分阶段记录（见 Kind.PROGRESS）
        boolean progressPair = isProgressType(a.templateType()) && isProgressType(b.templateType());
        return new Score(total, tag, title, content, shared, ca.size(), cb.size(), lineMode,
                coverage, sharedAll, avgLen, progressPair, a.title(), b.title());
    }

    /** template_type 是否属于「进度 / 日志」类 */
    public static boolean isProgressType(String templateType) {
        return templateType != null && PROGRESS_TEMPLATE_TYPES.contains(templateType.trim().toLowerCase());
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
