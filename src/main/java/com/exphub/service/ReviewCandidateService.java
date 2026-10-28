package com.exphub.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
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

    /**
     * 得分归一化（包级静态便于单测）。
     *
     * <p>线上实测发现的坑：调用方传 `score=0` 时库里落 `0.0000`，
     * 而 `list_review_candidates` 按得分倒序 —— 0 分等于排在最后，看起来像「算了个 0」。
     * 但相似度 0 的语义是「完全不像」，它几乎一定是「没算出来就随手填了 0」。
     *
     * <p>所以 0 与负数一律当「未提供」存 NULL：**不假装它是真实得分**。
     * 超过 1 的也拒 —— 约定范围是 0~1，传 5 说明调用方口径不同，
     * 与其入库污染排序，不如拒掉并在回执里说清楚。
     *
     * @return 可入库的得分；null 表示应存 NULL（未提供 / 无效）
     */
    public static java.math.BigDecimal normalizeScore(java.math.BigDecimal score) {
        if (score == null) return null;
        if (score.compareTo(java.math.BigDecimal.ZERO) <= 0) return null;
        if (score.compareTo(java.math.BigDecimal.ONE) > 0) return null;
        return score;
    }

    /**
     * 归一化时丢弃的说明（null = 没丢弃）。
     *
     * <p>存在的理由只有一个：**回执必须自证值**。上一版回执写「已刷新得分」，
     * 但没说记成了多少 —— 于是调用方以为传了 0.92 就记了 0.92，实际库里是 0.0000，
     * 直到第二天从列表里才看出来。回执只证明动作发出去了，不证明结果对。
     *
     * <p>⚠️ 2026-10-25 第二次教训（阁主验收）：原文案写「**传了** 0」，读起来是在指责调用方传了 0。
     * 实测真相是忆阁把 `0.33` 用 `asInt()` 截成了整数 0 —— 调用方传的是小数，
     * **ExpHub 收到的才是 0**。措辞把「我这边收到什么」说成「你传了什么」，
     * 等于把链路问题推给上游，让人往错的方向查。
     * 现在改成「ExpHub 实际收到的是 X」，并在收到恰好 0 时给出整数截断的排查提示。
     */
    public static String scoreDiscardReason(java.math.BigDecimal score) {
        if (score == null) return null;
        if (score.compareTo(java.math.BigDecimal.ZERO) == 0) {
            return "ExpHub 实际收到的是 0（不是一个有效相似度：相似度 0 的语义是「完全不像」，"
                    + "真实调用里几乎一定是没算出来），已按「未提供」存 NULL，不参与排序。\n"
                    + "  ⚠️ 如果你传的是小数（如 0.33 / 0.92）却变成了 0，那是**调用链路上把小数截成了整数**："
                    + "检查 MCP 客户端的参数转换有没有用 `asInt()` / `intValue()` / `(int)` 这类取整操作"
                    + "（正确做法是 `asDouble()` 或直接保留 BigDecimal）。这一步在 ExpHub 之外，ExpHub 收不到原始小数。";
        }
        if (score.compareTo(java.math.BigDecimal.ZERO) < 0) {
            return "ExpHub 实际收到的是 " + plain(score) + "，负数不是有效相似度，已按「未提供」存 NULL";
        }
        if (score.compareTo(java.math.BigDecimal.ONE) > 0) {
            return "ExpHub 实际收到的是 " + plain(score) + "，超出约定范围 0~1，已按「未提供」存 NULL"
                    + "（若是百分制请除以 100，如 92 → 0.92）";
        }
        return null;
    }

    /** 状态的中文注解：列表里 ALL 模式下 PENDING 与 DISTINCT 混排，光看英文值难分辨 */
    public static String statusLabel(String status) {
        if (status == null) return "未知";
        return switch (status) {
            case ReviewCandidate.PENDING -> "待判断";
            case ReviewCandidate.DUPLICATE -> "已标重复";
            case ReviewCandidate.SUPERSEDED -> "已标取代";
            case ReviewCandidate.DISTINCT -> "已判不同";
            case ReviewCandidate.SKIPPED -> "已忽略";
            default -> status;
        };
    }

    /** 得分展示：固定两位小数，避免 0.9 与 0.9000 在回执里长得不一样 */
    public static String formatScore(java.math.BigDecimal score) {
        if (score == null) return "未记录";
        return score.setScale(2, java.math.RoundingMode.HALF_UP).toPlainString();
    }

    private static String plain(java.math.BigDecimal v) {
        return v.stripTrailingZeros().toPlainString();
    }

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
        // 归一化一次，后面所有写入路径都用它 —— 避免「新建时存 NULL、刷新时存 0」这种不一致
        BigDecimal validScore = normalizeScore(score);
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
                // 只在有值时刷新：线上验证时发现，后一次传来的弱理由会把前一次的强理由覆盖掉
                // （第一次写「标签交集:3；正文高度重合」，第二次随手写「又算到了」，人看到的就只剩后者）
                if (validScore != null) {
                    exist.setScore(validScore);
                }
                // 理由「先到先得」：只在为空时补写，不覆盖已有内容。
                // 为什么不是「有值就刷新」：线上验证时亲眼看到，L2 重算时随手写的一句
                // 「又算到了」把前一天详细写的「标签交集:3；正文高度重合」冲掉了。
                // 理由的价值在于可解释，被弱理由覆盖等于让下一个人重新判断一遍。
                boolean reasonKept = exist.getReasons() != null && !exist.getReasons().isBlank();
                if (!reasonKept && reasons != null && !reasons.trim().isEmpty()) {
                    exist.setReasons(truncate(reasons, 500));
                }
                // source 刻意不改：它记录「这条候选最初是谁发现的」，刷新一次就变等于丢失来源
                candidateMapper.updateById(exist);
                // 回执只说真的发生了的事：得分被规范化掉（validScore=null）时不能写「已刷新得分」，
                // 否则又是一句「动作发出去了，结果没发生」的假话。得分到底记成了多少，
                // 由工具层从 exist 里重新读出来展示。
                StringBuilder msg = new StringBuilder();
                msg.append("该候选对已在队列中（#id=").append(exist.getId()).append("）");
                if (validScore != null) {
                    msg.append("，已刷新得分");
                } else if (score != null) {
                    msg.append("，得分未改动（本次传入的值无效，见下方说明）");
                }
                if (!reasonKept) {
                    msg.append("与理由");
                } else {
                    msg.append("；原有理由保留未覆盖");
                }
                return new EnqueueResult(false, exist, msg.toString());
            }
            return new EnqueueResult(false, exist,
                    "该候选对已处理过（状态 " + exist.getStatus() + "），不重复入队 —— "
                            + "人已经判断过的对，不该每周再问一遍");
        }

        ReviewCandidate c = new ReviewCandidate();
        c.setDocA(a);
        c.setDocB(b);
        c.setScore(validScore);
        c.setReasons(reasons == null ? null : truncate(reasons, 500));
        c.setSource(source == null ? "MANUAL" : source);
        c.setStatus(ReviewCandidate.PENDING);
        c.setCreatedAt(LocalDateTime.now());
        candidateMapper.insert(c);
        return new EnqueueResult(true, c, "已入队 #id=" + c.getId() + "（#" + a + " 与 #" + b + "）");
    }

    /** 候选状态全集（用于校验与错误提示） */
    public static final java.util.Set<String> VALID_STATUSES = java.util.Set.of(
            ReviewCandidate.PENDING, ReviewCandidate.DUPLICATE, ReviewCandidate.SUPERSEDED,
            ReviewCandidate.DISTINCT, ReviewCandidate.SKIPPED);

    /**
     * 状态过滤归一化（包级静态便于单测）。
     *
     * <p>线上实测踩到的坑（阁主 2026-10-25 18:0x 发现）：传 `status="ALL"` 返回空，
     * 而同刻 `status=PENDING` 有数据 —— 因为原实现直接把 "ALL" 拼进了
     * `WHERE status='ALL'`，库里根本不存在这个值。
     *
     * <p>为什么这不是小事：`check_my_todos` 早就用 `ALL` 表示「不限状态」，
     * 同一个 `ALL` 在两个工具里语义不同，调用方一定会踩。**语义必须统一**。
     *
     * <p>同时把「非法状态值」从静默空结果改成明确报错：
     * 拼错一个字母就返回空，人会以为「队列真的空了」，而不是「我查错了」。
     *
     * @return null = 不过滤（传了 ALL）；否则返回可入库的状态值
     * @throws IllegalArgumentException 不是合法状态值
     */
    public static String normalizeStatusFilter(String status) {
        if (status == null || status.trim().isEmpty()) {
            return ReviewCandidate.PENDING;   // 默认只看待判断
        }
        String st = status.trim().toUpperCase();
        if ("ALL".equals(st)) {
            return null;                      // 不限状态
        }
        if (!VALID_STATUSES.contains(st)) {
            throw new IllegalArgumentException("无法识别的候选状态：" + status
                    + "。可用：" + String.join(" / ", ReviewCandidate.PENDING, ReviewCandidate.DUPLICATE,
                    ReviewCandidate.SUPERSEDED, ReviewCandidate.DISTINCT, ReviewCandidate.SKIPPED)
                    + "（或 ALL 表示不限状态）");
        }
        return st;
    }

    /** 按状态列出候选，默认 PENDING，按得分倒序；status=ALL 表示不限状态 */
    /** 按状态列出候选，默认 PENDING，按得分倒序 */
    public List<ReviewCandidate> listByStatus(String status, int limit) {
        String st = normalizeStatusFilter(status);   // null = 不过滤
        int size = (limit < 1) ? 20 : Math.min(limit, 100);
        QueryWrapper<ReviewCandidate> qw = new QueryWrapper<>();
        if (st != null) {
            qw.eq("status", st);
        }
        return candidateMapper.selectList(qw
                .orderByDesc("score")
                .orderByAsc("id")
                .last("LIMIT " + size));
    }

    /** 取一条候选（回执要按它对判悬空/已判） */
    public ReviewCandidate get(Long id) {
        return id == null ? null : candidateMapper.selectById(id);
    }

    /** 一对文档的稳定键（扫描回执要按「对」查历史判定，A/B 顺序不能影响命中） */
    public static String pairKey(Long a, Long b) {
        if (a == null || b == null) return "-";
        long x = Math.min(a, b), y = Math.max(a, b);
        return x + "-" + y;
    }

    /**
     * 全部候选对（键 = {@link #pairKey}）。
     *
     * <p>2026-10-26 12:0x 阁主：扫描清单里判过的对没有任何标记，他会重复判一遍。
     * 候选表量级是几十~几百条，一次全取比逐对查省事，也不会有 N+1。
     */
    public java.util.Map<String, ReviewCandidate> pairMap() {
        java.util.Map<String, ReviewCandidate> m = new java.util.LinkedHashMap<>();
        for (ReviewCandidate c : candidateMapper.selectList(new QueryWrapper<ReviewCandidate>()
                .orderByAsc("id").last("LIMIT 2000"))) {
            m.put(pairKey(c.getDocA(), c.getDocB()), c);
        }
        return m;
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

    /**
     * 撤回一次已判的候选：状态回 {@code PENDING}，让它重新参与扫描与入队。
     *
     * <p><b>为什么必须有这个动作</b>（越哥 2026-10-28 提的 P0-2）：
     * {@code distinct} 的语义是「这一对**永久**不再入队」。判错一次，将来真重复了也不会再被扫出来，
     * 而且除了候选表里那一行，没有任何地方记着「曾经判过」。整个整理链路里
     * **这是唯一的不可逆动作**（合并、删除都有撤销手段），却没有撤销入口 —— 只能靠 SQL 改库。
     *
     * <p>两条设计取舍：
     * <ul>
     *   <li><b>原判定不擦掉</b>：历史判定人 / 时间 / 说明全部拼进 {@code note}，审计线索不能因为撤回就消失</li>
     *   <li><b>只回退候选状态</b>：判定时顺手打在文档上的 {@code duplicate} 标记、{@code superseded} 指针
     *       <b>不在这里撤销</b>。它们是两个独立动作，混在一起做等于「撤回候选」顺手改正文 ——
     *       回执里必须写明这一点，否则调用方会以为全清了</li>
     * </ul>
     */
    public ResolveResult reopen(Long candidateId, String note, String by) {
        ReviewCandidate c = candidateMapper.selectById(candidateId);
        if (c == null) {
            return new ResolveResult(false, "未找到候选 #" + candidateId, null);
        }
        if (ReviewCandidate.PENDING.equals(c.getStatus())) {
            return new ResolveResult(false, "该候选本来就是 PENDING（待判断），无需撤回", c);
        }
        String prev = c.getStatus();
        String who = (by == null || by.trim().isEmpty()) ? "unknown" : by.trim();

        StringBuilder his = new StringBuilder("撤回：").append(prev).append(" → PENDING");
        if (note != null && !note.trim().isEmpty()) his.append("｜原因：").append(note.trim());
        if (c.getResolvedBy() != null) his.append("｜原判定人：").append(c.getResolvedBy());
        if (c.getResolvedAt() != null) {
            his.append("｜原判定时间：").append(c.getResolvedAt().toString().replace('T', ' '));
        }
        if (c.getNote() != null && !c.getNote().trim().isEmpty()) his.append("｜原判定说明：").append(c.getNote());

        // ⚠️ MyBatis-Plus 默认忽略 null 字段，setResolvedBy(null) 不会写库 ——
        //    想让 resolved_by / resolved_at 真的变回 NULL 必须走 UpdateWrapper.set。
        candidateMapper.update(null, new UpdateWrapper<ReviewCandidate>()
                .eq("id", candidateId)
                .set("status", ReviewCandidate.PENDING)
                .set("note", truncate(his.toString(), 500))
                .set("resolved_by", null)
                .set("resolved_at", null));

        ReviewCandidate after = candidateMapper.selectById(candidateId);
        return new ResolveResult(true,
                "✅ 已撤回：候选 #" + candidateId + " 由 " + prev + " 恢复为 PENDING（待判断）。"
                        + "下一轮 scan 会重新考虑这一对。\n"
                        + "⚠️ 只回退了**候选状态**：当初判定时写在文档上的标记（duplicate 标记 / 取代指针）**没有撤销**。"
                        + "要一并撤销请用 `set_flags` 或 `clear_superseded`。\n"
                        + "📝 原判定（" + who + "）的说明已并入本候选的 note，没有丢。",
                after);
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
