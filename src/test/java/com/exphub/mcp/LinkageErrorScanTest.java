package com.exphub.mcp;

import com.exphub.service.CallLogService;
import com.exphub.service.SimilarityService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@code scan_similar_docs} 遇到 {@link LinkageError} 时，必须「有日志 + 回执能看懂」。
 *
 * <p><b>为什么单独写这条</b>（#712 实测教训）：原代码只有 {@code catch (Exception e)}，
 * 而 {@code NoClassDefFoundError} / {@code ExceptionInInitializerError} 都属 {@code Error}，
 * 根本不会被捕获 —— 于是异常直接冒泡，{@code app.log} 里一条 FAILED 都没有，
 * 客户端只收到一个裸类名 {@code com/exphub/service/SimilarityScorer$Shape}。
 * 「日志里查不到」被误读成「没发生过」，那次排查因此绕了一大圈。
 *
 * <p>这条测试不依赖数据库、不依赖真实类加载：直接把 LinkageError 打进 mock 的服务层。
 * 阁主 2026-10-27 建议的做法 —— 比等线上复发强。
 */
@ExtendWith(MockitoExtension.class)
class LinkageErrorScanTest {

    @Mock
    private SimilarityService similarityService;

    @Mock
    private CallLogService callLogService;

    @InjectMocks
    private ExpHubTools tools;

    @Test
    @DisplayName("LinkageError 不被吞：回执带引导语与类名，且计入失败调用")
    void linkageErrorIsReportedNotSwallowed() {
        when(similarityService.scan(any(), any(), any(), any(), any(), anyBoolean(), any()))
                .thenThrow(new NoClassDefFoundError("com/exphub/service/SimilarityScorer$Shape"));

        String out = tools.scanSimilarDocs(null, null, 5, null, null, true, null);

        assertTrue(out.contains("类加载/链接错误"),
                "回执必须点明是类加载/链接错误，否则读的人会当成参数问题继续调参：" + out);
        assertTrue(out.contains("SimilarityScorer$Shape"),
                "回执必须带上出问题的类名：" + out);
        assertTrue(out.contains("LINKAGE FAILED"),
                "回执要告诉人去日志里搜哪个关键字：" + out);
        assertFalse(out.startsWith("扫描失败: java.lang.NoClassDefFoundError"),
                "不能退回原来那种「裸类名 + 无引导」的形态：" + out);
        verify(callLogService).incrementFailCalls();
    }

    @Test
    @DisplayName("ExceptionInInitializerError 同样进 LinkageError 分支（它是类初始化失败的信号）")
    void initializerErrorAlsoCovered() {
        when(similarityService.scan(any(), any(), any(), any(), any(), anyBoolean(), any()))
                .thenThrow(new ExceptionInInitializerError(new IllegalStateException("boom")));

        String out = tools.scanSimilarDocs(null, null, 5, null, null, true, null);

        assertTrue(out.contains("类加载/链接错误"), out);
        verify(callLogService).incrementFailCalls();
    }

    @Test
    @DisplayName("普通 Exception 仍走原分支，不被 LinkageError 分支抢走")
    void plainExceptionKeepsOldBranch() {
        when(similarityService.scan(any(), any(), any(), any(), any(), anyBoolean(), any()))
                .thenThrow(new IllegalStateException("db down"));

        String out = tools.scanSimilarDocs(null, null, 5, null, null, true, null);

        assertTrue(out.startsWith("扫描失败: java.lang.IllegalStateException"), out);
        assertFalse(out.contains("类加载/链接错误"), out);
    }
}
