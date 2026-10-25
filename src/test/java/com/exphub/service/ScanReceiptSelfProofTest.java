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
