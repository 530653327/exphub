package com.exphub.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 「退役角色」收尾迁移的结构守卫（阁主线程 T261109120139-277 / #332）。
 *
 * <p>这条迁移的职责是**把某个已下线的角色从配置表里彻底去掉**，
 * 所以最危险的失败形态与「停用」那条正相反：
 * <ul>
 *   <li><b>删多了</b>：写成 {@code DELETE FROM mail_roles}（无 WHERE）或者
 *       {@code WHERE status <> 'ACTIVE'}，会把还在用的角色一起删掉 —— 当天就有一批人收不到信，
 *       现象是「信发出去了、没人回」，比报错难查得多；</li>
 *   <li><b>删到历史</b>：顺手清 {@code mail_messages} / {@code mail_cursors}，
 *       那是在改事实（历史信件不是配置），同样不可逆。</li>
 * </ul>
 *
 * <p>这里钉五件事（全部按结构判，不依赖具体是哪个角色名）：
 * <ol>
 *   <li>文件落在 deploy.sh 的 {@code migration_2026*.sql} glob 里，且排在种子迁移之后；</li>
 *   <li>可执行部分恰好两条写语句：别名表一条 DELETE、正名表一条 DELETE；</li>
 *   <li>每条都被**等值谓词**收窄（不许整表删、不许按 status 扫）；</li>
 *   <li>两张表用的是同一个角色字面量（别名与正名一起收干净，不许只删一半）；</li>
 *   <li>不碰历史消息/游标，也不改表结构。</li>
 * </ol>
 */
class MailRoleRemovalMigrationTest {

    private static final Path MIGRATION =
            Path.of("sql/migrations/migration_20261109c_disable_fuzhu_role.sql");

    private static String raw() throws Exception {
        assertTrue(Files.exists(MIGRATION), "找不到迁移文件：" + MIGRATION);
        return Files.readString(MIGRATION, StandardCharsets.UTF_8);
    }

    private static String executable() throws Exception {
        return stripComments(raw());
    }

    private static List<String> writeStatements(String sql) {
        List<String> out = new ArrayList<>();
        for (String st : sql.split(";")) {
            if (Pattern.compile("\\b(UPDATE|DELETE|INSERT|REPLACE|DROP|TRUNCATE|ALTER)\\b",
                    Pattern.CASE_INSENSITIVE).matcher(st).find()) {
                out.add(st.trim());
            }
        }
        return out;
    }

    @Test
    @DisplayName("文件名必须被 deploy.sh 的 migration_2026*.sql 收走，且排在角色清单种子迁移之后")
    void fileIsPickedUpByDeployAfterSeedMigration() {
        String name = MIGRATION.getFileName().toString();
        assertTrue(Pattern.compile("^migration_2026\\d+[a-z]?_.+\\.sql$").matcher(name).matches(),
                "deploy.sh 只跑 sql/migrations/migration_2026*.sql，当前文件名不会被执行：" + name);
        assertTrue(name.compareTo("migration_20261109b_mail_roles.sql") > 0,
                "必须排在 migration_20261109b_mail_roles.sql 之后（先建表/种子，再收拾存量行），实际：" + name);
    }

    @Test
    @DisplayName("恰好两条写语句：别名表一条 DELETE、正名表一条 DELETE（多一条都要先问为什么）")
    void exactlyTwoDeletesOneEachTable() throws Exception {
        List<String> writes = writeStatements(executable());
        assertEquals(2, writes.size(),
                "这条迁移只该有两条写语句（两张配置表各删一次），实际 " + writes.size() + " 条：\n"
                        + String.join("\n---\n", writes));

        String aliases = null, roles = null;
        for (String w : writes) {
            if (Pattern.compile("DELETE\\s+FROM\\s+mail_role_aliases\\b", Pattern.CASE_INSENSITIVE)
                    .matcher(w).find()) aliases = w;
            if (Pattern.compile("DELETE\\s+FROM\\s+mail_roles\\b", Pattern.CASE_INSENSITIVE)
                    .matcher(w).find()) roles = w;
        }
        assertNotNull(aliases, "缺少对 mail_role_aliases 的 DELETE（别名留着，拼音入口会指向已不存在的正名）：\n"
                + String.join("\n---\n", writes));
        assertNotNull(roles, "缺少对 mail_roles 的 DELETE：\n" + String.join("\n---\n", writes));
    }

    @Test
    @DisplayName("每条 DELETE 都必须被等值谓词收窄，不许整表删、不许按 status 扫")
    void deletesAreNarrowedByEqualityOnly() throws Exception {
        for (String w : writeStatements(executable())) {
            assertTrue(Pattern.compile("\\bWHERE\\b", Pattern.CASE_INSENSITIVE).matcher(w).find(),
                    "DELETE 没有 WHERE —— 等于清空配置表，所有人当天都收不到信：" + w);
            assertTrue(Pattern.compile("(role|alias)\\s*=\\s*'[^']+'").matcher(w).find(),
                    "必须按 role/alias 等值精确收窄：" + w);
            assertFalse(Pattern.compile("\\b1\\s*=\\s*1\\b").matcher(w).find(), "禁止恒真条件：" + w);
            assertFalse(Pattern.compile("status\\s*(<>|!=|NOT\\s+IN|<|>)\\s*'", Pattern.CASE_INSENSITIVE)
                            .matcher(w).find(),
                    "禁止按状态成批删（会把别的停用行一起带走）：" + w);
        }
    }

    @Test
    @DisplayName("两张表必须用同一个角色字面量：别名与正名一起收干净，不许只删一半")
    void bothDeletesTargetTheSameRole() throws Exception {
        String aliases = null, roles = null;
        for (String w : writeStatements(executable())) {
            if (w.contains("mail_role_aliases")) aliases = w;
            if (w.contains("mail_roles")) roles = w;
        }
        assertNotNull(aliases);
        assertNotNull(roles);

        Matcher rolePredicate = Pattern.compile("role\\s*=\\s*'([^']+)'").matcher(roles);
        assertTrue(rolePredicate.find(), "mail_roles 的 DELETE 必须按 role='…' 收窄：" + roles);
        String canonical = rolePredicate.group(1);
        assertTrue(aliases.contains("'" + canonical + "'"),
                "别名的 DELETE 没有覆盖同一个角色「" + canonical + "」—— 只删正名会留下悬空别名：\n" + aliases);
    }

    @Test
    @DisplayName("只删配置行：历史消息/游标一行都不许碰，也不许改表结构")
    void historyAndSchemaAreUntouched() throws Exception {
        String sql = executable();
        assertFalse(Pattern.compile("\\b(mail_messages|mail_cursors)\\b").matcher(sql).find(),
                "历史消息与游标是事实，不是配置；这条迁移不许碰它们：\n" + sql);
        assertFalse(Pattern.compile("\\b(DROP|TRUNCATE|ALTER|CREATE)\\b", Pattern.CASE_INSENSITIVE)
                        .matcher(sql).find(),
                "这条迁移只删数据行，不许改表结构：\n" + sql);
        assertTrue(Pattern.compile("COUNT\\(\\*\\)", Pattern.CASE_INSENSITIVE).matcher(sql).find(),
                "缺自证计数：改完要能一眼看出表里还剩几行");
    }

    /** 去 SQL 的 -- 行注释（保留换行）。 */
    private static String stripComments(String sql) {
        return java.util.Arrays.stream(sql.split("\n", -1))
                .map(l -> {
                    int i = l.indexOf("--");
                    return i >= 0 ? l.substring(0, i) : l;
                })
                .reduce((a, b) -> a + "\n" + b)
                .orElse("");
    }
}
