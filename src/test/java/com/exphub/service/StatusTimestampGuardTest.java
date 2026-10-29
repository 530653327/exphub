package com.exphub.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * updated_at 的写入口径守卫（2026-10-30 立）。
 *
 * <p>为什么需要它：docs.updated_at 带 {@code ON UPDATE CURRENT_TIMESTAMP}，看起来「只要改行就会自动刷新」。
 * 实际不是——MySQL 只在**没有显式给 updated_at 赋值**时才刷。而 MyBatis-Plus 的
 * {@code strictUpdateFill} 只在字段为 null 时填 now；实体若是 {@code selectById} 读出来的，
 * updatedAt 带着旧值，会被原样塞进 SET 子句，于是时间戳**冻结在读取那一刻**。
 *
 * <p>三种会写错的写法，按危险度排：
 * <ol>
 *   <li><b>读改写（最常见）</b>：{@code Doc d = selectById(id); d.setStatus(x); updateById(d);}
 *       —— 状态变了、updated_at 不动。当前代码里 updateStatus / markForDeletion / cancelDeletion 属此类，
 *       本测试钉住它们必须显式 {@code setUpdatedAt(null)}。</li>
 *   <li><b>调用方显式传旧值</b>：迁移/归档脚本想保留原时间，故意传旧 updated_at。
 *       VersionArchiveService 属此类，是<b>有意为之</b>，不能一刀切禁止。</li>
 *   <li><b>绕过 fill 的批量 UPDATE</b>（UpdateWrapper / 原生 SQL）：靠 MySQL 自己刷。
 *       若在语句里写了 {@code updated_at = updated_at} 就会冻结 —— 迁移脚本回填 last_verified_at 必须这么写，
 *       否则全库修改时间被刷成执行那一秒（见 DocVerifyTimeTest.migrationDoesNotTouchUpdatedAt）。</li>
 * </ol>
 *
 * <p>反向守卫同样重要：计数类写入（call_count / success_count / rating）**必须**保持冻结，
 * 否则「被检索一次」就等于「内容改了一次」，排序与「多久没更新」全部失真。
 * 这条规则以前只存在于口头，这里钉成断言。
 */
class StatusTimestampGuardTest {

    private static String docServiceSource() throws Exception {
        Path src = Path.of("src/main/java/com/exphub/service/DocService.java");
        assertTrue(Files.exists(src), "找不到 DocService 源码：" + src.toAbsolutePath());
        return new String(Files.readAllBytes(src), StandardCharsets.UTF_8);
    }

    @Test
    @DisplayName("守卫：状态/标记三处读改写必须把 updatedAt 置 null（让 fill 填 now）")
    void statusWritePathsRefreshUpdatedAt() throws Exception {
        String code = docServiceSource();

        assertRefreshes(code, "public Doc updateStatus(Long id, String status)", "updateStatus");
        assertRefreshes(code, "public Doc markForDeletion(Long id, String reason, String by)", "markForDeletion");
        assertRefreshes(code, "public Doc cancelDeletion(Long id)", "cancelDeletion");
    }

    @Test
    @DisplayName("反向守卫：计数类写入不许碰 updatedAt（被检索 ≠ 内容修改）")
    void counterWritePathsKeepUpdatedAt() throws Exception {
        String code = docServiceSource();

        assertKeepsFrozen(code, "public void updateCallResult(Long docId, boolean success)", "updateCallResult");
        assertKeepsFrozen(code, "public Doc reportResult(Long docId, boolean helpful, String feedback)", "reportResult");
        assertKeepsFrozen(code, "public void updateRating(Long docId, Integer rating)", "updateRating");
    }

    @Test
    @DisplayName("cancelDeletion 的 delete_* 三列必须走 UpdateWrapper 置空（updateById 对 null 是跳过）")
    void cancelDeletionClearsMarkColumns() throws Exception {
        String body = SourceScan.methodBody(docServiceSource(), "public Doc cancelDeletion(Long id)");
        assertTrue(!body.isEmpty(), "cancelDeletion 方法体没取到（签名变了？）—— 守卫不能静默放行");
        assertTrue(body.contains("uw.set(\"delete_reason\", null)"),
                "delete_reason 没有用 UpdateWrapper 置空：updateById 对 null 字段是「跳过」，"
                        + "取消待删后这一行仍会显示「谁在什么时候以什么理由标过待删」");
        assertTrue(body.contains("delete_marked_at"),
                "delete_marked_at 也要一起清，否则后台按标记时间倒序的队列里会残留脏值");
    }

    private static void assertRefreshes(String code, String signature, String label) {
        String body = SourceScan.methodBody(code, signature);
        assertTrue(!body.isEmpty(), label + " 方法体没取到（签名变了？）—— 守卫不能静默放行");
        assertTrue(body.contains(".setUpdatedAt(null)"),
                label + " 是「selectById 读出来 → 改字段 → updateById」的读改写：实体带着旧 updatedAt，"
                        + "MyBatis-Plus strictUpdateFill 只在 null 时填，旧值会被原样写回，"
                        + "MySQL 的 ON UPDATE 因为「显式赋值」不再触发 —— updated_at 就此冻结。"
                        + "必须在 updateById 之前 setUpdatedAt(null)。");
    }

    private static void assertKeepsFrozen(String code, String signature, String label) {
        String body = SourceScan.methodBody(code, signature);
        assertTrue(!body.isEmpty(), label + " 方法体没取到（签名变了？）—— 守卫不能静默放行");
        assertFalse(body.contains(".setUpdatedAt(null)"),
                label + " 是计数类写入，不该刷新 updated_at："
                        + "否则「被检索/被评价一次」等于「内容改了一次」，"
                        + "搜索排序和「多久没更新」统计会全部失真。");
    }
}
