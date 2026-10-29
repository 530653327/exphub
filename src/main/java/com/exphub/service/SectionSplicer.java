package com.exphub.service;

import java.util.ArrayList;
import java.util.List;

/**
 * 按「## 小节标题」整节替换正文（2026-10-29 越哥 P2-6）。
 *
 * <p><b>为什么需要它</b>：滚动日志类文档要求「用 patch 替换最新一轮整节」，但 {@code patch_experience}
 * 要求 find 在正文里<b>恰好命中一次</b>。整节内容每轮全变，锚点只能靠「上一节尾巴 + 本节标题」夹着，
 * 标题一改就断；而修三处事实错误就要赔三次整篇写回（= 三个快照）。这个工具把「定位」和「替换」分开：
 * 你只说节标题，边界由代码算。
 *
 * <p><b>三条硬规则</b>（都是为了让失败可解释，而不是猜）：
 * <ol>
 *   <li>命中 <b>0</b> 个 → 拒绝，并列出正文里现有的二级标题（直接照抄就能用）</li>
 *   <li>命中 <b>≥2</b> 个 → 拒绝，并列出每个命中的行号与所在前缀（并列候选必须由人挑）</li>
 *   <li>{@code newContent} 为空 → 拒绝，提示「要清空整节请用 patch，或传一句占位说明」——
 *       静默把一节删空是最容易后悔的操作</li>
 * </ol>
 *
 * <p>本节范围 = 从该标题行开始，到<b>下一个同级或更高级标题</b>之前（不含）。三级及以下标题不影响边界。
 *
 * <p>纯函数、无 IO，便于单测（边界判断全靠它）。
 */
public final class SectionSplicer {

    /** 替换成功时需要回执的事实：字符区间 + 前后上下文，否则调用方不知道自己截到哪儿了 */
    public record Result(boolean ok,
                         String message,
                         String newContent,
                         int start,
                         int end,
                         String heading,
                         String before,
                         String after,
                         List<String> headings,
                         List<String> candidates) {

        static Result fail(String msg) {
            return new Result(false, msg, null, -1, -1, null, null, null, List.of(), List.of());
        }

        static Result fail(String msg, List<String> headings, List<String> candidates) {
            return new Result(false, msg, null, -1, -1, null, null, null, headings, candidates);
        }
    }

    private static final int CTX = 40;

    private SectionSplicer() {}

    public static Result splice(String content, String sectionHeading, String newBody) {
        if (content == null) {
            return Result.fail("该文档没有正文，无法按节替换");
        }
        String h = normalizeHeading(sectionHeading);
        if (h.isEmpty()) {
            return Result.fail("sectionHeading 不能为空。传二级标题的文本即可，例如「最新一轮」，不用带 ##");
        }
        if (newBody == null || newBody.trim().isEmpty()) {
            return Result.fail("newContent 为空。要清空整节请用 patch_experience（删指定片段），"
                    + "或传一句占位说明 —— 静默把一节删空最容易后悔");
        }

        String[] lines = content.split("\n", -1);
        List<Integer> hitLines = new ArrayList<>();
        List<String> allHeadings = new ArrayList<>();
        for (int i = 0; i < lines.length; i++) {
            int lv = headingLevel(lines[i]);
            if (lv == 0) continue;
            String t = normalizeHeading(lines[i]);
            if (lv <= 3) allHeadings.add("「" + t + "」");
            if (t.equals(h)) hitLines.add(i);
        }

        if (hitLines.isEmpty()) {
            String tip = allHeadings.isEmpty() ? "（这篇正文里没有任何一~三级标题）"
                    : "现有标题：" + String.join("、", allHeadings);
            return Result.fail("没找到标题为「" + h + "」的小节。" + tip, allHeadings, List.of());
        }
        if (hitLines.size() > 1) {
            List<String> cands = new ArrayList<>();
            for (int i : hitLines) {
                cands.add("第 " + (i + 1) + " 行：" + lines[i].trim());
            }
            return Result.fail("标题「" + h + "」在正文里出现 " + hitLines.size()
                    + " 次，无法确定改哪一节。请先把标题改成互不相同（或改用 patch 精确替换）。", allHeadings, cands);
        }

        int startLine = hitLines.get(0);
        int level = headingLevel(lines[startLine]);
        int endLine = lines.length;
        // 边界：下一个「同级或更高级」标题（level ≤ 本节 level）；更深层标题属于本节内容
        for (int i = startLine + 1; i < lines.length; i++) {
            int lv = headingLevel(lines[i]);
            if (lv != 0 && lv <= level) {
                endLine = i;
                break;
            }
        }

        int start = offsetOf(lines, startLine);
        int end = offsetOf(lines, endLine);

        // 标题行**原样保留**（2026-10-29 越哥要求）：原来取 lines[startLine].trim()，
        // 会把「  ### 甲」这类带缩进的标题改成顶格 —— 只是替换正文，不该顺手改别人的格式。
        String head = lines[startLine];
        if (head.endsWith("\r")) head = head.substring(0, head.length() - 1);
        String body = newBody.trim();
        String block = head + "\n\n" + body + "\n\n";
        String tail = (end < content.length()) ? content.substring(end) : "";
        String merged = content.substring(0, start) + block + tail;

        String before = window(content, Math.max(0, start - CTX), start);
        String after = window(content, end, Math.min(content.length(), end + CTX));

        return new Result(true,
                "命中标题「" + h + "」（第 " + (startLine + 1) + " 行起，到第 " + endLine + " 行之前）",
                merged, start, end, head, before, after, allHeadings, List.of());
    }

    /** 把「## 最新一轮」「最新一轮」「##最新一轮」统一成「最新一轮」 */
    static String normalizeHeading(String s) {
        if (s == null) return "";
        String t = s.trim();
        int i = 0;
        while (i < t.length() && t.charAt(i) == '#') i++;
        if (i > 0) t = t.substring(i);
        return t.trim();
    }

    /**
     * 标题级别：{@code #} 的数量（1~6），不是标题返回 0。
     *
     * <p>2026-10-29 修正：原来只认 1~2 级 —— 但实际文档里「三、待办」这类小节常写成 {@code ###}，
     * 于是工具在最该用的地方用不了。改成通用级别判断：边界 = 下一个「级别 ≤ 本节级别」的标题，
     * 更深的子标题属于本节内容。
     */
    static int headingLevel(String line) {
        if (line == null) return 0;
        String t = line.trim();
        if (t.isEmpty() || t.charAt(0) != '#') return 0;
        int n = 0;
        while (n < t.length() && t.charAt(n) == '#') n++;
        if (n < 1 || n > 6) return 0;
        if (n >= t.length()) return 0;                 // 纯 "###" 不算标题
        char c = t.charAt(n);
        if (c != ' ' && c != '\t') return 0;           // 必须有空白，避免把 #tag 当标题
        return n;
    }

    /** 是标题行则返回标题文本，否则返回 null（一~六级都算，级别见 {@link #headingLevel}） */
    static String headingText(String line) {
        return headingLevel(line) == 0 ? null : normalizeHeading(line);
    }

    /** 第 lineIdx 行的起始字符偏移（按 \n 计，行尾的 \n 归前一行） */
    private static int offsetOf(String[] lines, int lineIdx) {
        int off = 0;
        for (int i = 0; i < lineIdx && i < lines.length; i++) {
            off += lines[i].length() + 1;
        }
        return Math.min(off, sumLen(lines));
    }

    private static int sumLen(String[] lines) {
        int n = 0;
        for (String l : lines) n += l.length() + 1;
        return Math.max(0, n - 1);
    }

    private static String window(String s, int from, int to) {
        if (from >= to || from >= s.length()) return "";
        int f = Math.max(0, from);
        int t = Math.min(s.length(), to);
        String w = s.substring(f, t).replace("\n", "⏎");
        return (f > 0 ? "…" : "") + w + (t < s.length() ? "…" : "");
    }
}
