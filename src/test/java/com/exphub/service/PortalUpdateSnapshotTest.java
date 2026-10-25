package com.exphub.service;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 门户「更新我的经验」必须在递增版本号之前写快照（2026-10-26 03:1x 查数据定位）。
 *
 * <p>全库复查（主表 + 冷存档）只有 4 个文档存在「版本号缺号」，<b>全是门户文档</b>：
 * #101 缺 v13/v15、#578 缺 v8/v11/v16/v29、#126 缺 v3/v5/v7、#30 缺 v2 —— 共 10 个版本号没副本。
 * 根因是 {@code PublicUserController.updateMyDoc} 只 {@code setVersion(old+1)} + updateById，
 * <b>跳过了 saveVersionSnapshot</b>，于是版本号被吃掉、正文没留底。
 *
 * <p>⚠️ 已吃掉的 10 个版本号<b>无法恢复</b>（当时就没存，不是被删）。本测试只保证不再新增。
 */
class PortalUpdateSnapshotTest {

    private static final Path CTRL = Path.of(
            "src/main/java/com/exphub/controller/PublicUserController.java");
    private static final Path SVC = Path.of("src/main/java/com/exphub/service/DocService.java");

    @Test
    void portalUpdateWritesSnapshotBeforeBumpingVersion() throws Exception {
        String src = Files.readString(CTRL, StandardCharsets.UTF_8);

        int snap = src.indexOf("snapshotBeforeExternalUpdate(");
        int bump = src.indexOf("doc.setVersion(existing.getVersion()");
        assertTrue(snap > 0, "门户更新必须先写快照，否则版本号被吃掉");
        assertTrue(bump > 0, "版本号递增仍在（这是对的，只是要先存底）");
        assertTrue(snap < bump,
                "顺序不能反：先存的是「改之前」的正文，反了就没有旧正文可存");
    }

    @Test
    void serviceExposesSnapshotForExternalWriters() throws Exception {
        String src = Files.readString(SVC, StandardCharsets.UTF_8);
        assertTrue(src.contains("public void snapshotBeforeExternalUpdate("),
                "给不走 service 的写路径留一个补快照的入口");
        assertTrue(src.contains("saveVersionSnapshot(old, changed, author)"),
                "复用同一份快照实现，别另写一份（另写必走样）");
    }

    /**
     * 其余门户写接口不许偷偷递增版本号。
     *
     * <p>状态接口用 existing 实体 updateById（不递增），是安全的；
     * 但只要出现 {@code setVersion} 就必须同时有快照，否则又是一类缺号。
     */
    @Test
    void noOtherVersionBumpWithoutSnapshot() throws Exception {
        String src = Files.readString(CTRL, StandardCharsets.UTF_8);
        // 先剥注释：本次修复的说明里就写着「原来直接 setVersion(old+1)」，不剥会把注释算成代码
        StringBuilder code = new StringBuilder();
        for (String line : src.split("\n")) {
            String t = line.trim();
            if (t.startsWith("//") || t.startsWith("*") || t.startsWith("/*")) continue;
            code.append(line).append('\n');
        }
        src = code.toString();
        int occurrences = src.split("setVersion\\(", -1).length - 1;
        int snapshots = src.split("snapshotBeforeExternalUpdate\\(", -1).length - 1;
        assertTrue(occurrences <= snapshots,
                "每个 setVersion 都要有对应快照：" + occurrences + " 次递增 vs " + snapshots + " 次快照");
        assertFalse(src.contains("updateById(doc)") && !src.contains("snapshotBeforeExternalUpdate("),
                "有 updateById 却没有快照 = 缺号制造机");
    }
}
