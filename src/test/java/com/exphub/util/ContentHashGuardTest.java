package com.exphub.util;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * A5 B 方案：整篇写回的 originalHash 校验（纯函数层）。
 *
 * <p>这组用例回答的是同一个问题：<b>「字数看起来没缩水」能不能当没丢数据的证据</b>。
 * 答案是不能 —— 所以这里第 2 条专门复刻那种情形：新内容<b>比原文更长</b>，
 * 旧启发式（更短 + 前缀相同）根本不会触发，而中间整段可以已经丢了。
 */
class ContentHashGuardTest {

    /** 6240 字的技术文档（#124 的长度），正文带可辨识的小节标题 */
    private static String longDoc(int n) {
        StringBuilder sb = new StringBuilder();
        int i = 0;
        while (sb.length() < n) {
            sb.append("## 第").append(++i).append("节 小节标题\n");
            sb.append("正文内容占位，用于撑到目标长度，确保 hash 覆盖整篇而不是开头几行。\n");
        }
        return sb.substring(0, n);
    }

    @Test
    @DisplayName("sha256 口径固定：UTF-8 + 小写十六进制（换编码就换 hash，README 里的示例要能对上）")
    void sha256IsStable() {
        assertEquals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",
                ContentHashGuard.sha256("abc"));
        assertEquals(64, ContentHashGuard.sha256("任意中文正文").length());
        // null 与空串必须同 hash，否则「文档从没写过」和「正文是空串」会被判成不一致
        assertEquals(ContentHashGuard.sha256(""), ContentHashGuard.sha256(null));
    }

    @Test
    @DisplayName("验收 1：只写回 26 字 + 正确 hash → 放行（hash 证明读过全文，精简是允许的）")
    void shortWriteWithCorrectHashIsAllowed() {
        String stored = longDoc(6240);
        // 这才是重点：内容从 6240 缩到 26，长度启发式一定报警，但 hash 对得上就是合法的精简
        assertEquals(ContentHashGuard.Verdict.OK,
                ContentHashGuard.check(ContentHashGuard.sha256(stored), stored));
    }

    @Test
    @DisplayName("验收 1 反面：26 字 + 错误 hash → 拒绝")
    void shortWriteWithWrongHashIsRejected() {
        String stored = longDoc(6240);
        assertEquals(ContentHashGuard.Verdict.MISMATCH,
                ContentHashGuard.check(ContentHashGuard.sha256("别的内容"), stored));
    }

    @Test
    @DisplayName("验收 2：前 20000 字 + 两句、总长比原文更长 + 错误 hash → 必须拒绝（旧逻辑漏的正是这种）")
    void longerContentWithWrongHashIsRejected() {
        String stored = longDoc(20000);
        String incoming = stored.substring(0, 20000) + "\n补充两句：一是，二是。\n";
        // 旧判定是「比原文更短 且 以原文开头」两条同时成立才拦 —— 这里更长，旧逻辑直接放行。
        // 而「更长」完全不代表没丢东西：调用方手上那份若本来就是被截断的，加两句只会让它看起来更全。
        assertFalse(incoming.length() < stored.length(), "构造错误：本条必须比原文更长才复刻得出旧漏洞");
        assertTrue(stored.startsWith(incoming.substring(0, 100)), "构造错误：必须是原文前缀（否则不是截断场景）");
        // 调用方对自己手上那份算 hash（而不是对原文）→ 对不上，必须拒
        assertEquals(ContentHashGuard.Verdict.MISMATCH,
                ContentHashGuard.check(ContentHashGuard.sha256(incoming), stored));
    }

    @Test
    @DisplayName("验收 3：不传 hash → MISSING，且文案里点名 export_document（不许静默降级成字数启发式）")
    void missingHashIsRejectedWithExportDocument() {
        String stored = longDoc(6240);
        assertEquals(ContentHashGuard.Verdict.MISSING, ContentHashGuard.check(null, stored));
        assertEquals(ContentHashGuard.Verdict.MISSING, ContentHashGuard.check("   ", stored));
        String msg = ContentHashGuard.missingMessage(124, stored);
        assertTrue(msg.contains("export_document"), msg);
        assertTrue(msg.contains("originalHash"), msg);
        // 三步必须齐全：导出 → 本地改 → 回传
        assertTrue(msg.contains("1.") && msg.contains("2.") && msg.contains("3."), msg);
        assertTrue(msg.contains("patch_experience"), "要给出不必读全文的替代路径：" + msg);
    }

    @Test
    @DisplayName("hash 不一致的文案：给出两边前缀 + 三步走，并说清两种可能（被别人改过 / 自己读到截断）")
    void mismatchMessageExplainsBothCauses() {
        String stored = longDoc(6240);
        String msg = ContentHashGuard.mismatchMessage(124, stored, "deadbeef" + ContentHashGuard.sha256("x").substring(8));
        assertTrue(msg.contains("export_document"), msg);
        assertTrue(msg.contains("deadbeef"), "要能看到你给的那个 hash 前缀：" + msg);
        assertTrue(msg.contains("被别人改过") && msg.contains("截断"), msg);
    }

    @Test
    @DisplayName("hash 归一化：大小写、首尾空白、sha256: / 0x 前缀都应接受（同一个值只是写法不同）")
    void hashNormalizationAcceptsCommonForms() {
        String stored = longDoc(300);
        String h = ContentHashGuard.sha256(stored);
        assertEquals(ContentHashGuard.Verdict.OK, ContentHashGuard.check(h, stored));
        assertEquals(ContentHashGuard.Verdict.OK, ContentHashGuard.check(h.toUpperCase(), stored));
        assertEquals(ContentHashGuard.Verdict.OK, ContentHashGuard.check("  " + h + "\n", stored));
        assertEquals(ContentHashGuard.Verdict.OK, ContentHashGuard.check("sha256:" + h, stored));
        assertEquals(ContentHashGuard.Verdict.OK, ContentHashGuard.check("0x" + h, stored));
    }

    @Test
    @DisplayName("正文被改过一个字，旧 hash 立刻失效（这是「两个人同时改」的天然互斥）")
    void oneCharEditInvalidatesHash() {
        String stored = longDoc(6240);
        String h = ContentHashGuard.sha256(stored);
        String edited = stored.replace("第1节", "第 1 节");
        assertEquals(ContentHashGuard.Verdict.MISMATCH, ContentHashGuard.check(h, edited));
    }

    // ================= 写回凭证（writeback_fingerprint，2026-11-08） =================

    /** 从回执行里抠出 writeback_fingerprint 的值，模拟调用方复制它。 */
    private static String fingerprintIn(String line) {
        String marker = "writeback_fingerprint=";
        int i = line.indexOf(marker);
        assertTrue(i >= 0, "回执里没有 writeback_fingerprint：" + line);
        int start = i + marker.length();
        int end = start;
        while (end < line.length() && "0123456789abcdef".indexOf(line.charAt(end)) >= 0) end++;
        return line.substring(start, end);
    }

    @Test
    @DisplayName("写回凭证闭环：落库后给的 hash，下一次写回直接当 originalHash 用 → 必须 OK")
    void writebackFingerprintIsUsableAsNextOriginalHash() {
        String afterUpdate = longDoc(6240);
        String line = ContentHashGuard.writebackLine(afterUpdate);
        String fp = fingerprintIn(line);
        // 这一条是整件事的验收标准：不多不少正好能过校验。
        // 给短指纹就只有 12 位，check 会判 MISMATCH —— 调用方还得再 export 一次，白白多一轮。
        assertEquals(ContentHashGuard.Verdict.OK, ContentHashGuard.check(fp, afterUpdate));
        assertEquals(64, fp.length(), "必须是 64 位完整值，12 位前缀填进 originalHash 会被拒");
    }

    @Test
    @DisplayName("写回凭证只对「写完之后」的那版有效：拿去校验旧正文必须拒（防止把凭证当万能钥匙）")
    void writebackFingerprintIsBoundToTheNewContent() {
        String before = longDoc(3000);
        String after = before + "\n### 追加一段\n新的结论。\n";
        String fp = fingerprintIn(ContentHashGuard.writebackLine(after));
        assertEquals(ContentHashGuard.Verdict.MISMATCH, ContentHashGuard.check(fp, before),
                "写完之后的新 hash 不能用来校验旧正文；能过就说明 hash 没覆盖全部内容");
    }

    @Test
    @DisplayName("写回凭证文案必须写明语义边界：给下一次用、本次仍要读过全文、局部改用 patch")
    void writebackLineStatesItsBoundary() {
        String line = ContentHashGuard.writebackLine("abc");
        assertTrue(line.contains("originalHash"), "要说清它能填哪个参数：" + line);
        assertTrue(line.contains("下一次") || line.contains("下次"), "要说清是给下一次写回用的：" + line);
        assertTrue(line.contains("patch"), "要给出不必用它的替代路径：" + line);
        assertTrue(line.contains("读完") || line.contains("读过"), "要写死「本次仍须读过全文」：" + line);
    }

    @Test
    @DisplayName("没有正文（null）时不给凭证，不能抛异常、也不能给一个空 hash 的假通行证")
    void writebackLineIsEmptyForNullContent() {
        assertEquals("", ContentHashGuard.writebackLine(null));
    }
}
