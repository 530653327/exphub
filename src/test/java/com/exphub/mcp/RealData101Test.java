package com.exphub.mcp;

import org.junit.jupiter.api.Test;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

/** 用 ID=101 的真实污染数据验证清洗（数据从库里导出到 /tmp/101_raw.md） */
class RealData101Test {

    @Test
    void cleanRealPollutedContent() throws Exception {
        Path p = Path.of("/tmp/101_raw.md");
        org.junit.jupiter.api.Assumptions.assumeTrue(Files.exists(p), "无真实数据，跳过");
        String raw = Files.readString(p, StandardCharsets.UTF_8);
        String clean = ExpHubTools.stripRenderArtifacts(raw);
        Files.writeString(Path.of("/tmp/101_clean.md"), clean, StandardCharsets.UTF_8);

        System.out.println("=== 真实数据清洗：raw=" + raw.length() + " -> clean=" + clean.length()
                + " (剥离 " + (raw.length() - clean.length()) + " 字) ===");

        assertFalse(clean.contains("历史导读"), "导读段必须清零");
        assertFalse(clean.contains("关联经验"), "关联经验段必须清零");
        assertFalse(clean.contains("文档结尾"), "结尾标记必须清零");
        assertFalse(clean.contains("Terminated"), "shell 残留必须清零");
        assertFalse(clean.contains("**分类**:"), "元信息块必须清零");
        // 关键：正文主体必须完整保留
        assertTrue(clean.contains("ExpHub 项目待办清单"), "主标题保留");
        assertTrue(clean.contains("CSRF"), "CSRF 条目保留");
        assertTrue(clean.contains("22:47") || clean.contains("E6"), "最后一节保留");
    }
}
