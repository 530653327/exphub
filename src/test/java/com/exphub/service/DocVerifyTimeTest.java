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
 * 验证时间（last_verified_at）的写入口径（#752）。
 *
 * <p>这一列的价值全在「不会被顺手刷新」：改错字刷了它，90 天过期判定就永远不会触发，
 * 于是它退化成第二个 updated_at —— 功能看着有，实际没用。所以两个方向都要钉：
 * ① 不传就填（否则永远是 null，超期统计一片空白）；
 * ② 传了旧值就原样保留（否则等于没有「改错字不算验证」这个能力）。
 *
 * <p>另外用源码守卫钉住「四条写路径都填了」：{@code DocService} 里 create / update 走
 * updateById，而 appendContent / patchContent 自己拼 Doc 对象调 updateById，
 * 漏掉后者就会出现「append 过的文档永远不超期」—— 而滚动日志恰恰全靠 append 长大。
 */
class DocVerifyTimeTest {

    @Test
    @DisplayName("不传 → 填当前时间（允许一点点执行耗时）")
    void fillsNowWhenAbsent() {
        LocalDateTime before = LocalDateTime.now().minusSeconds(1);
        LocalDateTime got = DocService.resolveVerifiedAt(null);
        assertNotNull(got);
        assertTrue(got.isAfter(before), "应接近当前时间，实际 " + got);
        assertTrue(got.isBefore(LocalDateTime.now().plusSeconds(1)), "不该是未来时间");
    }

    @Test
    @DisplayName("传了旧值 → 原样保留（这就是「改错字不刷新」的全部实现）")
    void keepsExplicitValue() {
        LocalDateTime old = LocalDateTime.of(2026, 6, 1, 9, 0);
        assertEquals(old, DocService.resolveVerifiedAt(old));
    }

    @Test
    @DisplayName("守卫：create / update / appendContent / patchContent 四条写路径都要填验证时间")
    void allWritePathsFillVerifiedAt() throws Exception {
        Path src = Path.of("src/main/java/com/exphub/service/DocService.java");
        assertTrue(Files.exists(src), "找不到 DocService 源码：" + src.toAbsolutePath());
        String code = new String(Files.readAllBytes(src), StandardCharsets.UTF_8);

        assertTrue(code.contains("doc.setLastVerifiedAt(resolveVerifiedAt(doc.getLastVerifiedAt()))"),
                "create 未填验证时间 —— 新建的文档会永远显示「未记录」");
        assertTrue(code.contains("newDoc.setLastVerifiedAt(resolveVerifiedAt(newDoc.getLastVerifiedAt()))"),
                "update 未填验证时间");
        // append / patch 各一处（两条路径写法同名，用出现次数区分：合计 2 次 patch 变量赋值）
        int patchAssigns = code.split("patch\\.setLastVerifiedAt\\(resolveVerifiedAt\\(", -1).length - 1;
        assertEquals(2, patchAssigns,
                "appendContent / patchContent 各需一处（实际 " + patchAssigns + "）—— "
                        + "少一处就会出现「这条路径改过的文档永远不超期」");
    }

    @Test
    @DisplayName("守卫：迁移回填必须显式写 updated_at = updated_at，否则全库修改时间被刷成执行那一刻")
    void migrationDoesNotTouchUpdatedAt() throws Exception {
        Path sql = Path.of("sql/migrations/migration_20261029c_last_verified_at.sql");
        assertTrue(Files.exists(sql), "找不到迁移脚本：" + sql.toAbsolutePath());
        String code = new String(Files.readAllBytes(sql), StandardCharsets.UTF_8);
        assertTrue(code.contains("updated_at        = updated_at")
                        || code.contains("updated_at = updated_at"),
                "回填语句缺 updated_at = updated_at。updated_at 列带 ON UPDATE CURRENT_TIMESTAMP，"
                        + "只 SET last_verified_at 也会把它刷成执行时刻 —— 2026-10-29 实测把 652 篇的"
                        + "修改时间全部改成了部署那一秒");
    }

    @Test
    @DisplayName("守卫：get_experience_detail 的验证时间行要登记进剥离白名单，否则会被写回固化")
    void detailHeaderLineIsStripped() throws Exception {
        Path src = Path.of("src/main/java/com/exphub/util/RenderArtifactStripper.java");
        String code = new String(Files.readAllBytes(src), StandardCharsets.UTF_8);
        assertTrue(code.contains("|验证时间)"),
                "头部元信息正则里没有「验证时间」：读出来→改一点→整篇写回会把"
                        + "「**验证时间**: 2026-10-20（12 天前）」固化进正文，"
                        + "和之前「状态」「⚑ 标记」踩的是同一个坑");
    }
}
