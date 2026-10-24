package com.exphub.mcp;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;

/**
 * 【数据迁移工具，非功能测试】
 *
 * 全量重算所有项目章节的 summary 并生成 UPDATE 语句。
 * 直接用真实代码算，避免另写一份逻辑与 ExpHubTools.autoSummaryFromContent 漂移。
 *
 * 输入 /tmp/exphub_ch/chapters_all.tsv：一条记录一行组，字段用 0x1F 分隔、记录用 0x1E 结尾，
 * 字段序：project_id / chapter_no / chapter_title / content。导出命令见经验条目。
 * 输入文件不存在时整类跳过（所以在没有导出文件的机器上不会失败）。
 */
class ChapterSummaryRegenTest {

    @Test
    void regenerateAllChapterSummaries() throws IOException {
        Path in = Paths.get("/tmp/exphub_ch/chapters_all.tsv");
        Assumptions.assumeTrue(Files.exists(in), "没有导出文件，跳过数据迁移");

        String raw = new String(Files.readAllBytes(in), StandardCharsets.UTF_8);
        List<String> sql = new ArrayList<>();
        StringBuilder report = new StringBuilder();
        int changed = 0;

        for (String rec : raw.split("\u001e")) {
            if (rec.isEmpty()) {
                continue;
            }
            String[] f = rec.split("\u001f", -1);
            if (f.length < 4) {
                continue;
            }
            String projectId = f[0].trim();
            String chapterNo = f[1].trim();
            String title = f[2];
            String content = f[3];
            String s = ExpHubTools.autoSummaryFromContent(content, title);
            if (s.isEmpty() || s.equals(title)) {
                report.append(String.format("!! 仍是标题回声 proj=%s ch=%s title=%s%n", projectId, chapterNo, title));
            }
            changed++;
            sql.add(String.format(
                    "UPDATE exphub.docs SET summary='%s' WHERE project_id=%s AND doc_role='CHAPTER' AND chapter_no='%s';",
                    s.replace("'", "''"), projectId, chapterNo));
        }

        Files.write(Paths.get("/tmp/exphub_ch/backfill.sql"),
                String.join("\n", sql).getBytes(StandardCharsets.UTF_8));
        Files.write(Paths.get("/tmp/exphub_ch/backfill_report.txt"),
                report.toString().getBytes(StandardCharsets.UTF_8));
        System.out.println("[regen] 生成 " + changed + " 条，其中仍回声 " + report.toString().split("!!", -1).length);
    }
}
