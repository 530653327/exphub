package com.exphub.service;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 不许替缺陷编解释（2026-10-26 08:3x 阁主实测）。
 *
 * <p>`get_experience_history(id=101, version=13)` 原来的兜底文案是：
 * <pre>
 *   ❌ v13 不存在（主表与冷存档都查过了）
 *   > 那它从来没被保存过（例如该文档是从 v21 直接创建的）。
 * </pre>
 * 第二句是错的：#101 是从 v1 一路走来的，不可能「从 v21 创建」；真因是门户更新跳过快照。
 *
 * <p><b>后果比措辞严重</b>：它给出一个听起来合理的解释，读到的人就不会再怀疑「版本号被消耗了」。
 * 全库 10 个永久缺失的版本号本该被发现，却被这句话解释掉了 ——
 * 导读（已修）、搜索结果（已修）、history 兜底（本测试守的就是这第三处）。
 */
class MissingVersionExplanationTest {

    private static final Path TOOLS = Path.of("src/main/java/com/exphub/mcp/ExpHubTools.java");

    /** 剥注释后再断言：本次修复的说明里就引用了旧文案，不剥会把「记录为什么删」当成「还没删」 */
    private static String src() throws Exception {
        return SlicedReadAndStatusFilterTest.stripComments(
                Files.readString(TOOLS, StandardCharsets.UTF_8));
    }

    @Test
    void mustNotInventAReasonForMissingVersion() throws Exception {
        String s = src();
        assertFalse(s.contains("从来没被保存过"),
                "「从来没被保存过」= 替缺陷编解释，删掉；只陈述「库里没有副本」这个事实");
        assertFalse(s.contains("直接创建的）"),
                "「例如该文档是从 vN 直接创建的」是错的原因（#101 从 v1 演进而来），不许留");
    }

    @Test
    void mustStateFactNotGuess() throws Exception {
        String s = src();
        assertTrue(s.contains("在库里没有副本"),
                "结论要落在事实层：有/没有副本，而不是「存在/不存在」这种会被理解成「从没产生过」的说法");
        assertTrue(s.contains("不要把它解释成"),
                "要明确写出「不要把它解释成从 vN 直接创建」—— 那句话正是把缺陷解释掉的那句");
        assertTrue(s.contains("给缺陷编一个合理的原因"),
                "把原则写进回执：编一个合理的原因，下一个人就不会再追它");
    }

    @Test
    void mustDistinguishNotYetProducedFromConsumedWithoutCopy() throws Exception {
        String s = src();
        assertTrue(s.contains("cov.missing().contains(version)"),
                "要能用缺号清单区分「被消耗但没留副本」与其它情况，而不是一句话糊过去");
        assertTrue(s.contains("还没产生"),
                "版本号大于「已存最高版本号」时，事实是「还没产生/记错了」，这可以直说");
        assertTrue(s.contains("不一致状态，请直接报给维护者"),
                "落在已存范围内、又不在缺号清单的，属于不一致，必须如实说不知道，不许推测");
    }
}
