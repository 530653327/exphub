package com.exphub.service;

import com.exphub.entity.CallLog;
import com.exphub.mapper.CallLogMapper;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * P0：日志写入失败不能拖垮业务（2026-10-25）。
 *
 * 真实故障：search_experience 偶发整个请求失败，
 * 报 `Data too long for column 'detail'`——logSearch 把命中前 10 条标题拼进
 * varchar(500) 的 detail，超了；服务器 sql_mode 含 STRICT_TRANS_TABLES 直接报错，
 * 而该 insert 在搜索主流程且未捕获 → 搜索结果和日志两头空。
 *
 * 本测试不连库，只验证兜底层（截断 + 吞异常）真的存在且生效。
 */
class CallLogWriterTest {

    /** 模拟 MyBatis 抛出的超长异常，验证不会被上抛 */
    private CallLogMapper explodingMapper() {
        CallLogMapper mapper = mock(CallLogMapper.class);
        when(mapper.insert(any(CallLog.class)))
                .thenThrow(new RuntimeException("Data too long for column 'detail' at row 1"));
        return mapper;
    }

    private CallLogWriter writerWith(CallLogMapper mapper) {
        CallLogWriter w = new CallLogWriter();
        ReflectionTestUtils.setField(w, "callLogMapper", mapper);
        return w;
    }

    @Test
    void insertFailureDoesNotPropagate() {
        // 核心断言：DB 报错时 write() 返回 false，但**不抛异常**
        CallLogWriter w = writerWith(explodingMapper());
        CallLog entry = new CallLog();
        entry.setAction("SEARCH");
        entry.setDetail("随便什么内容");

        boolean ok = w.write(entry);
        assertFalse(ok, "写入失败应返回 false");
        // 若上一行抛了异常，这个测试直接红——这就是回归保护点
    }

    @Test
    void nullEntryIsIgnored() {
        CallLogWriter w = writerWith(explodingMapper());
        assertFalse(w.write(null));
    }

    @Test
    void truncatesEveryTextFieldToColumnWidth() {
        // 光改 TEXT 不够：换个字段超长，同样的坑会从别的列冒出来。
        // 这里逐个验证按列宽截断（列宽取自 call_logs 的 DDL）。
        assertEquals(64, CallLogWriter.cut(repeat("a", 100), 64).length(), "api_key varchar(64)");
        assertEquals(100, CallLogWriter.cut(repeat("a", 300), 100).length(), "caller_name varchar(100)");
        assertEquals(20, CallLogWriter.cut(repeat("a", 90), 20).length(), "action varchar(20)");
        assertEquals(50, CallLogWriter.cut(repeat("a", 90), 50).length(), "tool_name varchar(50)");
        assertEquals(500, CallLogWriter.cut(repeat("a", 900), 500).length(), "keyword varchar(500)");
        assertEquals(200, CallLogWriter.cut(repeat("a", 600), 200).length(), "doc_title varchar(200)");
    }

    @Test
    void doesNotTouchValueWithinLimit() {
        // 没超长的值必须原样返回，不能因为"防超长"把正常内容改了
        assertEquals("短内容", CallLogWriter.cut("短内容", 500));
        assertNull(CallLogWriter.cut(null, 500), "null 保持 null，不要变成空串");
    }

    @Test
    void truncationMarksOriginalLength() {
        // 截断必须留痕：看到日志的人要知道"这条是被截过的"，
        // 否则会把残缺内容当成全部（和 M18「给半条比少给几条更危险」一个道理）
        String out = CallLogWriter.cut(repeat("标", 800), 200);
        assertTrue(out.length() <= 200, "截断后不能超列宽，实际 " + out.length());
        assertTrue(out.contains("截断"), "必须标注被截断，实际：" + out);
        assertTrue(out.contains("800"), "必须标注原长度，实际：" + out);
    }

    @Test
    void realisticSearchDetailThatUsedToFailNowFits() {
        // 复现真实触发场景：10 条标题用「；」拼接。
        // 实测库里最长标题 88 字、已有 detail 最长 480 字——所以这个量级完全真实。
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 10; i++) {
            if (i > 0) {
                sb.append("；");
            }
            sb.append(repeat("标题", 30));   // 每条约 60 字 × 10 = 600+ 字，原 varchar(500) 必爆
        }
        String detail = sb.toString();
        assertTrue(detail.length() > 500, "构造的输入必须真的超过原列宽，实际 " + detail.length());

        String cut = CallLogWriter.cut(detail, 2000);
        assertEquals(detail, cut, "detail 已是 TEXT（业务上限 2000），这个量级不该被截");
        assertTrue(cut.length() < 2000, "确认在业务上限内");
    }

    private static String repeat(String s, int n) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < n; i++) {
            sb.append(s);
        }
        return sb.toString();
    }
}
