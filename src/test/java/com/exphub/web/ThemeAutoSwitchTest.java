package com.exphub.web;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 后台白天/夜间主题守卫（2026-11-19，W-20261119-000001）。
 *
 * <h3>这条测试要钉住的三件事</h3>
 * <ol>
 *   <li><b>规则只有一份边界</b>：PC 的防闪内联脚本与 {@code /js/theme.js} 都按
 *       「6:00–18:00 白天」判断。两处写两套数字，跨过 18:00 就会出现「首帧设白天、
 *       JS 加载后改夜间」的闪一下 —— 只在夜里看得见，测试不看就永远发现不了。</li>
 *   <li><b>不能漏页</b>：新加的页面如果没引 {@code fragments/head}、也没自己引
 *       {@code fragments/theme}，它就没有 data-theme，夜间会整页白底。</li>
 *   <li><b>CSS 括号必须平衡</b>：{@code pc.css} 从 2026-11-17 首版起就少一个
 *       {@code :root} 的闭合大括号。Chrome 的 CSS Nesting 容错把它当成
 *       「:root 内嵌规则」继续渲染，所以页面看起来正常 —— 但
 *       {@code html[data-theme="dark"]} 会被解释成 {@code :root html[...]}（永不匹配），
 *       窄屏 {@code @media} 里的 {@code :root{--sidebar-w:0}} 也变成 {@code :root :root}。
 *       这类缺陷浏览器不报错、构建不报错，只能靠括号计数。</li>
 * </ol>
 */
class ThemeAutoSwitchTest {

    private static final Path TEMPLATES = Path.of("src/main/resources/templates");
    private static final Path THEME_JS = Path.of("src/main/resources/static/js/theme.js");
    private static final Path PC_CSS = Path.of("src/main/resources/static/css/pc.css");

    /** 时段边界只允许这一种写法：6:00 起、18:00 前（两处文件都必须有同一串） */
    private static final String BOUNDARY = "h >= 6 && h < 18";

    private static String read(Path p) throws IOException {
        return Files.readString(p, StandardCharsets.UTF_8);
    }

    /** 剥掉 HTML 注释：源码文本断言先剥注释，否则注释里写一句就能让测试变绿（AGENTS 红线 10） */
    private static String stripHtmlComments(String s) {
        return s.replaceAll("(?s)<!--.*?-->", "");
    }

    @Test
    @DisplayName("PC 防闪内联脚本与 theme.js 的时段边界是同一份")
    void boundaryIsSameInInlineScriptAndRuntime() throws IOException {
        String inline = stripHtmlComments(read(TEMPLATES.resolve("fragments/theme.html")));
        String runtime = read(THEME_JS);

        assertTrue(inline.contains(BOUNDARY),
                "fragments/theme.html 的首帧脚本必须按 '" + BOUNDARY + "' 判断，否则夜间先白闪一帧");
        assertTrue(runtime.contains(BOUNDARY),
                "theme.js 的 byHour() 必须与内联脚本同一边界（'" + BOUNDARY + "'）");
        assertTrue(inline.contains("exphub-pc-theme"),
                "内联脚本必须读同一个 localStorage 键，否则手动选择的主题首帧不生效");
        assertTrue(runtime.contains("exphub-pc-theme"), "theme.js 与内联脚本必须共用 exphub-pc-theme");
    }

    @Test
    @DisplayName("PC 页面要么引 fragments/head、要么自己引 fragments/theme，不许漏")
    void everyPcTemplateLoadsTheme() throws IOException {
        List<String> missing = new ArrayList<>();
        try (Stream<Path> files = Files.walk(TEMPLATES)) {
            for (Path p : files.filter(f -> f.toString().endsWith(".html")).toList()) {
                String rel = TEMPLATES.relativize(p).toString().replace('\\', '/');
                // 移动端有自己的主题入口（m/layout.html），fragments 与 layout 是壳本身
                if (rel.startsWith("m/") || rel.startsWith("fragments/") || rel.equals("layout/base.html")) {
                    continue;
                }
                String html = stripHtmlComments(read(p));
                if (!html.contains("fragments/head") && !html.contains("fragments/theme")) {
                    missing.add(rel);
                }
            }
        }
        assertTrue(missing.isEmpty(),
                "以下 PC 页面没有引入主题脚本，夜间会整页白底（加 ~{fragments/theme :: init} 或改用 fragments/head）："
                        + missing);
    }

    @Test
    @DisplayName("主题脚本在 CSS 之前：先定 data-theme，再加载样式，避免白闪")
    void themeRunsBeforeStylesheets() throws IOException {
        String head = stripHtmlComments(read(TEMPLATES.resolve("fragments/head.html")));
        int theme = head.indexOf("fragments/theme");
        int bootstrap = head.indexOf("bootstrap.min.css");
        assertTrue(theme >= 0, "fragments/head.html 必须引入 fragments/theme");
        assertTrue(bootstrap >= 0, "fragments/head.html 应该引入 bootstrap（结构变了就看这条）");
        assertTrue(theme < bootstrap,
                "主题初始化必须在第一个 <link rel=stylesheet> 之前，否则夜间先白闪");

        String login = stripHtmlComments(read(TEMPLATES.resolve("login.html")));
        assertTrue(login.contains("fragments/theme"), "登录页是独立 head，必须自己引入主题脚本");
    }

    @Test
    @DisplayName("pc.css 有夜间变量层，且 color-scheme 一起换（原生控件不跟着变就是白块）")
    void darkVariablesExist() throws IOException {
        String css = read(PC_CSS);
        int dark = css.indexOf("html[data-theme=\"dark\"]");
        assertTrue(dark >= 0, "pc.css 缺少 html[data-theme=\"dark\"] 变量层");

        String block = css.substring(dark, Math.min(css.length(), dark + 4000));
        assertTrue(block.contains("color-scheme: dark"),
                "夜间必须设 color-scheme: dark，否则下拉框/滚动条/日期控件还是白的");
        for (String v : List.of("--bg:", "--surface:", "--text:", "--line:", "--brand:")) {
            assertTrue(block.contains(v), "夜间变量层缺少 " + v);
        }
    }

    @Test
    @DisplayName("所有 CSS 文件大括号平衡（pc.css 曾少一个 }，被 CSS Nesting 容错掩盖）")
    void cssBracesAreBalanced() throws IOException {
        Path cssDir = Path.of("src/main/resources/static/css");
        List<String> broken = new ArrayList<>();
        try (Stream<Path> files = Files.list(cssDir)) {
            for (Path p : files.filter(f -> f.toString().endsWith(".css")).toList()) {
                String css = read(p);
                long open = css.chars().filter(c -> c == '{').count();
                long close = css.chars().filter(c -> c == '}').count();
                if (open != close) {
                    broken.add(p.getFileName() + "（{ " + open + " 个 / } " + close + " 个）");
                }
            }
        }
        assertEquals(List.of(), broken,
                "CSS 括号不平衡：浏览器会静默按嵌套规则解析，选择器全变后代选择器；"
                        + "html[data-theme] 这种根元素选择器将永远不匹配。");
    }

    @Test
    @DisplayName("移动端不再「无设置就永远深色」：没手动选择时按 6:00–18:00 自动切")
    void mobileDefaultsToAuto() throws IOException {
        String layout = stripHtmlComments(read(TEMPLATES.resolve("m/layout.html")));
        assertTrue(layout.contains(BOUNDARY), "m/layout.html 必须按同一时段边界自动切");
        assertTrue(layout.contains("'explight' : 'exp'") || layout.contains("\"explight\" : \"exp\""),
                "白天用 explight、夜间用 exp，值写错就是切了没效果");

        String me = stripHtmlComments(read(TEMPLATES.resolve("m/me.html")));
        assertTrue(me.contains("data-theme-pick=\"auto\""),
                "「我的 → 外观」必须有「自动」档，否则用户选了手动就再也回不到自动");
    }

    @Test
    @DisplayName("侧栏有主题按钮，theme.js 有事件委托（页面里不写 onclick）")
    void sidebarHasThemeButton() throws IOException {
        String sidebar = stripHtmlComments(read(TEMPLATES.resolve("fragments/sidebar.html")));
        assertTrue(sidebar.contains("data-theme-cycle"), "侧栏缺少主题切换按钮");
        assertFalse(sidebar.contains("onclick"), "主题按钮不要写内联 onclick，统一走 theme.js 的事件委托");

        String js = read(THEME_JS);
        assertTrue(js.contains("closest('[data-theme-cycle]')"), "theme.js 必须用事件委托绑定主题按钮");
        assertTrue(js.contains("60 * 1000"), "必须每分钟检查一次，否则跨过 6:00/18:00 不切");
        assertTrue(js.contains("visibilitychange"), "回前台也要检查：休眠时定时器会停");
    }

    /** 防回归：页面内联样式不要再写死浅色底/深色字（用变量） */
    @Test
    @DisplayName("PC 模板里不再有写死的浅色背景（#fff / #f0f0f0 之类）")
    void noHardcodedLightBackgroundsInPcTemplates() throws IOException {
        Pattern bad = Pattern.compile(
                "background(-color)?\\s*:\\s*(#fff\\b|#ffffff\\b|#f0f0f0\\b|#f4f4f4\\b|#e0e0e0\\b)",
                Pattern.CASE_INSENSITIVE);
        List<String> hits = new ArrayList<>();
        try (Stream<Path> files = Files.walk(TEMPLATES)) {
            for (Path p : files.filter(f -> f.toString().endsWith(".html")).toList()) {
                String rel = TEMPLATES.relativize(p).toString().replace('\\', '/');
                if (rel.startsWith("m/") || rel.startsWith("fragments/")) continue;
                String html = stripHtmlComments(read(p));
                Matcher m = bad.matcher(html);
                while (m.find()) hits.add(rel + " → " + m.group());
            }
        }
        assertEquals(List.of(), hits, "这些写死的浅色底在夜间会变成白块，改用 var(--surface) / var(--line)");
    }
}
