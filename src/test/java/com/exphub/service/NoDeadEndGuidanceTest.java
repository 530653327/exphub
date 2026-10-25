package com.exphub.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 静态守卫：**别再把长文本的兜底指向一个做不到的工具**。
 *
 * <p><b>2026-10-26 阁主四轮实测，这是同一类错误的第三次</b>：
 * <ol>
 *   <li>第一轮：{@code get_experience_history} 截断后建议「改用 export_document」——
 *       但 export_document 不接受 version，导出的是当前正文（死路）</li>
 *   <li>第二轮：修了单版本读取，**列表这条没一并检查**</li>
 *   <li>第三轮：{@code list_archived_versions} 被转发层按 3500 剪掉，
 *       兜底文案又是「改用 export_document」—— 它**列不出归档清单**（还是死路）</li>
 * </ol>
 *
 * <p>共同特征：**建议的工具并不具备被建议的能力**。这类文案危害大于没文案 ——
 * 没文案人知道自己缺信息；给了死路，人会照着做、失败、再怀疑是数据丢了。
 *
 * <p>所以守卫不查「有没有写指引」，而是查**写指引时有没有同时说明适用边界**。
 */
class NoDeadEndGuidanceTest {

    private static final Path TOOLS = Path.of("src/main/java/com/exphub/mcp/ExpHubTools.java");

    /** 剥注释：注释里会引用错误写法作为历史记录，那是文档不是违规（已踩过两次） */
    private static String read() throws IOException {
        return stripComments(Files.readString(TOOLS, StandardCharsets.UTF_8));
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
     * 凡在「分段/截断指引」里提到 export_document 的地方，必须同时说清它的边界。
     *
     * <p><b>范围刻意收窄到这三个方法</b>，不扫全文件：第一版扫全文件时报了 5 处，
     * 其中 3 处是 {@code @Tool} 注解（工具定义本身）、1 处是 export_document 自己的
     * 成功回执 —— 那些都不是「给人指路」。**守卫判据太宽 = 假警报 = 会被关掉。**
     * 真正出过事的是这三个方法，就只守它们。
     */
    @Test
    @DisplayName("分段指引提到 export_document 时必须标明它做不到什么")
    void exportDocumentMentionsMustStateItsLimits() throws IOException {
        String src = read();
        String[] methods = {"appendContentSlice", "archivedVersionResponse", "listArchivedVersions"};
        StringBuilder detail = new StringBuilder();
        int offenders = 0;

        for (String m : methods) {
            String body = methodBody(src, m);
            assertNotNull(body, m + " 不见了？守卫要跟着改");
            for (String ln : body.split("\n", -1)) {
                if (!ln.contains("export_document")) continue;
                boolean statesLimit = ln.contains("不能") || ln.contains("不列") || ln.contains("只导")
                        || ln.contains("只接受") || ln.contains("读不到") || ln.contains("不分段");
                if (!statesLimit) {
                    offenders++;
                    detail.append("  · [").append(m).append("] ").append(ln.trim()).append("\n");
                }
            }
        }

        assertTrue(offenders == 0,
                "这些指引提到 export_document 却没说明它做不到什么，容易被当成万能兜底：\n" + detail
                        + "→ 它只接受 id、只导当前正文、不分段、不列归档清单、读不到历史版本。"
                        + "写指引时必须把边界一起写出来。");
    }

    /**
     * 取某个方法的源码（含签名，到大括号配平为止）。
     *
     * <p>⚠️ **不能直接 `indexOf(name + "(")`**：第一次出现往往是**调用点**，
     * 不是定义点。第一版就这么取错了 —— 拿到 getExperienceDetail 里
     * `appendContentSlice(sb, ...)` 那个调用后面的 `{`（其实是「关联经验」的 if 块），
     * 于是断言「必须拼 detailTool」直接失败，看起来像代码坏了，实则守卫取错范围。
     *
     * <p>定义点的判据：参数列表的 `)` 之后**紧跟 `{`**（调用点后面是 `;` 或别的）。
     */
    private static String methodBody(String src, String name) {
        int from = 0;
        while (true) {
            int idx = src.indexOf(name + "(", from);
            if (idx < 0) return null;
            // 括号配平找到该参数列表的右括号
            int depth = 0, i = idx + name.length();
            while (i < src.length()) {
                char c = src.charAt(i);
                if (c == '(') depth++;
                else if (c == ')') { depth--; if (depth == 0) break; }
                i++;
            }
            // 跳过 ) 与 { 之间的空白（含换行）
            int j = i + 1;
            while (j < src.length() && Character.isWhitespace(src.charAt(j))) j++;
            if (j < src.length() && src.charAt(j) == '{') {
                int start = j, d = 0;
                int k = start;
                while (k < src.length()) {
                    if (src.charAt(k) == '{') d++;
                    else if (src.charAt(k) == '}') { d--; if (d == 0) break; }
                    k++;
                }
                return src.substring(idx, k + 1);
            }
            from = idx + name.length();
        }
    }

    /**
     * 续读指引必须指向**当前这个工具**，不能写死成别的工具名。
     *
     * <p>2026-10-26 阁主实测：从 {@code get_experience_detail} 拿到的续读指令写的是
     * {@code get_experience_history(...)} —— 虽然能走通，但**指引与入口不一致**，
     * 读的人会以为 detail 自己不能分段，进而怀疑它的 offset 是摆设（而它其实是好的）。
     */
    @Test
    @DisplayName("分段续读指引必须用传入的 detailTool，不许写死工具名")
    void sliceGuidanceMustUseCurrentToolName() throws IOException {
        String src = read();
        String body = methodBody(src, "appendContentSlice");
        assertNotNull(body, "appendContentSlice 不见了？");

        assertTrue(body.contains("detailTool"),
                "续读指引必须拼 detailTool，不能写死工具名");
        assertFalse(body.contains("`get_experience_history(id=\")\""),
                "不许把续读指引硬编码成 get_experience_history —— 从 detail 进来的人会被指去另一个工具");
    }

    /**
     * 归档清单必须自己分段，不能依赖转发层的通用上限。
     *
     * <p>55 版约 4200 字符，转发层默认剪到 3500 —— 剪了不算致命，
     * 致命的是兜底文案把人指向 export_document（它列不出归档清单）。
     * 所以这里要求：必须支持 offset 续读，并在有后续时给出确切的续读参数。
     */
    @Test
    @DisplayName("归档清单要能 offset 分段，且续读参数是它自己的")
    void archiveListMustSelfPaginate() throws IOException {
        String src = read();
        // 用含签名的整段：offset 参数声明在签名里，只取方法体会漏掉它
        String body = methodBody(src, "listArchivedVersions");
        assertNotNull(body, "listArchivedVersions 不见了？");

        assertTrue(body.contains("ARCHIVE_LIST_PAGE"), "必须按页大小分段，否则长清单必被外层剪");
        assertTrue(body.contains("Integer offset"), "必须支持 offset 续读");
        assertTrue(body.contains("list_archived_versions(id="),
                "续读指引必须指向它自己，不能指去导出工具");
        assertTrue(body.contains("不列归档清单"),
                "必须明确说 export_document 列不出归档清单，否则下个人还会往那儿指");
    }
}
