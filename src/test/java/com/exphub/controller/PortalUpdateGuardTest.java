package com.exphub.controller;

import com.exphub.common.R;
import com.exphub.entity.Doc;
import com.exphub.entity.PublicUser;
import com.exphub.interceptor.PortalAuthInterceptor;
import com.exphub.mapper.DocMapper;
import com.exphub.service.DocService;
import com.exphub.service.PublicUserService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.lang.reflect.Field;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 门户「更新我的经验」PUT 的凭据拦截（2026-11-08 阁主 #134 第 2 件）。
 *
 * <p>这条路径直接 {@code docMapper.updateById}，绕开 {@code DocService.update}，
 * 是拦截上线后<b>唯一还漏的写入口</b>。阁主的要求是两条都测：
 * ① 塞敏感串必须被拦下；② 正常更新不受影响。
 *
 * <p>只断言「调用了 guard」不够 —— 真正的风险是<b>顺序</b>：拦在快照之后，
 * 含 Key 的正文已经进了主表，快照还会把它复制进 doc_versions。所以这里同时验
 * {@code updateById} 与 {@code snapshotBeforeExternalUpdate} 都没被调用。
 */
@ExtendWith(MockitoExtension.class)
class PortalUpdateGuardTest {

    @Mock private PublicUserService publicUserService;
    @Mock private DocMapper docMapper;
    @Mock private DocService docService;

    @InjectMocks private PublicUserController controller;

    private static final String API_KEY = "key-owner-1";

    private void loginAs(String apiKey) throws Exception {
        PublicUser u = new PublicUser();
        u.setId(1L);
        u.setApiKey(apiKey);
        Field f = PortalAuthInterceptor.class.getDeclaredField("currentPortalUser");
        f.setAccessible(true);
        @SuppressWarnings("unchecked")
        InheritableThreadLocal<PublicUser> tl =
                (InheritableThreadLocal<PublicUser>) f.get(null);
        tl.set(u);
    }

    @AfterEach
    void logout() throws Exception {
        Field f = PortalAuthInterceptor.class.getDeclaredField("currentPortalUser");
        f.setAccessible(true);
        @SuppressWarnings("unchecked")
        InheritableThreadLocal<PublicUser> tl =
                (InheritableThreadLocal<PublicUser>) f.get(null);
        tl.remove();
    }

    private Doc existing() {
        Doc d = new Doc();
        d.setId(9L);
        d.setApiKey(API_KEY);
        d.setVersion(3);
        d.setTitle("旧标题");
        d.setContent("旧的正文");
        d.setCallCount(5);
        return d;
    }

    @Test
    void sensitiveContentIsRejectedBeforeAnyWrite() throws Exception {
        loginAs(API_KEY);
        Doc old = existing();
        when(docMapper.selectById(9L)).thenReturn(old);

        Doc body = new Doc();
        body.setTitle("更新标题");
        // 明显是假值，但形态会命中高置信前缀（sk- + 长度足够的英数）
        body.setContent("配置里别写死：" + "sk-" + "abcdefgh12345678");

        R<Doc> r = controller.updateMyDoc(9L, body);

        assertEquals(400, r.getCode(), "含凭据的更新必须被拒绝，且是客户端错误而不是 500");
        assertNotNull(r.getMessage());
        assertTrue(r.getMessage().contains("已拒绝写入"), r.getMessage());
        assertTrue(r.getMessage().contains("重试同样内容不会通过"), "必须让人知道重试没用，改内容才有救");
        verify(docMapper, never()).updateById((Doc) any(Doc.class));
        verify(docService, never()).snapshotBeforeExternalUpdate(any(), any(), any());
    }

    @Test
    void normalUpdateStillWorks() throws Exception {
        loginAs(API_KEY);
        Doc old = existing();
        when(docMapper.selectById(9L)).thenReturn(old);

        Doc body = new Doc();
        body.setTitle("更新标题");
        body.setContent("正常的新正文，没有任何凭据。");

        R<Doc> r = controller.updateMyDoc(9L, body);

        assertEquals(0, r.getCode(), "正常更新不能被误伤：" + r.getMessage());
        verify(docService).snapshotBeforeExternalUpdate(eq(old), eq("正常的新正文，没有任何凭据。"), any());
        verify(docMapper).updateById((Doc) any(Doc.class));
        assertNotNull(controller.getMyDoc(9L));
    }
}
