package com.exphub.mcp;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 守卫：写回凭证必须写在**工具描述**里，不能只藏在回执里。
 *
 * <p><b>为什么要有这条</b>（阁主 2026-11-08 #47 派单）：一个只读工具描述、没读过任何回执的人，
 * 应该能知道这三个写回工具会回传可用于 originalHash 的凭证。藏在回执里等于「用到才发现」——
 * 模型没理由为了找一个不确定存在的东西去读完一次全文，而那正是 writeback_fingerprint 要省掉的动作。
 *
 * <p><b>为什么断言「64 位」</b>：这个字段曾经被我写成 12 位（#27），而 12 位填进 originalHash
 * 必被判不一致 —— 比不做更亏（调用方被拒后还得再 export 一次）。括号里的位数是语义的一部分。
 *
 * <p><b>为什么要剥注释</b>（AGENTS.md 第 10 章）：源码文本守卫最典型的死法是「改了真文案、
 * 注释里还留着旧句子」，断言照样为真。这里的断言如果被注释喂饱，就等于没有。
 */
class WritebackFingerprintDescribedTest {

    private static final String SRC = "src/main/java/com/exphub/mcp/ExpHubTools.java";

    private static String stripComments(String src) {
        return src.replaceAll("(?s)/\\*.*?\\*/", "").replaceAll("(?m)//.*$", "");
    }

    /** 取某个 @Tool 注解块（从它的 @Tool 起，到下一个 @Tool 或方法体之前）。 */
    private static String toolBlock(String stripped, String toolName) {
        int start = stripped.indexOf("@Tool(name = \"" + toolName + "\"");
        assertTrue(start >= 0, "找不到工具 " + toolName + " 的 @Tool 注解（改名字了？）");
        int next = stripped.indexOf("@Tool(", start + 6);
        return next > start ? stripped.substring(start, next) : stripped.substring(start);
    }

    private static String source() throws Exception {
        return stripComments(Files.readString(Path.of(SRC), StandardCharsets.UTF_8));
    }

    @Test
    @DisplayName("三个写回工具的描述里都必须出现 writeback_fingerprint，并写明位数与用途")
    void allWriteToolsDescribeTheCredential() throws Exception {
        String src = source();
        for (String tool : new String[]{"update_experience", "patch_experience", "append_experience"}) {
            String block = toolBlock(src, tool);
            assertTrue(block.contains("writeback_fingerprint"),
                    tool + " 的描述里没有 writeback_fingerprint —— 模型只看描述，看不到就等于没有这个能力");
            assertTrue(block.contains("64 位"),
                    tool + " 的描述里没写 64 位。位数是语义的一部分：12 位填进 originalHash 必被拒");
            assertTrue(block.contains("originalHash"),
                    tool + " 的描述里没说它能填哪个参数 —— 只说「有个凭证」等于没说");
            assertTrue(block.contains("content_sha256"),
                    tool + " 的描述没说它和 export_document 的 content_sha256 同值 —— "
                            + "阁主 #47 的验收口径是「只读描述就知道三者同源」，缺这句就只满足 2/3");
            assertTrue(block.contains("不必再 export") || block.contains("不用再 export"),
                    tool + " 的描述没说「不必再 export 重读」——「能当 originalHash」与「省掉一次 export」是两件事，"
                            + "后者才是这个字段省下的动作");
        }
    }

    @Test
    @DisplayName("get_experience_detail 的描述必须反向提醒：展示指纹不能当写回凭证")
    void detailDescriptionWarnsAboutFingerprint() throws Exception {
        String block = toolBlock(source(), "get_experience_detail");
        assertTrue(block.contains("writeback_fingerprint"),
                "detail 的描述要把模型指到真正的凭证上去，只说「不能用」是不够的");
        assertTrue(block.contains("detail_fingerprint"),
                "要点名那个容易被误用的字段，否则模型不知道哪一行才是「不能用」的那个");
        assertTrue(block.contains("不能") || block.contains("不可"),
                "必须写死「不能当 originalHash 用」，不能只描述它是展示指纹");
    }

    @Test
    @DisplayName("描述里不许把凭证写成 12 位（我 #27 犯过的错，别再犯第二次）")
    void descriptionMustNotClaimShortFingerprint() throws Exception {
        String src = source();
        for (String tool : new String[]{"update_experience", "patch_experience", "append_experience",
                "get_experience_detail"}) {
            String block = toolBlock(src, tool);
            assertFalse(block.contains("writeback_fingerprint（12 位）") || block.contains("writeback_fingerprint 前 12"),
                    tool + " 的描述把 writeback_fingerprint 说成 12 位了 —— 那个长度过不了校验");
        }
    }
}
