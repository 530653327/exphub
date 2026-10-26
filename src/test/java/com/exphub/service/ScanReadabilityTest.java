package com.exphub.service;

import com.exphub.entity.ReviewCandidate;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 2026-10-26 12:0x 阁主提的三条「扫描清单读不动」的问题，逐条钉住：
 *
 * <ol>
 *   <li><b>③层名额被同一话题占满</b>：`#470/#477/#478/#464/#471/#479` 全是
 *       `list_recent_memories` 的同族记录，按分数抽 5 条 = 只抽到 1 个话题。</li>
 *   <li><b>判过的对没有任何标记</b>：`#63↔#67` 是候选 #17、`#159↔#160` 是候选 #25，
 *       都判过了，这次照样写「判 distinct」，于是他会再判一遍。</li>
 *   <li><b>只写「两侧独有 8 行」，不说哪 8 行</b>：要判断那 8 行是细节还是骨架，
 *       唯一办法是读两篇全文，两篇上千字就把上下文占满。</li>
 * </ol>
 */
class ScanReadabilityTest {

    // ---------- ① ③层按话题去重 ----------

    private static SimilarityService.Row progress(long a, long b, String ta, String tb, double score) {
        return new SimilarityService.Row(a, ta, b, tb, score, "r", null,
                SimilarityScorer.Kind.PROGRESS, 0, 0.0, "进度类");
    }

    /** 5 条同话题 + 3 条别的话题 → 老逻辑抽到 5 条全是 list_recent_memories，新逻辑每话题 1 条 */
    @Test
    void progressSamplingMustNotFillAllSlotsWithOneTopic() {
        List<SimilarityService.Row> all = new ArrayList<>();
        String[][] same = {
                {"待开发：新增 MCP 工具 list_recent_memories", "实现 MCP 工具 list_recent_memories"},
                {"实现 MCP 工具 list_recent_memories（忆阁）", "list_recent_memories 已部署上线"},
                {"list_recent_memories 改为游标分页", "list_recent_memories 已上线"},
                {"职责划分：list_recent_memories 已移交", "list_recent_memories 已上线"},
                {"list_recent_memories 游标续读", "list_recent_memories 客户端需还原"},
        };
        long id = 100;
        for (String[] t : same) {
            all.add(progress(id, id + 1, t[0], t[1], 0.62));
            id += 2;
        }
        all.add(progress(900, 901, "百炼宠物 - 地图1 生成", "百炼宠物 - 地图2 生成", 0.55));
        all.add(progress(910, 911, "回金算 - 入库单改造", "回金算 - 入库单联调", 0.54));
        all.add(progress(920, 921, "Jarvis - 语音唤醒", "Jarvis - 语音唤醒优化", 0.53));
        all.sort(SimilarityService.Row.order());

        SimilarityService.Selection sel = SimilarityService.select(all, 20, 2, 5);
        int progressShown = 0;
        java.util.Set<String> distinctTopics = new java.util.LinkedHashSet<>();
        for (SimilarityService.Row r : sel.rows()) {
            if (r.kind() == SimilarityScorer.Kind.PROGRESS) {
                progressShown++;
                distinctTopics.add(r.titleA().substring(0, Math.min(6, r.titleA().length())));
            }
        }
        // 8 条里只有 4 个话题（1 个 list_recent_memories 族 + 3 个各自独立）
        assertEquals(4, progressShown,
                "应把 4 个**不同话题**都抽出来（老逻辑会抽 5 条但全是 list_recent_memories）："
                        + progressShown);
        SimilarityService.TierDetail d = sel.detail().get(SimilarityScorer.Kind.PROGRESS);
        assertEquals(4, d.droppedSameTopic(),
                "同话题被跳过的条数必须记账（5 条族里 4 条被跳过），否则「16 对只展示 4 条」又被读成名额不够");
        assertEquals(4, distinctTopics.size(), "抽中的必须来自 4 个不同话题，不能是同一个族：" + distinctTopics);
    }

    /** 话题 token 必须抓得住工具名这类长标识符（中文 3-gram 也要有） */
    @Test
    void topicTokensMustCatchIdentifierAndChinese() {
        java.util.Set<String> a = SimilarityScorer.topicTokens("list_recent_memories 已上线");
        assertTrue(a.contains("list_recent_memories"), "长标识符本身就该是一个 token：" + a);
        java.util.Set<String> b = SimilarityScorer.topicTokens("回金算系统入库单改造");
        assertTrue(b.contains("回金算"), "中文要出 3-gram：" + b);
        assertTrue(SimilarityScorer.topicTokens(null).isEmpty(), "空标题不该抛异常");
    }

    // ---------- ② 判过的对要标出来 ----------

    @Test
    void judgedCandidateMustBeLabelledWithIdAndTime() {
        ReviewCandidate c = new ReviewCandidate();
        c.setId(17L);
        c.setDocA(63L);
        c.setDocB(67L);
        c.setStatus(ReviewCandidate.DISTINCT);
        c.setResolvedAt(LocalDateTime.of(2026, 10, 26, 0, 44));

        String s = SimilarityService.describePrev(c);
        assertTrue(s.contains("已判"), "判过的必须写「已判」：" + s);
        assertTrue(s.contains("#17"), "要能给回候选号，否则「判过」无法核对：" + s);
        assertTrue(s.contains("10-26 00:44"), "要带判定时间：" + s);
        assertTrue(s.contains("不必再判"), "要明说不必再判，否则他会重复判一遍：" + s);
    }

    @Test
    void pendingCandidateSaysWaitingNotJudged() {
        ReviewCandidate c = new ReviewCandidate();
        c.setId(26L);
        c.setStatus(ReviewCandidate.PENDING);
        String s = SimilarityService.describePrev(c);
        assertTrue(s.contains("待判断"), s);
        assertTrue(!s.contains("已判"), "还在队列里就不能说「已判」：" + s);
    }

    @Test
    void noCandidateFallsBackToDryRunText() {
        assertEquals("未入队（dryRun）", SimilarityService.describePrev(null));
    }

    // ---------- ③ 独有行样例 ----------

    /** 生产里 Sketch 都过一遍 applyDistinctLines（回填去模板行 + lineModeReliable），这里必须同样处理 */
    private static SimilarityScorer.Sketch[] pair(String bodyA, String bodyB) {
        List<SimilarityScorer.Sketch> raw = List.of(
                SimilarityScorer.Sketch.of(1L, "A", "t", null, "c", null, "k", bodyA),
                SimilarityScorer.Sketch.of(2L, "B", "t", null, "c", null, "k", bodyB));
        List<SimilarityScorer.Sketch> idx = SimilarityScorer.applyDistinctLines(raw);
        return new SimilarityScorer.Sketch[]{idx.get(0), idx.get(1)};
    }

    @Test
    void uniqueSamplesMustExcludeSharedLines() {
        String a = "环境说明：Node 20 与 JDK 21\n端口号固定为 8088\n反向代理统一走 nginx\n证书放在 /etc/ssl 下\n"
                + "部署脚本是 deploy.sh\n回滚第一步先停服务";
        String b = "部署脚本是 deploy.sh\n回滚第一步先停服务\n证书放在 /etc/ssl 下";
        SimilarityScorer.Sketch[] p = pair(a, b);
        String[] out = SimilarityScorer.uniqueLineSamples(p[0], p[1], 3);
        assertTrue(out[0].contains("环境说明"), "A 侧样例必须给出独有的行：" + out[0]);
        assertTrue(!out[0].contains("deploy.sh"), "样例里不许出现共享行（共享行会让人以为独有行数算错）：" + out[0]);
        assertTrue(SimilarityScorer.uniqueLineSamples(p[1], p[0], 3)[0].isEmpty(),
                "一侧没有独有行时必须是空串（而不是空白占位）");
    }

    @Test
    void uniqueSamplesAreCappedToThreeLines() {
        StringBuilder body = new StringBuilder();
        for (int i = 0; i < 10; i++) body.append("A 独有行 ").append(i).append("：这是一段足够长的正文内容\n");
        body.append("共享行：先停服务再部署\n共享行：查日志用 journalctl\n共享行：端口固定 8088\n");
        String other = "共享行：先停服务再部署\n共享行：查日志用 journalctl\n共享行：端口固定 8088\n";
        SimilarityScorer.Sketch[] p = pair(body.toString(), other);
        String[] out = SimilarityScorer.uniqueLineSamples(p[0], p[1], 3);
        long lines = out[0].split("｜").length;
        assertEquals(3, lines, "最多 3 行（回执篇幅有限，读的人只要判断形态）：" + out[0]);
    }
}
