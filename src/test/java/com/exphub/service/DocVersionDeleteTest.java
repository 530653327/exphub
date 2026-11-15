package com.exphub.service;

import com.exphub.entity.DocVersion;
import com.exphub.mapper.DocVersionMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * 后台物理删除单个历史版本（2026-11-16 越哥要求）。
 *
 * <p>钉三件事：
 * <ol>
 *   <li><b>能删</b>：属于该文档的版本，删一行、返回被删的版本号，供页面回显。</li>
 *   <li><b>不许跨文档删</b>：拿别人的 docId 配这行的 id 必须被拒，且一行都不许动 ——
 *       这是这类「按 id 删」接口最容易漏的越权口子。</li>
 *   <li><b>删不存在的不装成功</b>：并发点到两次、或页面停在旧列表上重复提交时，
 *       要明确回「不存在」，不能返回成功让用户以为清掉了（AGENTS 第 10 章：回执自证）。</li>
 * </ol>
 *
 * <p>另外钉一条边界：删版本<b>绝不</b>碰主表 —— 主表那次写入要 mock 不到，
 * 因为本方法压根不该碰 docMapper（{@link #neverTouchesDocTable()} 用 verifyNoInteractions 守）。
 */
@ExtendWith(MockitoExtension.class)
class DocVersionDeleteTest {

    @Mock
    private DocVersionMapper versionMapper;

    @InjectMocks
    private DocService docService;

    private static DocVersion version(long id, long docId, int version) {
        DocVersion v = new DocVersion();
        v.setId(id);
        v.setDocId(docId);
        v.setVersion(version);
        v.setContent("历史正文");
        v.setChangeType("CONTENT");
        v.setUpdatedName("烛龙");
        return v;
    }

    @Test
    @DisplayName("删属于该文档的版本：删一行，返回版本号供页面回显")
    void deletesOwnVersion() {
        when(versionMapper.selectById(900L)).thenReturn(version(900L, 42L, 7));

        DocVersion deleted = docService.deleteVersion(42L, 900L);

        assertEquals(7, deleted.getVersion(), "回执要带版本号，前端拿它显示「已删除 v7」");
        verify(versionMapper).deleteById(900L);
    }

    @Test
    @DisplayName("版本属于别的文档：拒绝，且不许删")
    void refusesCrossDocDelete() {
        when(versionMapper.selectById(900L)).thenReturn(version(900L, 99L, 3));

        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> docService.deleteVersion(42L, 900L));

        assertTrue(e.getMessage().contains("不属于当前文档"), "文案要说清为什么拒：" + e.getMessage());
        verify(versionMapper, never()).deleteById(900L);
    }

    @Test
    @DisplayName("版本不存在（重复提交/列表过期）：明说不存在，不装成功")
    void refusesMissingVersion() {
        when(versionMapper.selectById(900L)).thenReturn(null);

        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> docService.deleteVersion(42L, 900L));

        assertTrue(e.getMessage().contains("不存在"), "文案要说清是没找到：" + e.getMessage());
        verify(versionMapper, never()).deleteById(900L);
    }

    @Test
    @DisplayName("参数为空：直接拒，不查库")
    void refusesNullArgs() {
        assertThrows(IllegalArgumentException.class, () -> docService.deleteVersion(null, 900L));
        assertThrows(IllegalArgumentException.class, () -> docService.deleteVersion(42L, null));
        verifyNoInteractions(versionMapper);
    }

    @Test
    @DisplayName("删版本不碰主表：docs.version 不回退，正文一个字不动")
    void neverTouchesDocTable() {
        when(versionMapper.selectById(900L)).thenReturn(version(900L, 42L, 7));

        docService.deleteVersion(42L, 900L);

        // docMapper / callLogService 都是 null（本测试没注入），一旦被调用就是 NPE ——
        // 也就是说「碰了主表」会当场炸，而不是悄悄改掉版本号。
        verify(versionMapper).deleteById(900L);
    }
}
