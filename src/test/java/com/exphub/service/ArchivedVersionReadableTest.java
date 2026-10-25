package com.exphub.service;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 归档版本必须能通过 MCP 读回原文。
 *
 * <p><b>2026-10-26 01:0x 阁主实测发现的硬伤</b>：
 * 归档后 {@code get_experience_history(654, version=1)} 返回
 * 「未找到经验 ID:654 的 v1 历史版本」，{@code version=44} 同样。
 * 而 {@code list_archived_versions} 能看到 44 版在档、元数据齐全 ——
 * <b>元数据能查，正文一个字都拿不到</b>。
 *
 * <p>严重性不在于「功能缺失」，而在于：
 * <ul>
 *   <li>文档当时写着「归档不是删除，每条都能原样取回」，还用这个理由说服用户放心搬</li>
 *   <li>工具回的是<b>「该版本不存在」</b>而不是「已归档」—— 读的人会以为数据丢了</li>
 * </ul>
 * 也就是说：<b>承诺与实现不一致，而且错误信息把人往「数据丢失」引</b>。
 * 比「没有入口」更糟。
 *
 * <p>这个测试是静态的（不连库），检查代码里确实做了归档回查、且三种情况分开表述。
 * 真正的端到端验证要在线上跑 {@code get_experience_history(654, version=1)} 看是否返回正文。
 */
class ArchivedVersionReadableTest {

    private static final Path TOOLS =
            Path.of("src/main/java/com/exphub/mcp/ExpHubTools.java");
    private static final Path SERVICE =
            Path.of("src/main/java/com/exphub/service/VersionArchiveService.java");

    /**
     * 读源码并**先剥掉注释**。
     *
     * <p>为什么必须剥：注释里会写「原来的坏写法是什么」（我就是这么记录历史的），
     * 那是文档，不是违规。第一版守卫没剥注释，直接把历史说明当成违规报了出来 ——
     * 与 {@code SelectColumnGuardTest} 踩的是同一个坑。
     */
    private static String read(Path p) throws IOException {
        return stripComments(Files.readString(p, StandardCharsets.UTF_8));
    }

    static String stripComments(String src) {
        StringBuilder out = new StringBuilder(src.length());
        boolean inBlock = false;
        for (String line : src.split("\n", -1)) {
            String t = line;
            if (inBlock) {
                int end = t.indexOf("*/");
                if (end < 0) { out.append('\n'); continue; }
                t = t.substring(end + 2);
                inBlock = false;
            }
            int blk = t.indexOf("/*");
            if (blk >= 0) {
                int end = t.indexOf("*/", blk + 2);
                if (end < 0) { t = t.substring(0, blk); inBlock = true; }
                else { t = t.substring(0, blk) + t.substring(end + 2); }
            }
            int lc = t.indexOf("//");
            if (lc >= 0) t = t.substring(0, lc);
            out.append(t).append('\n');
        }
        return out.toString();
    }

    /**
     * 核心：{@code get_experience_history} 在主表查不到时**必须**回查归档表。
     *
     * <p>如果这个断言断了，说明有人把归档回查逻辑删了/绕过了 ——
     * 那「归档不是删除」这句话又变成假的。
     */
    @Test
    void historyToolMustFallBackToArchive() throws IOException {
        String src = read(TOOLS);

        // 1. 必须有回查入口
        assertTrue(src.contains("versionArchiveService.findArchived"),
                "get_experience_history 必须调用 findArchived 回查冷存档。"
                        + "否则归档后的版本在 MCP 层就是「不存在」，与「归档不是删除」的承诺矛盾");

        // 2. 主表找不到时必须走归档分支，不能直接 return「未找到」
        assertTrue(src.contains("archivedVersionResponse"),
                "主表查不到版本时必须转到 archivedVersionResponse 处理三种情况，"
                        + "不能像 2026-10-26 之前那样直接返回「未找到」");

        // 3. 历史列表也必须合并归档（否则用户看不到被搬走的版本，会以为只有 10 版）
        assertTrue(src.contains("archivedAll"),
                "不传 version 的列表分支必须合并归档版本 —— 列表缺项比查询失败更隐蔽");
        assertTrue(src.contains("📦 已归档"),
                "列表里要标出哪些版本在冷存档，让人一眼看出「不是没有，是搬走了」");
    }

    /** 找不到时必须区分「已归档」和「真的不存在」，不许回同一句话 */
    @Test
    void missingVersionMustDistinguishArchivedFromNonexistent() throws IOException {
        String src = read(TOOLS);

        // 「主表与冷存档都查过了」—— 明确告知查了两个地方，而不是只说「未找到」
        assertTrue(src.contains("主表与冷存档都查过了"),
                "查不到时要说明「两个地方都查过了」，否则会被读成「可能还有别处没查」");

        // 要列出两边分别有哪些版本
        assertTrue(src.contains("主表里的版本"), "要列出主表实际有哪些版本");
        assertTrue(src.contains("冷存档里的版本"), "要列出冷存档实际有哪些版本");

        // 从未归档的情况要单独说
        assertTrue(src.contains("从未归档过任何版本"),
                "该文档根本没归档过时要单独说明，不能和「有归档但没这个版本」混为一谈");

        // 命中归档时要标明这是归档副本 + 归档来源信息
        assertTrue(src.contains("已归档版本"), "读到归档版本时要标明身份，不能让人以为读的是主表版本");
        assertTrue(src.contains("归档只是搬位置，不是删除"),
                "回执要重申归档的性质，避免被误解为数据已删");
    }

    /** service 层必须提供按版本精确回查（且允许多条，因为主表允许重复版本行） */
    @Test
    void archiveServiceExposesExactVersionLookup() throws IOException {
        String src = read(SERVICE);
        assertTrue(src.contains("public List<DocVersionArchive> findArchived(Long docId, Integer version)"),
                "findArchived 必须返回 List —— 主表允许同一 doc_id + version 多行（#608 的 v2 就有 2 条），"
                        + "返回单条会静默丢掉另一条");
        assertTrue(src.contains("public boolean hasAnyArchived"),
                "需要 hasAnyArchived 来区分「从未归档」与「版本号不存在」");
    }

    /**
     * 归档正文必须原样存储 —— 取回时不能只回元数据。
     *
     * <p>阁主的原话：「元数据能查（版本号、字数、归档人、预览 200 字），正文一个字都拿不到」。
     * 所以这条钉住：取回走的是 {@code getContent()}，不是 preview。
     */
    @Test
    void retrievedContentComesFromFullBodyNotPreview() throws IOException {
        String src = read(TOOLS);
        int idx = src.indexOf("private String archivedVersionResponse");
        assertTrue(idx > 0, "找不到 archivedVersionResponse");
        String method = src.substring(idx, Math.min(src.length(), idx + 4000));

        assertTrue(method.contains("a.getContent()"),
                "取回归档版本必须用 getContent()（完整正文），不能只用 summary/preview");
        assertTrue(method.contains("appendContentSlice"),
                "超长正文要走统一的分段输出（不是截断了事）：回执要给出本段范围与下一段 offset");
        assertTrue(method.contains("offset"),
                "归档版本也必须支持 offset 续读 —— 归档里 31 个版本超过 8000 字，"
                        + "不分段就只能看前面一截");
    }

    /**
     * 反向守卫：不许再出现「只查主表就说版本不存在」的写法。
     *
     * <p>原代码是：
     * <pre>
     *   if (target == null) {
     *       sb.append("未找到经验 ID:").append(id).append(" 的 v").append(version).append(" 历史版本。");
     *   }
     * </pre>
     * 这种表述在归档上线后是有害的 —— 它把「已归档」谎报成「不存在」。
     */
    @Test
    void noBareNotFoundMessageForVersions() throws IOException {
        String src = read(TOOLS);
        assertFalse(src.contains("未找到经验 ID:"),
                "「未找到经验 ID:xxx 的 vN 历史版本」这句话已废弃：主表有、存档没有时它是对的，"
                        + "但主表没有、存档有时就变成谎报。必须走 archivedVersionResponse 区分三种情况");
    }
}
