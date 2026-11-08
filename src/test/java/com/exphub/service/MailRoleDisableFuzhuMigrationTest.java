package com.exphub.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 「停用夫诸」这一条迁移的守卫（阁主线程 T261109062718-346 / #305）。
 *
 * <p><b>这条迁移最危险的失败形态不是「没停用」，而是「顺手多停用了几个」</b>：
 * {@code mail_roles} 是信箱的可投递地址清单，哨兵 {@code roles.json} 是「有人在轮询的角色清单」，
 * 两个口径不是一回事。谁要是把这脚本写成「所有非 ACTIVE 的一起扫」或者
 * {@code WHERE status <> 'ACTIVE'}，当天就会有一批还在用的人收不到信，而且现象是
 * 「信发出去了、没人回」——比直接报错难查得多。
 *
 * <p>所以这里钉五件事：
 * <ol>
 *   <li>文件必须落在 deploy.sh 的 {@code migration_2026*.sql} glob 里，且排在 b 之后；</li>
 *   <li>写 {@code mail_roles} 的语句<b>有且只有一条</b>，且必须被 {@code role='夫诸'} 收窄；</li>
 *   <li>必须幂等（{@code status <> 'DISABLED'} 守卫）；</li>
 *   <li>回滚语句只许出现在注释里 —— 混进可执行部分等于「刚停用就自己恢复」；</li>
 *   <li>ON UPDATE 列必须显式写回（{@code updated_at = updated_at}）。</li>
 * </ol>
 */
class MailRoleDisableFuzhuMigrationTest {

    private static final Path MIGRATION =
            Path.of("sql/migrations/migration_20261109c_disable_fuzhu_role.sql");

    private static String raw() throws Exception {
        assertTrue(Files.exists(MIGRATION), "找不到迁移文件：" + MIGRATION);
        return Files.readString(MIGRATION, StandardCharsets.UTF_8);
    }

    private static String executable() throws Exception {
        return stripComments(raw());
    }

    @Test
    @DisplayName("文件名必须被 deploy.sh 的 migration_2026*.sql 收走，且排在角色清单迁移之后")
    void fileIsPickedUpByDeployAfterSeedMigration() {
        String name = MIGRATION.getFileName().toString();
        assertTrue(Pattern.compile("^migration_2026\\d+[a-z]?_.+\\.sql$").matcher(name).matches(),
                "deploy.sh 只跑 sql/migrations/migration_2026*.sql，当前文件名不会被执行：" + name);
        assertTrue(name.compareTo("migration_20261109b_mail_roles.sql") > 0,
                "必须排在 migration_20261109b_mail_roles.sql 之后（先种 ACTIVE 行，再停用），实际：" + name);
    }

    @Test
    @DisplayName("写 mail_roles 的语句有且只有一条，且必须被 role='夫诸' 收窄（只动这一行）")
    void onlyOneRowIsTouched() throws Exception {
        String sql = executable();
        List<String> writes = new ArrayList<>();
        for (String st : sql.split(";")) {
            if (Pattern.compile("\\b(UPDATE|DELETE|INSERT|REPLACE|DROP|TRUNCATE|ALTER)\\b",
                    Pattern.CASE_INSENSITIVE).matcher(st).find()) {
                writes.add(st.trim());
            }
        }
        assertEquals(1, writes.size(),
                "这条迁移只该有一条写语句（停用夫诸），实际 " + writes.size() + " 条：\n"
                        + String.join("\n---\n", writes));

        String w = writes.get(0);
        assertTrue(Pattern.compile("UPDATE\\s+mail_roles", Pattern.CASE_INSENSITIVE).matcher(w).find(),
                "唯一那条写语句必须是 UPDATE mail_roles：" + w);
        assertTrue(Pattern.compile("role\\s*=\\s*'夫诸'").matcher(w).find(),
                "必须按 role='夫诸' 精确收窄，不许整表扫：" + w);
        assertTrue(Pattern.compile("status\\s*=\\s*'DISABLED'").matcher(w).find(),
                "目标状态必须是 DISABLED：" + w);
        assertFalse(Pattern.compile("\\b(DELETE|DROP|TRUNCATE|ALTER)\\b", Pattern.CASE_INSENSITIVE)
                        .matcher(w).find(),
                "停用不许删行/改结构（名单本身是审计证据）：" + w);
        assertFalse(Pattern.compile("status\\s*(<>|!=|NOT\\s+IN)", Pattern.CASE_INSENSITIVE)
                        .matcher(w.replaceAll("status\\s*<>\\s*'DISABLED'", "")).find(),
                "WHERE 只许排除「已经是 DISABLED」，不许出现其它按状态筛的条件（会误停别人）：" + w);
    }

    @Test
    @DisplayName("必须幂等：带 status <> 'DISABLED' 守卫，重跑匹配 0 行")
    void forwardUpdateIsIdempotent() throws Exception {
        String w = executable();
        assertTrue(Pattern.compile("status\\s*<>\\s*'DISABLED'", Pattern.CASE_INSENSITIVE).matcher(w).find(),
                "缺少幂等守卫：deploy.sh 在 md5 变化时会重跑，没有守卫虽不报错但语义不清");
    }

    @Test
    @DisplayName("回滚语句只许在注释里（混进可执行部分 = 刚停用就自己恢复）")
    void rollbackIsDocumentedButNotExecutable() throws Exception {
        String raw = raw();
        String sql = executable();
        assertFalse(sql.contains("'ACTIVE'"),
                "可执行部分出现了 'ACTIVE' —— 回滚语句被写成了真语句，会把刚停用的行立刻恢复：\n" + sql);

        String comments = commentsOnly(raw);
        assertTrue(Pattern.compile("UPDATE\\s+mail_roles", Pattern.CASE_INSENSITIVE).matcher(comments).find()
                        && comments.contains("'ACTIVE'"),
                "注释里必须写明反向 UPDATE（回滚就这一句），否则下一个人只能靠猜");
        assertTrue(comments.contains("updated_at = updated_at"),
                "回滚语句也要写回 updated_at（同样是 ON UPDATE 列）");
    }

    @Test
    @DisplayName("ON UPDATE 列必须显式写回：updated_at = updated_at")
    void updatedAtIsWrittenBack() throws Exception {
        assertTrue(Pattern.compile("updated_at\\s*=\\s*updated_at").matcher(executable()).find(),
                "mail_roles.updated_at 带 ON UPDATE，不写回会把上次变更时间刷成执行时刻（AGENTS 第 15 章）");
    }

    @Test
    @DisplayName("可执行部分不许出现别的角色名（防止「顺手把 PENDING_REVIEW 也扫一遍」这类改动）")
    void noOtherRoleLiteralsInExecutableSql() throws Exception {
        String sql = executable();
        List<String> others = Stream.of("烛龙", "白泽", "阁主", "貔貅", "睚眦", "麒麟",
                        "凤凰", "重明鸟", "九尾狐", "帝江", "穷奇")
                .filter(sql::contains)
                .collect(Collectors.toList());
        assertTrue(others.isEmpty(), "可执行 SQL 里出现了别的角色名：" + others + "\n" + sql);

        Matcher m = Pattern.compile("'([^']*)'").matcher(sql);
        List<String> literals = new ArrayList<>();
        while (m.find()) literals.add(m.group(1));
        // 只允许这几个字面量：状态值 + 目标角色 + updated_by 审计标识
        List<String> unexpected = literals.stream()
                .filter(v -> !Arrays.asList("DISABLED", "夫诸", "migration_20261109c").contains(v))
                .collect(Collectors.toList());
        assertTrue(unexpected.isEmpty(), "可执行 SQL 里出现了预期之外的字面量：" + unexpected);
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

    /** 只留 -- 行注释的内容。 */
    private static String commentsOnly(String sql) {
        return Arrays.stream(sql.split("\n", -1))
                .map(l -> {
                    int i = l.indexOf("--");
                    return i >= 0 ? l.substring(i + 2) : "";
                })
                .collect(Collectors.joining("\n"));
    }
}
