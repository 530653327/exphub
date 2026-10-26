package com.exphub.mcp;

import com.exphub.entity.Doc;
import com.exphub.service.DocService;
import com.exphub.util.RenderArtifactStripper;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 详情页的待删状态渲染（2026-10-26 16:5x 阁主实跑标记链路发现）。
 *
 * <p>漏洞：{@code get_experience_detail} 回执原本只有「分类/作者/版本/标签」，
 * <b>一个字不提「这条已标记待删」</b>。下一个 AI 读到会当正常文档用，
 * 而它已在待删队列等着人确认 —— 比「列表漏排」更危险，
 * 因为列表只是多显示一条，详情是<b>直接给出内容且没有任何警示</b>。
 *
 * <p>三件事都要断言（这类的坑不在「多写了」而在「漏写了」）：
 * <ol>
 *   <li>待删文档的 detail 必须显示状态 + 原因 + 标记人</li>
 *   <li>正常文档的 detail <b>不能</b>出现待删字样（不误伤）</li>
 *   <li>渲染出来的待删块必须能被剥离器剥掉 ——
 *       否则「读出来 → 改一点 → 整篇写回」会把它固化进正文，
 *       而且那行提示<b>不会随后续 cancel_deletion 消失</b>，等于给文档刻了个假碑</li>
 * </ol>
 */
class PendingDeleteDetailBannerTest {

    /** 造一个待删文档 */
    private static Doc pendingDeleteDoc() {
        Doc d = new Doc();
        d.setId(675L);
        d.setTitle("【临时靶子】勿引用");
        d.setCategory("测试");
        d.setAuthorName("阁主");
        d.setVersion(1);
        d.setTags("靶子");
        d.setStatus(DocService.PENDING_DELETE);
        d.setDeleteReason("标记链路验证靶子：验完即删");
        d.setDeleteMarkedBy("烛龙");
        d.setDeleteMarkedAt(java.time.LocalDateTime.of(2026, 10, 26, 16, 33, 5));
        d.setContent("## 概述\n\n靶子正文。\n");
        return d;
    }

    /** 造一个正常文档 */
    private static Doc activeDoc() {
        Doc d = pendingDeleteDoc();
        d.setId(100L);
        d.setStatus("ACTIVE");
        d.setDeleteReason(null);
        d.setDeleteMarkedBy(null);
        d.setDeleteMarkedAt(null);
        return d;
    }

    /** 模拟 detail 的渲染视图：头部 + 横幅 + 正文 */
    private static String renderDetail(Doc doc) {
        return ExpHubTools.renderDetailHeader(doc)
                + ExpHubTools.renderPendingDeleteBlock(doc)
                + "\n---\n\n"
                + (doc.getContent() != null ? doc.getContent() : "");
    }

    // ==================== ① 必须显示 ====================

    @Test
    void pendingDeleteDocMustShowStatusInDetailHeader() {
        String out = renderDetail(pendingDeleteDoc());
        assertTrue(out.contains("**状态**"),
                "detail 头部必须有状态行: " + out);
        assertTrue(out.contains("待删"),
                "detail 必须说清这条是待删: " + out);
    }

    @Test
    void pendingDeleteDocMustShowReasonAndMarkedBy() {
        String out = renderDetail(pendingDeleteDoc());
        assertTrue(out.contains("标记链路验证靶子"),
                "detail 必须显示待删原因（用户要据此判断该不该删）: " + out);
        assertTrue(out.contains("烛龙"),
                "detail 必须显示标记人: " + out);
    }

    @Test
    void pendingDeleteDocMustSayItIsNotDeletedYet() {
        String out = renderDetail(pendingDeleteDoc());
        assertTrue(out.contains("尚未删除"),
                "必须说清「尚未删除」—— 否则 AI 会以为文档已经没了: " + out);
    }

    @Test
    void pendingDeleteDocMustPointToCancelDeletion() {
        String out = renderDetail(pendingDeleteDoc());
        assertTrue(out.contains("cancel_deletion"),
                "必须给出撤回路径（误标时 AI 要知道怎么办）: " + out);
    }

    // ==================== ② 不误伤 ====================

    @Test
    void pendingDeleteMustHaveReadableStatusLabel() {
        // 原来漏了这个分支，回执显示成「❓ PENDING_DELETE」——
        // 机器看得懂、中文看不懂，等于没告诉人这条正等着被删。
        String label = ExpHubTools.getStatusLabel(DocService.PENDING_DELETE);
        assertFalse(label.contains("❓"),
                "待删状态不能落到 default 分支（显示成机器值）: " + label);
        assertTrue(label.contains("待删"), "标签必须说清是待删: " + label);
        assertTrue(label.contains("未删除"),
                "标签必须说清还没真删（否则 AI 会以为文档已消失）: " + label);
    }

    @Test
    void otherStatusLabelsMustNotChange() {
        assertTrue(ExpHubTools.getStatusLabel("ACTIVE").contains("正常"));
        assertTrue(ExpHubTools.getStatusLabel("COMPLETED").contains("已完成"));
        assertTrue(ExpHubTools.getStatusLabel("BROKEN").contains("已失效"));
        assertTrue(ExpHubTools.getStatusLabel("DEPRECATED").contains("已过期"));
    }

    @Test
    void activeDocMustNotShowPendingDeleteBanner() {
        String out = renderDetail(activeDoc());
        assertFalse(out.contains("🗑"),
                "正常文档不能出现待删标记: " + out);
        assertFalse(out.contains("待删原因"),
                "正常文档不能出现待删原因: " + out);
        // 但状态行本身要在（这是头部元信息的一部分）
        assertTrue(out.contains("**状态**: 🟢 正常"),
                "正常文档仍要显示状态行: " + out);
    }

    @Test
    void bodyMentioningPendingDeleteMustNotBeTreatedAsBanner() {
        // 正文里真的会写「待删」这两个字 —— 那是内容，不是渲染痕迹
        Doc d = activeDoc();
        d.setContent("## 概述\n\n本文讲的是**待删标记**机制怎么用，delete_reason 是必填项。\n");
        String out = renderDetail(d);
        assertTrue(out.contains("本文讲的是**待删标记**机制怎么用"),
                "正文里提到待删不能被当成横幅吃掉: " + out);
    }

    // ==================== ③ 剥离器必须能剥 ====================

    @Test
    void stripperMustRemovePendingDeleteBanner() {
        String detail = renderDetail(pendingDeleteDoc());
        String out = RenderArtifactStripper.strip(detail);

        assertFalse(out.contains("🗑"),
                "待删横幅必须剥掉（否则写回时固化进正文）: " + out);
        assertFalse(out.contains("待删原因"),
                "待删原因行必须剥掉: " + out);
        assertFalse(out.contains("待删标记人"),
                "待删标记人行必须剥掉: " + out);
        assertFalse(out.contains("**状态**"),
                "头部状态行必须剥掉（否则写回时固化成正文的一行状态）: " + out);
        assertFalse(out.contains("本条被标记为"),
                "待删提示的说明行必须剥掉: " + out);
    }

    @Test
    void stripperMustKeepBodyAfterStrippingBanner() {
        String detail = renderDetail(pendingDeleteDoc());
        String out = RenderArtifactStripper.strip(detail);
        assertTrue(out.contains("靶子正文。"),
                "剥掉横幅后正文必须还在: " + out);
        assertTrue(out.contains("## 概述"),
                "剥掉横幅后正文标题必须还在: " + out);
    }

    @Test
    void stripperMustNotEatBodyThatMentionsDeleteReason() {
        // 反测：正文里真的出现「**待删原因**: ...」这种行形态怎么办？
        // 剥离器按字面前缀剥，🗑 前缀足够独特，正文不会这么写；
        // 即便真写了，也不能整段丢光。
        String content = """
                ## 字段说明

                **待删原因**: 标记时必填，说明为什么这条该删。
                """;
        String out = RenderArtifactStripper.strip(content);
        assertTrue(out.contains("字段说明"),
                "正文标题不能丢: " + out);
    }
}
