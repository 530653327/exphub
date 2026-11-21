package com.exphub.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.exphub.entity.DispatchEvent;
import com.exphub.entity.DispatchHeader;
import com.exphub.entity.DispatchItem;
import com.exphub.mapper.DispatchEventMapper;
import com.exphub.mapper.DispatchHeaderMapper;
import com.exphub.mapper.DispatchItemMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * 派单源头强制发版判断守卫（W-20261121-000018）。
 * 盯住：① submit_accept 三选一发版结论 + 按态证据，缺项零写入拒收；② 提交时把 release_flag 落库、记事件；
 * ③ 发版状态/怎么验文案同源；④ 发版证据核对打回置回进行中 + 留独立事件、不动四态。
 */
class DispatchSourceReleaseTest {

    private DispatchService service;
    private DispatchHeaderMapper headerMapper;
    private DispatchEventMapper eventMapper;
    private DispatchItemMapper itemMapper;

    private DispatchHeader runningOrder() {
        DispatchHeader h = new DispatchHeader();
        h.setId(1L);
        h.setOrderNo("W-20261121-000099");
        h.setAssignee("烛龙");
        h.setCreator("阁主");
        h.setOrigin(DispatchHeader.ORIGIN_AI);
        h.setStatus(DispatchHeader.ST_RUNNING);
        h.setReleaseFlag(null);
        h.setApiKey("k");
        return h;
    }

    @BeforeEach
    void setUp() {
        service = new DispatchService();
        headerMapper = mock(DispatchHeaderMapper.class);
        eventMapper = mock(DispatchEventMapper.class);
        itemMapper = mock(DispatchItemMapper.class);
        org.springframework.test.util.ReflectionTestUtils.setField(service, "headerMapper", headerMapper);
        org.springframework.test.util.ReflectionTestUtils.setField(service, "itemMapper", itemMapper);
        org.springframework.test.util.ReflectionTestUtils.setField(service, "eventMapper", eventMapper);
        when(headerMapper.selectOne(any(QueryWrapper.class))).thenAnswer(inv -> runningOrder());
        when(headerMapper.update(any(), any(UpdateWrapper.class))).thenReturn(1);
        when(itemMapper.selectOne(any(QueryWrapper.class))).thenReturn(null);
    }

    // ===== ① 纯函数：validateReleaseConclusion 三选一 + 证据 =====

    @Test
    @DisplayName("发版结论三选一：空/枚举外一律拒，回执写明三选一")
    void flagMustBeOneOfThree() {
        assertNotNull(DispatchService.validateReleaseConclusion("", "一些证据"));
        assertNotNull(DispatchService.validateReleaseConclusion(null, "一些证据"));
        String msg = DispatchService.validateReleaseConclusion("BOGUS", "一些证据");
        assertNotNull(msg);
        assertTrue(msg.contains("BOGUS"), "回显收到的原值：" + msg);
        assertTrue(msg.contains("RELEASED") && msg.contains("NEED") && msg.contains("NONE"));
    }

    @Test
    @DisplayName("RELEASED 必须同时给 commit sha 与线上版本回读，缺哪项写明哪项")
    void releasedNeedsCommitAndEcho() {
        assertNull(DispatchService.validateReleaseConclusion("RELEASED",
                "commit 387cf14 / 线上 ping 回读 3.5.12 / health UP"));
        String noCommit = DispatchService.validateReleaseConclusion("RELEASED", "线上版本 3.5.12 已核对");
        assertNotNull(noCommit);
        assertTrue(noCommit.contains("commit sha"), noCommit);
        String noEcho = DispatchService.validateReleaseConclusion("RELEASED", "commit 387cf14");
        assertNotNull(noEcho);
        assertTrue(noEcho.contains("线上版本回读"), noEcho);
        assertNotNull(DispatchService.validateReleaseConclusion("RELEASED", ""));
    }

    @Test
    @DisplayName("NEED 必须给目标版本号 + 发版步骤；NONE 必须给理由")
    void needAndNoneEvidence() {
        assertNull(DispatchService.validateReleaseConclusion("NEED", "目标 3.5.20，先 mvn clean test 再 deploy"));
        String noVer = DispatchService.validateReleaseConclusion("NEED", "发版步骤见文档");
        assertNotNull(noVer);
        assertTrue(noVer.contains("目标版本号"), noVer);
        String noStep = DispatchService.validateReleaseConclusion("NEED", "目标 3.5.20");
        assertNotNull(noStep);
        assertTrue(noStep.contains("发版步骤"), noStep);

        assertNull(DispatchService.validateReleaseConclusion("NONE", "纯文档改动，无版本号"));
        assertNotNull(DispatchService.validateReleaseConclusion("NONE", ""));
        assertNotNull(DispatchService.validateReleaseConclusion("NONE", "  "));
    }

    // ===== ② 端到端：submit_accept 缺项零写入 / 通过则落库 =====

    @Test
    @DisplayName("submit_accept 不带发版结论 → 拒收，header/item/event 一行不动")
    void submitAcceptWithoutReleaseConclusionRejectedZeroWrite() {
        DispatchService.Outcome out = service.submitAccept(
                "W-20261121-000099", "烛龙", "做完了", "详细过程", null, null, "k");
        assertFalse(out.ok(), out.message());
        assertTrue(out.message().contains("发版结论"), out.message());
        assertTrue(out.message().contains("数据未改动"), out.message());
        verify(headerMapper, never()).update(any(), any(UpdateWrapper.class));
        verify(itemMapper, never()).insert(any(DispatchItem.class));
        verify(eventMapper, never()).insert(any(DispatchEvent.class));
    }

    @Test
    @DisplayName("submit_accept RELEASED 缺 commit → 拒收，数据零写入")
    void submitAcceptReleasedMissingCommitRejected() {
        DispatchService.Outcome out = service.submitAccept(
                "W-20261121-000099", "烛龙", "做完了", "详细过程",
                "RELEASED", "线上版本 3.5.12 已核对", "k");
        assertFalse(out.ok(), out.message());
        assertTrue(out.message().contains("commit sha"), out.message());
        verify(headerMapper, never()).update(any(), any(UpdateWrapper.class));
        verify(eventMapper, never()).insert(any(DispatchEvent.class));
    }

    @Test
    @DisplayName("submit_accept 带齐发版结论 → 提交时把 release_flag 落库，并记 RELEASE_FLAG 事件含证据")
    void submitAcceptWithConclusionPersistsFlagAndEvent() {
        String ev = "commit 387cf14 / 线上 ping 回读 3.5.12 / health UP";
        DispatchService.Outcome out = service.submitAccept(
                "W-20261121-000099", "烛龙", "做完了", "详细过程", "RELEASED", ev, "k");
        assertTrue(out.ok(), out.message());

        ArgumentCaptor<UpdateWrapper> uw = ArgumentCaptor.forClass(UpdateWrapper.class);
        verify(headerMapper, times(1)).update(any(), uw.capture());
        assertTrue(uw.getValue().getSqlSet().contains("release_flag"), "提交时落 release_flag：" + uw.getValue().getSqlSet());
        assertTrue(paramValues(uw.getValue()).contains(DispatchHeader.ST_VERIFYING),
                "SET 的状态值是 VERIFYING：" + uw.getValue().getParamNameValuePairs());

        ArgumentCaptor<DispatchEvent> ec = ArgumentCaptor.forClass(DispatchEvent.class);
        verify(eventMapper, times(2)).insert(ec.capture());
        DispatchEvent releaseEv = ec.getAllValues().stream()
                .filter(e -> DispatchEvent.EV_RELEASE_FLAG.equals(e.getEventType())).findFirst().orElseThrow();
        assertEquals("RELEASED", releaseEv.getToStatus());
        assertEquals(ev, releaseEv.getEvidence(), "证据原文随提交落库");
        assertEquals("烛龙", releaseEv.getActor());
        assertEquals(DispatchEvent.ACTOR_AI, releaseEv.getActorType());
    }

    // ===== ③ 列表文案同源 =====

    @Test
    @DisplayName("发版状态未标记显示「未标记 · 待补」，怎么验四态文案同源")
    void listLabels() {
        assertEquals("未标记 · 待补", DispatchService.releaseFlagCellLabel(null));
        assertEquals("已发版", DispatchService.releaseFlagCellLabel("RELEASED"));
        assertEquals("核对线上版本号是否等于标记版本", DispatchService.releaseVerifyHint("RELEASED"));
        assertEquals("先发版再验", DispatchService.releaseVerifyHint("NEED"));
        assertEquals("验改动本身（代码 / 配置 / 文档）", DispatchService.releaseVerifyHint("NONE"));
        assertTrue(DispatchService.releaseVerifyHint(null).contains("未标记"));
    }

    // ===== ④ 发版证据核对打回 =====

    @Test
    @DisplayName("发版证据打回：非待验收拒、空原因拒；待验收→进行中，留独立事件，不动 release_flag")
    void rejectReleaseEvidence() {
        // 空原因拒
        assertFalse(service.rejectReleaseEvidence("W-20261121-000099", "  ").ok());
        // VERIFYING 单才能打回
        DispatchHeader verifying = runningOrder();
        verifying.setStatus(DispatchHeader.ST_VERIFYING);
        verifying.setReleaseFlag("RELEASED");
        when(headerMapper.selectOne(any(QueryWrapper.class))).thenReturn(verifying);

        DispatchService.Outcome out = service.rejectReleaseEvidence("W-20261121-000099", "线上回读 3.4.9，不是 3.5.12");
        assertTrue(out.ok(), out.message());

        ArgumentCaptor<UpdateWrapper> uw = ArgumentCaptor.forClass(UpdateWrapper.class);
        verify(headerMapper, times(1)).update(any(), uw.capture());
        String set = uw.getValue().getSqlSet();
        java.util.Collection<Object> vals = paramValues(uw.getValue());
        assertTrue(vals.stream().anyMatch(v -> v instanceof String && ((String) v).contains("RUNNING")),
                "SET 状态值是 RUNNING：" + vals);
        assertFalse(set.contains("release_flag"), "打回不改四态语义：" + set);
        assertTrue(vals.stream().anyMatch(v -> v instanceof String && ((String) v).contains("发版证据核对打回")),
                "写原因：" + vals);

        ArgumentCaptor<DispatchEvent> ec = ArgumentCaptor.forClass(DispatchEvent.class);
        verify(eventMapper, times(1)).insert(ec.capture());
        assertEquals(DispatchEvent.EV_RELEASE_RECHECK, ec.getValue().getEventType());
        assertEquals(DispatchHeader.ST_VERIFYING, ec.getValue().getFromStatus());
        assertEquals(DispatchHeader.ST_RUNNING, ec.getValue().getToStatus());
        assertEquals(DispatchEvent.ACTOR_HUMAN, ec.getValue().getActorType());
    }

    /** UpdateWrapper 的 SET 值是「#{ew.paramNameValuePairs.MPGENVALn}」占位符，真值在 paramNameValuePairs。 */
    private static java.util.Collection<Object> paramValues(UpdateWrapper<?> uw) {
        java.util.Map<String, Object> m = uw.getParamNameValuePairs();
        return m == null ? java.util.List.of() : m.values();
    }

    @Test
    @DisplayName("源码守卫：submit_accept 校验在写库前 + 打回不改 release_flag（剥注释后断言）")
    void sourceGuard() throws Exception {
        String src = new String(Files.readAllBytes(
                Path.of("src/main/java/com/exphub/service/DispatchService.java")), StandardCharsets.UTF_8);
        String body = src.replaceAll("(?s)/\\*.*?\\*/", "").replaceAll("(?m)//.*$", "");
        int validate = body.indexOf("validateReleaseConclusion(flag, evidence)");
        int firstWrite = body.indexOf("closeOpenItem(h, role, null, DispatchItem.ST_DONE)");
        assertTrue(validate > 0 && firstWrite > validate, "发版结论校验必须在提交写库之前");
        assertTrue(body.contains("EV_RELEASE_RECHECK"), "打回独立事件还在");
        assertTrue(body.contains("releaseFlagCellLabel") && body.contains("releaseVerifyHint"), "列表文案同源方法还在");
    }
}
