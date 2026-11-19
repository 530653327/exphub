package com.exphub.service;

import com.exphub.entity.DispatchHeader;
import com.exphub.entity.Project;
import com.exphub.mapper.DispatchHeaderMapper;
import com.exphub.mapper.DispatchItemMapper;
import com.exphub.mapper.ProjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/**
 * 发版版本号取号（2026-11-19 派单 W-20261119-000008）。
 *
 * <p><b>为什么这些断言必须存在</b>：W-20261119-000006（忆阁）没填版本号，落成 1.0.3，
 * 而线上实际是 3.4.x。整条链路（建单 → 审核 → 派单 → 发版）都不报错，
 * 只有到发版那一刻才发现号是错的。所以断言要盯住三件具体的事：
 * <ol>
 *   <li>基准不是「最近一张」（id 最大），而是「数值最大」+ 项目当前版本；</li>
 *   <li>版本比较按数字段，1.0.10 不许排在 1.0.9 前面；</li>
 *   <li>算不出基准时**明拒**，不许静默给 1.0.0。</li>
 * </ol>
 */
@ExtendWith(MockitoExtension.class)
class DispatchVersionTest {

    @Mock
    private DispatchHeaderMapper headerMapper;

    @Mock
    private DispatchItemMapper itemMapper;

    @Mock
    private ProjectMapper projectMapper;

    @InjectMocks
    private DispatchService dispatchService;

    private static Project project(long id, String name, String currentVersion) {
        Project p = new Project();
        p.setId(id);
        p.setName(name);
        p.setCurrentVersion(currentVersion);
        return p;
    }

    private static DispatchHeader order(String no, long id, String version) {
        DispatchHeader h = new DispatchHeader();
        h.setId(id);
        h.setOrderNo(no);
        h.setVersion(version);
        h.setCreatedAt(LocalDateTime.now());
        return h;
    }

    // ==================== 纯函数：末尾 +1 / 数字段比较 ====================

    @Test
    @DisplayName("末尾连续数字 +1，前面原样保留；前导零不跳格式")
    void bumpTailKeepsFormat() {
        assertEquals("3.4.65", DispatchService.bumpTail("3.4.64"));
        assertEquals("2.5.12", DispatchService.bumpTail("2.5.11"));
        assertEquals("v2", DispatchService.bumpTail("v1"));
        assertEquals("02", DispatchService.bumpTail("01"));
        assertEquals("3.4.99", DispatchService.bumpTail("3.4.98"));
    }

    @Test
    @DisplayName("版本按数字段比较：1.0.10 > 1.0.9，不能按字符串比")
    void compareVersionsIsNumeric() {
        assertTrue(DispatchService.compareVersions("1.0.10", "1.0.9") > 0,
                "字符串比较会把 1.0.10 排在 1.0.9 前面，取最大历史版本时会退到旧号");
        assertTrue(DispatchService.compareVersions("3.4.98", "3.4.64") > 0);
        assertEquals(0, DispatchService.compareVersions("3.4", "3.4.0"));
        assertTrue(DispatchService.compareVersions("2.5.12", "2.5.9") > 0);
    }

    // ==================== 基准选择 ====================

    @Test
    @DisplayName("没项目 → 明确报「无法确定基准」，不给 1.0.0")
    void noProjectIsRejected() {
        DispatchService.VersionBasis b = dispatchService.suggestVersionInfo(null);
        assertFalse(b.ok());
        assertNull(b.value(), "没有项目时编一个版本号，就是下一个 1.0.3");
        assertTrue(b.problem().contains("没有指定项目"));
        assertTrue(dispatchService.suggestVersion(null).isEmpty());
    }

    @Test
    @DisplayName("项目不存在 → 拒绝，不拿全局历史兜底")
    void missingProjectIsRejected() {
        when(projectMapper.selectById(99L)).thenReturn(null);
        DispatchService.VersionBasis b = dispatchService.suggestVersionInfo(99L);
        assertFalse(b.ok());
        assertTrue(b.problem().contains("不存在"));
    }

    @Test
    @DisplayName("忆阁场景：项目当前版本 3.4.98 + 历史里全是 1.0.x → 得 3.4.99")
    void projectCurrentVersionBeatsWrongHistory() {
        when(projectMapper.selectById(6L)).thenReturn(project(6L, "忆阁 MemHaven", "3.4.98"));
        when(headerMapper.selectList(any())).thenReturn(List.of(
                order("W-20261117-000003", 19L, "1.0.1"),
                order("W-20261117-000012", 28L, "1.0.2")));

        DispatchService.VersionBasis b = dispatchService.suggestVersionInfo(6L);
        assertTrue(b.ok(), b.problem());
        assertEquals("3.4.99", b.value(), "又按 1.0.x 的错号段取号，问题原样复发");
        assertTrue(b.basis().contains("3.4.98"), "回执必须写明基准来自哪：" + b.basis());
    }

    @Test
    @DisplayName("历史单最大版本按数值取：1.0.9 与 1.0.10 → 1.0.11")
    void historyMaxUsesNumericOrder() {
        when(projectMapper.selectById(6L)).thenReturn(project(6L, "忆阁", null));
        when(headerMapper.selectList(any())).thenReturn(List.of(
                order("W-1", 1L, "1.0.9"),
                order("W-2", 2L, "1.0.10")));

        DispatchService.VersionBasis b = dispatchService.suggestVersionInfo(6L);
        assertTrue(b.ok(), b.problem());
        assertEquals("1.0.11", b.value(), "按字符串或按 id 取最近都会得到 1.0.10");
    }

    @Test
    @DisplayName("历史单号比当前版本更新时，取更大的那个（当前版本没及时更新也不倒退）")
    void newerHistoryWins() {
        when(projectMapper.selectById(6L)).thenReturn(project(6L, "忆阁", "3.4.90"));
        when(headerMapper.selectList(any())).thenReturn(List.of(order("W-9", 9L, "3.4.98")));

        DispatchService.VersionBasis b = dispatchService.suggestVersionInfo(6L);
        assertEquals("3.4.99", b.value());
        assertTrue(b.basis().contains("W-9"));
    }

    @Test
    @DisplayName("项目既没当前版本也没历史 → 明拒，要求先填当前版本")
    void noBasisAtAllIsRejected() {
        when(projectMapper.selectById(6L)).thenReturn(project(6L, "忆阁", ""));
        when(headerMapper.selectList(any())).thenReturn(List.of());

        DispatchService.VersionBasis b = dispatchService.suggestVersionInfo(6L);
        assertFalse(b.ok());
        assertTrue(b.problem().contains("当前版本"));
    }

    // ==================== 建单 ====================

    @Test
    @DisplayName("勾发版 + 没项目 + 没填版本 → 建单失败，且不写库")
    void createRejectsUnresolvableVersion() {
        DispatchService.Outcome out = dispatchService.create(
                "阁主", DispatchHeader.ORIGIN_AI, "白泽", "标题", "内容", 3, null,
                Map.of("release", true), "key-1", null);

        assertFalse(out.ok());
        assertTrue(out.message().contains("无法自动推算"), out.message());
        verify(headerMapper, never()).insert(any(DispatchHeader.class));
    }

    @Test
    @DisplayName("显式传版本 → 原样使用，依据记「手动指定」")
    void explicitVersionWins() {
        when(headerMapper.selectOne(any())).thenReturn(null);
        when(headerMapper.selectCount(any())).thenReturn(0L);

        DispatchService.Outcome out = dispatchService.create(
                "阁主", DispatchHeader.ORIGIN_AI, "白泽", "标题", "内容", 3, null,
                Map.of("release", true), "key-1", "3.4.99");

        assertTrue(out.ok(), out.message());
        ArgumentCaptor<DispatchHeader> cap = ArgumentCaptor.forClass(DispatchHeader.class);
        verify(headerMapper).insert(cap.capture());
        assertEquals("3.4.99", cap.getValue().getVersion());
        assertEquals("手动指定", cap.getValue().getVersionBasis());
        assertTrue(out.message().contains("3.4.99"), "回执没写版本号，等于没说取到几号");
    }

    @Test
    @DisplayName("勾发版 + 项目有当前版本 → 自动取号，并把基准写进单子和回执")
    void createAutoVersionWithBasis() {
        when(headerMapper.selectOne(any())).thenReturn(null);
        when(headerMapper.selectCount(any())).thenReturn(0L);
        when(projectMapper.selectById(6L)).thenReturn(project(6L, "忆阁 MemHaven", "3.4.98"));
        when(headerMapper.selectList(any())).thenReturn(List.of(order("W-old", 19L, "1.0.2")));

        DispatchService.Outcome out = dispatchService.create(
                "阁主", DispatchHeader.ORIGIN_AI, "白泽", "标题", "内容", 3, 6L,
                Map.of("release", true), "key-1", null);

        assertTrue(out.ok(), out.message());
        ArgumentCaptor<DispatchHeader> cap = ArgumentCaptor.forClass(DispatchHeader.class);
        verify(headerMapper).insert(cap.capture());
        assertEquals("3.4.99", cap.getValue().getVersion());
        assertTrue(cap.getValue().getVersionBasis().contains("3.4.98"));
        assertTrue(out.message().contains("项目「忆阁 MemHaven」当前版本 3.4.98"),
                "回执必须自证基准，不能只给一个结果：" + out.message());
    }

    // ==================== 验收通过回写项目当前版本 ====================

    @Test
    @DisplayName("发版单验收通过 → 把版本回写成项目当前版本（只升不降）")
    void verifyPassRemembersProjectVersion() {
        DispatchHeader h = order("W-20261119-000099", 99L, "3.4.99");
        h.setStatus(DispatchHeader.ST_VERIFYING);
        h.setProjectId(6L);
        when(headerMapper.selectOne(any())).thenReturn(h);
        when(headerMapper.update(any(), any())).thenReturn(1);
        when(projectMapper.selectById(6L)).thenReturn(project(6L, "忆阁", "3.4.98"));

        DispatchService.Outcome out = dispatchService.verifyPass("W-20261119-000099", "ok");
        assertTrue(out.ok(), out.message());

        ArgumentCaptor<com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper> cap =
                ArgumentCaptor.forClass(com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper.class);
        verify(projectMapper).update(any(), cap.capture());
        String sqlSet = cap.getValue().getSqlSet();
        assertTrue(sqlSet.contains("current_version"), sqlSet);
        assertTrue(cap.getValue().getParamNameValuePairs().values().contains("3.4.99"),
                "回写值不是 3.4.99：" + cap.getValue().getParamNameValuePairs());
    }

    @Test
    @DisplayName("验收通过的版本比项目当前版本旧 → 不回写，避免把版本改回去")
    void verifyPassNeverDowngrades() {
        DispatchHeader h = order("W-20261119-000098", 98L, "1.0.3");
        h.setStatus(DispatchHeader.ST_VERIFYING);
        h.setProjectId(6L);
        when(headerMapper.selectOne(any())).thenReturn(h);
        when(headerMapper.update(any(), any())).thenReturn(1);
        when(projectMapper.selectById(6L)).thenReturn(project(6L, "忆阁", "3.4.98"));

        dispatchService.verifyPass("W-20261119-000098", "ok");
        verify(projectMapper, never()).update(any(), any());
    }
}
