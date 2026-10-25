package com.exphub.service;

import com.exphub.entity.ReviewCandidate;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 候选状态过滤（2026-10-25）。
 *
 * <p>线上实测踩到的坑（阁主 18:0x 发现）：传 `status="ALL"` 返回空，同刻 `status=PENDING` 有数据。
 * 原实现把 "ALL" 直接拼进 `WHERE status='ALL'` —— 库里不存在这个值，于是静默返回空。
 *
 * <p>为什么这不是小事：`check_my_todos` 早就用 `ALL` 表示「不限状态」。
 * 同一个 `ALL` 在两个工具里语义不同，调用方一定会踩。**语义必须统一。**
 *
 * <p>第二个问题同源：状态值拼错一个字母也返回空。
 * 空结果有歧义 —— 「真的没有」还是「我查错了」？所以非法值必须报错，不能静默。
 */
class CandidateStatusFilterTest {

    @Test
    void allMeansNoFilter() {
        assertNull(ReviewCandidateService.normalizeStatusFilter("ALL"),
                "ALL 必须翻译成「不过滤」，不能当字面状态值");
        assertNull(ReviewCandidateService.normalizeStatusFilter("all"));
        assertNull(ReviewCandidateService.normalizeStatusFilter(" All "), "大小写与空格都要容忍");
    }

    @Test
    void blankDefaultsToPending() {
        assertEquals(ReviewCandidate.PENDING, ReviewCandidateService.normalizeStatusFilter(null));
        assertEquals(ReviewCandidate.PENDING, ReviewCandidateService.normalizeStatusFilter(""));
        assertEquals(ReviewCandidate.PENDING, ReviewCandidateService.normalizeStatusFilter("   "));
    }

    @Test
    void validStatusesPassThrough() {
        assertEquals(ReviewCandidate.PENDING, ReviewCandidateService.normalizeStatusFilter("pending"));
        assertEquals(ReviewCandidate.DUPLICATE, ReviewCandidateService.normalizeStatusFilter("duplicate"));
        assertEquals(ReviewCandidate.SUPERSEDED, ReviewCandidateService.normalizeStatusFilter("Superseded"));
        assertEquals(ReviewCandidate.DISTINCT, ReviewCandidateService.normalizeStatusFilter("DISTINCT"));
        assertEquals(ReviewCandidate.SKIPPED, ReviewCandidateService.normalizeStatusFilter("skipped"));
    }

    @Test
    void unknownStatusThrowsWithUsableValues() {
        // 拼错一个字母不能静默返回空
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> ReviewCandidateService.normalizeStatusFilter("DUPLICAT"));

        String msg = e.getMessage();
        assertTrue(msg.contains("DUPLICAT"), "应回显传入的值: " + msg);
        assertTrue(msg.contains("PENDING") && msg.contains("DISTINCT"), "应列出可用值: " + msg);
        assertTrue(msg.contains("ALL"), "应提示 ALL 的用法: " + msg);
    }

    @Test
    void allCoveredByValidStatusUnion() {
        // 白名单必须覆盖实体里定义的全部状态，否则新增状态会被误拒
        assertTrue(ReviewCandidateService.VALID_STATUSES.contains(ReviewCandidate.PENDING));
        assertTrue(ReviewCandidateService.VALID_STATUSES.contains(ReviewCandidate.DUPLICATE));
        assertTrue(ReviewCandidateService.VALID_STATUSES.contains(ReviewCandidate.SUPERSEDED));
        assertTrue(ReviewCandidateService.VALID_STATUSES.contains(ReviewCandidate.DISTINCT));
        assertTrue(ReviewCandidateService.VALID_STATUSES.contains(ReviewCandidate.SKIPPED));
        // ALL 是过滤指令，不是状态值，不该进白名单
        assertTrue(!ReviewCandidateService.VALID_STATUSES.contains("ALL"));
    }
}
