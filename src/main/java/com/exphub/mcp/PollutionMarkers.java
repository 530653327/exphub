package com.exphub.mcp;

import com.exphub.entity.Doc;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * #1272 C14：回归评测污染源按**内容**判定，不再只靠硬编码 ID 表。
 *
 * <p>为什么改：阁主 18:20 实测 #677（搜索方案主文档）的命中片段里明明白白含验收 query 原话，
 * 但 {@code REGRESSION_POLLUTION_IDS} 里没有它，线上就没标。ID 表每加一篇回执/信箱/方案文档
 * 都要手工补一次，漏一次就是一处静默失效 —— 而这次漏的正好是方案主文档。</p>
 *
 * <p>主判据 = 文档正文/摘要/标题里出现「长且独特」的验收 query 原话（清单在
 * {@code classpath:pollution_markers.txt}）。ID 表降级为兜底：只覆盖那些正文暂未含原话、
 * 但确属回执/信箱的文档。<b>集合齐不齐不再是正确性前提</b>，这正是 C14 要的修复。</p>
 *
 * <p>只标注、不排除、不改排序：有人就是要搜信箱和方案文档，服务端不该替调用方隐藏结果。</p>
 */
final class PollutionMarkers {

    private static final String RESOURCE = "/pollution_markers.txt";

    private static final List<String> MARKERS = load();

    private PollutionMarkers() {
    }

    private static List<String> load() {
        List<String> out = new ArrayList<>();
        try (InputStream in = PollutionMarkers.class.getResourceAsStream(RESOURCE)) {
            if (in == null) {
                return out;
            }
            try (BufferedReader r = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
                String line;
                while ((line = r.readLine()) != null) {
                    String s = line.trim();
                    // 清单里的短词会把正常文档误标成污染源，加载时再挡一道
                    if (!s.isEmpty() && !s.startsWith("#") && s.length() >= 7) {
                        out.add(s);
                    }
                }
            }
        } catch (Exception e) {
            // 加载失败时返回空清单，ID 兜底仍生效；绝不因为标注功能异常把搜索打挂
            System.err.println("PollutionMarkers load failed: " + e.getMessage());
        }
        return List.copyOf(out);
    }

    static List<String> markers() {
        return MARKERS;
    }

    /** 内容判定：任一字段整串包含任一标记词。 */
    static boolean matches(Doc doc) {
        if (doc == null || MARKERS.isEmpty()) {
            return false;
        }
        for (String marker : MARKERS) {
            if (contains(doc.getTitle(), marker)
                    || contains(doc.getSummary(), marker)
                    || contains(doc.getContent(), marker)) {
                return true;
            }
        }
        return false;
    }

    private static boolean contains(String field, String marker) {
        return field != null && !field.isEmpty() && field.contains(marker);
    }
}
