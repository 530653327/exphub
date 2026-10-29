package com.exphub.mcp;

import com.exphub.service.SourceScan;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/**
 * A5 B 方案的结构守卫：<b>字数判定必须真的被删掉，force 必须真的跳不过 hash</b>。
 *
 * <p>为什么不能只测纯函数：{@link com.exphub.util.ContentHashGuard} 全绿，工具层仍然可以
 * 「先跑一遍 hash、再拿字数启发式兜一遍」，或者把 force 留在 hash 之前当逃生门 ——
 * 两种都能让线上继续丢数据，而纯函数测试看不出来。所以这里按方法名取方法体，逐条钉。
 */
class WholeDocHashGuardTest {

    private static final Path TOOLS = Path.of("src/main/java/com/exphub/mcp/ExpHubTools.java");
    private static final Path DOC_SERVICE = Path.of("src/main/java/com/exphub/service/DocService.java");

    private static String read(Path p) throws IOException {
        return Files.readString(p, StandardCharsets.UTF_8);
    }

    private static String body(String code, String signature) {
        String b = SourceScan.methodBody(code, signature);
        assertFalse(b.isEmpty(), "取不到方法体（签名变了？）：" + signature);
        return b;
    }

    @Test
    @DisplayName("整篇写回的两条路径都必须校验 originalHash")
    void bothWholeDocWritesCheckHash() throws IOException {
        String code = read(TOOLS);
        String update = body(code, "public String updateExperience(");
        String imp = body(code, "public String importDocument(");
        assertTrue(update.contains("ContentHashGuard.check(originalHash"), "update_experience 必须校验：" + update.substring(0, 200));
        assertTrue(imp.contains("ContentHashGuard.check(originalHash"), "import_document 必须校验");
    }

    @Test
    @DisplayName("旧的字数启发式已删除，不许两套判定并存")
    void lengthHeuristicIsGone() throws IOException {
        String code = read(TOOLS);
        assertFalse(code.contains("storedContent"), "旧的 storedContent 字数判定必须整段删除，不能留着并存");
        assertFalse(code.contains("isPrefixMatch"), "前缀判定是旧启发式的核心，必须删除");
        assertFalse(code.contains("跳过截断检测"), "force 不能再宣称「跳过截断检测」");
        String update = body(code, "public String updateExperience(");
        assertFalse(update.contains("force=true 跳过此检查"), "旧的逃生门文案不能留着");
    }

    @Test
    @DisplayName("force 跳不过 hash：校验必须排在 force 分支之前")
    void forceCannotBypassHash() throws IOException {
        String code = read(TOOLS);
        String update = body(code, "public String updateExperience(");
        int check = update.indexOf("ContentHashGuard.check(originalHash");
        int firstForceBranch = update.indexOf("force != null");
        assertTrue(check > 0, "没找到 hash 校验");
        assertTrue(firstForceBranch > check, "force 分支出现在 hash 校验之前 = 逃生门又回来了");
        // force 只允许影响「快照留痕」，不允许影响「是否校验」
        assertTrue(update.contains("WriteOrigin.FORCED"), "force 应落到 WriteOrigin.FORCED 留痕");
    }

    @Test
    @DisplayName("force 写回在快照里留痕：change_type=CONTENT_FORCED，且读取端能显示出来")
    void forcedWriteLeavesTraceInSnapshot() throws IOException {
        String svc = read(DOC_SERVICE);
        assertTrue(svc.contains("CONTENT_FORCED"), "快照必须标 CONTENT_FORCED");
        assertTrue(svc.contains("origin == WriteOrigin.FORCED"), "只有 FORCED 才标，别把普通写回也标上");
        String tools = read(TOOLS);
        assertTrue(tools.contains("正文（force 强压）"), "历史版本列表要能看出这一版是硬压的");
    }

    @Test
    @DisplayName("内部/门户路径不校验 hash，但每次正文写入都要留日志，且必须标出是哪条路径")
    void internalPathIsLogged() throws IOException {
        String svc = read(DOC_SERVICE);
        assertTrue(svc.contains("未经 originalHash 校验"), "未校验 hash 的正文写入必须留一行日志，否则成了无人知道的旁路");
        assertTrue(svc.contains("origin={}"),
                "日志必须打出 origin：不打的话，replace_section 的写入会被误读成「门户改的」");
        assertTrue(svc.contains("origin == WriteOrigin.INTERNAL") && svc.contains("origin == WriteOrigin.LOCAL_EDIT"),
                "只有「本来就不该有 hash」的两条路径才打日志；已校验/强制写回不许标成未校验");
        String tools = read(TOOLS);
        assertTrue(tools.contains("WriteOrigin.LOCAL_EDIT"), "replace_section 必须显式声明 LOCAL_EDIT，别落进 INTERNAL");
    }

    @Test
    @DisplayName("export_document 必须回传 hash —— 否则调用方拿不到 originalHash")
    void exportPrintsHash() throws IOException {
        String code = read(TOOLS);
        String exp = body(code, "public String exportDocument(");
        assertTrue(exp.contains("content_sha256"), "回执里必须有 content_sha256");
        assertTrue(exp.contains("ContentHashGuard.sha256(doc.getContent())"),
                "hash 必须对库里的原始正文算，不能对拼接了横幅的输出算");
    }
}
