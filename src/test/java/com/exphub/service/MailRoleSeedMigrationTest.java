package com.exphub.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
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

import static org.junit.jupiter.api.Assertions.*;

/**
 * 信箱角色清单迁库（A+B 合并 + 正名改汉字）的守卫
 * （阁主 T261109053740-701 P9 定案，方案 #1597；T261109054938-679 正名改汉字）。
 *
 * <p><b>这一段最危险的失败形态不是建表失败，而是「导漏了一个角色」/「漏删 yml 一段」/
 * 「库里留了拼音正名」</b>：
 * 前者让某个角色在上线当天突然收不到信，而报错看起来像「对方写错了」；
 * 后者让出口一半汉字、一半拼音，排查时根本分不清哪份是真的。
 *
 * <p>所以这里守六件事：
 * <ol>
 *   <li>种子正名 = 留档清单（12 个汉字）<b>逐项一致</b>；别名指向的正名必须 ACTIVE；</li>
 *   <li>12 条拼音别名齐全（兼容期客户端仍写拼音，漏一条就当场把发信打死）；</li>
 *   <li>历史代号导成 <b>OBSERVED + PENDING_REVIEW</b>（待复核，不是合法来源）；</li>
 *   <li>历史消息/游标必须有「按别名刷成汉字」的语句，且 to_role 列是 utf8mb4、长度够；</li>
 *   <li>{@code application.yml} 里<b>不许</b>再出现 {@code known-roles} / {@code role-aliases}；</li>
 *   <li>迁移脚本与 {@code sql/init.sql}（开源新装路径）一致，且都幂等。</li>
 * </ol>
 */
class MailRoleSeedMigrationTest {

    private static final Path MIGRATION = Path.of("sql/migrations/migration_20261109b_mail_roles.sql");
    private static final Path INIT_SQL = Path.of("sql/init.sql");

    /**
     * 阁主 #255 要留档的正名清单 —— 删 yml 之前必须全部在种子里，一个都不能少。
     * 2026-11-09 #679 起正名是**汉字**，拼音只允许出现在别名表。
     */
    private static final Set<String> ARCHIVED_ROLE_CODES = new LinkedHashSet<>(Arrays.asList(
            "烛龙", "白泽", "阁主", "夫诸", "貔貅", "睚眦",
            "麒麟", "凤凰", "重明鸟", "九尾狐", "帝江", "穷奇"));

    /** 12 条拼音别名：客户端工具说明里仍写着它们，缺一条就是「服务端只认汉字、调用方还说拼音」。 */
    private static final Map<String, String> PINYIN_ALIASES = Map.ofEntries(
            Map.entry("zhulong", "烛龙"),
            Map.entry("baize", "白泽"),
            Map.entry("gezhu", "阁主"),
            Map.entry("fuzhu", "夫诸"),
            Map.entry("pixiu", "貔貅"),
            Map.entry("yazi", "睚眦"),
            Map.entry("qilin", "麒麟"),
            Map.entry("fenghuang", "凤凰"),
            Map.entry("chongmingniao", "重明鸟"),
            Map.entry("jiuweihu", "九尾狐"),
            Map.entry("dijiang", "帝江"),
            Map.entry("qiongqi", "穷奇"));

    /** 种子角色行：('正名', '显示名', 'ACTIVE', 'SEEDED', …) */
    private static final Pattern SEEDED_ROLE = Pattern.compile(
            "\\(\\s*'([^']+)'\\s*,\\s*'([^']*)'\\s*,\\s*'ACTIVE'\\s*,\\s*'SEEDED'");

    /** 别名行：('alias', 'role', '原因') */
    private static final Pattern SEEDED_ALIAS = Pattern.compile(
            "\\(\\s*'([^']+)'\\s*,\\s*'([^']+)'\\s*,\\s*'");

    // ------------------------------------------------------------ 主守卫

    @Test
    @DisplayName("种子正名必须等于留档清单 12 个汉字（漏一个，上线当天就有人收不到信）")
    void seededRolesMatchArchivedList() throws Exception {
        assertEquals(ARCHIVED_ROLE_CODES, seededRoles(),
                "迁移种子的正名与留档清单不一致。若确实要增删，请同步本测试并在回执里说明");
    }

    @Test
    @DisplayName("正名必须是汉字；拼音/错拼只许出现在别名表（出口一律汉字）")
    void canonicalRolesAreHanAndAliasesAreAscii() throws Exception {
        for (String r : seededRoles()) {
            assertTrue(r.matches("\\p{IsHan}+"), "正名「" + r + "」不是汉字 —— 拼音正名会让出口半套");
            assertTrue(r.codePointCount(0, r.length()) <= 32, "正名「" + r + "」超过 32 字符");
        }
        for (Map.Entry<String, String> e : seededAliases().entrySet()) {
            assertTrue(e.getKey().matches("[a-z0-9_-]+"), "别名「" + e.getKey() + "」不是小写拼音");
            assertTrue(e.getKey().length() <= 32, "别名「" + e.getKey() + "」超过 32 字符");
            assertTrue(e.getValue().matches("\\p{IsHan}+"), "别名目标「" + e.getValue() + "」不是汉字正名");
        }
    }

    @Test
    @DisplayName("12 条拼音别名必须齐全（兼容期客户端仍写拼音，漏一条当场把发信打死）")
    void pinyinAliasesCoverEveryCanonicalRole() throws Exception {
        Map<String, String> aliases = seededAliases();
        for (Map.Entry<String, String> e : PINYIN_ALIASES.entrySet()) {
            assertEquals(e.getValue(), aliases.get(e.getKey()),
                    "缺少拼音别名 " + e.getKey() + " → " + e.getValue()
                            + "；客户端工具说明写的还是拼音，只认汉字会让发信两侧同时失效");
        }
    }

    @Test
    @DisplayName("别名只能指向种子里的正名（指向不存在的正名 = 把信归一到没人收的地方）")
    void aliasTargetsMustExistInSeededRoles() throws Exception {
        Set<String> roles = seededRoles();
        for (Map.Entry<String, String> e : seededAliases().entrySet()) {
            assertTrue(roles.contains(e.getValue()),
                    "别名 " + e.getKey() + " → " + e.getValue() + "，但 " + e.getValue()
                            + " 不在种子正名里：" + roles);
        }
    }

    @Test
    @DisplayName("貔貅/睚眦必须是汉字正名；错拼 piyao/yaci 只许出现在别名表里")
    void legacyMisspellingsAreAliasesOnly() throws Exception {
        // 2026-11-08 阁主 #119 真事故：yml 曾把貔貅/睚眦写成 piyao/yaci，
        // 而忆阁闸门与桥头身份用正名 → 两边名单对不上，yazi 被拒、貔貅收不到信。
        Set<String> roles = seededRoles();
        assertTrue(roles.contains("貔貅") && roles.contains("睚眦"), "正名：" + roles);
        assertFalse(roles.contains("piyao") || roles.contains("yaci"), "错拼不许进正名：" + roles);
        assertEquals("貔貅", seededAliases().get("piyao"));
        assertEquals("睚眦", seededAliases().get("yaci"));
    }

    @Test
    @DisplayName("迁移必须幂等：CREATE TABLE IF NOT EXISTS + INSERT IGNORE + UPDATE 写回 updated_at")
    void migrationMustBeIdempotent() throws Exception {
        String sql = Files.readString(MIGRATION, StandardCharsets.UTF_8);
        assertTrue(Pattern.compile("CREATE\\s+TABLE\\s+IF\\s+NOT\\s+EXISTS\\s+mail_roles\\b",
                Pattern.CASE_INSENSITIVE).matcher(sql).find(), "mail_roles 建表缺 IF NOT EXISTS");
        assertTrue(Pattern.compile("CREATE\\s+TABLE\\s+IF\\s+NOT\\s+EXISTS\\s+mail_role_aliases\\b",
                Pattern.CASE_INSENSITIVE).matcher(sql).find(), "mail_role_aliases 建表缺 IF NOT EXISTS");
        long inserts = Pattern.compile("INSERT\\s+(IGNORE\\s+)?INTO\\s+mail_role", Pattern.CASE_INSENSITIVE)
                .matcher(sql).results().count();
        long ignores = Pattern.compile("INSERT\\s+IGNORE\\s+INTO\\s+mail_role", Pattern.CASE_INSENSITIVE)
                .matcher(sql).results().count();
        assertEquals(inserts, ignores, "有 INSERT 没带 IGNORE —— 重跑会因主键重复失败");
        assertEquals(3, inserts, "预期三条写入（正名、别名、历史未知值），实际 " + inserts);

        // 兜底 UPDATE 必须显式写回 updated_at（mail_roles.updated_at 带 ON UPDATE）
        String fallback = null;
        for (String st : stripComments(sql).split(";")) {
            if (st.contains("UPDATE mail_roles") && st.contains("'PENDING_REVIEW'")) {
                fallback = st;
                break;
            }
        }
        assertNotNull(fallback, "找不到兜底 UPDATE（A 段若被手工执行过，OBSERVED 行会留在 ACTIVE）");
        assertTrue(fallback.contains("updated_at = updated_at"),
                "UPDATE 会把 updated_at 静默刷成执行时刻（OnUpdateColumnGuardTest 的红线）：" + fallback);

        // 旧版 A 段的拼音正名行也要被停用，且不能把 updated_at 刷掉
        boolean disablesLegacy = Pattern.compile(
                        "UPDATE\\s+mail_roles\\s+r\\s+JOIN\\s+mail_role_aliases\\s+a\\s+ON\\s+a\\.alias\\s*=\\s*r\\.role",
                        Pattern.CASE_INSENSITIVE | Pattern.DOTALL)
                .matcher(stripComments(sql)).find();
        assertTrue(disablesLegacy, "缺少「旧版拼音正名行 → DISABLED」的防御语句");
        assertTrue(stripComments(sql).contains("r.updated_at = r.updated_at"),
                "停用旧拼音正名行时必须显式写回 updated_at");
    }

    @Test
    @DisplayName("历史数据必须按别名刷成汉字：收发两侧 + 认领人 + 游标，刷不动的留待复核")
    void historicalRowsAreRewrittenToCanonicalHan() throws Exception {
        String sql = stripComments(Files.readString(MIGRATION, StandardCharsets.UTF_8));

        String messages = statementContaining(sql, "UPDATE mail_messages");
        assertNotNull(messages, "缺少历史消息刷写语句 —— 库里会留下拼音 from_role/to_role（半套状态）");
        assertTrue(messages.contains("from_role") && messages.contains("to_role") && messages.contains("claimed_by"),
                "收发两侧与认领人都要刷，只刷 to_role 等于 half-done：" + messages);
        assertTrue(messages.contains("mail_role_aliases"),
                "刷写必须按别名表 JOIN；硬编码映射会漏掉错拼：" + messages);
        assertTrue(messages.contains("COALESCE"),
                "查不到别名的值必须原样保留（刷不动就留待人工复核，不硬猜）：" + messages);

        assertTrue(Pattern.compile("INSERT\\s+INTO\\s+mail_cursors", Pattern.CASE_INSENSITIVE)
                        .matcher(sql).find(), "游标没有迁移语句：拼音游标会让已读线程重新变未读");
        assertTrue(Pattern.compile("DELETE\\s+c\\s+FROM\\s+mail_cursors", Pattern.CASE_INSENSITIVE)
                        .matcher(sql).find(), "游标迁移后没有删除拼音行");
        assertTrue(sql.contains("ON DUPLICATE KEY UPDATE"),
                "游标表主键是 (role, thread_id)，直接 UPDATE 主键会在同线程已有汉字行时撞主键");
    }

    @Test
    @DisplayName("游标 ODKU 的目标列必须带表名（MySQL 5.7.44 实测：不带就 ERROR 1052 ambiguous）")
    void cursorUpsertMustQualifyTargetColumns() throws Exception {
        // 2026-11-09 06:07 线上真事故：d06789c 把这里的目标列去掉了表名，部署时
        //   ERROR 1052 (23000): Column 'last_read_id' in field list is ambiguous
        // INSERT ... SELECT 的来源表同名，解析器分不清左右两侧。单测只断言「有 ON DUPLICATE」
        // 抓不到它，所以这里钉死「目标列带 mail_cursors.」。
        String sql = stripComments(Files.readString(MIGRATION, StandardCharsets.UTF_8));
        String cursor = statementContaining(sql, "INSERT INTO mail_cursors");
        assertNotNull(cursor, "找不到游标迁移语句");
        int odkuIdx = cursor.indexOf("ON DUPLICATE KEY UPDATE");
        assertTrue(odkuIdx >= 0, "游标迁移缺 ON DUPLICATE KEY UPDATE");
        String odku = cursor.substring(odkuIdx);

        assertTrue(Pattern.compile(
                        "last_read_id\\s*=\\s*GREATEST\\(\\s*mail_cursors\\.last_read_id\\s*,\\s*VALUES\\(\\s*last_read_id\\s*\\)\\s*\\)")
                        .matcher(odku).find(),
                "ODKU 的 last_read_id 必须写成 mail_cursors.last_read_id（左侧）与 VALUES(last_read_id)（右侧）；"
                        + "不带表名在 MySQL 5.7.44 会 ERROR 1052：\n" + odku);
        assertTrue(Pattern.compile("updated_at\\s*=\\s*mail_cursors\\.updated_at")
                        .matcher(odku).find(),
                "ODKU 的 updated_at 也必须带 mail_cursors. 表名（同上，否则同样 ambiguous）：\n" + odku);
    }

    @Test
    @DisplayName("收件人列必须是 utf8mb4 且长度够放「重明鸟」这类汉字正名")
    void recipientColumnsAreUtf8mb4AndWideEnough() throws Exception {
        String init = Files.readString(INIT_SQL, StandardCharsets.UTF_8);
        int idx = init.indexOf("CREATE TABLE IF NOT EXISTS mail_messages");
        assertTrue(idx >= 0, "init.sql 找不到 mail_messages 建表");
        String block = init.substring(idx, init.indexOf(';', idx));
        assertTrue(block.contains("DEFAULT CHARSET=utf8mb4"), "mail_messages 不是 utf8mb4，汉字会乱码/被截断");
        assertTrue(Pattern.compile("to_role\\s+VARCHAR\\((\\d+)\\)").matcher(block).find(),
                "mail_messages.to_role 不是 VARCHAR，汉字正名无法比较");
        Matcher m = Pattern.compile("to_role\\s+VARCHAR\\((\\d+)\\)").matcher(block);
        assertTrue(m.find());
        int width = Integer.parseInt(m.group(1));
        assertTrue(width >= 3, "to_role 宽度 " + width + " 放不下「重明鸟」（3 个字符）");
        // 迁移脚本自身也要在部署日志里自证列字符集
        String migration = Files.readString(MIGRATION, StandardCharsets.UTF_8);
        assertTrue(migration.contains("information_schema.COLUMNS")
                        && migration.contains("CHARACTER_SET_NAME"),
                "迁移脚本缺少 to_role 字符集/宽度自证 SELECT");
    }

    @Test
    @DisplayName("历史未知收件人导成 OBSERVED + PENDING_REVIEW（待复核，不是合法来源）")
    void observedRolesAreImportedAsPendingReview() throws Exception {
        String sql = stripComments(Files.readString(MIGRATION, StandardCharsets.UTF_8));
        String observed = statementContaining(sql, "'OBSERVED'");
        assertNotNull(observed, "找不到 OBSERVED 导入语句");
        assertTrue(observed.contains("mail_messages"),
                "OBSERVED 必须来自 mail_messages 历史行，否则切换当天老角色会突然「收件人不存在」");
        assertTrue(observed.contains("from_role") && observed.contains("to_role"),
                "收发两侧都要扫：只扫 to_role 会漏掉「只发过信、没收到过」的角色");
        assertTrue(observed.contains("mail_role_aliases"),
                "OBSERVED 必须排除别名键（piyao/zhulong…），"
                        + "否则它们会以「角色」身份混进名单，把别名归一的意义抵消掉");
        assertTrue(observed.contains("LOWER(TRIM("),
                "历史值必须 LOWER(TRIM()) 后再入库——大小写/空格不一致会让同一角色变成两行");
        assertTrue(observed.contains("'PENDING_REVIEW'"),
                "历史未知值必须是 PENDING_REVIEW：status='ACTIVE' 就等于承认「历史上出现过 = 现在该收信」，"
                        + "那正是 #1459 要修的那件事");
        assertFalse(observed.contains("'ACTIVE'"), "OBSERVED 导入不许写 ACTIVE：" + observed);
    }

    @Test
    @DisplayName("application.yml 不许再声明 known-roles / role-aliases（B 段已删，加回来只会误导）")
    void ymlNoLongerDeclaresRoleLists() {
        String yml = configText();
        assertFalse(Pattern.compile("(?m)^\\s*known-roles\\s*:").matcher(yml).find(),
                "application.yml 里还有 known-roles —— 运行时一行都不读它，改了也不生效");
        assertFalse(Pattern.compile("(?m)^\\s*role-aliases\\s*:").matcher(yml).find(),
                "application.yml 里还有 role-aliases —— 别名唯一来源是 mail_role_aliases 表");
    }

    @Test
    @DisplayName("sql/init.sql 的种子必须与迁移脚本一致（开源新装路径与升级路径不许漂）")
    void initSqlSeedsMatchMigration() throws Exception {
        String init = Files.readString(INIT_SQL, StandardCharsets.UTF_8);
        assertEquals(seededRoles(), seededRolesOf(init), "init.sql 与迁移脚本的正名种子不一致");
        assertEquals(seededAliases(), seededAliasesOf(init), "init.sql 与迁移脚本的别名种子不一致");
        assertTrue(init.contains("CREATE TABLE IF NOT EXISTS mail_roles"), "init.sql 缺 mail_roles 建表");
        assertTrue(init.contains("CREATE TABLE IF NOT EXISTS mail_role_aliases"),
                "init.sql 缺 mail_role_aliases 建表");
    }

    @Test
    @DisplayName("读名单只许走两个 mapper：别的类自己拼 SQL 读表，等于绕过缓存与降级逻辑")
    void onlyMappersMayReferenceRoleTables() throws Exception {
        Set<String> allowed = Set.of(
                "MailRole.java", "MailRoleAlias.java", "MailRoleMapper.java", "MailRoleAliasMapper.java");
        List<String> offenders = new ArrayList<>();
        try (Stream<Path> s = Files.walk(Path.of("src/main"))) {
            for (Path p : s.filter(Files::isRegularFile).collect(Collectors.toList())) {
                if (!p.toString().endsWith(".java") && !p.toString().endsWith(".xml")) continue;
                String text = stripJavaAndSqlComments(Files.readString(p, StandardCharsets.UTF_8))
                        .toLowerCase(Locale.ROOT);
                if (!text.contains("mail_roles") && !text.contains("mail_role_aliases")) continue;
                if (allowed.contains(p.getFileName().toString())) continue;
                offenders.add(p.toString());
            }
        }
        assertTrue(offenders.isEmpty(),
                "这些文件直接引用了角色表名 —— 读名单请走 MailRoleCatalog（30 秒快照 + 降级都在那里）：\n  "
                        + String.join("\n  ", offenders));
    }

    // ------------------------------------------------------------ 读 application.yml（先剔注释）

    private static String configText() {
        InputStream in = MailRoleSeedMigrationTest.class.getClassLoader()
                .getResourceAsStream("application.yml");
        assertNotNull(in, "classpath 上找不到 application.yml");
        try (BufferedReader r = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
            return r.lines().filter(l -> !l.trim().startsWith("#")).collect(Collectors.joining("\n"));
        } catch (Exception e) {
            fail("读 application.yml 失败：" + e.getMessage());
            return "";
        }
    }

    // ------------------------------------------------------------ 读 SQL

    private static Set<String> seededRoles() throws Exception {
        return seededRolesOf(Files.readString(MIGRATION, StandardCharsets.UTF_8));
    }

    private static Map<String, String> seededAliases() throws Exception {
        return seededAliasesOf(Files.readString(MIGRATION, StandardCharsets.UTF_8));
    }

    private static Set<String> seededRolesOf(String sql) {
        Set<String> out = new LinkedHashSet<>();
        Matcher m = SEEDED_ROLE.matcher(sql);
        while (m.find()) out.add(m.group(1));
        return out;
    }

    private static Map<String, String> seededAliasesOf(String sql) {
        Map<String, String> out = new LinkedHashMap<>();
        int idx = sql.indexOf("INSERT IGNORE INTO mail_role_aliases");
        assertTrue(idx >= 0, "找不到 mail_role_aliases 的种子 INSERT");
        int end = sql.indexOf(';', idx);
        String block = end > idx ? sql.substring(idx, end) : sql.substring(idx);
        Matcher m = SEEDED_ALIAS.matcher(block);
        while (m.find()) out.put(m.group(1), m.group(2));
        return out;
    }

    private static String statementContaining(String sql, String needle) {
        for (String st : sql.split(";")) {
            if (st.contains(needle)) return st;
        }
        return null;
    }

    /** 去 SQL 的 -- 行注释（保留换行）。 */
    private static String stripComments(String sql) {
        return Arrays.stream(sql.split("\n", -1))
                .map(l -> {
                    int i = l.indexOf("--");
                    return i >= 0 ? l.substring(0, i) : l;
                })
                .collect(Collectors.joining("\n"));
    }

    /** 去 Java/SQL 注释，避免「注释里提到表名」被当成真的读写（AGENTS.md 红线 10）。 */
    private static String stripJavaAndSqlComments(String text) {
        String noBlock = text.replaceAll("(?s)/\\*.*?\\*/", "");
        return Arrays.stream(noBlock.split("\n", -1))
                .map(l -> {
                    int sl = l.indexOf("//");
                    int dd = l.indexOf("--");
                    int cut = sl >= 0 ? sl : -1;
                    if (dd >= 0 && (cut < 0 || dd < cut)) cut = dd;
                    return cut >= 0 ? l.substring(0, cut) : l;
                })
                .collect(Collectors.joining("\n"));
    }
}
