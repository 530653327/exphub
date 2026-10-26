package com.exphub.service;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * `diff_experiences` 的底层计算（2026-10-26 13:0x 阁主第 2 条）。
 *
 * <p>他的原话：「要知道两条文档差在哪，只能读两篇全文 —— 这是**判断重复最底层的操作**，
 * 却没有专门工具」。两篇上千字，读完就占满上下文。
 *
 * <p>这里钉三件事：
 * <ol>
 *   <li>共同行 / A 独有 / B 独有 的划分要准（共享行不许混进独有）</li>
 *   <li>行数太少时要明说「逐行不可靠」，不许假装可信</li>
 *   <li>口径与扫描不同：**不剔模板**（判重复要看实际差在哪），这一点要能从数字上看出来</li>
 * </ol>
 */
class DiffLinesTest {

    @Test
    void sharedAndUniqueLinesAreSplitCorrectly() {
        String a = "第一行：安装依赖\n第二行：改配置\n第三行：重启服务\n第四行：验证健康检查";
        String b = "第一行：安装依赖\n第二行：改配置\n第五行：回滚脚本";
        SimilarityScorer.Diff d = SimilarityScorer.diffLines(a, b);
        assertEquals(2, d.shared(), "前两行是共同的");
        assertEquals(2, d.onlyA().size(), "A 独有应为第三、四行：" + d.onlyA());
        assertEquals(1, d.onlyB().size(), "B 独有应为回滚脚本那行：" + d.onlyB());
        assertTrue(d.onlyA().get(0).contains("重启服务"), "顺序要按原文出现顺序：" + d.onlyA());
        assertFalse(d.onlyA().contains("第一行：安装依赖"), "共享行不许出现在独有列表里");
        assertTrue(d.lineMode(), "两侧各 3~4 行，逐行比较应判为可靠");
    }

    @Test
    void tooFewLinesMustBeDeclaredUnreliable() {
        String a = "只有一行";
        String b = "只有另一行";
        SimilarityScorer.Diff d = SimilarityScorer.diffLines(a, b);
        assertFalse(d.lineMode(), "行太少时必须说「逐行不可靠」，不许假装可信");
        assertEquals(0, d.shared(), "一行的文档没有共同行（分不出是共享还是独有）");
    }

    @Test
    void templateLinesAreNotStrippedHere() {
        // 扫描为了「不被骨架骗」会剔模板行；diff 不剔 —— 判重复要看实际差在哪
        StringBuilder a = new StringBuilder();
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < 5; i++) {
            a.append("模板行 ").append(i).append("：同一套骨架内容\n");
            b.append("模板行 ").append(i).append("：同一套骨架内容\n");
        }
        a.append("A 独有：这一段只有 A 有\n");
        b.append("B 独有：这一段只有 B 有\n");
        SimilarityScorer.Diff d = SimilarityScorer.diffLines(a.toString(), b.toString());
        assertEquals(5, d.shared(), "模板行在这里算共同行（diff 不剔模板）：" + d.shared());
        assertEquals(1, d.onlyA().size());
        assertEquals(1, d.onlyB().size());
    }

    @Test
    void identicalContentHasNoUniqueLines() {
        String body = "第一行：装依赖\n第二行：改配置\n第三行：重启服务";
        SimilarityScorer.Diff d = SimilarityScorer.diffLines(body, body);
        assertTrue(d.onlyA().isEmpty() && d.onlyB().isEmpty(), "完全相同的正文不该有独有行");
        assertEquals(3, d.shared());
    }
}
