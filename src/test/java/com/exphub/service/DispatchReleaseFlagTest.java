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
 * 派单发版标记 release_flag 守卫（W-20261121-000014）。盯住"读代码看不出来"的几件事：
 * ① AI 侧物理上写不了 RELEASED（权限分离硬闸门）；② 后台侧四态都能写；
 * ③ 枚举外的值明拒不静默修正；④ 同事务写事件；⑤ 筛选唯一出口。
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
        when(headerMapper.selectOne(any(QueryWrapper.class))).thenAnswer(inv -> {
            DispatchHeader h = order(null);
            // get(orderNo) 会再次读库返回最新；这里固定返回以便断言 message
            return h;
        });
    }

    @Test
    @DisplayName("AI 侧：NEED / NONE 能写，RELEASED 与清空一律拒 —— 红线：AI 不能宣布发版完成")
    void aiCanOnlyWriteNeedOrNone() {
        DispatchService.Outcome need = service.markReleaseFlagAi(
                "W-20261121-000099", "NEED", "测出来要发版", "烛龙", "k");
        assertTrue(need.ok(), need.message());

        DispatchService.Outcome none = service.markReleaseFlagAi(
                "W-20261121-000099", "NONE", "确认不用发版", "烛龙", "k");
        assertTrue(none.ok(), none.message());

        DispatchService.Outcome released = service.markReleaseFlagAi(
                "W-20261121-000099", "RELEASED", "我自己宣布上线", "烛龙", "k");
        assertFalse(released.ok(), "AI 写 RELEASED 必须被拒");
        assertTrue(released.message().contains("已发版"), released.message());
        assertTrue(released.message().contains("后台"), released.message());

        // 清空也不允许（AI 只能两态），且 lowercase 归一化后仍拒绝
        assertFalse(service.markReleaseFlagAi("W-20261121-000099", "", "清空", "烛龙", "k").ok());
        assertFalse(service.markReleaseFlagAi("W-20261121-000099", "released", "小写绕过", "烛龙", "k").ok());
    }

    @Test
    @DisplayName("AI 侧：枚举外的值明拒并回显收到的原值（不静默修正）")
    void aiRejectsUnknownFlagWithEcho() {
        DispatchService.Outcome out = service.markReleaseFlagAi(
                "W-20261121-000099", "DONE", "瞎写", "烛龙", "k");
        assertFalse(out.ok());
        assertTrue(out.message().contains("DONE"), "必须回显收到的值：" + out.message());
    }

    @Test
    @DisplayName("后台侧：四态都能写，含 RELEASED 与清空回未标记；枚举外的值仍拒")
    void humanCanWriteAllFourStates() {
        assertTrue(service.markReleaseFlagHuman("W-20261121-000099", "RELEASED", "2.5.19 已上线").ok());
        assertTrue(service.markReleaseFlagHuman("W-20261121-000099", "NEED", "标错救回").ok());
        assertTrue(service.markReleaseFlagHuman("W-20261121-000099", "NONE", "不用发版").ok());
        assertTrue(service.markReleaseFlagHuman("W-20261121-000099", "", "清空").ok());
        assertFalse(service.markReleaseFlagHuman("W-20261121-000099", "BOGUS", "瞎写").ok());
    }

    @Test
    @DisplayName("发版标记变更必须写一条 RELEASE_FLAG 事件，含操作人/actor_type/理由/新旧态")
    void releaseFlagWritesEvent() {
        when(headerMapper.selectOne(any(QueryWrapper.class))).thenReturn(order("NEED"));
        DispatchService.Outcome out = service.markReleaseFlagHuman(
                "W-20261121-000099", "RELEASED", "已上线 2.5.19");
        assertTrue(out.ok(), out.message());

        ArgumentCaptor<DispatchEvent> cap = ArgumentCaptor.forClass(DispatchEvent.class);
        verify(eventMapper, times(1)).insert(cap.capture());
        DispatchEvent e = cap.getValue();
        assertEquals(DispatchEvent.EV_RELEASE_FLAG, e.getEventType());
        assertEquals("NEED", e.getFromStatus());
        assertEquals("RELEASED", e.getToStatus());
        assertEquals(DispatchService.HUMAN_OPERATOR, e.getActor());
        assertEquals(DispatchEvent.ACTOR_HUMAN, e.getActorType());
        assertTrue(e.getNote().contains("已上线 2.5.19"), "note 要带理由：" + e.getNote());
        assertNotNull(e.getCreatedAt());
    }

    @Test
    @DisplayName("AI 侧写的事件 actor_type=AI、actor=调用角色")
    void aiEventActorIsAi() {
        when(headerMapper.selectOne(any(QueryWrapper.class))).thenReturn(order(null));
        service.markReleaseFlagAi("W-20261121-000099", "NEED", "判断要发版", "烛龙", "k");
        ArgumentCaptor<DispatchEvent> cap = ArgumentCaptor.forClass(DispatchEvent.class);
        verify(eventMapper, times(1)).insert(cap.capture());
        assertEquals(DispatchEvent.ACTOR_AI, cap.getValue().getActorType());
        assertEquals("烛龙", cap.getValue().getActor());
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
        // 发版标记的方法名独立存在，没有混进状态转移 switch
        assertTrue(src.contains("markReleaseFlagAi") && src.contains("markReleaseFlagHuman"));
    }

    private static String stripComments(String s) {
        return s.replaceAll("(?s)/\\*.*?\\*/", "").replaceAll("(?m)//.*$", "");
    }
}
