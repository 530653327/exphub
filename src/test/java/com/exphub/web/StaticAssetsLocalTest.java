package com.exphub.web;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 前端静态资源本地化守卫（2026-11-17 加）。
 *
 * <p><b>背景</b>：后台全部页面原来从 {@code cdn.jsdelivr.net} 拉 bootstrap / jquery / fontawesome /
 * marked / chart.js，共 72 处、20 个模板各写各的。jsdelivr 在国内不稳，一旦抖动或证书过期，
 * 页面就是「裸 HTML 无样式 + 按钮点了没反应」，而服务端 health 依然 200 —— 排查时极易误判成后端故障。
 *
 * <p><b>为什么要有这个测试</b>：把 CDN 换成本地只是一次批量替换，下次有人新增页面时很自然会
 * 再贴一段 CDN 的 script 标签（网上搜到的模板都长那样）。那时代码、渲染测试都全绿，
 * 只有真断网才看得出来。所以这里做两条源级守卫：
 * <ol>
 *   <li>模板里<b>不许再出现任何外链 CDN</b>（jsdelivr / unpkg / cdnjs / googleapis）；</li>
 *   <li>模板里引用到的每个 {@code /lib/**} 文件<b>必须真实存在于</b> {@code static/} 下，
 *       否则线上就是 404（th:href 拼错一个字母，页面照样渲染成功）。</li>
 * </ol>
 */
class StaticAssetsLocalTest {

    private static final Path TEMPLATE_ROOT = Path.of("src/main/resources/templates");
    private static final Path STATIC_ROOT = Path.of("src/main/resources/static");

    /** 任何 CDN 域名都算违规，不只 jsdelivr —— 换一个源同样是「国内不稳 + 断网白屏」。 */
    private static final Pattern CDN = Pattern.compile(
            "https?://(?:cdn\\.jsdelivr\\.net|unpkg\\.com|cdnjs\\.cloudflare\\.com"
                    + "|ajax\\.googleapis\\.com|fonts\\.googleapis\\.com|cdn\\.bootcdn\\.net)");

    /** 模板里的本地静态资源引用：th:href="@{/lib/...}" / th:src="@{/lib/...}" / 裸 href="/lib/..." */
    private static final Pattern LOCAL_REF = Pattern.compile(
            "(?:th:(?:href|src)|href|src)\\s*=\\s*\"(?:@\\{)?(/lib/[^\"}]+)");

    private static List<Path> templates() throws IOException {
        try (Stream<Path> s = Files.walk(TEMPLATE_ROOT)) {
            List<Path> out = new ArrayList<>();
            s.filter(p -> p.toString().endsWith(".html")).forEach(out::add);
            return out;
        }
    }

    private static String read(Path p) throws IOException {
        return Files.readString(p, StandardCharsets.UTF_8);
    }

    @Test
    @DisplayName("模板里不许再出现任何外链 CDN")
    void noCdnAnywhere() throws IOException {
        Map<String, Integer> hits = new LinkedHashMap<>();
        for (Path p : templates()) {
            Matcher m = CDN.matcher(read(p));
            while (m.find()) {
                hits.merge(TEMPLATE_ROOT.relativize(p) + " → " + m.group(), 1, Integer::sum);
            }
        }
        assertTrue(hits.isEmpty(),
                "以下模板仍在引用外部 CDN，断网/被墙时整页会没样式或交互失灵，请改成本地 "
                        + "/lib/** 路径（资源已在 static/lib 下备好）：\n" + join(hits));
    }

    @Test
    @DisplayName("模板引用的每个 /lib 文件都必须真实存在于 static 下")
    void everyLibFileExists() throws IOException {
        Map<String, String> missing = new LinkedHashMap<>();
        for (Path p : templates()) {
            Matcher m = LOCAL_REF.matcher(read(p));
            while (m.find()) {
                String ref = m.group(1);
                // /lib/xxx 在 classpath 里对应 static/lib/xxx
                Path onDisk = STATIC_ROOT.resolve(ref.substring(1));
                if (!Files.isRegularFile(onDisk)) {
                    missing.put(TEMPLATE_ROOT.relativize(p) + " → " + ref, "不存在：" + onDisk);
                }
            }
        }
        assertTrue(missing.isEmpty(), "模板引用了不存在的本地静态资源（线上即 404）：\n" + join(missing));
    }

    /** 反向兜底：static/lib 下的东西必须有人引用，否则是替换没做完留下的死文件。 */
    @Test
    @DisplayName("static/lib 下不许有没人引用的死文件")
    void noOrphanLibFiles() throws IOException {
        StringBuilder all = new StringBuilder();
        for (Path p : templates()) {
            all.append(read(p));
        }
        List<String> orphans = new ArrayList<>();
        try (Stream<Path> s = Files.walk(STATIC_ROOT.resolve("lib"))) {
            s.filter(Files::isRegularFile).forEach(f -> {
                String rel = "/" + STATIC_ROOT.relativize(f).toString().replace('\\', '/');
                // 字体由 all.min.css 用相对 url(../webfonts/…) 引用，不在模板里出现，单独按前缀放行
                if (rel.contains("/webfonts/")) {
                    return;
                }
                if (!all.toString().contains(rel)) {
                    orphans.add(rel);
                }
            });
        }
        assertTrue(orphans.isEmpty(), "static/lib 下这些文件没有任何模板引用，删掉或补上引用：\n"
                + String.join("\n", orphans));
    }

    private static String join(Map<String, ?> m) {
        StringBuilder sb = new StringBuilder();
        m.forEach((k, v) -> sb.append("  · ").append(k).append(v instanceof Integer ? " × " + v : "").append('\n'));
        return sb.toString();
    }
}
