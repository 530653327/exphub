package com.exphub.service;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * scan 回执的「自证值」守卫（2026-10-26 03:01 越哥实测）。
 *
 * <p>他传 limit=50，回执却写「本次展示前 22 条（**limit=22**）」——
 * <b>把展示数当成了 limit</b>。后果不是难看：他无法判断是参数被改写，还是显示错了。
 * 红线原话：**把数值摆出来，而不是改成别的数值。**
 *
 * <p>另一半是小问题但同源：组头写「…别当重复判」，里面第 13 条却给 ✅ 合并动作 ——
 * 动作枚举上线后，组头按分层说、条目按动作说，两者必然会对不上。
 */
class ScanReceiptSelfProofTest {

    private static final Path TOOLS = Path.of("src/main/java/com/exphub/mcp/ExpHubTools.java");

    private static String read() throws IOException {
        return SlicedReadAndStatusFilterTest.stripComments(
                Files.readString(TOOLS, StandardCharsets.UTF_8));
    }

    /** limit 必须回显用户传的那个值，不许用展示条数顶替 */
    @Test
    void limitEchoMustPrintRequestedLimitNotShownCount() throws IOException {
        String src = read();
        assertTrue(src.contains("你传的 limit="), "回执要明确摆出「你传的 limit」，而不是一个换算后的数");
        assertTrue(src.contains("r.limit()"), "limit 要读 ScanResult.limit（用户实际请求值）");
        assertFalse(src.contains(".append(r.rows().size()).append(\" 条（limit=\")"),
                "不许再拿展示条数冒充 limit —— 传 50 显示 22 会让人以为参数被改写");
        assertTrue(src.contains("差额"), "展示数少于 limit 时要说明差额从哪来（抽样 / 限流）");
        assertTrue(src.contains("已补回清单"), "名额有余补回来的条数也要自证");
    }

    /** 组头不许预设动作（分层说的是「像什么」，动作说的是「做什么」） */
    /**
     * 差额为 0 时不许印「（差额 0 条 …）」（2026-10-26 03:1x 阁主实测）。
     *
     * <p>没差额还让人去找差额，等于凭空造一个不存在的东西。
     */
    /**
     * 范围行（summary）同样不许在没差额时喊「差额见分层明细」。
     *
     * <p>2026-10-26 08:4x 实测：展示 22 条 / limit=22 时，范围行仍印
     * 「（limit=22；差额见分层明细）」—— 上一轮只改了第二处，漏了这个模板句。
     */
    @Test
    void rangeLineMustNotMentionGapWhenFull() throws IOException {
        // 剥注释：本轮修复说明里引用了旧模板句，不剥会把「记录为什么删」当成「还没删」
        String src = SlicedReadAndStatusFilterTest.stripComments(new String(
                java.nio.file.Files.readAllBytes(
                        java.nio.file.Path.of("src/main/java/com/exphub/service/SimilarityService.java")),
                StandardCharsets.UTF_8));
        assertFalse(src.contains("；差额见分层明细）"),
                "范围行不许无条件拼「差额见分层明细」——只有真没满 limit 时才提，且要写出差额数字");
        assertTrue(src.contains("已用满"), "limit 用满时要明说「已用满」，而不是让人去找不存在的差额");
    }

    @Test
    void zeroGapMustNotRenderExplanation() throws IOException {
        String src = read();
        assertTrue(src.contains("if (gap > 0) {"),
                "差额解释必须挂在 gap > 0 分支里，否则 0 差额也会渲染");
        assertFalse(src.contains("if (r.rows().size() < r.limit() || filled) {"),
                "旧写法（展示数 < limit 就渲染差额）会让 0 差额也印出来，已废弃");
    }

    @Test
    void groupHeaderMustNotPresetAction() throws IOException {
        String src = read();
        assertFalse(src.contains("别当重复判"),
                "组头预设动作会和条目里的「动作：」打架（#514↔#542 就是 ✅ 合并）：");
        assertTrue(src.contains("别预设动作"), "组头应说明「别预设动作」");
        assertTrue(src.contains("本层动作："), "组头应给出本层的动作分布");
        assertTrue(src.contains("actionTag("), "动作分布要从已渲染的 advice 读回，别另算一遍");
    }
}
