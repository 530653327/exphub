package com.exphub.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * ✍️ 2026-10-29 烛龙 —— SQL 守卫的孪生兄弟，补的是<b>Java 层</b>这个缺口。
 *
 * <p><b>为什么需要它</b>：{@link OnUpdateColumnGuardTest} 只扫 {@code sql/**} 里的 UPDATE 语句，
 * 但真正在生产上跑的语句大半是 MyBatis-Plus 拼出来的。{@code mapper.update(null, wrapper)}
 * 传的实体是 {@code null}，{@code MyBatisPlusMetaObjectHandler.updateFill} 不会触发，
 * 生成的 SQL 里就没有 {@code updated_at} —— 于是 MySQL 的
 * {@code ON UPDATE CURRENT_TIMESTAMP} 把它静默刷成执行时刻。
 *
 * <p>2026-10-29 实测到的自相矛盾：{@code DocService.setFlags} 的注释写着
 * 「也不动 updated_at」，而代码只 set 了 flags 那几列 —— 打一次标记，那条老文档就被顶到
 * {@code list_recent_memories} 的最前面。{@code sql/**} 守卫对此一声不吭，因为它压根不看 Java。
 *
 * <p><b>口径</b>：扫 {@code src/main/java} 下所有 {@code mapper.update(null, ...)}，
 * 找到这次调用对应的 {@code UpdateWrapper<实体>} 推断目标表（先往调用点之前找，
 * 找不到再往调用点之后找 —— 内联写法 {@code update(null, new UpdateWrapper<Doc>()...)} 属于后者）；
 * 表带 ON UPDATE 列时，要求「从 wrapper 构造到语句结束」这段里出现 {@code updated_at = updated_at}。
 *
 * <p><b>两种放行方式，都必须留痕</b>：
 * <ol>
 *   <li>写回自身 —— 想让时间<b>不动</b>的唯一正确写法；</li>
 *   <li>在调用前 500 字符内写 {@code GUARD:ALLOW-ONUPDATE-REFRESH 理由} —— 声明「这次就是要刷新」。
 *       刻意刷新是合法需求（换项目归属），但必须是人写下来的决定，不能靠沉默。</li>
 * </ol>
 *
 * <p><b>为什么不用行号豁免</b>：行号一改就漂，漂了要么误报、要么静默放行，
 * 两种都比没守卫更糟。标记跟着代码走，删掉标记就等于当场声明「我要刷新」。
 */
class JavaUpdateWriteBackGuardTest {

    /** MyBatis-Plus 实体 → 表名。只列有 ON UPDATE 列的那些，其余表根本不需要写回。 */
    private static final Map<String, String> ENTITY_TABLE = Map.of(
            "Doc", "docs",
            "Project", "projects",
            "AiAssistant", "ai_assistants",
            "PublicUser", "public_users",
            "User", "users");

    /**
     * 线上 information_schema 实测 + 仓库 DDL 声明的 ON UPDATE 列都在这些表上，列名统一 updated_at。
     *
     * <p>2026-10-29：{@code doc_templates} 已移出 —— 该列线上从来没有 ON UPDATE，
     * init.sql 也已对齐删掉（仅脚本对齐，线上无 DDL 变更）。
     */
    private static final Set<String> TABLES_WITH_ON_UPDATE = Set.of(
            "docs", "projects", "ai_assistants", "public_users", "users");

    private static final String WRITEBACK = "updated_at = updated_at";
    private static final String ALLOW_MARK = "GUARD:ALLOW-ONUPDATE-REFRESH";

    private static final Pattern UPDATE_CALL =
            Pattern.compile("\\w*[Mm]apper\\s*\\.\\s*update\\s*\\(\\s*null\\s*,");
    private static final Pattern ENTITY = Pattern.compile("UpdateWrapper\\s*<\\s*(\\w+)\\s*>");

    // ===== 主守卫 =====

    @Test
    @DisplayName("Java 层的 update(null, wrapper)：改到带 ON UPDATE 的表，必须写回或显式声明要刷新")
    void everyJavaUpdateWritesBackOrDeclaresRefresh() throws Exception {
        List<String> violations = new ArrayList<>();
        for (Path file : javaFiles()) {
            String raw = Files.readString(file, StandardCharsets.UTF_8);
            for (Hit h : scan(file.getFileName().toString(), raw)) {
                if (h.ok) continue;
                violations.add(h.file + ":" + h.line + " → UPDATE " + h.table
                        + "（实体 " + h.entity + "）既没写回 " + WRITEBACK
                        + "，也没有 " + ALLOW_MARK + " 声明");
            }
        }
        assertTrue(violations.isEmpty(),
                "以下 Java 写路径会让 MySQL 把时间列静默刷成执行时刻"
                        + "（2026-10-29 已压平过全库 652 篇，根因就是这种 UPDATE）：\n  "
                        + String.join("\n  ", violations)
                        + "\n\n修法二选一："
                        + "\n  ① 不想动时间 → 加 .setSql(\"" + WRITEBACK + "\")"
                        + "\n  ② 确实要刷新 → 在调用前写 // " + ALLOW_MARK + " <理由>");
    }

    @Test
    @DisplayName("守卫不能静默失效：扫到的写入点数不能少（正则退化会让它一条都看不见）")
    void guardSeesKnownNumberOfWritePaths() throws Exception {
        int seen = 0;
        for (Path file : javaFiles()) {
            String raw = Files.readString(file, StandardCharsets.UTF_8);
            seen += scan(file.getFileName().toString(), raw).size();
        }
        // 2026-10-29 实数：6 条（DocService 5 + ProjectService 1）。
        // 2026-10-30 +1：DocService.cancelDeletion 里清 delete_reason/marked_by/marked_at 三列
        //   必须走 UpdateWrapper（updateById 对 null 是跳过，置空写不进去），成为第 7 条。
        // 2026-11-07 +1：LoginSuccessHandler 写 users.last_login（登录防爆破那批）——
        //   这个字段以前从没被写过，整条 users 写入路径是那次新增的，成为第 8 条。
        // ReviewCandidate 那条不算 —— review_candidates 表只有 created_at / resolved_at，没有 ON UPDATE 列。
        assertEquals(8, seen,
                "扫到的 Java 写入点变了（实际 " + seen + "）。新增写路径请同步这个数字；"
                        + "数字变**少**通常意味着正则或注释遮蔽逻辑退化，守卫会在没人察觉的情况下失效");
    }

    // ===== 解析器自身的用例（守卫也要被守卫） =====

    @Test
    @DisplayName("解析器能抓到漏写回的形态")
    void detectsMissingWriteBack() {
        // 内联构造
        assertTrue(violationsOf("void f() {\n  docMapper.update(null, new UpdateWrapper<Doc>()\n"
                + "      .eq(\"id\", 1L).set(\"flags\", \"x\"));\n}").size() == 1);
        // 先声明再用（setFlags 的真实形态）
        assertTrue(violationsOf("void f() {\n  UpdateWrapper<Doc> uw = new UpdateWrapper<>();\n"
                + "  uw.set(\"flag_note\", \"x\");\n  docMapper.update(null, uw);\n}").size() == 1);
    }

    @Test
    @DisplayName("解析器认这几种放行：setSql 写回 / 别名链式 / 显式声明刷新")
    void acceptsWriteBackAndDeclaration() {
        assertEquals(List.of(), violationsOf("void f() {\n  UpdateWrapper<Doc> uw = new UpdateWrapper<>();\n"
                + "  uw.set(\"flags\", \"x\").setSql(\"updated_at = updated_at\");\n"
                + "  docMapper.update(null, uw);\n}"));
        assertEquals(List.of(), violationsOf("void f() {\n  // " + ALLOW_MARK + " 归属变更应当刷新\n"
                + "  docMapper.update(null, new UpdateWrapper<Doc>().set(\"project_id\", 2L));\n}"));
    }

    @Test
    @DisplayName("注释里的 setSql 不算数：否则写一行注释就能骗过守卫")
    void commentsDoNotCount() {
        assertEquals(1, violationsOf("void f() {\n  // .setSql(\"updated_at = updated_at\")\n"
                + "  docMapper.update(null, new UpdateWrapper<Doc>().set(\"flags\", \"x\"));\n}").size(),
                "注释里的写回语句被当成真写回了 —— 守卫被人一行注释绕过");
        // 注释掉的 UPDATE 调用本身也不该被看见
        assertEquals(0, violationsOf("void f() {\n  // docMapper.update(null, new UpdateWrapper<Doc>()\n"
                + "  //     .set(\"flags\", \"x\"));\n}").size());
    }

    @Test
    @DisplayName("字符串字面量不能被当成注释吃掉：写回语句本来就住在引号里")
    void stringLiteralsSurviveMasking() {
        String code = new String(maskComments("// 说明\nuw.setSql(\"updated_at = updated_at\"); // 尾注"));
        assertTrue(code.contains(WRITEBACK), "字符串里的写回语句被遮蔽了，守卫会永远报红");
        assertFalse(code.contains("尾注"), "行注释没被遮掉");
    }

    @Test
    @DisplayName("没有 ON UPDATE 列的表不该被要求写回（否则守卫会逼人给无关表加空操作）")
    void tablesWithoutOnUpdateAreIgnored() {
        assertEquals(0, violationsOf("void f() {\n  candidateMapper.update(null, "
                + "new UpdateWrapper<ReviewCandidate>().set(\"status\", \"PENDING\"));\n}").size());
    }

    // ===== 扫描实现 =====

    /** 一个写入点：命中了带 ON UPDATE 的表，以及它是否已放行。 */
    private record Hit(String file, int line, String entity, String table, boolean ok) {}

    private List<Hit> scan(String fileName, String raw) {
        String code = new String(maskComments(raw));
        List<Hit> hits = new ArrayList<>();
        Matcher m = UPDATE_CALL.matcher(code);
        while (m.find()) {
            int idx = m.start();
            int from = Math.max(0, idx - 1500);
            String head = code.substring(from, idx);

            // 最近的 UpdateWrapper<实体> 决定目标表
            Matcher em = ENTITY.matcher(head);
            String entity = null;
            int segStart = -1;
            while (em.find()) {
                entity = em.group(1);
                segStart = from + em.start();
            }
            // 内联写法把 wrapper 放在调用点之后（new UpdateWrapper<Doc>().set(...)），
            // 只往前找会把它当成「没有 wrapper」而漏掉 —— 2026-10-29 实测漏了 2 处。
            if (entity == null) {
                Matcher tm = ENTITY.matcher(code.substring(idx, Math.min(code.length(), idx + 300)));
                if (!tm.find()) continue;
                entity = tm.group(1);
                segStart = idx + tm.start();
            }
            String table = ENTITY_TABLE.get(entity);
            if (table == null || !TABLES_WITH_ON_UPDATE.contains(table)) continue;

            int semi = code.indexOf(';', idx);
            int end = semi < 0 ? code.length() : semi;
            String segment = code.substring(segStart, end);

            // 豁免标记住在注释里，而 code 里的注释已被遮成空格 —— 必须回原文读，
            // 否则「显式声明要刷新」的写法永远不被承认，守卫会逼所有人二选一里选错的那个。
            String nearRaw = raw.substring(Math.max(0, idx - 500), idx);
            boolean ok = segment.contains(WRITEBACK) || nearRaw.contains(ALLOW_MARK);

            hits.add(new Hit(fileName, lineOf(code, idx), entity, table, ok));
        }
        return hits;
    }

    private List<String> violationsOf(String src) {
        List<String> out = new ArrayList<>();
        for (Hit h : scan("Test.java", src)) {
            if (!h.ok) out.add(h.table);
        }
        return out;
    }

    private static int lineOf(String code, int idx) {
        int line = 1;
        for (int i = 0; i < idx && i < code.length(); i++) {
            if (code.charAt(i) == '\n') line++;
        }
        return line;
    }

    private static List<Path> javaFiles() throws IOException {
        try (Stream<Path> s = Files.walk(Path.of("src/main/java"))) {
            return s.filter(p -> p.toString().endsWith(".java"))
                    .filter(Files::isRegularFile)
                    .sorted()
                    .collect(Collectors.toList());
        }
    }

    /**
     * 把注释替换成空格（<b>保留长度与换行</b>），字符串/字符字面量原样保留。
     *
     * <p>为什么是「替换成空格」而不是「删掉」：位置索引必须和原文一一对应，
     * 否则「调用点前 N 字符内有没有豁免标记」这种判断会全错位。
     *
     * <p>为什么不连字符串一起遮：{@code setSql("updated_at = updated_at")} 这行写回
     * <b>本来就住在引号里</b>，遮掉它，守卫会对所有合规代码报红 —— 而误报的守卫最终会被人关掉。
     */
    private static char[] maskComments(String src) {
        char[] out = src.toCharArray();
        int n = src.length();
        int mode = 0; // 0=代码 1=行注释 2=块注释 3=字符串 4=字符
        for (int i = 0; i < n; i++) {
            char c = src.charAt(i);
            char d = i + 1 < n ? src.charAt(i + 1) : '\0';
            switch (mode) {
                case 0 -> {
                    if (c == '/' && d == '/') {
                        out[i] = ' ';
                        out[i + 1] = ' ';
                        i++;
                        mode = 1;
                    } else if (c == '/' && d == '*') {
                        out[i] = ' ';
                        out[i + 1] = ' ';
                        i++;
                        mode = 2;
                    } else if (c == '"') {
                        mode = 3;
                    } else if (c == '\'') {
                        mode = 4;
                    }
                }
                case 1 -> {
                    if (c == '\n') {
                        mode = 0;
                    } else {
                        out[i] = ' ';
                    }
                }
                case 2 -> {
                    if (c == '*' && d == '/') {
                        out[i] = ' ';
                        out[i + 1] = ' ';
                        i++;
                        mode = 0;
                    } else if (c != '\n') {
                        out[i] = ' ';
                    }
                }
                case 3 -> {
                    if (c == '\\') {
                        i++;
                    } else if (c == '"') {
                        mode = 0;
                    }
                }
                default -> {
                    if (c == '\\') {
                        i++;
                    } else if (c == '\'') {
                        mode = 0;
                    }
                }
            }
        }
        return out;
    }
}
