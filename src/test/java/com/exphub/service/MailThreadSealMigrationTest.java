package com.exphub.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 线程封存迁移的守卫（2026-11-14 阁主 W-20261114-12）。
 *
 * <p>这一单的迁移有两条硬约束，写错任何一条都是不可逆的：
 * <ol>
 *   <li><b>只许 ADD COLUMN / 新建表 / 加索引</b> —— 不许删改已有数据；</li>
 *   <li><b>新装路径（sql/init.sql）必须同步</b> —— 否则开源新装起来的库缺表，
 *       要到第一次发 delivered 回执时才炸（而且报的是 Unknown table）。</li>
 * </ol>
 *
 * <p>⚠️ 扫的是<b>去掉 SQL 注释之后</b>的正文：注释里当然会出现「不许 DROP」这类字样，
 * 直接在原文上找关键字会被自己的注释喂饱（AGENTS 红线 10）。
 */
class MailThreadSealMigrationTest {

    private static final Path MIGRATION = Path.of("sql/migrations/migration_20261114b_mail_thread_seal.sql");
    private static final Path INIT_SQL = Path.of("sql/init.sql");

    private static String read(Path p) throws IOException {
        return Files.readString(p, StandardCharsets.UTF_8);
    }

    /** 去掉以 {@code --} 开头的单行注释，只留可执行正文。 */
    private static String stripSqlComments(String sql) {
        return sql.lines()
                .filter(l -> !l.trim().startsWith("--"))
                .collect(Collectors.joining("\n"));
    }

    @Test
    @DisplayName("迁移建了三张表 + 加了 order_no 列与索引")
    void createsTablesAndColumn() throws IOException {
        String raw = read(MIGRATION);
        String sql = stripSqlComments(raw);

        assertTrue(sql.contains("ADD COLUMN order_no"),
                "mail_messages 必须有 order_no 列（按单号找线程、唤醒去重都靠它）");
        assertTrue(sql.contains("idx_mail_order"), "按单号查线程要索引");
        assertTrue(sql.contains("CREATE TABLE IF NOT EXISTS mail_thread_states"),
                "缺 mail_thread_states：封存状态无处落库");
        assertTrue(sql.contains("CREATE TABLE IF NOT EXISTS mail_thread_state_log"),
                "缺 mail_thread_state_log：解锁留痕（谁/何时/哪根线程）就没地方记");
        assertTrue(sql.contains("CREATE TABLE IF NOT EXISTS mail_wake_offers"),
                "缺 mail_wake_offers：唤醒去重账本没有");
    }

    @Test
    @DisplayName("迁移不删、不改任何已有数据（只允许加列/建表/加索引）")
    void migrationIsAdditiveOnly() throws IOException {
        String sql = stripSqlComments(read(MIGRATION)).toLowerCase();

        assertFalse(sql.contains("drop table"), "禁止 DROP TABLE");
        assertFalse(sql.contains("drop column"), "禁止 DROP COLUMN");
        assertFalse(sql.contains("drop index"), "禁止 DROP INDEX（20261026c 已经这么坑过一次）");
        assertFalse(sql.contains("delete from"), "禁止 DELETE 任何行");
        assertFalse(sql.contains("truncate"), "禁止 TRUNCATE");
        // UPDATE 只允许动「本单新建的表」；对 mail_messages 的 UPDATE 就是回填历史数据，本单禁止。
        assertFalse(sql.replace(" ", "").contains("updatemail_messages"),
                "禁止 UPDATE mail_messages：历史信不回填（本单明确不改已有数据）");
    }

    @Test
    @DisplayName("新装路径 sql/init.sql 同步了同样的列与三张表")
    void initSqlIsInSync() throws IOException {
        String init = stripSqlComments(read(INIT_SQL));

        assertTrue(init.contains("order_no"),
                "sql/init.sql 的 mail_messages 缺 order_no —— 新装库会缺列");
        assertTrue(init.contains("idx_mail_order"), "sql/init.sql 缺 idx_mail_order");
        assertTrue(init.contains("mail_thread_states"), "sql/init.sql 缺 mail_thread_states");
        assertTrue(init.contains("mail_thread_state_log"), "sql/init.sql 缺 mail_thread_state_log");
        assertTrue(init.contains("mail_wake_offers"), "sql/init.sql 缺 mail_wake_offers");
    }

    @Test
    @DisplayName("迁移可重复执行：列/索引先查 information_schema，表用 IF NOT EXISTS")
    void migrationIsIdempotent() throws IOException {
        String sql = stripSqlComments(read(MIGRATION));

        assertTrue(sql.contains("information_schema.COLUMNS"), "加列前必须查列是否存在");
        assertTrue(sql.contains("information_schema.STATISTICS"), "加索引前必须查索引是否存在");
        List<String> creates = sql.lines()
                .filter(l -> l.trim().toUpperCase().startsWith("CREATE TABLE"))
                .map(l -> l.trim().toUpperCase())
                .collect(Collectors.toList());
        assertFalse(creates.isEmpty());
        creates.forEach(l -> assertTrue(l.contains("IF NOT EXISTS"), "建表必须 IF NOT EXISTS：" + l));
    }
}
