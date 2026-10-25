package com.exphub.service;

import com.exphub.entity.Doc;
import com.exphub.entity.DocVersion;
import com.exphub.mapper.DocMapper;
import com.exphub.mapper.DocVersionMapper;
import com.exphub.mapper.ReviewCandidateMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * set_flags 与删除路径写行为（2026-10-25）。
 *
 * 两个断言都是「不写」和「多删」，靠读代码看不出来，只能在调用边界上验证：
 * 1) set_flags 绝不能写 doc_versions —— 否则整理动作每打一个标记就多一份 4KB 全文快照；
 * 2) 删除必须级联清 doc_versions —— 否则主表删了、全文快照还在库里且界面看不到。
 */
@ExtendWith(MockitoExtension.class)
class FlagsWritePathTest {

    @Mock
    private DocMapper docMapper;

    @Mock
    private DocVersionMapper versionMapper;

    @Mock
    private CallLogService callLogService;

    @Mock
    private ReviewCandidateMapper reviewCandidateMapper;

    @InjectMocks
    private DocService docService;

    private Doc existingDoc(long id, int version) {
        Doc d = new Doc();
        d.setId(id);
        d.setVersion(version);
        d.setTitle("测试文档");
        return d;
    }

    @Test
    void setFlagsNeverWritesVersionSnapshot() {
        when(docMapper.selectById(1L)).thenReturn(existingDoc(1L, 5));

        docService.setFlags(1L, List.of("needs_review"), "待复核：结论可能已过时", "阁主");

        // 这是本功能存在的全部理由：打标记不进版本表
        verify(versionMapper, never()).insert(any(DocVersion.class));
        // 用 UpdateWrapper 单条更新（updateById 对 null 字段是「跳过」而非「置空」）
        verify(docMapper).update(isNull(), any());
    }

    @Test
    void clearingFlagsAlsoWritesNoSnapshot() {
        when(docMapper.selectById(1L)).thenReturn(existingDoc(1L, 5));

        docService.setFlags(1L, List.of(), null, "阁主");

        verify(versionMapper, never()).insert(any(DocVersion.class));
    }

    @Test
    void deleteAlsoRemovesVersionSnapshots() {
        when(docMapper.selectById(3L)).thenReturn(existingDoc(3L, 2));

        docService.delete(3L);

        verify(docMapper).deleteById(3L);
        // M10 上线后「后台点删除」是正规路径，不级联就是永久孤儿：不可见、不可清、全文还在
        verify(versionMapper).delete(any());
        verify(callLogService).logDelete(3L, "测试文档");
        // D1：待判断候选也要跟着清，否则队列里永久挂着指向已删文档的候选
        verify(reviewCandidateMapper).delete(any());
    }
}
