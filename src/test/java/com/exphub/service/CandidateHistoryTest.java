package com.exphub.service;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.exphub.entity.CandidateResolutionLog;
import com.exphub.entity.ReviewCandidate;
import com.exphub.mapper.CandidateResolutionLogMapper;
import com.exphub.mapper.DocMapper;
import com.exphub.mapper.ReviewCandidateMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.when;

/**
 * 判定流水：连续 <b>8 轮</b> reopen/resolve 往返，note 不增长、历史不丢。
 *
 * <p><b>为什么是 8 轮而不是 1 轮</b>（2026-10-29 阁主验收意见，原测试被否）：
 * 上一版把用例固定成「434 字符一次往返」，而一次往返根本测不出边界 ——
 * 列宽改了 4 倍、`mergeNote` 里给旧 note 的预算仍是固定数，8 轮之后照样截断，
 * 而单轮测试仍然会通过。<b>用一次往返的字符数当上限，等于没测。</b>
 *
 * <p><b>现在的结构</b>：
 * <ul>
 *   <li>{@code review_candidates.note} = 当前判定理由，一判定一覆盖 —— 长度<b>与轮次无关</b></li>
 *   <li>{@code candidate_resolution_log} = 每次动作一行，只追加、TEXT 不截断</li>
 * </ul>
 * 于是两条断言就能钉住设计：
 * <ol>
 *   <li>8 轮之后 note 长度仍等于「最后一次说明」的长度（不随轮次膨胀）</li>
 *   <li>8 轮产生 16 条流水，第 1 条 reopen 里存着原始判定说明，<b>一字不少</b></li>
 * </ol>
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class CandidateHistoryTest {

    /** #17 线上真实的原判定说明长度量级（271 字符），用它当基准比随手造字符串更有说服力 */
    private static final String ORIGINAL_NOTE =
            "判为不同，不是重复。实测正文：#63 是 4 张地图的母设计文档（195 行），"
                    + "#67 是从其中「地图3：北部丘陵」一节抽出的单独提示词页（47 行），"
                    + "39 行逐字相同属母集-子集关系，不是两份独立重复内容。"
                    + "判 duplicate 会把母文档 #63 一起打上重复标记，错误。"
                    + "#67 独有内容仅 4 条丘陵专项生成建议（过渡版本 / 2×2 图块组合 / 风滚草动画帧 / 洞穴入口暗色），"
                    + "前两条已被 #63 的「图块拼接技巧」覆盖。→ 若要收敛，人工把剩余细节并回 #63 后再清 #67；"
                    + "本条判 distinct 让系统以后别再问这一对。";

    @Mock
    private ReviewCandidateMapper candidateMapper;
    @Mock
    private DocMapper docMapper;
    @Mock
    private DocService docService;
    @Mock
    private CandidateResolutionLogMapper resolutionLogMapper;

    private ReviewCandidateService service;

    /** 内存里那张候选表 */
    private ReviewCandidate row;
    /** 内存里那张流水表 */
    private final List<CandidateResolutionLog> logs = new ArrayList<>();
    private long logSeq = 0;

    @BeforeEach
    void setUp() {
        service = new ReviewCandidateService(candidateMapper, docMapper, docService, resolutionLogMapper);

        row = new ReviewCandidate();
        row.setId(17L);
        row.setDocA(63L);
        row.setDocB(67L);
        row.setStatus(ReviewCandidate.DISTINCT);
        row.setResolvedBy("阁主");
        row.setResolvedAt(LocalDateTime.of(2026, 10, 26, 0, 7, 36));
        row.setNote(ORIGINAL_NOTE);

        when(candidateMapper.selectById(anyLong())).thenAnswer(inv -> copy(row));
        when(candidateMapper.updateById(any(ReviewCandidate.class))).thenAnswer(inv -> {
            ReviewCandidate c = inv.getArgument(0);
            row.setStatus(c.getStatus());
            row.setNote(c.getNote());
            row.setResolvedBy(c.getResolvedBy());
            row.setResolvedAt(c.getResolvedAt());
            return 1;
        });
        // reopen 走 UpdateWrapper（MyBatis-Plus 默认忽略 null，置空必须走它）。
        // 这里按 set(...) 的调用顺序还原：status, note, resolved_by, resolved_at
        when(candidateMapper.update(any(), any())).thenAnswer(inv -> {
            Wrapper<ReviewCandidate> w = inv.getArgument(1);
            String sqlSet = w == null ? "" : String.valueOf(w.getSqlSet());
            if (sqlSet.contains("status=")) row.setStatus(ReviewCandidate.PENDING);
            if (sqlSet.contains("note=")) row.setNote(null);
            if (sqlSet.contains("resolved_by=")) row.setResolvedBy(null);
            if (sqlSet.contains("resolved_at=")) row.setResolvedAt(null);
            return 1;
        });
        when(resolutionLogMapper.insert(any(CandidateResolutionLog.class))).thenAnswer(inv -> {
            CandidateResolutionLog l = inv.getArgument(0);
            l.setId(++logSeq);
            logs.add(l);
            return 1;
        });
        when(resolutionLogMapper.selectList(any())).thenAnswer(inv -> new ArrayList<>(logs));
    }

    private ReviewCandidate copy(ReviewCandidate s) {
        ReviewCandidate c = new ReviewCandidate();
        c.setId(s.getId());
        c.setDocA(s.getDocA());
        c.setDocB(s.getDocB());
        c.setStatus(s.getStatus());
        c.setNote(s.getNote());
        c.setResolvedBy(s.getResolvedBy());
        c.setResolvedAt(s.getResolvedAt());
        return c;
    }

    @Test
    @DisplayName("8 轮往返：note 长度不随轮次增长，历史表累积 16 条")
    void eightRoundsDoNotGrowNote() {
        int noteLenBefore = row.getNote().length();
        assertTrue(noteLenBefore > 200, "基准说明应接近线上量级：" + noteLenBefore);

        for (int round = 1; round <= 8; round++) {
            ReviewCandidateService.ResolveResult r1 =
                    service.reopen(17L, "第 " + round + " 轮验收往返", "烛龙");
            assertTrue(r1.ok(), "第 " + round + " 轮 reopen 应成功：" + r1.message());
            assertEquals(ReviewCandidate.PENDING, row.getStatus(), "第 " + round + " 轮撤回后应为 PENDING");
            assertEquals(null, row.getNote(), "撤回后 note 应清空（它只存当前判定理由）");

            ReviewCandidateService.ResolveResult r2 =
                    service.resolve(17L, "distinct", "第 " + round + " 轮还原结论", "烛龙");
            assertTrue(r2.ok(), "第 " + round + " 轮 resolve 应成功：" + r2.message());
            assertEquals(ReviewCandidate.DISTINCT, row.getStatus());

            // 核心断言：note 只装「本次说明」，与轮次无关
            assertEquals("第 " + round + " 轮还原结论", row.getNote(),
                    "第 " + round + " 轮 note 应只含本次说明，不能累积历史");
            assertTrue(row.getNote().length() <= ReviewCandidateService.NOTE_MAX,
                    "第 " + round + " 轮 note 超上限：" + row.getNote().length());
        }

        assertEquals(16, logs.size(), "8 轮往返应产生 16 条流水（每轮 reopen + resolve 各 1）");

        // 原始判定说明必须完整留在第 1 条 reopen 流水里，一字不少
        CandidateResolutionLog firstReopen = logs.get(0);
        assertEquals(CandidateResolutionLog.ACT_REOPEN, firstReopen.getAction());
        assertNotNull(firstReopen.getNote());
        assertTrue(firstReopen.getNote().contains(ORIGINAL_NOTE),
                "第 1 条 reopen 必须完整存住原判定说明（不截断），实际长度 " + firstReopen.getNote().length());
        assertTrue(firstReopen.getNote().contains("判定人：阁主"), "原判定人必须记住");
        assertEquals(ReviewCandidate.DISTINCT, firstReopen.getPrevStatus());

        // 最后一轮撤回（第 8 轮 reopen）存的是「上一轮（第 7 轮）的理由」——
        // 每一版判定都如实存档，不是只有最初那一版
        CandidateResolutionLog lastReopen = logs.get(logs.size() - 2);
        assertEquals(CandidateResolutionLog.ACT_REOPEN, lastReopen.getAction());
        assertTrue(lastReopen.getNote().contains("第 7 轮还原结论"),
                "第 8 轮撤回应存第 7 轮的判定理由：" + lastReopen.getNote());
        assertTrue(lastReopen.getNote().contains("第 8 轮验收往返"), "撤回原因也要留：" + lastReopen.getNote());
    }

    @Test
    @DisplayName("8 轮之后历史仍可完整读出（不是被 note 挤掉的那种「历史」）")
    void historyReadableAfterEightRounds() {
        for (int round = 1; round <= 8; round++) {
            service.reopen(17L, "第 " + round + " 轮", "烛龙");
            service.resolve(17L, "distinct", "第 " + round + " 轮理由", "烛龙");
        }
        List<CandidateResolutionLog> h = service.history(17L, 50);
        assertEquals(16, h.size(), "history() 应返回全部 16 条");
        // 每一条 resolve 的理由都能读到，一轮都没丢
        for (int round = 1; round <= 8; round++) {
            final int r = round;
            assertTrue(h.stream().anyMatch(l -> ("第 " + r + " 轮理由").equals(l.getNote())),
                    "第 " + round + " 轮的理由应能在流水里查到");
        }
    }

    @Test
    @DisplayName("单次说明超长时只截当前说明，历史不受影响")
    void longSingleNoteDoesNotAffectHistory() {
        service.reopen(17L, "撤回一次", "烛龙");
        service.resolve(17L, "distinct", "长".repeat(5000), "烛龙");
        assertTrue(row.getNote().length() <= ReviewCandidateService.NOTE_MAX,
                "当前说明应被截到上限：" + row.getNote().length());
        // 历史里的原说明仍然完整
        assertTrue(logs.get(0).getNote().contains(ORIGINAL_NOTE), "历史不能被当前说明的截断波及");
    }
}
