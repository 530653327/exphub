package com.exphub.service;

import com.exphub.entity.DispatchEvent;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 派单操作流水守卫（2026-11-20 W-20261120-000025）。
 *
 * <p>盯四件「读代码看不出来」的事：
 * <ol>
 *   <li>11 个状态转移点**每一个**都在成功分支后写了 recordEvent，一处不能漏</li>
 *   <li>dispatch_event 是只追加表：业务代码里不许出现对它的 update / delete</li>
 *   <li>事件类型常量 11 个齐全，且与状态转移一一对应</li>
 *   <li>recordEvent 不被 try-catch 吞（事件写失败必须让事务回滚，与状态要么全成要么全不成）</li>
 * </ol>
 */
class DispatchEventWriteTest {

    private static final Path SERVICE =
            Path.of("src/main/java/com/exphub/service/DispatchService.java");
    private static final Path MAPPER =
            Path.of("src/main/java/com/exphub/mapper/DispatchEventMapper.java");

    private static String stripComments(String src) {
        return src.replaceAll("(?s)/\\*.*?\\*/", "").replaceAll("(?m)//.*$", "");
    }

    @Test
    @DisplayName("11 种事件类型常量齐全")
    void allElevenEventTypesDeclared() throws Exception {
        String src = stripComments(Files.readString(
                Path.of("src/main/java/com/exphub/entity/DispatchEvent.java"), StandardCharsets.UTF_8));
        String[] types = {"CREATE", "REVIEW_PASS", "REVIEW_REJECT", "CLAIM", "NEED_CONFIRM",
                "REPLY", "SUBMIT_ACCEPT", "ACCEPT_PASS", "ACCEPT_REJECT", "CANCEL", "TERMINATE"};
        for (String t : types) {
            assertTrue(src.contains("EV_" + t + " = \"" + t + "\""),
                    "缺事件类型常量 EV_" + t);
        }
    }

    @Test
    @DisplayName("每个状态转移方法的成功分支后都追加了对应事件（11 类全覆盖，REPLY 两入口）")
    void everyTransitionWritesEvent() throws Exception {
        String src = stripComments(Files.readString(SERVICE, StandardCharsets.UTF_8));
        // {eventType, 该方法成功回执的唯一文案锚点}
        String[][] pairs = {
                {"EV_CREATE", "已建单 "},
                {"EV_CLAIM", "已认领 "},
                {"EV_NEED_CONFIRM", "已把球传给"},
                {"EV_REPLY", "已回复并继续执行"},            // AI 单 reply
                {"EV_SUBMIT_ACCEPT", "已提交验收"},
                {"EV_TERMINATE", "本轮已终止"},
                {"EV_REVIEW_PASS", "已审核通过"},
                {"EV_REVIEW_REJECT", "已驳回，单子已取消"},
                {"EV_ACCEPT_PASS", "验收通过，单子已完成"},
                {"EV_ACCEPT_REJECT", "已驳回，球回到"},
                {"EV_CANCEL", "整单已取消"},
        };
        for (String[] p : pairs) {
            int anchor = src.indexOf(p[1]);
            assertTrue(anchor > 0, "找不到成功文案锚点：" + p[1]);
            // 在成功文案之前 1600 字窗口里必须出现 recordEvent 调用（同事务内、成功分支后）
            // create 方法 recordEvent 到成功文案之间夹着 ownerNote / versionNote 拼接，窗口要够宽
            int windowStart = Math.max(0, anchor - 1600);
            String around = src.substring(windowStart, anchor);
            assertTrue(around.contains("recordEvent("),
                    "转移点「" + p[1] + "」成功分支没写 recordEvent");
            assertTrue(around.contains(p[0]),
                    "转移点「" + p[1] + "」写的不是 " + p[0]);
        }
        // REPLY 有两个入口（AI 单 reply + 人工单 humanReply），必须都写
        int replyCount = countOccurrences(src, "DispatchEvent.EV_REPLY,");
        assertEquals(2, replyCount, "EV_REPLY 应在 reply 与 humanReply 两处各写一次");
    }

    @Test
    @DisplayName("只追加：业务代码与 mapper 里不许对 dispatch_event 做 UPDATE / DELETE")
    void eventTableIsAppendOnly() throws Exception {
        String service = stripComments(Files.readString(SERVICE, StandardCharsets.UTF_8));
        String mapper = stripComments(Files.readString(MAPPER, StandardCharsets.UTF_8));

        // 真正的判据：业务代码里不许调 eventMapper.update / eventMapper.delete（BaseMapper 自带这些方法）
        assertFalse(service.matches("(?s).*eventMapper\\.update\\s*\\(.*"),
                "DispatchService 里出现了 eventMapper.update —— 事件表只许 insert");
        assertFalse(service.matches("(?s).*eventMapper\\.delete\\s*\\(.*"),
                "DispatchService 里出现了 eventMapper.delete —— 事件表绝不删");
        assertFalse(service.matches("(?s).*new UpdateWrapper<DispatchEvent>.*"),
                "DispatchService 里出现了针对 DispatchEvent 的 UpdateWrapper");
        // mapper 只 extends BaseMapper，没有自定义写方法（BaseMapper 自带的 update/delete 不许在业务层调）
        assertTrue(mapper.contains("extends BaseMapper<DispatchEvent>"),
                "DispatchEventMapper 不再是纯 BaseMapper（可能加了 update/delete 自定义方法）");
        assertFalse(mapper.contains("UPDATE") && !mapper.contains("--"),
                "DispatchEventMapper 里出现 UPDATE 语句");
    }

    @Test
    @DisplayName("recordEvent 不吞异常：事件写失败要让事务回滚，不能像邮件那样 try-catch 降级")
    void recordEventDoesNotSwallowException() throws Exception {
        String src = stripComments(Files.readString(SERVICE, StandardCharsets.UTF_8));
        int sig = src.indexOf("private void recordEvent(");
        assertTrue(sig > 0, "找不到 recordEvent 方法");
        int next = src.indexOf("public String ballOwnerLabel", sig);
        assertTrue(next > sig, "找不到 recordEvent 方法结尾");
        String body = src.substring(sig, next);
        assertFalse(body.contains("try") || body.contains("catch"),
                "recordEvent 里不能 try-catch 吞异常 —— 吞了就会出现「状态变了事件没写」，违反同事务要求");
        assertTrue(body.contains("eventMapper.insert"),
                "recordEvent 必须调用 eventMapper.insert 追加事件");
    }

    private static int countOccurrences(String s, String sub) {
        int n = 0, i = 0;
        while ((i = s.indexOf(sub, i)) >= 0) { n++; i += sub.length(); }
        return n;
    }
}
