package com.exphub.mcp;

import com.exphub.service.SourceScan;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 守卫：{@code update_experience} 只传 {@code verifiedAt}（其他字段全不传）必须能落库。
 *
 * <p>为什么这条不能只靠单测跑一遍工具方法就算完：这个判断裹在 MCP 工具方法里，要
 * {@code getCaller()} + 数据库 + 权限才能走到，起不了轻量单测。而它一旦回退，
 * 症状是「复核完结论仍然成立、正文不用改」时**无法留下验证证据** —— 唯一的出路变成
 * 「随便改点东西」，正好是白名单要防的伪造。所以用源码守卫钉住三件事：
 *
 * <ol>
 *   <li>{@code verified != null} 时确实往 {@code changedFields} 里记一笔；</li>
 *   <li>这一笔发生在「没有任何字段被修改」的拦截**之前**（顺序反了等于没写）；</li>
 *   <li>这一笔不许顺手改内容字段 —— 记验证不等于改正文。</li>
 * </ol>
 */
class VerifiedAtCountsAsChangeTest {

    private static final String SRC = "src/main/java/com/exphub/mcp/ExpHubTools.java";
    private static final String SIGNATURE = "public String updateExperience(";
    private static final String EMPTY_GUARD = "if (changedFields.isEmpty() && projectId == null)";
    private static final String VERIFY_BLOCK = "if (verified != null)";

    private static String updateExperienceBody() throws Exception {
        Path p = Path.of(SRC);
        assertTrue(Files.exists(p), "找不到 ExpHubTools 源码：" + p.toAbsolutePath());
        String code = new String(Files.readAllBytes(p), StandardCharsets.UTF_8);
        String body = SourceScan.methodBody(code, SIGNATURE);
        assertTrue(!body.isEmpty(),
                "取不到 updateExperience 方法体（签名改了？）—— 守卫不能静默放行");
        return body;
    }

    @Test
    @DisplayName("守卫：只传 verifiedAt 要算一次真实改动，且必须记在「空改动拦截」之前")
    void verifiedAtCountsAsAChange() throws Exception {
        String body = updateExperienceBody();

        assertTrue(body.contains(EMPTY_GUARD),
                "空改动拦截不见了？它是这条守卫的参照物，被改名/删掉都要先改这里");

        int verifyAt = body.indexOf(VERIFY_BLOCK);
        int emptyGuardAt = body.indexOf(EMPTY_GUARD);
        assertTrue(verifyAt >= 0,
                "updateExperience 里没有 `if (verified != null)` —— 显式 verifiedAt 不再计入 changedFields，"
                        + "「只记录一次验证、不改正文」会被「没有任何字段被修改」拦下");
        assertTrue(verifyAt < emptyGuardAt,
                "verifiedAt 记账排在空改动拦截之后 —— 顺序反了等于没记，只传 verifiedAt 依旧被拦");

        assertTrue(extractBlock(body, verifyAt).contains("changedFields.add("),
                "`if (verified != null)` 里没有往 changedFields 记一笔，拦截照样会命中");
    }

    @Test
    @DisplayName("守卫：记验证不许顺带改内容字段（记验证 ≠ 改正文）")
    void verifyingDoesNotTouchContent() throws Exception {
        String body = updateExperienceBody();
        int verifyAt = body.indexOf(VERIFY_BLOCK);
        assertTrue(verifyAt >= 0, "找不到 `if (verified != null)` 块");
        String block = extractBlock(body, verifyAt);

        for (String forbidden : new String[]{"setContent(", "setSummary(", "setTags(", "setTitle("}) {
            assertFalse(block.contains(forbidden),
                    "`if (verified != null)` 块里出现了 " + forbidden
                            + " —— 记录验证不得改动任何内容字段，否则等于用验证动作伪造一次内容修改");
        }
    }

    /** 从 {@code start} 处的 '{' 取到配对 '}'（这段代码里是单层块，遇第一个 '}' 即结束）。 */
    private static String extractBlock(String code, int start) {
        int open = code.indexOf('{', start);
        assertTrue(open > start, "`if (verified != null)` 后面没有 '{' —— 写法变了，守卫要跟着改");
        int close = code.indexOf('}', open);
        assertTrue(close > open, "`if (verified != null)` 块没有闭合");
        return code.substring(open, close + 1);
    }
}
