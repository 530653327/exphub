package com.exphub.service;

import com.exphub.entity.DispatchHeader;
import com.exphub.mapper.DispatchHeaderMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 后台人工建单的归属 Key（2026-11-19 修）。
 *
 * <p><b>为什么必须有这几条</b>：后台建单原来固定 {@code api_key=''}，
 * 而 MCP 查单按 Key 隔离，于是「单子建好了、审核通过了、指派了」，哨兵却一条都看不到
 * （W-20261119-000001 挂在 READY 无人接，根因就是这个）。
 *
 * <p>这条缺陷最坏的地方是**全链路都不报错**：建单成功、审核成功、页面也正常显示，
 * 只有哨兵那边「没有符合条件的单」。所以断言必须盯住「写进库的 api_key 到底是什么」，
 * 而不是「create 返回了 ok」。
 */
@ExtendWith(MockitoExtension.class)
class DispatchCreateOwnerKeyTest {

    @Mock
    private DispatchHeaderMapper headerMapper;

    @InjectMocks
    private DispatchService dispatchService;

    /** 测试用的假 Key（真实 Key 不进仓库；只校验「尾号露不露」这一行为）。 */
    private static final String SHARED_KEY = "test-shared-key-9999";

    /** 最近一张 AI 单 —— sharedDispatchKey() 就是从这里取共享 Key。 */
    private static DispatchHeader lastAiOrder() {
        DispatchHeader h = new DispatchHeader();
        h.setApiKey(SHARED_KEY);
        // orderNo 留空：nextOrderNo() 也会用到 selectOne，见到空单号会从 1 开始，不干扰断言
        return h;
    }

    private DispatchHeader createAndCapture(String creator, String origin, String apiKey) {
        DispatchService.Outcome out = dispatchService.create(
                creator, origin, "烛龙", "测试标题", "测试内容", 3, null, Map.of(), apiKey, null);
        assertTrue(out.ok(), "建单不应该失败：" + out.message());

        ArgumentCaptor<DispatchHeader> cap = ArgumentCaptor.forClass(DispatchHeader.class);
        verify(headerMapper).insert(cap.capture());
        return cap.getValue();
    }

    @Test
    @DisplayName("人工单没给 Key → 自动写派单共享 Key，哨兵才查得到")
    void humanOrderGetsSharedKey() {
        when(headerMapper.selectOne(any())).thenReturn(lastAiOrder());

        DispatchHeader saved = createAndCapture("越哥", DispatchHeader.ORIGIN_HUMAN, null);

        assertEquals(SHARED_KEY, saved.getApiKey(),
                "人工单的 api_key 还是空 —— 哨兵走 MCP 永远查不到这张单");
    }

    @Test
    @DisplayName("回执露 Key 尾号自证，但不许回显完整 Key")
    void receiptShowsKeyTailOnly() {
        when(headerMapper.selectOne(any())).thenReturn(lastAiOrder());

        DispatchService.Outcome out = dispatchService.create(
                "越哥", DispatchHeader.ORIGIN_HUMAN, "烛龙", "t", "d", 3, null, Map.of(), null, null);

        assertTrue(out.message().contains("…9999"), "回执没写归属哪把 Key，写没写进去只能查库");
        assertFalse(out.message().contains(SHARED_KEY), "回执回显了完整 API Key —— 会被日志/页面带走");
    }

    @Test
    @DisplayName("取不到共享 Key（库里一张 AI 单都没有）→ 保持空串并明确警告，不许静默")
    void missingSharedKeyIsReported() {
        // selectOne 默认返回 null：既没有 AI 单，nextOrderNo 也按第一张处理
        DispatchHeader saved = createAndCapture("越哥", DispatchHeader.ORIGIN_HUMAN, null);
        assertEquals("", saved.getApiKey(), "取不到共享 Key 时不应编一个");

        DispatchService.Outcome out = dispatchService.create(
                "越哥", DispatchHeader.ORIGIN_HUMAN, "烛龙", "t", "d", 3, null, Map.of(), null, null);
        assertTrue(out.message().contains("看不见"),
                "空 Key 的人工单哨兵看不见，回执必须说出来，否则建单的人以为派出去了：" + out.message());
    }

    @Test
    @DisplayName("AI 单自带 Key → 原样使用，绝不被共享 Key 覆盖")
    void aiOrderKeepsItsOwnKey() {
        when(headerMapper.selectOne(any())).thenReturn(lastAiOrder());

        DispatchHeader saved = createAndCapture("阁主", DispatchHeader.ORIGIN_AI, "ai-caller-key-1234");

        assertEquals("ai-caller-key-1234", saved.getApiKey(),
                "AI 单被改写成共享 Key —— 多租户下会把单子串到别人的租户里");
    }

    @Test
    @DisplayName("AI 单身份缺失（Key 为空）也不许拿共享 Key 兜底")
    void aiOrderWithoutKeyStaysEmpty() {
        when(headerMapper.selectOne(any())).thenReturn(lastAiOrder());

        DispatchHeader saved = createAndCapture("阁主", DispatchHeader.ORIGIN_AI, null);

        assertEquals("", saved.getApiKey(), "只有人工单才该兜底；AI 单兜底会让身份异常被掩盖");
    }

    @Test
    @DisplayName("人工单显式传了 Key → 按传入的写，不被共享 Key 顶掉")
    void humanOrderExplicitKeyWins() {
        DispatchHeader saved = createAndCapture("越哥", DispatchHeader.ORIGIN_HUMAN, "explicit-key-9999");
        assertEquals("explicit-key-9999", saved.getApiKey());
    }

    @Test
    @DisplayName("Key 掩码只留尾 4 位，短 Key 与空 Key 都不崩")
    void maskKeyNeverLeaks() {
        assertEquals("…9999", DispatchService.maskKey(SHARED_KEY));
        assertEquals("…abcd", DispatchService.maskKey("abcd"));
        assertEquals("（空）", DispatchService.maskKey(null));
        assertEquals("（空）", DispatchService.maskKey("  "));
    }
}
