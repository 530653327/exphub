package com.exphub.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 里程碑字段守卫（W-20261120-000026）。盯「读代码看不出来」的四件事：
 * 超长明拒不静默截断、DDL 只加列不回填、三处建单入口都传了 milestone、burndown 缺参 400。
 */
class DispatchMilestoneTest {

    private static String stripComments(String src) {
        return src.replaceAll("(?s)/\\*.*?\\*/", "").replaceAll("(?m)//.*$", "");
    }

    @Test
    @DisplayName("里程碑超 64 码点：明确拒绝并回显收到的值，绝不静默截断")
    void oversizedMilestoneIsRejected() {
        DispatchService svc = new DispatchService(); // 校验在碰 mapper 之前，无 DB 也能跑
        String tooLong = "里".repeat(DispatchService.MAX_MILESTONE_CHARS + 1);
        DispatchService.Outcome out = svc.create(
                "阁主", "AI", "烛龙", "标题", "描述", 3, null, null, null, null, tooLong);
        assertFalse(out.ok(), "超长里程碑必须拒绝");
        assertTrue(out.message().contains("64"), "回执要报出上限：" + out.message());
        assertTrue(out.message().contains("65"), "回执要报出实际收到的长度：" + out.message());
        assertTrue(out.message().contains("不会静默截断"), "回执要说明没有偷偷截断：" + out.message());
    }

    @Test
    @DisplayName("正常长度里程碑不被长度校验挡住（真正的落库另测，这里只验校验阈值不误伤）")
    void normalMilestonePassesLengthCheck() {
        DispatchService svc = new DispatchService();
        // 恰好 64 字应越过长度关；无 DB 环境会在 headerMapper 处 NPE，而不是返回「长度校验失败」
        assertThrows(NullPointerException.class, () -> svc.create(
                "阁主", "AI", "烛龙", "标题", "描述", 3, null, null, null, null,
                "里".repeat(DispatchService.MAX_MILESTONE_CHARS)));
    }

    @Test
    @DisplayName("迁移脚本：只加列+索引，不回填老单（DDL 里没有 UPDATE dispatch_header）")
    void migrationAddsColumnAndIndexWithoutBackfill() throws Exception {
        String sql = Files.readString(
                Path.of("sql/migrations/migration_20261120b_dispatch_milestone.sql"), StandardCharsets.UTF_8);
        assertTrue(sql.contains("ADD COLUMN milestone VARCHAR(64) NULL"), "缺 milestone 列");
        assertTrue(sql.contains("idx_dispatch_milestone"), "缺里程碑索引（燃尽图按它 group by）");
        assertFalse(sql.toUpperCase().contains("UPDATE DISPATCH_HEADER"),
                "迁移脚本不许回填老单：空值就是独立单");
    }

    @Test
    @DisplayName("三处建单入口都把 milestone 传进 create（MCP / 后台 / 手机端）")
    void allCreateEntriesPassMilestone() throws Exception {
        String mcp = stripComments(Files.readString(
                Path.of("src/main/java/com/exphub/mcp/ExpHubTools.java"), StandardCharsets.UTF_8));
        String web = stripComments(Files.readString(
                Path.of("src/main/java/com/exphub/controller/DispatchController.java"), StandardCharsets.UTF_8));
        String mobile = stripComments(Files.readString(
                Path.of("src/main/java/com/exphub/controller/MobileController.java"), StandardCharsets.UTF_8));
        assertTrue(mcp.contains("callerApiKey(), version, milestone)"), "MCP 建单没传 milestone");
        assertTrue(web.contains("auths, null, version, milestone)"), "后台建单没传 milestone");
        assertTrue(mobile.contains("auths, null, version, milestone)"), "手机端建单没传 milestone");
        assertTrue(mcp.contains("name = \"dispatch_create\""), "MCP 工具名被改了");
        assertTrue(mcp.contains("里程碑（最多 64 字符）"), "dispatch_create 参数说明缺里程碑描述");
    }

    @Test
    @DisplayName("burndown 缺 milestone 返回 400，且明确不返回全库")
    void burndownWithoutMilestoneIsBadRequest() throws Exception {
        String api = stripComments(Files.readString(
                Path.of("src/main/java/com/exphub/controller/DispatchApiController.java"), StandardCharsets.UTF_8));
        assertTrue(api.contains("@GetMapping(\"/burndown\")"), "缺 burndown 接口");
        assertTrue(api.contains("缺少必填参数 milestone（不返回全库数据）"),
                "缺参必须用 400 明拒，且说明不返回全库");
        assertTrue(api.contains("badRequest("), "缺参分支必须走 400");
        // 鉴权沿用 025：拦截器按 /api/dispatch/** 整段拦，burndown 在同一前缀下自动覆盖
        assertTrue(api.contains("@RequestMapping(\"/api/dispatch\")"), "burndown 不在受保护的接口前缀下");
    }

    @Test
    @DisplayName("不填里程碑 = 独立单：byMilestone 对空值返回空表，绝不退化成全库查询")
    void emptyMilestoneNeverMeansAll() throws Exception {
        DispatchService svc = new DispatchService();
        // 空值直接返回空表，不碰 mapper（null mapper 也不该抛 NPE）
        assertTrue(svc.byMilestone(null).isEmpty());
        assertTrue(svc.byMilestone("   ").isEmpty());
        assertEquals(0L, svc.countByMilestone(""));
        assertTrue(svc.listByMilestone("  ", 50, 0).isEmpty());
    }
}
