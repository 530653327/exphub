package com.exphub.service;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 分段读取、当前版本识别、待办状态过滤 —— 2026-10-26 01:1x 阁主实测的三条。
 *
 * <p>三条其实是<b>同一个病根</b>：<b>分了类、但类分不全</b>。
 * <ul>
 *   <li>版本号：分了「已归档 / 不存在」，漏了「<b>就是当前版本</b>」→ v58 被报成不存在</li>
 *   <li>长度：只有「全给 / 截断」两种，漏了「<b>分段给</b>」→ 31 个长版本只能看一截</li>
 *   <li>待办：注释写「null = ACTIVE」，代码漏了「<b>null 也要加条件</b>」→ 7 条里 4 条已完成</li>
 * </ul>
 * 共同点：**分支少了一条，而且缺的那条恰好是「最常见的情况」。**
 */
class SlicedReadAndStatusFilterTest {

    private static final Path TOOLS = Path.of("src/main/java/com/exphub/mcp/ExpHubTools.java");
    private static final Path DOCSERVICE = Path.of("src/main/java/com/exphub/service/DocService.java");

    private static String read(Path p) throws IOException {
        return stripComments(Files.readString(p, StandardCharsets.UTF_8));
    }

    /** 剥注释：注释里会记录「旧代码长什么样」，那是文档不是违规 */
    static String stripComments(String src) {
        StringBuilder out = new StringBuilder(src.length());
        boolean inBlock = false;
        for (String line : src.split("\n", -1)) {
            String t = line;
            if (inBlock) {
                int end = t.indexOf("*/");
                if (end < 0) { out.append('\n'); continue; }
                t = t.substring(end + 2); inBlock = false;
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

    // ==================== ① 分段读取 ====================

    /** 分段切片的边界逻辑（与 appendContentSlice 等价，纯函数复刻便于断言） */
    private static int[] slice(int total, Integer offset, int max) {
        int from = (offset == null || offset < 0) ? 0 : Math.min(offset, total);
        int to = Math.min(from + max, total);
        return new int[]{from, to};
    }

    @Test
    void slicingCoversEverythingWithoutGapOrOverlap() {
        int total = 17289;   // v44 的真实长度
        int max = 20000;      // detailMaxChars 默认值

        // 一次读完
        int[] one = slice(total, null, max);
        assertEquals(0, one[0]);
        assertEquals(total, one[1], "总长小于上限时应一次读完");

        // 用较小的 max 模拟需要多段的情况，验证「无缝隙、无重叠、能走到尾」
        int smallMax = 4000;
        int cursor = 0, rounds = 0;
        List<Integer> starts = new ArrayList<>();
        while (cursor < total && rounds < 100) {
            int[] s = slice(total, cursor, smallMax);
            starts.add(s[0]);
            assertEquals(cursor, s[0], "本段起点必须等于上一段给的下一个 offset（无缝隙/无重叠）");
            cursor = s[1];
            rounds++;
        }
        assertEquals(total, cursor, "反复续读必须能读到最后（能走到尾）");
        assertEquals((total + smallMax - 1) / smallMax, rounds, "段数应为总长/段长向上取整");

        // offset 超出总长时不能抛错，收敛到末尾
        int[] beyond = slice(total, total + 999, smallMax);
        assertEquals(total, beyond[0], "offset 超出总长要收敛到末尾，不能越界");
        assertEquals(total, beyond[1]);
    }

    @Test
    void slicingIsAdvertisedWithRealNextOffsetNotDeadEnd() throws IOException {
        String src = read(TOOLS);

        assertTrue(src.contains("appendContentSlice"), "必须有统一的分段输出方法");
        assertTrue(src.contains("offset="), "回执必须给出下一段的确切 offset 参数");

        // 关键：不许再把 export_document 当成长文档的兜底 —— 它不接受 version，也不分段
        assertFalse(src.contains("请使用 export_document"),
                "「改用 export_document」是死路：它只接受 id、不能指定 version、也不分段。"
                        + "历史版本导出的是当前正文，长文档也只能整块吐。必须用 offset 分段读");

        // 但要明确警示这个死路，避免下个人又想这么绕
        assertTrue(src.contains("它只接受 id、不能指定版本"),
                "要写明 export_document 为什么不能替代，否则下个人还会这么建议");
    }

    /**
     * 同一段回执里不许自相矛盾（2026-10-26 02:2x 越哥实测）。
     *
     * <p>原来 appendContentSlice 的结尾还有一句
     * 「也可用 get_experience_detail(id=654) 读当前正文（同样支持大文档，但那个工具不分段）」——
     * 而那段回执上面刚教人「继续读 get_experience_detail(id=654, offset=20000)」。
     * <b>同一个工具，上面在分段、下面说不分段。</b>
     * 病根是这句写于 detail 还没有 offset 的时候，后来补了分段能力，这句没回头改。
     */
    @Test
    void sliceHintMustNotContradictItself() throws IOException {
        String src = read(TOOLS);
        assertFalse(src.contains("那个工具不分段"),
                "分段回执里不许再说「那个工具不分段」—— 它指的就是当前这个工具，直接自相矛盾");
    }

    // ==================== ② 当前版本号 ====================

    @Test
    void currentVersionMustNotBeReportedMissing() throws IOException {
        String src = read(TOOLS);
        assertTrue(src.contains("version.equals(doc.getVersion())"),
                "必须先判断「这是不是当前版本」——当前正文在 docs 表，不在 doc_versions 里，"
                        + "漏了这一支就会把 v58（当前版）报成「不存在」");
        assertTrue(src.contains("这就是当前正文"),
                "命中当前版本时要明说「这就是当前正文」，而不是让人以为要查历史");
        assertTrue(src.contains("不在历史版本表"),
                "要解释「为什么历史列表里看不到当前版本」，否则同样的困惑会再来一次");
    }

    /** 三种「找不到」的语义必须齐：已归档 / 就是当前版 / 真的不存在 */
    @Test
    void threeKindsOfNotFoundAreAllDistinguished() throws IOException {
        String src = read(TOOLS);
        // 已归档
        assertTrue(src.contains("已归档版本"));
        // 就是当前版
        assertTrue(src.contains("当前版本"));
        // 真不存在
        assertTrue(src.contains("主表与冷存档都查过了"));
    }

    // ==================== ③ 待办状态过滤 ====================

    /**
     * 不传 status 必须真的按 ACTIVE 过滤，而不是「不过滤」。
     *
     * <p>这是本轮第三条，也是最影响日常的一条：会话开场读待办，每次都把已完成项
     * 喂给下一个助手。而红线「本清单不标 COMPLETED」正是为了防止误判完成 ——
     * 过滤坏了，标了也白标。
     */
    @Test
    void defaultStatusMustActuallyFilterActive() throws IOException {
        String src = read(DOCSERVICE);
        int idx = src.indexOf("listByTemplateType(String templateType, Long projectId, String status)");
        assertTrue(idx > 0);
        String method = src.substring(idx, Math.min(src.length(), idx + 1600));

        assertTrue(method.contains("\"ACTIVE\""),
                "status 为空时必须显式兜底成 ACTIVE —— 原来 status==null 直接跳过过滤，等于返回全部状态");
        assertTrue(method.contains("!\"ALL\".equals"),
                "只有显式 ALL 才放开；不传 ≠ 不过滤");
        // 反向：不许再出现「status != null 才过滤」那种写法
        assertFalse(method.contains("if (status != null && !status.trim().isEmpty()"),
                "「status != null 才加条件」正是本 bug 的原写法，不许回来");
    }

    /** 回执标签必须反映实际生效的条件，不能凭「应该是什么」写 */
    @Test
    void statusLabelMustMatchEffectiveFilter() throws IOException {
        String src = read(TOOLS);
        assertTrue(src.contains("effectiveStatus"),
                "要有「实际生效的状态」这个变量，标签依据它而不是原始入参");
        assertTrue(src.contains("未传 status，默认只列未完成"),
                "没传 status 时标签要主动说明「默认只列未完成」，让人知道自己在看什么");
        assertTrue(src.contains("实际查询条件"),
                "空结果时也要报出实际查询条件，避免「标签说 ACTIVE、实际查全部」这种错配");
    }
}
