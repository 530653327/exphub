package com.exphub.service;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 「比较对数」必须自带算术（2026-10-26 11:5x 阁主实测质疑）。
 *
 * <p>他读到回执「553 篇、比较 51852 对」，自己算 553×552/2 = 152,628，
 * 于是怀疑中间有**从未写进任何文档的预筛**——怀疑是完全合理的，
 * 因为回执只给结果不给算式。真实原因是「跨作用域按设计不比」。
 *
 * <p>这里钉三件事：
 * <ol>
 *   <li>全组合数必须出现（让人对得上自己的算式）</li>
 *   <li>差值必须以「跨作用域 N 对按设计不比」明说，不能只说「有差额」</li>
 *   <li>全在同一作用域时，必须明说「一对没漏」（否则读者无法区分「没漏」和「漏了但没写」）</li>
 * </ol>
 */
class PairArithmeticTest {

    @Test
    void crossScopePairsMustBeNamedWithRealNumbers() {
        String note = SimilarityService.pairArithmeticNote(553, 51852);
        assertTrue(note.contains("152628"), "全组合必须印出来（553×552/2）：" + note);
        assertTrue(note.contains("51852"), "实际比较数必须印出来：" + note);
        assertTrue(note.contains("100776"), "差值必须印出来（152628−51852）：" + note);
        assertTrue(note.contains("跨作用域"), "差值的原因必须点名，不能只说「有差额」：" + note);
        assertTrue(note.contains("按设计不比"), "要说清这是设计，不是丢了对：" + note);
    }

    @Test
    void singleScopeMustDeclareNothingMissed() {
        // 100 篇且全在一个作用域：C(100,2) = 4950，等于实际比较数
        String note = SimilarityService.pairArithmeticNote(100, 4950);
        assertTrue(note.contains("4950"), note);
        assertTrue(note.contains("一对没漏"),
                "全同作用域时必须明说没漏，否则读者分不清「没漏」和「漏了没写」：" + note);
    }

    @Test
    void tooFewDocsProduceNoArithmetic() {
        assertEquals("", SimilarityService.pairArithmeticNote(1, 0), "1 篇没有对数，不该印算式");
        assertEquals("", SimilarityService.pairArithmeticNote(0, 0), "0 篇没有对数，不该印算式");
    }
}
