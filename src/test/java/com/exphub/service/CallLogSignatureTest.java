package com.exphub.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 操作人署名守卫（2026-10-30 阁主提的审计链问题）。
 *
 * <p><b>问题</b>：call_logs.caller_name 原来只取 {@code assistant.getAssistantName()}，
 * 也就是 <b>API Key 绑定的默认名</b>。现网 exp-af77fa… 至少被 烛龙 / 白泽 / 阁主 三个助手共用，
 * 于是无论谁改，日志里全是同一个名字 —— 这一列对追责没有任何信息量。
 *
 * <p>而 {@code docs.author_name} 和 {@code doc_versions.updated_name} 存的是工具层
 * resolveAuthor 解析出的 updatedName，是真实操作人（实测 #765：author_name=阁主、
 * doc_versions.updated_name=阁主，同一时刻 call_logs.caller_name=白虎 —— 只有日志这一列是错的）。
 *
 * <p><b>口径</b>：api_key 记「哪个凭证」，caller_name 记「哪个人」，两者分开才有追责能力。
 * 业务写日志时把自报署名传进 fillCaller，Key 名降级写进 detail 保留追溯线索。
 *
 * <p>这条守卫钉三件事：
 * ① logUpdate 必须接受签名并转交给 fillCaller；
 * ② 状态变更 / 待删标记 / 撤销待删三处必须显式传 operator（不能沿用 doc.getAuthorName()，
 *    那是文档原作者，阁主可以把别人的文档置为 DEPRECATED）；
 * ③ fillCaller 必须同时设置 api_key 与 caller_name，不能只留一个。
 */
class CallLogSignatureTest {

    private static String read(String rel) throws Exception {
        Path p = Path.of(rel);
        assertTrue(Files.exists(p), "找不到源码：" + p.toAbsolutePath());
        return new String(Files.readAllBytes(p), StandardCharsets.UTF_8);
    }

    @Test
    @DisplayName("logUpdate 必须把自报署名传给 fillCaller，而不是回落到 Key 名")
    void logUpdatePassesSignedName() throws Exception {
        String code = read("src/main/java/com/exphub/service/CallLogService.java");
        String body = SourceScan.methodBody(code, "public CallLog logUpdate(Doc doc, String signedName)");
        assertTrue(!body.isEmpty(), "logUpdate(doc, signedName) 方法体没取到（签名变了？）—— 守卫不能静默放行");
        assertTrue(body.contains("fillCaller(entry, signedName)"),
                "logUpdate 没有把 signedName 交给 fillCaller：这一列会退回成 Key 绑定名，"
                        + "多助手共用 Key 时等于所有写操作都匿名");
    }

    @Test
    @DisplayName("fillCaller 必须 api_key 与 caller_name 分开记")
    void fillCallerKeepsBothDimensions() throws Exception {
        String code = read("src/main/java/com/exphub/service/CallLogService.java");
        String body = SourceScan.methodBody(code, "private void fillCaller(CallLog entry, String signedName)");
        assertTrue(!body.isEmpty(), "fillCaller(entry, signedName) 方法体没取到");
        assertTrue(body.contains("setApiKey("), "fillCaller 丢了 api_key：追不到是哪个凭证");
        assertTrue(body.contains("setCallerName(signed)"),
                "fillCaller 没有用自报名覆盖 caller_name");
        assertTrue(body.contains("Key 绑定="),
                "覆盖 caller_name 后必须把 Key 名写进 detail，否则「同一 Key 下谁改的」仍然只有一半信息");
    }

    @Test
    @DisplayName("状态/待删三处必须显式传 operator，不能沿用文档原作者")
    void statusPathsPassOperator() throws Exception {
        String svc = read("src/main/java/com/exphub/service/DocService.java");
        String tools = read("src/main/java/com/exphub/mcp/ExpHubTools.java");

        assertTrue(SourceScan.methodBody(svc, "public Doc updateStatus(Long id, String status, String operator)")
                        .contains("logUpdate(doc, operator)"),
                "updateStatus 的日志没用 operator：会把文档原作者记成操作人");
        assertTrue(SourceScan.methodBody(svc, "public Doc markForDeletion(Long id, String reason, String by)")
                        .contains("logUpdate(doc, by)"),
                "markForDeletion 的日志没用 by：待删队列里「谁标的」会写成原作者");
        assertTrue(SourceScan.methodBody(svc, "public Doc cancelDeletion(Long id, String operator)")
                        .contains("logUpdate(doc, operator)"),
                "cancelDeletion 的日志没用 operator");

        assertTrue(tools.contains("docService.updateStatus(id, upperStatus, operator)"),
                "工具层调 updateStatus 没传 operator —— 上面 service 层的参数永远拿到 null");
        assertTrue(tools.contains("docService.cancelDeletion(id, operator)"),
                "工具层调 cancelDeletion 没传 operator");
    }
}
