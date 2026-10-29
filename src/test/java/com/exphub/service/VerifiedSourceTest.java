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
    @DisplayName("守卫：按方法名钉住 4 条 DocService 路径 + 门户 1 条，每条都要同时填「时间」和「来源」")
    void allWritePathsFillSource() throws Exception {
        String code = read("src/main/java/com/exphub/service/DocService.java");

        // 🔴 2026-10-29 改口径（越哥）：不再数总数（合并两处调用面后「少一条 + 另一条重复」总和不变）。
        //    逐条按方法名钉，少哪条红哪条；update 与门户走白名单，时间/来源由判定结果成对产出。
        assertPair(code, "public Doc create(Doc doc)", "create");
        assertPair(code, "public Doc update(Long id, Doc newDoc, java.util.Set<String> clearFields, String authorOverride)", "update");
        assertPair(code, "public Doc appendContent(Long id, String addition, String authorName, LocalDateTime verifiedAt)", "appendContent");
        assertPair(code, "public Doc patchContent(Long id, String find, String replacement, String authorName, LocalDateTime verifiedAt)", "patchContent");

        // 第 5 条：门户改正文（2026-10-29 补写）。来源只能是 auto —— 门户证明不了用户真的复核过
        String portal = read("src/main/java/com/exphub/controller/PublicUserController.java");
        String portalBody = SourceScan.methodBody(portal, "public R<Doc> updateMyDoc(");
        assertTrue(!portalBody.isEmpty(), "门户 updateMyDoc 方法体没取到（签名变了？）—— 守卫不能静默放行");
        assertTrue(portalBody.contains("setLastVerifiedAt("), "门户改正文必须写验证时间，否则与 MCP 侧语义分叉");
        assertTrue(portalBody.contains("setLastVerifiedSource(DocService.VERIFIED_AUTO)"),
                "门户来源必须填 auto；填 manual 等于伪造一次「人工确认」");
        assertTrue(portalBody.contains("contentChanged"),
                "门户必须判 content 是否真变了：只改标题/摘要不该刷新验证时间");
    }

    /** 方法体必须存在，且「时间」与「来源」成对出现（否则来源为 null，回执只能印「来源未记录」）。 */
    private static void assertPair(String code, String signature, String label) {
        String body = SourceScan.methodBody(code, signature);
        assertTrue(!body.isEmpty(), label + " 方法体没取到（签名变了？）—— 守卫不能静默放行");
        assertTrue(body.contains(".setLastVerifiedAt("), label + " 没有填验证时间");
        assertTrue(body.contains(".setLastVerifiedSource("),
                label + " 填了时间却没填来源 —— 这条路径写出来的文档来源是 null，"
                        + "回执只能印「来源未记录」，等于把最需要区分的「自动/人工/回填」三态又还回去了");
    }

    @Test
    @DisplayName("守卫：必须先读入参再改写字段，否则来源永远是 manual（自己刚填的时间被当成调用方传的）")
    void readsExplicitValueBeforeOverwritingField() throws Exception {
        String code = read("src/main/java/com/exphub/service/DocService.java");

        assertTrue(code.contains("LocalDateTime explicitVerify = doc.getLastVerifiedAt();"),
                "create 路径没有先存局部变量：setLastVerifiedAt(resolveVerifiedAt(...)) 之后字段已非 null，"
                        + "再 resolveVerifiedSource(doc.getLastVerifiedAt()) 会判成 manual —— "
                        + "每条新建经验都会被标成「人工声明验证时间」");
        // update 路径 2026-10-29 起改成把入参直接交给 decideVerify：仍然必须在改写字段**之前**读，
        // 否则读到的是刚填的时间，来源会从 backfill 被误判成 manual（伪造一次人工确认）
        assertTrue(code.contains("decideVerify(old, contentChanged, newDoc.getLastVerifiedAt())"),
                "update 路径必须在 setLastVerifiedAt 之前读入参并交给 decideVerify，理由同上");
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
