package com.exphub.mcp;

import com.exphub.util.RenderArtifactStripper;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 终端残留的**片段级**剥离（2026-10-29 ③，B6 通路）。
 *
 * <p>现场：#237（第 15 章）v5 正文里有一行
 * {@code /tmp/probe/mcpcall.sh: line 50: 58885 Terminated: 15 curl -sN ...} ——
 * 是 bash 作业控制消息，AI 从终端复制输出时一起贴进了章节正文。
 *
 * <p>为什么单独测「片段级」：{@code strip} 全量剥会把以 {@code **状态**:} 开头的**正常片段**
 * 当成渲染头部元信息吃掉。append / patch 写的是片段，只能剥终端残留这一类。
 */
class TerminalResidueStripTest {

    /** #237 v5 里那行的原文（脱敏不需要：$KEY / $BASE 是未展开的变量名） */
    private static final String REAL_DIRTY =
            "/tmp/probe/mcpcall.sh: line 50: 58885 Terminated: 15          curl -sN --noproxy '*' "
                    + "-H \"authorization-key: $KEY\" \"$BASE/exphub/mcp/sse\" > \"$SSEF\" 2>&1";

    @Test
    void stripsTheRealLineFromChapter15() {
        String dirty = "## 13. heredoc 里的变量会变空\n\n改用 Python 或把 URL 内联。\n\n"
                + REAL_DIRTY + "\n\n## 14. AGENTS.md 不要手写标题行\n\n正文。";
        String clean = RenderArtifactStripper.stripTerminalResidue(dirty);

        assertFalse(clean.contains("Terminated: 15"), "终端残留必须剥掉：\n" + clean);
        assertFalse(clean.contains("mcpcall.sh"), "脚本报错行也要剥掉");
        assertTrue(clean.contains("## 13."), "正文保留");
        assertTrue(clean.contains("## 14."), "正文保留");
    }

    @Test
    void stripsAllFourKnownForms() {
        String dirty = """
                ### 补充
                bash: line 12: foo: command not found
                curl: (7) Failed to connect to 127.0.0.1 port 3098
                /tmp/x.sh: line 3: 12345 Terminated: 15 sleep 10
                正常一行
                """;
        String clean = RenderArtifactStripper.stripTerminalResidue(dirty);

        assertEquals("### 补充\n正常一行\n", clean);
    }

    @Test
    void allResidueBecomesEmptySoAppendCanRefuse() {
        // append 的判断依据：剥完是空 → 明确拒绝，而不是静默写一段空内容
        assertEquals("", RenderArtifactStripper.stripTerminalResidue(REAL_DIRTY));
        assertEquals(1, ExpHubTools.terminalResidueLines(REAL_DIRTY));
    }

    @Test
    void normalFragmentStartingWithMetaIsKept() {
        // 这正是「不能对片段用全量 strip」的理由
        String fragment = "**状态**: 已上线";
        assertEquals(fragment, RenderArtifactStripper.stripTerminalResidue(fragment));
        assertTrue(RenderArtifactStripper.strip(fragment).isBlank(),
                "全量 strip 会把它当渲染头部吃掉 —— 所以 append/patch 必须用片段级剥离");
    }

    @Test
    void countsResidueLinesForReceipt() {
        assertEquals(0, ExpHubTools.terminalResidueLines(null));
        assertEquals(0, ExpHubTools.terminalResidueLines("普通正文\n两行"));
        assertEquals(2, ExpHubTools.terminalResidueLines(REAL_DIRTY + "\nbash: line 9: x"));
    }

    @Test
    void appendAndPatchActuallyCallTheStrip() throws Exception {
        // 源码守卫：规则写好了但没接线，等于没修（AGENTS 第 9 章「修了一条要回头问还有哪条」）
        String src = new String(Files.readAllBytes(
                Path.of("src/main/java/com/exphub/mcp/ExpHubTools.java")), StandardCharsets.UTF_8);

        String appendBody = methodBody(src, "public String appendExperience(");
        String patchBody = methodBody(src, "public String patchExperience(");

        assertTrue(appendBody.contains("stripTerminalResidue"),
                "append_experience 必须剥终端残留，否则从终端复制的内容会直接进正文");
        assertTrue(appendBody.contains("terminalResidueLines"),
                "剥了几行必须在回执里说出来（AGENTS 第 10 章：回执必须自证值）");
        assertTrue(patchBody.contains("stripTerminalResidue"),
                "patch_experience 的 replace 同样会夹带终端残留");
    }

    /** 截出方法体（从方法签名到下一个方法签名/类尾），够源码守卫用。 */
    private static String methodBody(String src, String signature) {
        int start = src.indexOf(signature);
        assertTrue(start > 0, "源码里找不到方法：" + signature);
        int next = src.indexOf("\n    @Tool(", start + signature.length());
        int nextPublic = src.indexOf("\n    public ", start + signature.length());
        int end = src.length();
        if (next > 0) end = Math.min(end, next);
        if (nextPublic > 0) end = Math.min(end, nextPublic);
        return src.substring(start, end);
    }
}
