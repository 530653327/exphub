package com.exphub.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.exphub.entity.DispatchEvent;
import com.exphub.entity.DispatchHeader;
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
 * 派单发版标记守卫（W-20261121-000014 立 / W-20261121-000017 修正）。
 * 盯住"读代码看不出来"的几件事：
 * ① AI 写 RELEASED 必须有 evidence，空证据明拒且数据一行不动；② 证据原文随事件落库、actor_type=AI；
 * ③ 后台四态都能写（含带证据）；④ 枚举外的值明拒不静默修正；⑤ 同事务写事件、清空 set NULL；
 * ⑥ 筛选唯一出口；⑦ 九态状态机零改动 + 证据闸门源码守卫。
 */
class DispatchReleaseFlagTest {

    private DispatchService service;
    private DispatchHeaderMapper headerMapper;
    private DispatchEventMapper eventMapper;

    private DispatchHeader order(String flag) {
        DispatchHeader h = new DispatchHeader();
        h.setId(1L);
        h.setOrderNo("W-20261121-000099");
        h.setAssignee("烛龙");
        h.setCreator("阁主");
        h.setOrigin(DispatchHeader.ORIGIN_AI);
        h.setStatus(DispatchHeader.ST_VERIFYING);
        h.setReleaseFlag(flag);
        h.setApiKey("k");
        return h;
    }

    @BeforeEach
    void setUp() {
        service = new DispatchService();
        headerMapper = mock(DispatchHeaderMapper.class);
        eventMapper = mock(DispatchEventMapper.class);
        org.springframework.test.util.ReflectionTestUtils.setField(service, "headerMapper", headerMapper);
        org.springframework.test.util.ReflectionTestUtils.setField(service, "itemMapper", mock(DispatchItemMapper.class));
        org.springframework.test.util.ReflectionTestUtils.setField(service, "eventMapper", eventMapper);
        when(headerMapper.update(any(), any(UpdateWrapper.class))).thenReturn(1);
        when(headerMapper.selectOne(any(QueryWrapper.class))).thenAnswer(inv -> order(null));
    }

    @Test
    @DisplayName("RELEASED 空证据：null / 纯空白一律拒，且 header 一行不动、事件不写")
    void releasedWithoutEvidenceRejectedAndNoWrite() {
        DispatchService.Outcome nullEv = service.markReleaseFlagAi(
                "W-20261121-000099", "RELEASED", "我自己宣布上线", null, "阁主", "k");
        assertFalse(nullEv.ok(), "空证据必须拒");
        assertTrue(nullEv.message().contains("证据"), nullEv.message());
        assertTrue(nullEv.message().contains("必须"), nullEv.message());
        assertTrue(nullEv.message().contains("数据未改动"), nullEv.message());

        DispatchService.Outcome blankEv = service.markReleaseFlagAi(
                "W-20261121-000099", "RELEASED", "我自己宣布上线", "   ", "阁主", "k");
        assertFalse(blankEv.ok(), "纯空白证据必须拒");

        // 拒绝发生在任何写库之前 —— 这就是验收标准「数据未变」
        verify(headerMapper, never()).update(any(), any(UpdateWrapper.class));
        verify(eventMapper, never()).insert(any(DispatchEvent.class));
    }

    @Test
    @DisplayName("AI：NEED / NONE 免证据可写（evidence 可选），不受影响")
    void needNoneNeedNoEvidence() {
        assertTrue(service.markReleaseFlagAi(
                "W-20261121-000099", "NEED", "判断要发版", null, "烛龙", "k").ok());
        assertTrue(service.markReleaseFlagAi(
                "W-20261121-000099", "NONE", "确认不用发版", null, "烛龙", "k").ok());
        // AI 仍不能清空标记（白名单只有三态）
        assertFalse(service.markReleaseFlagAi(
                "W-20261121-000099", "", "清空", null, "烛龙", "k").ok());
    }

    @Test
    @DisplayName("AI：带证据写 RELEASED 成功，事件 actor_type=AI、actor=角色、evidence 原文原样落库")
    void releasedWithEvidenceWritesEventWithEvidence() {
        when(headerMapper.selectOne(any(QueryWrapper.class))).thenReturn(order("NEED"));
        String ev = "commit 387cf14 / 线上 ping 回读 3.5.12 / health UP";
        DispatchService.Outcome out = service.markReleaseFlagAi(
                "W-20261121-000099", "RELEASED", "验收通过", ev, "阁主", "k");
        assertTrue(out.ok(), out.message());

        ArgumentCaptor<DispatchEvent> cap = ArgumentCaptor.forClass(DispatchEvent.class);
        verify(eventMapper, times(1)).insert(cap.capture());
        DispatchEvent e = cap.getValue();
        assertEquals(DispatchEvent.EV_RELEASE_FLAG, e.getEventType());
        assertEquals("NEED", e.getFromStatus());
        assertEquals("RELEASED", e.getToStatus());
        assertEquals("阁主", e.getActor());
        assertEquals(DispatchEvent.ACTOR_AI, e.getActorType());
        assertEquals(ev, e.getEvidence(), "证据原文必须原样落库");
        assertTrue(e.getNote().contains("验收通过"), "note 仍带理由：" + e.getNote());
        assertNotNull(e.getCreatedAt());
    }

    @Test
    @DisplayName("AI 侧枚举外的值明拒并回显收到的原值（不静默修正）")
    void aiRejectsUnknownFlagWithEcho() {
        DispatchService.Outcome out = service.markReleaseFlagAi(
                "W-20261121-000099", "DONE", "瞎写", "some evidence", "烛龙", "k");
        assertFalse(out.ok());
        assertTrue(out.message().contains("DONE"), "必须回显收到的值：" + out.message());
    }

    @Test
    @DisplayName("后台侧：四态都能写，含 RELEASED（可带证据）与清空回未标记；枚举外的值仍拒")
    void humanCanWriteAllFourStates() {
        assertTrue(service.markReleaseFlagHuman("W-20261121-000099", "RELEASED", "2.5.19 已上线").ok());
        assertTrue(service.markReleaseFlagHuman("W-20261121-000099", "NEED", "标错救回").ok());
        assertTrue(service.markReleaseFlagHuman("W-20261121-000099", "NONE", "不用发版").ok());
        assertTrue(service.markReleaseFlagHuman("W-20261121-000099", "", "清空").ok());
        assertFalse(service.markReleaseFlagHuman("W-20261121-000099", "BOGUS", "瞎写").ok());
        // 带证据版（越哥手工点也已留痕）
        assertTrue(service.markReleaseFlagHuman("W-20261121-000099", "RELEASED", "越哥手工点", "线上 3.5.12 已核对").ok());
    }

    @Test
    @DisplayName("后台带证据写 RELEASED：事件 actor_type=HUMAN、evidence 原样落库")
    void humanEvidenceEvent() {
        when(headerMapper.selectOne(any(QueryWrapper.class))).thenReturn(order("NEED"));
        service.markReleaseFlagHuman("W-20261121-000099", "RELEASED", "越哥核对", "线上 3.5.12 已核对");
        ArgumentCaptor<DispatchEvent> cap = ArgumentCaptor.forClass(DispatchEvent.class);
        verify(eventMapper, times(1)).insert(cap.capture());
        assertEquals(DispatchEvent.ACTOR_HUMAN, cap.getValue().getActorType());
        assertEquals("线上 3.5.12 已核对", cap.getValue().getEvidence());
    }

    @Test
    @DisplayName("AI 侧 NEED 事件不伪造证据：evidence 为 null")
    void aiEventActorIsAiWithoutEvidence() {
        service.markReleaseFlagAi("W-20261121-000099", "NEED", "判断要发版", null, "烛龙", "k");
        ArgumentCaptor<DispatchEvent> cap = ArgumentCaptor.forClass(DispatchEvent.class);
        verify(eventMapper, times(1)).insert(cap.capture());
        assertEquals(DispatchEvent.ACTOR_AI, cap.getValue().getActorType());
        assertEquals("烛龙", cap.getValue().getActor());
        assertNull(cap.getValue().getEvidence());
    }

    @Test
    @DisplayName("清空时 set(col,null) 显式写 NULL —— 不靠实体 null 默认忽略")
    void clearWritesNullExplicitly() {
        when(headerMapper.selectOne(any(QueryWrapper.class))).thenReturn(order("NONE"));
        service.markReleaseFlagHuman("W-20261121-000099", "", "清空");
        ArgumentCaptor<UpdateWrapper> cap = ArgumentCaptor.forClass(UpdateWrapper.class);
        verify(headerMapper).update(any(), cap.capture());
        String setSql = cap.getValue().getSqlSet();
        assertTrue(setSql.contains("release_flag"), "清空也要 SET release_flag：" + setSql);
    }

    @Test
    @DisplayName("筛选唯一出口：空=不过滤，UNMARKED 单筛未标记，其余精确匹配")
    void filterIsSingleExit() {
        assertEquals("", DispatchService.buildFilter(null, null, null, null, null, null, null).getTargetSql());
        String s = DispatchService.buildFilter(null, "DONE", null, "派单", null, "NEED", null).getTargetSql();
        assertTrue(s.contains("release_flag = ?"), "缺发版条件：" + s);
        assertTrue(s.contains("status = ?") && s.contains("title LIKE"), "发版筛选要与状态/关键词且：" + s);
        String u = DispatchService.buildFilter(null, null, null, null, null, "UNMARKED", null).getTargetSql();
        assertTrue(u.contains("release_flag IS NULL"), "未标记要含 IS NULL：" + u);
    }

    @Test
    @DisplayName("状态机零改动：九态常量/LIVE_STATUSES/转移守卫一处都没改（源码守卫）")
    void statusMachineUntouched() throws Exception {
        String src = new String(Files.readAllBytes(
                Path.of("src/main/java/com/exphub/service/DispatchService.java")), StandardCharsets.UTF_8);
        assertTrue(src.contains("ST_REVIEWING"), "九态常量还在");
        assertTrue(src.contains("LIVE_STATUSES"), "取消用的状态集合还在");
        assertTrue(src.contains("guardAssigneeAndStatus"), "转移守卫还在");
        assertTrue(src.contains("markReleaseFlagAi") && src.contains("markReleaseFlagHuman"));
    }

    @Test
    @DisplayName("证据闸门源码守卫：RELEASED 分支必须做 evidence 空校验（防止回退成空口宣布发版）")
    void releasedEvidenceGuardInSource() throws Exception {
        String src = new String(Files.readAllBytes(
                Path.of("src/main/java/com/exphub/service/DispatchService.java")), StandardCharsets.UTF_8);
        String body = stripComments(src);
        assertTrue(body.contains("RF_RELEASED.equals(flag)"), "RELEASED 白名单还在");
        assertTrue(body.contains("evidence == null || evidence.isBlank()"), "evidence 空校验还在");
        assertTrue(body.contains("必须提供发版证据"), "空证据报错文案还在");
    }

    private static String stripComments(String s) {
        return s.replaceAll("(?s)/\\*.*?\\*/", "").replaceAll("(?m)//.*$", "");
    }
}
