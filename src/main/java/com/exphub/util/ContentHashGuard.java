package com.exphub.util;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/**
 * 整篇写回的「你确实读过完整原文」证明（A5 B 方案，2026-10-30）。
 *
 * <p><b>为什么废掉字数启发式</b>：旧判定是「新内容比旧正文短，且新内容以旧正文开头」→ 判为截断。
 * 它能挡住「越写越短」，但挡不住真正会丢数据的那一种：
 * <b>读到前 2 万字、加两句话、总长度还比原文长</b> —— 中间那 1 万字照丢，而字数检查一声不响。
 * 长度是「结果」的代理指标，截断是「读取」的故障，两者不是一回事。
 *
 * <p><b>换成 hash 的理由</b>：调用方声称的原文 hash 与库里<b>当前</b>正文的 hash 一致，
 * 才说明「你手上那份 = 库里的全部」。这不是启发式，是等式 —— 读短了、读旧了、读的是别人的版本，
 * 三种情况都会 hash 不上。它也顺便解决了「两个人同时改一篇」的覆盖问题。
 *
 * <p>本类只做纯计算，不碰数据库、不抛业务异常，便于单测直接覆盖。
 */
public final class ContentHashGuard {

    private ContentHashGuard() {
    }

    /** 校验结论。三种，没有第四种「大概可以」。 */
    public enum Verdict {
        /** hash 与库中正文一致 —— 允许写回。 */
        OK,
        /** 没传 hash —— 拒绝，不降级成字数启发式。 */
        MISSING,
        /** 传了但和库中正文不一致 —— 拒绝（原文已被改过，或手上是截断片段）。 */
        MISMATCH
    }

    /** 库里正文为 null 时按空串算，避免 hash 结果随 null 抖动。 */
    public static String sha256(String content) {
        String s = content == null ? "" : content;
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] d = md.digest(s.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(d.length * 2);
            for (byte b : d) {
                sb.append(Character.forDigit((b >> 4) & 0xF, 16));
                sb.append(Character.forDigit(b & 0xF, 16));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            // SHA-256 是 JDK 强制实现的算法，走不到这里；真走到了绝不能静默放行
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    /**
     * 归一化调用方传来的 hash：去空白、小写、容忍 {@code sha256:} / {@code 0x} 前缀。
     * 空 → null（算「没传」，不是「传了个空串所以不校验」）。
     */
    public static String normalize(String provided) {
        if (provided == null) return null;
        String s = provided.trim().toLowerCase(java.util.Locale.ROOT);
        if (s.startsWith("sha256:")) s = s.substring(7).trim();
        else if (s.startsWith("sha256=")) s = s.substring(7).trim();
        if (s.startsWith("0x")) s = s.substring(2).trim();
        return s.isEmpty() ? null : s;
    }

    public static Verdict check(String providedHash, String storedContent) {
        String p = normalize(providedHash);
        if (p == null) return Verdict.MISSING;
        return p.equals(sha256(storedContent)) ? Verdict.OK : Verdict.MISMATCH;
    }

    /** 没传 hash 时的拒绝文案：必须给出三步，且点名 export_document。 */
    public static String missingMessage(long id, String storedContent) {
        int len = storedContent == null ? 0 : storedContent.length();
        return "❌ **缺少 originalHash，已拒绝写入（未做任何改动）。**\n\n"
                + "整篇写回等于「用你手上这份替换库里的全部正文」，所以必须能证明你读到的是**完整原文**（当前 "
                + len + " 字）。本次没传 originalHash，无法证明；我不会退回去猜字数 —— 字数挡不住"
                + "「读到前半段、加两句、总长度反而更长」这一种。\n\n"
                + "📋 **三步走：**\n"
                + "1. `export_document(id=" + id + ")` → 拿到完整正文，回执里有 `content_sha256=<64位>`\n"
                + "2. 把正文存成本地 .md 改完（原样保留其余部分，不要只留片段）\n"
                + "3. 回传 `originalHash=<第 1 步那个 sha256>` —— 它是**原文**的 hash，不是新内容的 hash\n\n"
                + "只改一段 / 只追加 / 整节替换不必读全文：`patch_experience` / `append_experience` / `replace_section`。";
    }

    /** hash 对不上时的拒绝文案：点明差异在哪，再给同样的三步。 */
    public static String mismatchMessage(long id, String storedContent, String providedHash) {
        String cur = sha256(storedContent);
        String p = normalize(providedHash);
        String pShort = p == null ? "(空)" : p.substring(0, Math.min(8, p.length()));
        int len = storedContent == null ? 0 : storedContent.length();
        return "❌ **originalHash 与库中当前正文不一致，已拒绝写入（未做任何改动）。**\n\n"
                + "· 你给的: `" + pShort + "…`（" + (p == null ? 0 : p.length()) + " 位）\n"
                + "· 库中当前: `" + cur.substring(0, 8) + "…`（" + len + " 字）\n\n"
                + "两种可能，处理方式一样：**你手上的不是最新全文** ——\n"
                + "① 文档在你读取之后被别人改过（这时写回会覆盖别人的改动）；\n"
                + "② 你读到的是截断片段，或从头拼过一份（这时写回会删掉没读到的部分）。\n\n"
                + "📋 **三步走：**\n"
                + "1. `export_document(id=" + id + ")` → 重新拿完整正文与新的 `content_sha256`\n"
                + "2. 在**这份**全文上改，存本地 .md\n"
                + "3. 回传 `originalHash=<刚才导出的 sha256>`\n\n"
                + "局部修改不必整篇写回：`patch_experience`（改一段）/ `append_experience`（追加）/ `replace_section`（整节替换）。";
    }
}
