package com.exphub.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 验证时间的「来源」（2026-10-29 阁主第 ① 条）。
 *
 * <p><b>为什么需要一个来源列，而不是把时间显示出来就够了</b>：{@code last_verified_at} 上线时，
 * 645 篇存量值是用 {@code updated_at} 回填的 —— 那只是「最后一次被改过」的下界，
 * 不是「有人确认过结论仍然成立」。时间本身看不出这个差别，回执里不标来源，
 * 下一个 AI 就会把它当验证证据引用，而**这个误会不会有任何报错提醒**。
 *
 * <p>三条要钉死：
 * <ol>
 *   <li>不传 {@code verifiedAt} → {@code auto}；传了 → {@code manual}（不能反过来）</li>
 *   <li>四条写路径都填来源 —— 少一处就出现「这条路径改过的文档永远显示来源未记录」</li>
 *   <li>回填迁移必须把存量标成 {@code backfill}，且不许顺手刷掉 {@code updated_at}</li>
 * </ol>
 */
class VerifiedSourceTest {

    @Test
    @DisplayName("不传 verifiedAt → auto（写操作顺带记录，不是人工确认）；传了 → manual")
    void resolvesThreeStates() {
        assertEquals(DocService.VERIFIED_AUTO, DocService.resolveVerifiedSource(null));
        assertEquals(DocService.VERIFIED_MANUAL,
                DocService.resolveVerifiedSource(LocalDateTime.of(2026, 10, 20, 9, 0)));
    }

    @Test
    @DisplayName("守卫：create / update / appendContent / patchContent 四条写路径都要同时填「时间」和「来源」")
    void allWritePathsFillSource() throws Exception {
        String code = read("src/main/java/com/exphub/service/DocService.java");

        int verifyAssigns = count(code, ".setLastVerifiedAt(resolveVerifiedAt(");
        assertEquals(4, verifyAssigns,
                "写验证时间的路径应为 4 条（create / update / appendContent / patchContent），实际 "
                        + verifyAssigns);

        int sourceAssigns = count(code, ".setLastVerifiedSource(resolveVerifiedSource(");
        assertEquals(4, sourceAssigns,
                "填了验证时间却没填来源的路径有 " + (verifyAssigns - sourceAssigns) + " 条。"
                        + "后果：这条路径写出来的文档来源是 null，回执只能印「来源未记录」——"
                        + "等于把「自动/人工/回填」三态里最需要区分的那一态又还回去了");
    }

    @Test
    @DisplayName("守卫：必须先读入参再改写字段，否则来源永远是 manual（自己刚填的时间被当成调用方传的）")
    void readsExplicitValueBeforeOverwritingField() throws Exception {
        String code = read("src/main/java/com/exphub/service/DocService.java");

        assertTrue(code.contains("LocalDateTime explicitVerify = doc.getLastVerifiedAt();"),
                "create 路径没有先存局部变量：setLastVerifiedAt(resolveVerifiedAt(...)) 之后字段已非 null，"
                        + "再 resolveVerifiedSource(doc.getLastVerifiedAt()) 会判成 manual —— "
                        + "每条新建经验都会被标成「人工声明验证时间」");
        assertTrue(code.contains("LocalDateTime explicitVerifyU = newDoc.getLastVerifiedAt();"),
                "update 路径同样要先读入参，理由同上");
    }

    @Test
    @DisplayName("守卫：回填迁移把存量标 backfill，且必须显式写 updated_at = updated_at")
    void backfillMigrationIsSafe() throws Exception {
        String sql = read("sql/migrations/migration_20261029d_verify_source.sql");

        assertTrue(sql.contains("ADD COLUMN last_verified_source"),
                "迁移里没有加列语句");
        assertTrue(sql.contains("SET last_verified_source = 'backfill'"),
                "回填值没有标成 backfill —— 存量 645 篇会被当成真验证记录");
        assertTrue(sql.contains("updated_at           = updated_at")
                        || sql.contains("updated_at = updated_at"),
                "回填语句缺 updated_at = updated_at。docs.updated_at 带 ON UPDATE CURRENT_TIMESTAMP，"
                        + "只 SET 新列也会把全库修改时间刷成执行那一刻 —— 2026-10-29 已经这样压平过一次");
    }

    @Test
    @DisplayName("守卫：来源列的取值必须与回执标签同源（常量只在 DocService 定义一次）")
    void sourceConstantsAreSingleSourced() throws Exception {
        String code = read("src/main/java/com/exphub/mcp/ExpHubTools.java");
        assertTrue(code.contains("DocService.VERIFIED_BACKFILL"),
                "回执里没有引用 DocService 的常量，而是自己写了字符串 —— 改常量后回执会静默失配");
        assertTrue(code.contains("DocService.VERIFIED_MANUAL") && code.contains("DocService.VERIFIED_AUTO"),
                "三态标签必须都走常量");
    }

    private static String read(String path) throws Exception {
        Path p = Path.of(path);
        assertTrue(Files.exists(p), "找不到文件：" + p.toAbsolutePath());
        assertNotNull(p);
        return new String(Files.readAllBytes(p), StandardCharsets.UTF_8);
    }

    private static int count(String code, String needle) {
        return code.split(java.util.regex.Pattern.quote(needle), -1).length - 1;
    }
}
