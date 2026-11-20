package com.exphub.service;

import com.exphub.entity.DispatchEvent;
import com.exphub.entity.DispatchHeader;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 事件回填的纯逻辑守卫（2026-11-20 W-20261120-000025）。
 *
 * <p>不连库：只测 status → 事件的映射，以及「取不到时间就不补、不编时间」这条硬约束。
 */
class DispatchEventBackfillTest {

    private DispatchHeader h(String status, LocalDateTime reviewed, LocalDateTime accepted,
                             LocalDateTime verified, LocalDateTime updated) {
        DispatchHeader x = new DispatchHeader();
        x.setOrderNo("W-20261120-000099");
        x.setStatus(status);
        x.setCreator("阁主");
        x.setAssignee("烛龙");
        x.setAccepter("烛龙");
        x.setReviewedAt(reviewed);
        x.setAcceptedAt(accepted);
        x.setVerifiedAt(verified);
        x.setUpdatedAt(updated);
        x.setCreatedAt(LocalDateTime.of(2026, 11, 20, 10, 0));
        return x;
    }

    @Test
    @DisplayName("CREATE 用建单时间；各终态按专门时间戳回填，取不到才回退 updated_at")
    void seedTimeSources() {
        LocalDateTime t = LocalDateTime.of(2026, 11, 20, 12, 0);
        LocalDateTime up = LocalDateTime.of(2026, 11, 20, 13, 0);

        assertEquals(DispatchEvent.EV_REVIEW_PASS,
                DispatchEventBackfillService.seedForStatus(DispatchHeader.ST_READY).eventType());
        assertEquals(t, DispatchEventBackfillService.seedForStatus(DispatchHeader.ST_READY)
                .time().apply(h(DispatchHeader.ST_READY, t, null, null, up)));

        // 验收通过：优先 verified_at，没有才回退 updated_at
        assertEquals(t, DispatchEventBackfillService.seedForStatus(DispatchHeader.ST_DONE)
                .time().apply(h(DispatchHeader.ST_DONE, null, null, t, up)));
        assertEquals(up, DispatchEventBackfillService.seedForStatus(DispatchHeader.ST_DONE)
                .time().apply(h(DispatchHeader.ST_DONE, null, null, null, up)));

        // 认领：优先 accepted_at
        assertEquals(t, DispatchEventBackfillService.seedForStatus(DispatchHeader.ST_RUNNING)
                .time().apply(h(DispatchHeader.ST_RUNNING, null, t, null, up)));
    }

    @Test
    @DisplayName("REVIEWING 与 DRAFT 不补第二条（CREATE 已到 REVIEWING / DRAFT 无对应动作）")
    void noSecondEventForReviewingOrDraft() {
        assertNull(DispatchEventBackfillService.seedForStatus(DispatchHeader.ST_REVIEWING));
        assertNull(DispatchEventBackfillService.seedForStatus(DispatchHeader.ST_DRAFT));
        assertNull(DispatchEventBackfillService.seedForStatus(null));
    }

    @Test
    @DisplayName("取不到时间戳时回填 time 为 null（调用方据此跳过，绝不编 now）")
    void missingTimeStaysNull() {
        // CONFIRMING / VERIFYING 只看 updated_at，取不到就是 null，不能回退到 created_at 或 now
        LocalDateTime r = DispatchEventBackfillService.seedForStatus(DispatchHeader.ST_CONFIRMING)
                .time().apply(h(DispatchHeader.ST_CONFIRMING, null, null, null, null));
        assertNull(r, "取不到时间必须保持 null，调用方会跳过这条，不许编时间");
    }

    @Test
    @DisplayName("11 状态各映射到正确的 event_type 与 to_status")
    void eventTypeMappingCoversAllTerminalAndLiveStates() {
        assertEquals(DispatchEvent.EV_REVIEW_PASS, type(DispatchHeader.ST_READY));
        assertEquals(DispatchEvent.EV_CLAIM, type(DispatchHeader.ST_RUNNING));
        assertEquals(DispatchEvent.EV_NEED_CONFIRM, type(DispatchHeader.ST_CONFIRMING));
        assertEquals(DispatchEvent.EV_SUBMIT_ACCEPT, type(DispatchHeader.ST_VERIFYING));
        assertEquals(DispatchEvent.EV_ACCEPT_PASS, type(DispatchHeader.ST_DONE));
        assertEquals(DispatchEvent.EV_CANCEL, type(DispatchHeader.ST_CANCELLED));
    }

    @Test
    @DisplayName("回填开关默认关闭：字段初值为 false（不因应用启动就改历史数据）")
    void backfillSwitchDefaultsFalse() throws Exception {
        Field f = DispatchEventBackfillService.class.getDeclaredField("backfillOnStart");
        assertNotNull(f);
        f.setAccessible(true);
        assertFalse(f.getBoolean(new DispatchEventBackfillService()),
                "回填开关字段初值必须是 false");
    }

    private static String type(String status) {
        DispatchEventBackfillService.EventSeed s = DispatchEventBackfillService.seedForStatus(status);
        return s == null ? null : s.eventType();
    }
}
