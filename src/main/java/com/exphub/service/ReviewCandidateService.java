package com.exphub.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.exphub.entity.Doc;
import com.exphub.entity.ReviewCandidate;
import com.exphub.mapper.DocMapper;
import com.exphub.mapper.ReviewCandidateMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * 记忆整理候选队列（D1 施工包 3）。
 *
 * <p>它解决的问题：相似度算出来的候选对如果只活在工具返回的文本里，
 * 下一次调用就没了，人也来不及判断。所以候选必须落库、能排队、能回溯谁判过、判成什么。
 *
 * <p>三条设计取舍：
 * <ol>
 *   <li><b>入库时规范化顺序</b>（docA &lt; docB）。否则同一对先按 (5,9) 入、再按 (9,5) 入，
 *       唯一键形同虚设。</li>
 *   <li><b>判为「不同」的对不再重新入队</b>。每周跑一遍相似度，同一对反复出现；
 *       人已经说过「这俩不一样」，就不能再去问第二遍。</li>
 *   <li><b>任何判定都不合并、不删除</b>。重复 → 只打标记；取代 → 只写指针，旧条原文完整保留。</li>
 * </ol>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ReviewCandidateService {

    private final ReviewCandidateMapper candidateMapper;
    private final DocMapper docMapper;
    private final DocService docService;

    /** 入队结果：created=false 表示这一对已经在队列里（或已被判过），没有新建 */
    public record EnqueueResult(boolean created, ReviewCandidate candidate, String message) {}

    /** 处理结果 */
    public record ResolveResult(boolean ok, String message, ReviewCandidate candidate) {}

    /**
     * 把一对候选放进队列。已存在时不新建：
     * <ul>
     *   <li>PENDING → 更新得分与理由（同样的对，新算出更高分就刷新）</li>
     *   <li>DISTINCT / SKIPPED / DUPLICATE / SUPERSEDED → 保持原状，不再打扰</li>
     * </ul>
     */
    public EnqueueResult enqueue(Long docA, Long docB, BigDecimal score, String reasons, String source) {
        if (docA == null || docB == null) {
            return new EnqueueResult(false, null, "docA 与 docB 都必填");
        }
        if (docA.equals(docB)) {
            return new EnqueueResult(false, null, "同一条经验不能与自己配对");
        }
        // 规范化顺序，否则唯一键 (doc_a, doc_b) 挡不住 (9,5) 与 (5,9) 两种写法
        Long a = Math.min(docA, docB);
        Long b = Math.max(docA, docB);

        if (docMapper.selectById(a) == null || docMapper.selectById(b) == null) {
            return new EnqueueResult(false, null, "候选对里有不存在的经验（#" + a + " / #" + b + "）");
        }

        ReviewCandidate exist = candidateMapper.selectOne(new QueryWrapper<ReviewCandidate>()
                .eq("doc_a", a).eq("doc_b", b).last("LIMIT 1"));
        if (exist != null) {
            if (ReviewCandidate.PENDING.equals(exist.getStatus())) {
                exist.setScore(score);
                exist.setReasons(reasons);
                candidateMapper.updateById(exist);
                return new EnqueueResult(false, exist, "该候选对已在队列中（#id=" + exist.getId() + "），已刷新得分与理由");
            }
            return new EnqueueResult(false, exist,
                    "该候选对已处理过（状态 " + exist.getStatus() + "），不重复入队 —— "
                            + "人已经判断过的对，不该每周再问一遍");
        }

        ReviewCandidate c = new ReviewCandidate();
        c.setDocA(a);
        c.setDocB(b);
        c.setScore(score);
        c.setReasons(reasons == null ? null : truncate(reasons, 500));
        c.setSource(source == null ? "MANUAL" : source);
        c.setStatus(ReviewCandidate.PENDING);
        c.setCreatedAt(LocalDateTime.now());
        candidateMapper.insert(c);
        return new EnqueueResult(true, c, "已入队 #id=" + c.getId() + "（#" + a + " 与 #" + b + "）");
    }

    /** 按状态列出候选，默认 PENDING，按得分倒序 */
    public List<ReviewCandidate> listByStatus(String status, int limit) {
        String st = (status == null || status.trim().isEmpty()) ? ReviewCandidate.PENDING : status.trim().toUpperCase();
        int size = (limit < 1) ? 20 : Math.min(limit, 100);
        return candidateMapper.selectList(new QueryWrapper<ReviewCandidate>()
                .eq("status", st)
                .orderByDesc("score")
                .orderByAsc("id")
                .last("LIMIT " + size));
    }

    /** 待判断数量（后台角标用） */
    public long pendingCount() {
        return candidateMapper.selectCount(new QueryWrapper<ReviewCandidate>()
                .eq("status", ReviewCandidate.PENDING));
    }

    /**
     * 处理一条候选。action 语义：
     * <ul>
     *   <li>{@code duplicate} → 两条都打 {@code duplicate} 标记（不合并）</li>
     *   <li>{@code superseded} → 较旧那条标记为被较新那条取代（只写指针）</li>
     *   <li>{@code distinct} → 判为不同，此后不再重新入队</li>
     *   <li>{@code skip} → 本次忽略</li>
     * </ul>
     */
    public ResolveResult resolve(Long candidateId, String action, String note, String by) {
        ReviewCandidate c = candidateMapper.selectById(candidateId);
        if (c == null) {
            return new ResolveResult(false, "未找到候选 #" + candidateId, null);
        }
        if (!ReviewCandidate.PENDING.equals(c.getStatus())) {
            return new ResolveResult(false, "该候选已处理过（状态 " + c.getStatus() + "），未做改动", c);
        }
        String act = action == null ? "" : action.trim().toLowerCase();
        String who = (by == null || by.trim().isEmpty()) ? "unknown" : by.trim();
        String reason = "候选 #" + candidateId + (note == null || note.trim().isEmpty() ? "" : "：" + note.trim());

        switch (act) {
            case "duplicate", "重复" -> {
                Doc a = docMapper.selectById(c.getDocA());
                Doc b = docMapper.selectById(c.getDocB());
                if (a == null || b == null) {
                    return new ResolveResult(false, "候选对里的经验已不存在（#" + c.getDocA() + " / #" + c.getDocB() + "），请改为 skip", c);
                }
                addFlag(a, "duplicate", reason, who);
                addFlag(b, "duplicate", reason, who);
                c.setStatus(ReviewCandidate.DUPLICATE);
                c.setNote(truncate("判为重复（两条互指），未合并正文", 500));
                return finish(c, who, "✅ 已判定重复：两条都打了 duplicate 标记并互指，**未合并、未删除**，原文各自保留");
            }
            case "superseded", "supersedes", "取代" -> {
                Doc a = docMapper.selectById(c.getDocA());
                Doc b = docMapper.selectById(c.getDocB());
                if (a == null || b == null) {
                    return new ResolveResult(false, "候选对里的经验已不存在（#" + c.getDocA() + " / #" + c.getDocB() + "），请改为 skip", c);
                }
                // 旧 → 新：以创建时间为准，取不到就按 ID 小的当旧的
                boolean aIsOlder = olderIsA(a, b);
                Long oldId = aIsOlder ? a.getId() : b.getId();
                Long newId = aIsOlder ? b.getId() : a.getId();
                DocService.SupersedeResult r = docService.markSuperseded(oldId, newId, who);
                if (!r.ok()) {
                    return new ResolveResult(false, "标取代失败：" + r.message(), c);
                }
                c.setStatus(ReviewCandidate.SUPERSEDED);
                c.setNote(truncate("#" + oldId + " 已被 #" + newId + " 取代", 500));
                return finish(c, who, "✅ 已标取代：#" + oldId + " → #" + newId + "（只写指针，旧条原文保留）");
            }
            case "distinct", "不同" -> {
                c.setStatus(ReviewCandidate.DISTINCT);
                c.setNote(truncate(note == null || note.trim().isEmpty() ? "判为不同" : note.trim(), 500));
                return finish(c, who, "✅ 已判为不同，未改动任何数据；此后这一对不再重新入队");
            }
            case "skip", "忽略" -> {
                c.setStatus(ReviewCandidate.SKIPPED);
                c.setNote(truncate(note == null || note.trim().isEmpty() ? "本次忽略" : note.trim(), 500));
                return finish(c, who, "✅ 已忽略，未改动任何数据；下次相似度再算到它仍会提出来");
            }
            default -> {
                return new ResolveResult(false, "无法识别的 action：" + action
                        + "。可用：duplicate / superseded / distinct / skip", c);
            }
        }
    }

    private ResolveResult finish(ReviewCandidate c, String by, String msg) {
        c.setResolvedBy(by);
        c.setResolvedAt(LocalDateTime.now());
        candidateMapper.updateById(c);
        return new ResolveResult(true, msg, c);
    }

    /** 以创建时间判断谁是「旧的」；时间取不到或相等时按 ID 小的算旧 */
    private boolean olderIsA(Doc a, Doc b) {
        LocalDateTime ta = a.getCreatedAt();
        LocalDateTime tb = b.getCreatedAt();
        if (ta != null && tb != null && !ta.isEqual(tb)) {
            return ta.isBefore(tb);
        }
        return a.getId() < b.getId();
    }

    /** 追加标记：保留原有 flags 与说明，不覆盖（覆盖会把别人之前打的标记抹掉） */
    private void addFlag(Doc doc, String flag, String note, String by) {
        List<String> flags = new ArrayList<>();
        for (String f : DocService.parseFlags(doc.getFlags())) {
            if (!flags.contains(f)) flags.add(f);
        }
        if (!flags.contains(flag)) flags.add(flag);
        String merged = note;
        String oldNote = doc.getFlagNote();
        if (oldNote != null && !oldNote.isBlank() && !oldNote.equals(note)) {
            merged = oldNote + "；" + note;
        }
        docService.setFlags(doc.getId(), flags, truncate(merged, 500), by);
    }

    private String truncate(String s, int max) {
        if (s == null) return null;
        return s.length() > max ? s.substring(0, max) : s;
    }
}
