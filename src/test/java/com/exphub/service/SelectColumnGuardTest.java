package com.exphub.service;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 列白名单守卫：禁止在查文档时硬编码 select 列。
 *
 * <p><b>2026-10-26 00:18 的线上事故</b>：{@code buildIndex} 里有
 * <pre>
 *   qw.select("id","title","tags","aliases","category","project_id","status","superseded_by","content")
 * </pre>
 * 后来打分需要 {@code template_type}（判断进度类降级），**但白名单没加** →
 * {@code getTemplateType()} 恒为 {@code null} → 新功能在线上完全不生效。
 *
 * <p>而这一次的失败方式是**最恶劣的一种**：
 * <ul>
 *   <li>编译通过（字段确实存在）</li>
 *   <li>单测通过（测试里直接 new Sketch，绕过了数据库查询）</li>
 *   <li>回执不报错（只是没降级，看起来"判成演进链"也合理）</li>
 * </ul>
 * 只有拿真实数据跑一遍、发现分数明明该降级却没降，才会暴露。
 *
 * <p><b>同一天第三次「白名单漏字段」</b>：
 * ① 忆阁 {@code updateStatus} 漏 {@code updatedName}
 * ② 忆阁 {@code createExperience} 的注释早写着「不能只挑已知字段，否则静默丢参数」
 * ③ 这里漏 {@code template_type}
 * 三次形态完全一样 —— **白名单在加字段时不会提醒你**。
 *
 * <p>所以这个测试不检查「有没有漏某个字段」（那永远追不上），而是
 * <b>直接禁止白名单本身</b>：查 Doc 就查全部列。拿一点 IO 换「永不再漏」。
 */
class SelectColumnGuardTest {

    private static final Path SERVICE =
            Path.of("src/main/java/com/exphub/service/SimilarityService.java");

    /** 匹配 qw.select("...") 这种硬编码列调用 */
    private static final Pattern COLUMN_LIST = Pattern.compile(
            "\\b(?:qw|queryWrapper|wrapper)\\.select\\s*\\(\\s*\"");

    /** 去掉注释再检查 —— 注释里写反面示例是允许的（我自己就写了），不能当违规 */
    private static String stripComments(String src) {
        StringBuilder out = new StringBuilder(src.length());
        boolean inBlock = false;
        for (String line : src.split("\n", -1)) {
            String t = line;
            if (inBlock) {
                int end = t.indexOf("*/");
                if (end < 0) { out.append('\n'); continue; }
                t = t.substring(end + 2);
                inBlock = false;
            }
            int blk = t.indexOf("/*");
            if (blk >= 0) {
                int end = t.indexOf("*/", blk + 2);
                if (end < 0) { t = t.substring(0, blk); inBlock = true; }
                else { t = t.substring(0, blk) + t.substring(end + 2); }
            }
            int lineComment = t.indexOf("//");
            if (lineComment >= 0) t = t.substring(0, lineComment);
            out.append(t).append('\n');
        }
        return out.toString();
    }

    @Test
    void documentQueriesMustNotHardcodeColumnLists() throws IOException {
        String src = stripComments(Files.readString(SERVICE, StandardCharsets.UTF_8));
        List<Integer> hits = new ArrayList<>();
        Matcher m = COLUMN_LIST.matcher(src);
        while (m.find()) {
            // 行号便于定位
            int line = (int) src.substring(0, m.start()).chars().filter(c -> c == '\n').count() + 1;
            hits.add(line);
        }
        assertTrue(hits.isEmpty(),
                "SimilarityService 里出现了硬编码列白名单（第 " + hits + " 行）。\n"
                        + "为什么禁止：加新字段时白名单不会提醒你，新功能会静默不生效\n"
                        + "（2026-10-26 漏 template_type，导致进度降级线上完全没跑）。\n"
                        + "正确做法：不调 select(...)，让 MyBatis-Plus 查全部列。");
    }

    /**
     * 反向验证：模板字段必须真的能传进 Sketch。
     *
     * <p>这条只验构造链路，不验数据库 —— 数据库那环由上面的白名单禁令保证。
     */
    @Test
    void templateTypeReachesSketch() {
        SimilarityScorer.Sketch s = SimilarityScorer.Sketch.of(
                1L, "待开发：新增工具", "进度,交接", "", "运维", 1L, "project_progress", "正文占位内容");
        assertTrue("project_progress".equals(s.templateType()),
                "templateType 必须能从构造参数带进来，实际 " + s.templateType());
        assertTrue(SimilarityScorer.isProgressType(s.templateType()));

        // 老的四参式（不带 templateType）仍要能用，不能因为加了新字段就破现有调用
        SimilarityScorer.Sketch legacy = SimilarityScorer.Sketch.of(
                2L, "普通文档", "标签", "", "分类", 1L, "正文占位内容");
        assertTrue(legacy.templateType() == null, "旧构造返回 null，不该抛异常");
    }
}
