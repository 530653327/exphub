package com.exphub.service;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 查询词有效性判定：空/空白/纯标点/纯停用词一律视为「无实质内容」，
 * 拒绝走全库兜底返回一堆无关经验（线上曾出现空 query 一次返回 198 条）。
 */
class DocServiceQueryGuardTest {

    @Test
    void nullAndBlankAreRejected() {
        assertFalse(DocService.hasMeaningfulQuery(null));
        assertFalse(DocService.hasMeaningfulQuery(""));
        assertFalse(DocService.hasMeaningfulQuery("   "));
        assertFalse(DocService.hasMeaningfulQuery("\t\n "));
        assertFalse(DocService.hasMeaningfulQuery("　"));   // 全角空格
    }

    @Test
    void punctuationOnlyIsRejected() {
        assertFalse(DocService.hasMeaningfulQuery("。。。"));
        assertFalse(DocService.hasMeaningfulQuery("???"));
        assertFalse(DocService.hasMeaningfulQuery("___"));
        assertFalse(DocService.hasMeaningfulQuery("---"));
        assertFalse(DocService.hasMeaningfulQuery("，、；"));
    }

    @Test
    void stopwordsOnlyIsRejected() {
        assertFalse(DocService.hasMeaningfulQuery("的 了 和"));
        assertFalse(DocService.hasMeaningfulQuery("怎么 如何 什么"));
        assertFalse(DocService.hasMeaningfulQuery("请问 帮我 看看"));
    }

    @Test
    void realKeywordsAreAccepted() {
        assertTrue(DocService.hasMeaningfulQuery("Nginx"));
        assertTrue(DocService.hasMeaningfulQuery("部"));           // 单字也是有内容的查询
        assertTrue(DocService.hasMeaningfulQuery("Nginx 反向代理 HTTPS 配置"));
        assertTrue(DocService.hasMeaningfulQuery("  Nginx  "));    // 前后空白不影响
        assertTrue(DocService.hasMeaningfulQuery("的 Nginx"));     // 停用词 + 实词
        assertTrue(DocService.hasMeaningfulQuery("v1.2"));
        assertTrue(DocService.hasMeaningfulQuery("MCP SSE 心跳"));
    }

    @Test
    void mixedPunctuationWithTextIsAccepted() {
        assertTrue(DocService.hasMeaningfulQuery("Nginx，配置？"));
        assertTrue(DocService.hasMeaningfulQuery("（部署）"));
    }
}
