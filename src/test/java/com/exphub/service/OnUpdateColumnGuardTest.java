package com.exphub.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 通用守卫：任何 UPDATE 只要改的是带 {@code ON UPDATE CURRENT_TIMESTAMP} 列的表，
 * 就必须把那些列显式写回自身（{@code updated_at = updated_at}）。
 *
 * <p><b>为什么需要「通用」而不是只盯 {@code docs.updated_at}</b>（2026-10-29 越哥第 ② 条）：
 * 只钉住那一条迁移，下一个建 {@code projects} / {@code ai_assistants} / 新表的人照样会中招 ——
 * 事故的形态是「只改别的列，MySQL 顺手把时间列刷成执行时刻」，而这类事故**不报错、不崩溃、
 * 测试全绿**，只能靠当场核验（见 AGENTS.md 第 15 章）。守卫要盯的是**机制**，不是一个语句。
 *
 * <p><b>口径</b>：扫描 {@code sql/**}{@code /*.sql} 里所有 UPDATE 语句，
 * 用 SET 左值解析真正被修改的表（知道别名，所以 {@code UPDATE docs d JOIN ... SET d.api_key}
 * 判的是 {@code docs}，不会把没被写的 JOIN 表也算进来），再要求该表的每个 ON UPDATE 列
 * 在 SET 里出现 {@code col = [alias.]col} 形式的自赋值。
 *
 * <p><b>ON UPDATE 列从哪来</b>：仓库 DDL 里声明的 ∪ 线上 {@code information_schema} 实测的。
 * 两边都要 —— 表结构不一定写在仓库里（{@code projects} 就没有 CREATE TABLE），
 * 而只看线上又会在新装环境上与 DDL 漂移。
 */
class OnUpdateColumnGuardTest {

    /**
     * 线上 information_schema 实测（2026-10-29，{@code EXTRA LIKE '%on update%'}）：
     * ai_assistants / docs / docs_updated_at_bak20261029 / projects / public_users / users。
     *
     * <p>为什么写死而不是连库查：单测不起 Spring 上下文、也不该依赖生产数据库。
     * 代价是「线上加了 ON UPDATE 列而这里没同步」时守卫会漏 —— 所以下面
     * {@link #ddlDeclaredOnUpdateColumnsNotLiveMustBeExplained()} 兜住能自动发现的那一半。
     */
    private static final Map<String, Set<String>> LIVE_ON_UPDATE = Map.of(
            "ai_assistants", Set.of("updated_at"),
            "docs", Set.of("updated_at"),
            "docs_updated_at_bak20261029", Set.of("updated_at"),
            "projects", Set.of("updated_at"),
            "public_users", Set.of("updated_at"),
            "users", Set.of("updated_at"));

    /**
     * 仓库 DDL 声明了 ON UPDATE、但线上实测**没有**的列。必须逐条说明理由，
     * 否则 {@link #ddlDeclaredOnUpdateColumnsNotLiveMustBeExplained()} 会红。
     *
     * <p>它不是「豁免」，是一份**已知漂移清单**：新装环境（跑 init.sql）与生产库行为不同，
     * 这一条要么统一 DDL、要么统一线上，别让它悄悄存在。
     */
    private static final Map<String, String> KNOWN_DDL_ONLY = Map.of(
            "doc_templates.updated_at",
            "init.sql 声明 ON UPDATE，线上实测是普通 datetime（新装环境与生产漂移，待统一）");

    /**
     * 历史脚本的豁免：它们**不在** deploy 的自动执行范围里。
     *
     * <p>deploy.sh 只跑 {@code sql/migrations/migration_2026*.sql}（见第 2.5 步的 glob），
     * 这三个文件是 2026-10-25 之前的一次性脚本，永远不会再执行；**改它们反而是风险**：
     * 文件内容一变，若将来有人把 glob 放宽，就会重跑 {@code ALTER TABLE ADD COLUMN} 直接失败。
     *
     * <p>所以这里不做「静默跳过」，而是**点名 + 自证**：
     * {@link #legacyAllowlistStillMatches()} 会验证每一条现在**仍然**是违规的，
     * 修好了或文件被删了都会红，逼着下一个人回来删条目。
     */
    private static final Map<String, String> LEGACY_NEVER_RERUN = Map.of(
            "upgrade_call_logs.sql|ai_assistants|updated_at",
            "2026-10-25 之前的一次性脚本，deploy 不执行；改动会带来重跑 ALTER 的风险",
            "update_template_type.sql|docs|updated_at",
            "同上：一次性回填脚本",
            "v2_add_api_key_to_docs.sql|docs|updated_at",
            "同上：v2 升级脚本，含 ALTER TABLE ADD COLUMN");

    // ===== 主守卫 =====

    @Test
    @DisplayName("全部 SQL 的 UPDATE：改到带 ON UPDATE 列的表，就必须显式写回那些列")
    void everyUpdateWritesBackOnUpdateColumns() throws Exception {
        Map<String, Set<String>> onUpdate = mergedOnUpdate();
        List<String> violations = new ArrayList<>();

        for (Path file : sqlFiles()) {
            String raw = new String(Files.readAllBytes(file), StandardCharsets.UTF_8);
            for (Statement st : splitStatements(raw)) {
                if (!st.startsWithUpdate()) continue;
                for (Map.Entry<String, Set<String>> e : st.assignedTables().entrySet()) {
                    String table = e.getKey();
                    Set<String> onUpdateCols = onUpdate.getOrDefault(table, Set.of());
                    for (String col : onUpdateCols) {
                        if (st.selfAssigns(table, col)) continue;
                        String key = file.getFileName() + "|" + table + "|" + col;
                        if (LEGACY_NEVER_RERUN.containsKey(key)) continue;
                        violations.add(file + ":" + st.lineNo + " → UPDATE " + table
                                + " 未写回 " + col + "（应为 " + col + " = " + col + "）");
                    }
                }
            }
        }

        assertTrue(violations.isEmpty(),
                "以下 UPDATE 会让 MySQL 把时间列静默刷成执行时刻（2026-10-29 已压平过全库 652 篇）：\n  "
                        + String.join("\n  ", violations)
                        + "\n\n修法：在同一条 SET 里加 `" + "updated_at = updated_at"
                        + "` —— 显式赋值不会触发 ON UPDATE，这是必要的空操作。");
    }

    @Test
    @DisplayName("守卫不能静默失效：仓库里该扫到的 UPDATE 条数不能少（注释/分号解析退化会漏扫）")
    void guardSeesKnownNumberOfUpdates() throws Exception {
        int seen = 0;
        for (Path file : sqlFiles()) {
            String raw = new String(Files.readAllBytes(file), StandardCharsets.UTF_8);
            for (Statement st : splitStatements(raw)) {
                if (st.startsWithUpdate()) seen++;
            }
        }
        // 2026-10-29 实数：8 条（3 条合规 + 3 条 upgrade_call_logs + 1 条 update_template_type + 1 条 v2）
        assertEquals(8, seen,
                "扫到的 UPDATE 条数变了（实际 " + seen + "）。新增脚本请同步这个数字；"
                        + "数字变**少**通常意味着分号/注释解析退化，守卫会在没人察觉的情况下失效");
    }

    // ===== 解析器自身的用例（守卫也要被守卫） =====

    @Test
    @DisplayName("解析器能抓到几种真实形态的违规")
    void parserDetectsViolations() {
        assertViolation("UPDATE docs SET status = 'ACTIVE' WHERE id = 1;", "docs", "updated_at");
        // 别名形式：左值是 d.xxx，目标表仍是 docs
        assertViolation("UPDATE docs d JOIN ai_assistants a ON d.author_id = a.assistant_id\n"
                + "SET d.api_key = a.api_key WHERE d.api_key IS NULL;", "docs", "updated_at");
        // 多行 + SET 里有换行
        assertViolation("UPDATE docs\n   SET superseded_marked_by = '未记录'\n WHERE superseded_by IS NOT NULL;",
                "docs", "updated_at");
        // 多表：只有被赋值的表算目标，ai_assistants 没被写就不该报
        List<String> v = violationsOf("UPDATE docs d JOIN ai_assistants a ON d.id = a.id\n"
                + "SET a.can_search = 1 WHERE a.can_search IS NULL;");
        assertEquals(List.of("ai_assistants.updated_at"), v,
                "JOIN 表里没被 SET 的表不该被要求写回，否则守卫会逼人给只读表加空操作");
    }

    @Test
    @DisplayName("解析器认这几种写回形式（别名 / 多列 / WHERE 里才出现列名不算写回）")
    void parserAcceptsWriteBackForms() {
        assertEquals(List.of(), violationsOf(
                "UPDATE docs SET last_verified_at = updated_at, updated_at = updated_at WHERE id = 1;"));
        assertEquals(List.of(), violationsOf(
                "UPDATE docs d SET d.updated_at = d.updated_at WHERE d.id = 1;"));
        assertEquals(List.of(), violationsOf(
                "UPDATE projects SET name = 'x', updated_at = updated_at WHERE id = 2;"));

        // 列名只出现在 WHERE 里 = 没写回
        assertEquals(List.of("docs.updated_at"), violationsOf(
                "UPDATE docs SET title = 'x' WHERE updated_at < '2026-01-01';"));
    }

    @Test
    @DisplayName("注释里的 UPDATE 不算：注释掉一条语句不许让守卫以为没人碰这张表")
    void commentsAreIgnored() {
        assertEquals(List.of(), violationsOf(
                "-- UPDATE docs SET status = 'X';\n"
                        + "/* UPDATE docs SET status = 'Y'; */\n"
                        + "SELECT 1;"));
        // 真语句仍要被看到
        assertEquals(List.of("docs.updated_at"), violationsOf(
                "-- 说明\nUPDATE docs SET status = 'X';\n"));
    }

    @Test
    @DisplayName("豁免清单要自证：每条现在仍然违规、且都属于 deploy 不会执行的旧脚本")
    void legacyAllowlistStillMatches() throws Exception {
        for (Map.Entry<String, String> e : LEGACY_NEVER_RERUN.entrySet()) {
            String[] parts = e.getKey().split("\\|");
            String fileName = parts[0], table = parts[1], col = parts[2];
            Path file = Path.of("sql/migrations").resolve(fileName);
            assertTrue(Files.exists(file), "豁免清单指向已不存在的文件：" + file + " —— 请删掉该条目");
            assertFalse(fileName.startsWith("migration_2026"),
                    "deploy 会自动执行 migration_2026*.sql，这类文件**不许**进豁免清单：" + fileName);
            String raw = new String(Files.readAllBytes(file), StandardCharsets.UTF_8);
            boolean stillViolates = false;
            for (Statement st : splitStatements(raw)) {
                if (!st.startsWithUpdate()) continue;
                if (st.assignedTables().containsKey(table) && !st.selfAssigns(table, col)) stillViolates = true;
            }
            assertTrue(stillViolates,
                    fileName + " 对 " + table + "." + col + " 已不再违规 —— 请从豁免清单删除该条目"
                            + "（留着等于给未来的违规开一张空白通行证）");
        }
    }

    @Test
    @DisplayName("DDL 与线上不一致的列必须逐条说明，新漂移不许静默出现")
    void ddlDeclaredOnUpdateColumnsNotLiveMustBeExplained() throws Exception {
        Set<String> ddlOnly = new HashSet<>();
        for (Map.Entry<String, Set<String>> e : ddlOnUpdate().entrySet()) {
            for (String col : e.getValue()) ddlOnly.add(e.getKey() + "." + col);
        }
        for (Map.Entry<String, Set<String>> e : LIVE_ON_UPDATE.entrySet()) {
            for (String c : e.getValue()) ddlOnly.remove(e.getKey() + "." + c);
        }
        assertTrue(ddlOnly.containsAll(KNOWN_DDL_ONLY.keySet()),
                "已知漂移清单里有条目已经不在 DDL 里了，请清理：" + KNOWN_DDL_ONLY.keySet());
        assertEquals(KNOWN_DDL_ONLY.keySet(), ddlOnly,
                "DDL 里声明了 ON UPDATE、线上却没有的列变了：" + ddlOnly
                        + "。新装环境（跑 init.sql）会与生产行为不同 —— 要么统一 DDL，要么写进"
                        + " KNOWN_DDL_ONLY 并说明原因");
    }

    // ===== 解析实现（够用即止：只解析我们要判的形态） =====

    private static final Pattern UPDATE_HEAD = Pattern.compile(
            "(?is)^\\s*UPDATE\\s+(.+?)\\s+SET\\s+(.+)$");
    private static final Pattern SELF_ASSIGN = Pattern.compile(
            "(?i)^\\s*(?:`?(\\w+)`?\\.)?`?(\\w+)`?\\s*=\\s*(?:`?(\\w+)`?\\.)?`?(\\w+)`?\\s*$");

    private static List<Path> sqlFiles() throws Exception {
        try (Stream<Path> s = Files.walk(Path.of("sql"))) {
            return s.filter(p -> p.toString().endsWith(".sql"))
                    .filter(Files::isRegularFile)
                    .sorted()
                    .collect(Collectors.toList());
        }
    }

    private static Map<String, Set<String>> mergedOnUpdate() throws Exception {
        Map<String, Set<String>> merged = new HashMap<>();
        for (Map.Entry<String, Set<String>> e : LIVE_ON_UPDATE.entrySet()) {
            merged.computeIfAbsent(e.getKey(), k -> new LinkedHashSet<>()).addAll(e.getValue());
        }
        ddlOnUpdate().forEach((k, v) -> merged.computeIfAbsent(k, x -> new LinkedHashSet<>()).addAll(v));
        return merged;
    }

    /** 扫仓库 DDL：CREATE TABLE 里的列定义 + ALTER TABLE ADD COLUMN。返回 key = "table.col"。 */
    private static Map<String, Set<String>> ddlOnUpdate() throws Exception {
        Map<String, Set<String>> out = new LinkedHashMap<>();
        Pattern createHead = Pattern.compile("(?is)CREATE\\s+TABLE\\s+(?:IF\\s+NOT\\s+EXISTS\\s+)?`?(\\w+)`?\\s*\\(");
        for (Path file : sqlFiles()) {
            String sql = stripComments(new String(Files.readAllBytes(file), StandardCharsets.UTF_8));

            Matcher m = createHead.matcher(sql);
            while (m.find()) {
                String table = m.group(1);
                int open = m.end() - 1;
                int close = matchParen(sql, open);
                if (close < 0) continue;
                for (String line : sql.substring(open + 1, close).split("\n")) {
                    if (!line.toUpperCase(Locale.ROOT).contains("ON UPDATE CURRENT_TIMESTAMP")) continue;
                    Matcher col = Pattern.compile("^\\s*`?(\\w+)`?\\s+[A-Za-z]").matcher(line);
                    if (col.find()) out.computeIfAbsent(table, k -> new LinkedHashSet<>()).add(col.group(1));
                }
            }

            Matcher alter = Pattern.compile(
                    "(?is)ALTER\\s+TABLE\\s+`?(\\w+)`?[^;]*?\\bADD\\s+(?:COLUMN\\s+)?`?(\\w+)`?\\b[^;]*?ON\\s+UPDATE\\s+CURRENT_TIMESTAMP")
                    .matcher(sql);
            while (alter.find()) {
                out.computeIfAbsent(alter.group(1), k -> new LinkedHashSet<>()).add(alter.group(2));
            }
        }
        return out;
    }

    /** 去掉 `--` 行注释与块注释（保留换行，行号才算得准）。 */
    private static String stripComments(String sql) {
        StringBuilder sb = new StringBuilder(sql.length());
        boolean inLine = false, inBlock = false, inQuote = false;
        for (int i = 0; i < sql.length(); i++) {
            char c = sql.charAt(i);
            char n = i + 1 < sql.length() ? sql.charAt(i + 1) : '\0';
            if (inLine) {
                if (c == '\n') { inLine = false; sb.append(c); }
                continue;
            }
            if (inBlock) {
                if (c == '*' && n == '/') { inBlock = false; i++; }
                if (c == '\n') sb.append(c);
                continue;
            }
            if (inQuote) {
                sb.append(c);
                // SQL 里成对的 '' 是转义的单引号，**不结束字符串**。
                // 不处理这个问题，`COMMENT ''中文''` 会把后续整段代码当成字符串内部，
                // 于是 UPDATE 要么被吞、要么边界错位 —— 守卫静默失效。
                if (c == '\'' && n == '\'') {
                    sb.append(n);
                    i++;
                } else if (c == '\'') {
                    inQuote = false;
                }
                continue;
            }
            if (c == '-' && n == '-') { inLine = true; i++; continue; }
            if (c == '/' && n == '*') { inBlock = true; i++; continue; }
            if (c == '\'') inQuote = true;
            sb.append(c);
        }
        return sb.toString();
    }

    private static int matchParen(String s, int openIdx) {
        int depth = 0;
        for (int i = openIdx; i < s.length(); i++) {
            if (s.charAt(i) == '(') depth++;
            else if (s.charAt(i) == ')') {
                depth--;
                if (depth == 0) return i;
            }
        }
        return -1;
    }

    /** 按不落在括号/引号里的分号切语句，保留行号。 */
    private static List<Statement> splitStatements(String raw) {
        String text = stripComments(raw);
        List<Statement> out = new ArrayList<>();
        int start = 0, depth = 0, line = 1, startLine = 1;
        boolean inQuote = false;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '\n') line++;
            if (inQuote) {
                if (c == '\'') {
                    // 成对 '' = 转义引号，仍在字符串里
                    if (i + 1 < text.length() && text.charAt(i + 1) == '\'') i++;
                    else inQuote = false;
                }
                continue;
            }
            if (c == '\'') { inQuote = true; continue; }
            if (c == '(') depth++;
            else if (c == ')') depth--;
            else if (c == ';' && depth == 0) {
                out.add(new Statement(text.substring(start, i), startLine));
                start = i + 1;
                startLine = line;
            }
        }
        if (start < text.length()) out.add(new Statement(text.substring(start), startLine));
        return out;
    }

    private static List<String> violationsOf(String sql) {
        Map<String, Set<String>> onUpdate = new HashMap<>();
        onUpdate.put("docs", Set.of("updated_at"));
        onUpdate.put("ai_assistants", Set.of("updated_at"));
        onUpdate.put("projects", Set.of("updated_at"));
        List<String> out = new ArrayList<>();
        for (Statement st : splitStatements(sql)) {
            if (!st.startsWithUpdate()) continue;
            for (Map.Entry<String, Set<String>> e : st.assignedTables().entrySet()) {
                for (String col : onUpdate.getOrDefault(e.getKey(), Set.of())) {
                    if (!st.selfAssigns(e.getKey(), col)) out.add(e.getKey() + "." + col);
                }
            }
        }
        return out;
    }

    private static void assertViolation(String sql, String table, String col) {
        assertTrue(violationsOf(sql).contains(table + "." + col),
                "解析器没抓到违规：" + table + "." + col + " ← " + sql);
    }

    /** 一条 UPDATE 语句的解析结果。 */
    private record Statement(String sql, int lineNo) {

        boolean startsWithUpdate() {
            return sql.trim().toUpperCase(Locale.ROOT).startsWith("UPDATE ");
        }

        /** 真正被 SET 的表 → 被赋值的列。JOIN 进来没被写的表不在结果里。 */
        Map<String, Set<String>> assignedTables() {
            Matcher m = UPDATE_HEAD.matcher(sql);
            Map<String, Set<String>> out = new LinkedHashMap<>();
            if (!m.find()) return out;
            Map<String, String> aliasToTable = headerAliases(m.group(1));
            for (String assign : splitTopLevel(setClauseOnly(m.group(2)))) {
                int eq = assign.indexOf('=');
                if (eq < 0) continue;
                String lhs = assign.substring(0, eq).trim();
                String qualifier = null, col = lhs;
                int dot = lhs.indexOf('.');
                if (dot > 0) {
                    qualifier = unquote(lhs.substring(0, dot).trim());
                    col = unquote(lhs.substring(dot + 1).trim());
                } else {
                    col = unquote(col);
                }
                String table = qualifier != null ? aliasToTable.get(qualifier) : defaultTable(aliasToTable);
                if (table != null) {
                    out.computeIfAbsent(table, k -> new LinkedHashSet<>()).add(col.toLowerCase(Locale.ROOT));
                }
            }
            return out;
        }

        /** SET 里有 `col = col`（允许两侧同别名）形式的自赋值。 */
        boolean selfAssigns(String table, String col) {
            Matcher m = UPDATE_HEAD.matcher(sql);
            if (!m.find()) return false;
            Map<String, String> aliasToTable = headerAliases(m.group(1));
            for (String assign : splitTopLevel(setClauseOnly(m.group(2)))) {
                Matcher a = SELF_ASSIGN.matcher(assign);
                if (!a.matches()) continue;
                String lhsAlias = a.group(1), lhsCol = a.group(2), rhsAlias = a.group(3), rhsCol = a.group(4);
                if (!lhsCol.equalsIgnoreCase(col) || !rhsCol.equalsIgnoreCase(col)) continue;
                String lhsTable = lhsAlias == null ? defaultTable(aliasToTable) : aliasToTable.get(lhsAlias);
                String rhsTable = rhsAlias == null ? defaultTable(aliasToTable) : aliasToTable.get(rhsAlias);
                if (table.equals(lhsTable) && table.equals(rhsTable)) return true;
            }
            return false;
        }

        /** UPDATE 头里的「表/别名」映射。只认第一段与 JOIN 段。 */
        private static Map<String, String> headerAliases(String header) {
            Map<String, String> out = new LinkedHashMap<>();
            for (String chunk : header.split("(?i)\\bJOIN\\b")) {
                String first = chunk.trim().split("[\\s,]+")[0];
                if (first.isEmpty()) continue;
                String table = unquote(first);
                // 别名：紧跟表名后的标识符，但不是 ON / USING 这些关键字
                String[] tokens = chunk.trim().split("\\s+");
                String alias = table;
                if (tokens.length > 1) {
                    String t = unquote(tokens[1]);
                    if (t.matches("\\w+") && !t.equalsIgnoreCase("ON") && !t.equalsIgnoreCase("USING")
                            && !t.equalsIgnoreCase("SET")) {
                        alias = t;
                    }
                }
                out.put(alias, table);
                out.putIfAbsent(table, table);
            }
            return out;
        }

        private static String defaultTable(Map<String, String> aliasToTable) {
            return aliasToTable.isEmpty() ? null : aliasToTable.values().iterator().next();
        }

        /**
         * SET 子句只保留到 WHERE / ORDER BY / LIMIT 之前。
         *
         * <p>不切的话，`SET updated_at = updated_at WHERE id = 1` 的最后一个赋值会带着
         * `WHERE id = 1` 尾巴，自赋值判定必然失配 —— 守卫会对合规语句报红，
         * 而「守卫误报」最终会被人关掉，比没有守卫更糟。
         */
        private static String setClauseOnly(String setAndRest) {
            int cut = setAndRest.length();
            for (String kw : new String[]{"WHERE", "ORDER BY", "LIMIT"}) {
                int idx = topLevelKeywordIndex(setAndRest, kw);
                if (idx >= 0 && idx < cut) cut = idx;
            }
            return setAndRest.substring(0, cut);
        }

        /** 按「词边界」找关键字：`\nWHERE` / `where(` 都算，`updated_where` 不算。 */
        private static int topLevelKeywordIndex(String s, String kw) {
            int depth = 0;
            boolean inQuote = false;
            for (int i = 0; i < s.length(); i++) {
                char c = s.charAt(i);
                if (inQuote) {
                    if (c == '\'') {
                        if (i + 1 < s.length() && s.charAt(i + 1) == '\'') i++;
                        else inQuote = false;
                    }
                    continue;
                }
                if (c == '\'') inQuote = true;
                else if (c == '(') depth++;
                else if (c == ')') depth--;
                else if (depth == 0 && s.regionMatches(true, i, kw, 0, kw.length())) {
                    boolean leftOk = i == 0 || !isWordChar(s.charAt(i - 1));
                    int end = i + kw.length();
                    boolean rightOk = end >= s.length() || !isWordChar(s.charAt(end));
                    if (leftOk && rightOk) return i;
                }
            }
            return -1;
        }

        private static boolean isWordChar(char c) {
            return Character.isLetterOrDigit(c) || c == '_' || c == '`';
        }

        private static String unquote(String s) {
            return s.replace("`", "").trim();
        }

        /** 按顶层逗号切（SET 里可能有函数调用带逗号）。 */
        private static List<String> splitTopLevel(String s) {
            List<String> out = new ArrayList<>();
            int depth = 0, start = 0;
            boolean inQuote = false;
            for (int i = 0; i < s.length(); i++) {
                char c = s.charAt(i);
                if (inQuote) {
                    if (c == '\'') {
                        if (i + 1 < s.length() && s.charAt(i + 1) == '\'') i++;
                        else inQuote = false;
                    }
                    continue;
                }
                if (c == '\'') inQuote = true;
                else if (c == '(') depth++;
                else if (c == ')') depth--;
                else if (c == ',' && depth == 0) {
                    out.add(s.substring(start, i));
                    start = i + 1;
                }
            }
            out.add(s.substring(start));
            return out;
        }
    }
}
