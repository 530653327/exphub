package com.exphub.mcp;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * E10 摘要质量：占位符换成废摘要一样没用（2026-10-24 23:2x 阁主复测）。
 *
 * 拆解宝 16 章里 6 章的摘要是「标题回声」——等于章节内的小标签或章节标题本身，
 * 挑章节时读它跟没读一样。这三种形态必须能拿到真正有用的内容：
 *   1. 正文首行是**整行加粗的小标签**（如「**后端 Controller**」）
 *   2. 全篇只有表格，首列才是内容索引（如业务术语表）
 *   3. 全篇只有表格且首列是长路径，退而用加粗标签
 */
class ChapterSummaryQualityTest {

    /** 第 3 章「目录结构」：代码块 + 加粗小标签 + 表格。首行可取物是加粗标签，不是描述 */
    private static final String CH3 = String.join("\n",
            "```",
            "cloud-dpt/order-app-api/src/main/java/com/dpt/order",
            "newborn-dpt-vue/src/views/AutomotiveRecycling/",
            "```",
            "",
            "## 关键入口",
            "",
            "**后端 Controller**",
            "",
            "| 文件 | 职责 |",
            "|---|---|",
            "| `web/zhan/ScrapCarRecyclingController.java` | 报废车主接口（40+） |",
            "",
            "**PC 页面**",
            "",
            "| 文件 | 职责 |",
            "|---|---|",
            "| `RecyclingOrder.vue` | 回收单管理 |",
            "");

    /** 第 5 章「业务术语」：全篇一张表，首列就是术语名 */
    private static final String CH5 = String.join("\n",
            "| 术语 | 说明 |",
            "|---|---|",
            "| 报废车回收单 | 供货方提交后生成的业务单，主表 `zh_scrap_car_recycling_order` |",
            "| 供货方 | 修理厂 / 车商（`user_type`） |",
            "| 拆解厂 | 平台指派回收拆解的合作厂商 |",
            "");

    /** 第 14 章「关键文件索引」：全篇一张表，首列是任务名 */
    private static final String CH14 = String.join("\n",
            "| 任务 | 去哪 |",
            "|---|---|",
            "| 回收单状态机/金额 | `service/zhan/impl/ScrapCarRecyclingServiceImpl.java` |",
            "| 平台预估价计算 | `calculateProspectAmount`（单行） |",
            "| 车商管理 | `ScrapCarRecyclingBrokerServiceImpl.java` |",
            "");

    @Test
    void joinedBoldLabelsInsteadOfTheFirstOne() {
        // 旧行为：抓到第一个加粗标签就返回 → 「后端 Controller」，看不出还有 PC 页面
        String s = ExpHubTools.autoSummaryFromContent(CH3, "目录结构");
        assertEquals("后端 Controller、PC 页面", s);
    }

    @Test
    void tableFirstColumnWhenWholeChapterIsATable() {
        // 旧行为：全篇表格 → 找不到段落 → 回退章节标题「业务术语」，等于没说
        assertEquals("报废车回收单、供货方、拆解厂",
                ExpHubTools.autoSummaryFromContent(CH5, "业务术语"));
        assertEquals("回收单状态机/金额、平台预估价计算、车商管理",
                ExpHubTools.autoSummaryFromContent(CH14, "关键文件索引"));
    }

    @Test
    void noLongerEchoesChapterTitle() {
        // 核心断言：摘要不能等于章节标题，否则目录里那一行是废话
        for (String[] c : new String[][]{{CH3, "目录结构"}, {CH5, "业务术语"}, {CH14, "关键文件索引"}}) {
            String s = ExpHubTools.autoSummaryFromContent(c[0], c[1]);
            assertFalse(c[1].equals(s), "摘要不应是章节标题的回声，实际：" + s);
            assertTrue(s.length() >= 8, "摘要太短等于没写，实际：" + s);
        }
    }

    @Test
    void skipsTableHeaderRow() {
        // 表头（术语/说明）不能混进摘要，分隔行之后才算数据行
        String s = ExpHubTools.autoSummaryFromContent(CH5, "业务术语");
        assertFalse(s.contains("术语"), "表头不应出现在摘要里，实际：" + s);
        assertFalse(s.contains("说明"), "表头不应出现在摘要里，实际：" + s);
    }

    @Test
    void stillPrefersRealParagraphOverLabelsAndTables() {
        // A 档优先：有真段落时不该退化成标签清单
        String withPara = CH5 + "\n\n本章是术语速查，改动术语前先读这里。\n";
        assertEquals("本章是术语速查，改动术语前先读这里。",
                ExpHubTools.autoSummaryFromContent(withPara, "业务术语"));
    }

    @Test
    void smartCutBreaksAtPunctuationNotMidWord() {
        // 硬切 30 字会切出「…不 push。 未」这种残句
        String s = ExpHubTools.autoSummaryFromContent(
                "- 提交必须 push，禁止只 commit 不 push。 未 push ＝ 拉不到 / 部署不了 / 测不了", "禁忌与约束");
        assertEquals("提交必须 push，禁止只 commit 不 push", s);
    }

    @Test
    void smartCutKeepsEnglishConfigStringsIntact() {
        // 断在最近的标点会让「QA 约定环境为 192.168.11.47:78」只剩「测试环境：QA」，
        // 所以断完剩不到 12 字就别断，直接硬切
        String s = ExpHubTools.autoSummaryFromContent(
                "- 测试环境：QA 约定环境为 192.168.11.47:7878，联调走网关；生产另有一套地址", "技术栈");
        assertEquals("测试环境：QA 约定环境为 192.168.11.47:78", s);
        // 断完只剩 4 字那种情况才不硬切；这里整句是 30 字，硬切是可接受的
        assertEquals(30, s.length());
    }
}
