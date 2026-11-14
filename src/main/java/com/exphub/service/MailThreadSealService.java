package com.exphub.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.exphub.entity.MailMessage;
import com.exphub.entity.MailThreadState;
import com.exphub.entity.MailThreadStateLog;
import com.exphub.entity.MailWakeOffer;
import com.exphub.mapper.MailMessageMapper;
import com.exphub.mapper.MailThreadStateLogMapper;
import com.exphub.mapper.MailThreadStateMapper;
import com.exphub.mapper.MailWakeOfferMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 信箱线程封存 + 唤醒去重（2026-11-14 阁主 W-20261114-12）。
 */
@Service
public class MailThreadSealService {

    private static final Logger log = LoggerFactory.getLogger(MailThreadSealService.class);

    public static final String ST_SEALED = "SEALED";
    public static final String ST_OPEN = "OPEN";

    private static final Pattern ORDER_NO = Pattern.compile("W-\\d{8}-\\d{1,3}", Pattern.CASE_INSENSITIVE);
    private static final Pattern ORDER_LINE = Pattern.compile("^\\s*单号\\s*[:：]\\s*(W-\\d{8}-\\d{1,3})",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern TYPE_LINE = Pattern.compile("^\\s*类型\\s*[:：]\\s*(delivered|blocked)",
            Pattern.CASE_INSENSITIVE);

    @Autowired
    private MailThreadStateMapper stateMapper;

    @Autowired
    private MailThreadStateLogMapper logMapper;

    @Autowired
    private MailWakeOfferMapper offerMapper;

    @Autowired
    private MailMessageMapper msgMapper;

    @Value("${exphub.mail.wake.dedupe-window-seconds:900}")
    private long dedupeWindowSeconds = 900;

    /** 从主题 + 正文首部解析委托单号。 */
    public static String orderNoOf(String subject, String body) {
        if (body != null && !body.isBlank()) {
            String[] lines = body.split("\\R");
            int n = Math.min(lines.length, 15);
            for (int i = 0; i < n; i++) {
                Matcher m = ORDER_LINE.matcher(lines[i]);
                if (m.find()) return m.group(1).toUpperCase();
            }
        }
        if (subject != null) {
            Matcher m = ORDER_NO.matcher(subject);
            if (m.find()) return m.group().toUpperCase();
        }
        return null;
    }

    /** 回执第二行「类型: delivered|blocked」；其它形态返回 null。 */
    public static String receiptVerdict(String body) {
        if (body == null || body.isBlank()) return null;
        String[] lines = body.split("\\R");
        int n = Math.min(lines.length, 15);
        for (int i = 0; i < n; i++) {
            Matcher m = TYPE_LINE.matcher(lines[i]);
            if (m.find()) return m.group(1).toLowerCase();
        }
        return null;
    }

    /**
     * 当前封存的线程 → 封存水位（消息 id）。
     *
     * <p>水位语义：**id 大于水位的不再唤醒；id 小于等于水位的仍唤醒一次**。
     * 触发封存的那封（通常是 delivered 回执）就落在水位上 —— 它必须还能叫醒收件人一次。
     *
     * <p>读失败时返回空表（按未封存处理）：宁可多唤醒一次，也不要在故障期把所有人的信箱静默封死。
     */
    public Map<String, Long> sealedWatermarks() {
        try {
            List<MailThreadState> rows = stateMapper.selectSealed();
            Map<String, Long> m = new LinkedHashMap<>();
            if (rows != null) {
                for (MailThreadState r : rows) {
                    if (r.getThreadId() == null) continue;
                    m.put(r.getThreadId(), r.getSealFromId() == null ? 0L : r.getSealFromId());
                }
            }
            return m;
        } catch (Exception e) {
            log.warn("读取封存线程失败，按未封存处理: {}", e.toString());
            return new LinkedHashMap<>();
        }
    }

    public MailThreadState stateOf(String threadId) {
        if (threadId == null || threadId.isBlank()) return null;
        return stateMapper.selectById(threadId.trim());
    }

    public boolean isSealed(String threadId) {
        MailThreadState s = stateOf(threadId);
        return s != null && ST_SEALED.equals(s.getState());
    }

    public List<MailThreadState> statesOfOrder(String orderNo) {
        if (orderNo == null || orderNo.isBlank()) return new ArrayList<>();
        return stateMapper.selectByOrder(orderNo.trim());
    }

    public List<MailThreadState> recent(int limit) {
        return stateMapper.selectRecent(Math.max(1, Math.min(limit, 200)));
    }

    public List<MailThreadStateLog> logOf(String threadId, int limit) {
        if (threadId == null || threadId.isBlank()) return new ArrayList<>();
        return logMapper.selectByThread(threadId.trim(), Math.max(1, Math.min(limit, 100)));
    }

    public List<MailThreadStateLog> logOfOrder(String orderNo, int limit) {
        if (orderNo == null || orderNo.isBlank()) return new ArrayList<>();
        return logMapper.selectByOrder(orderNo.trim(), Math.max(1, Math.min(limit, 100)));
    }

    public List<String> threadsOfOrder(String orderNo) {
        if (orderNo == null || orderNo.isBlank()) return new ArrayList<>();
        try {
            List<String> list = msgMapper.selectThreadIdsByOrderNo(orderNo.trim());
            return list == null ? new ArrayList<>() : list;
        } catch (Exception e) {
            log.warn("按单号查线程失败 orderNo={}: {}", orderNo, e.toString());
            return new ArrayList<>();
        }
    }

    /** 封存/解锁结果。 */
    public record SealOutcome(String orderNo,
                              List<String> sealed,
                              List<String> alreadySealed,
                              List<String> skippedHumanOpen,
                              List<String> notFound,
                              List<String> orderThreads) {
        public int sealedCount() { return sealed.size(); }
        public int totalCovered() { return sealed.size() + alreadySealed.size() + skippedHumanOpen.size(); }
    }

    /** 解锁结果。 */
    public record UnsealOutcome(boolean changed, MailThreadState state, String detail) {
    }

    /** 自动封存结果（发信后置动作；失败只记日志，不影响发信）。 */
    public record AutoSealOutcome(String orderNo, SealOutcome outcome) {
    }

    /** 唤醒去重判定。 */
    public enum WakeOffer {
        /** 第一次进唤醒源（或没有单号/幂等键，不做去重）。 */
        FIRST,
        /** 去重窗口已过，本次放行重试。 */
        RETRY,
        /** 窗口内重复投递，本次吞掉。 */
        SUPPRESSED
    }

    public SealOutcome sealOrder(String orderNo, String actor, String note, boolean auto) {
        return sealOrder(orderNo, null, actor, note, auto);
    }

    /**
     * 按单号封存 + 显式补录线程。
     *
     * <p>补录不是可选的锦上添花：历史信没有 {@code order_no} 列值（本单禁止回填历史数据），
     * 只按单号查会一根都查不到 —— W-06 那 5 根老线程就是这种情况。
     */
    public SealOutcome sealOrder(String orderNo, Collection<String> extraThreads,
                                 String actor, String note, boolean auto) {
        List<String> threads = new ArrayList<>();
        if (orderNo != null) threads.addAll(threadsOfOrder(orderNo));
        return sealThreads(threads, null, orderNo, actor, note, auto, extraThreads);
    }

    public SealOutcome sealThreads(Collection<String> threads, String singleThread, String orderNo,
                                   String actor, String note, boolean auto) {
        return sealThreads(threads, singleThread, orderNo, actor, note, auto, null);
    }

    public SealOutcome sealThreads(Collection<String> threads, String singleThread, String orderNo,
                                   String actor, String note, boolean auto,
                                   Collection<String> extraThreads) {
        Set<String> target = new LinkedHashSet<>();
        if (singleThread != null && !singleThread.isBlank()) target.add(singleThread.trim());
        if (threads != null) {
            for (String t : threads) {
                if (t != null && !t.isBlank()) target.add(t.trim());
            }
        }
        if (extraThreads != null) {
            for (String t : extraThreads) {
                if (t != null && !t.isBlank()) target.add(t.trim());
            }
        }
        List<String> sealed = new ArrayList<>();
        List<String> already = new ArrayList<>();
        List<String> skippedHumanOpen = new ArrayList<>();
        List<String> notFound = new ArrayList<>();
        List<String> orderThreads = orderNo == null ? new ArrayList<>() : threadsOfOrder(orderNo);

        for (String threadId : target) {
            try {
                Long count = msgMapper.selectCount(new QueryWrapper<MailMessage>().eq("thread_id", threadId));
                if (count == null || count == 0) {
                    notFound.add(threadId);
                    continue;
                }
                MailThreadState exist = stateMapper.selectById(threadId);
                if (exist != null && ST_SEALED.equals(exist.getState())) {
                    // 已封存：不重复写、不重复留痕；只把单号补上（历史线程显式封存时可能没有）
                    already.add(threadId);
                    if (orderNo != null && !orderNo.equals(exist.getOrderNo())) {
                        exist.setOrderNo(orderNo);
                        stateMapper.updateById(exist);
                    }
                    continue;
                }
                if (exist != null && ST_OPEN.equals(exist.getState()) && auto) {
                    skippedHumanOpen.add(threadId);
                    continue;
                }
                MailThreadState row = exist == null ? new MailThreadState() : exist;
                row.setThreadId(threadId);
                if (orderNo != null) row.setOrderNo(orderNo);
                row.setState(ST_SEALED);
                // 封存水位：把「当前这条线程的最大消息 id」记下来。
                // id 大于它的不再唤醒，id 小于等于它的仍唤醒一次 —— 触发封存的那封
                // （通常是 delivered 回执）必须还能叫醒收件人一次，否则验收那一步永远推进不了。
                Long maxId = msgMapper.selectMaxIdByThread(threadId);
                row.setSealFromId(maxId == null ? 0L : maxId);
                row.setSealedAt(LocalDateTime.now());
                row.setSealedBy(actor);
                row.setSealReason(note);
                row.setUnsealedAt(null);
                row.setUnsealedBy(null);
                row.setUnsealNote(null);
                if (exist == null) {
                    stateMapper.insert(row);
                } else {
                    stateMapper.updateById(row);
                }
                appendLog(threadId, row.getOrderNo(), "SEAL", actor, note);
                sealed.add(threadId);
            } catch (Exception e) {
                log.warn("封存线程失败 thread={}: {}", threadId, e.toString());
            }
        }
        return new SealOutcome(orderNo, sealed, already, skippedHumanOpen, notFound, orderThreads);
    }

    public UnsealOutcome unseal(String threadId, String actor, String note) {
        if (threadId == null || threadId.isBlank()) {
            return new UnsealOutcome(false, null, "缺 threadId");
        }
        String id = threadId.trim();
        MailThreadState exist = stateMapper.selectById(id);
        if (exist == null) {
            return new UnsealOutcome(false, null, "这根线程从来没有被封存过");
        }
        if (ST_OPEN.equals(exist.getState())) {
            return new UnsealOutcome(false, exist, "这根线程已经是解锁状态");
        }
        exist.setState(ST_OPEN);
        exist.setUnsealedAt(LocalDateTime.now());
        exist.setUnsealedBy(actor);
        exist.setUnsealNote(note);
        stateMapper.updateById(exist);
        appendLog(id, exist.getOrderNo(), "UNSEAL", actor, note);
        return new UnsealOutcome(true, exist, "已解锁");
    }

    /** 发信后置：收到 delivered 回执 = 该单交付完成（转待验收），封存该单全部线程。 */
    public AutoSealOutcome autoSealIfDelivered(MailMessage m) {
        if (m == null) return null;
        String type = m.getMsgType();
        boolean receiptLike = "receipt".equals(type) || "accept".equals(type);
        if (!receiptLike) return null;
        if (!"delivered".equals(receiptVerdict(m.getBody()))) return null;
        String orderNo = m.getOrderNo() != null && !m.getOrderNo().isBlank()
                ? m.getOrderNo() : orderNoOf(m.getSubject(), m.getBody());
        if (orderNo == null) return null;
        SealOutcome outcome = sealOrder(orderNo, "auto:receipt-delivered#" + m.getId(),
                "收到 delivered 回执（视为该单转待验收）；trigger=" + m.getThreadId(), true);
        return new AutoSealOutcome(orderNo, outcome);
    }

    public WakeOffer claimWakeOffer(String orderNo, String clientMsgId, String threadId, Long messageId) {
        if (orderNo == null || orderNo.isBlank() || clientMsgId == null || clientMsgId.isBlank()) {
            return WakeOffer.FIRST;
        }
        String key = orderNo.trim() + "|" + clientMsgId.trim();
        try {
            if (offerMapper.tryInsert(key, orderNo.trim(), clientMsgId.trim(), threadId, messageId) == 1) {
                return WakeOffer.FIRST;
            }
            if (offerMapper.touchIfExpired(key, dedupeWindowSeconds) == 1) {
                return WakeOffer.RETRY;
            }
            return WakeOffer.SUPPRESSED;
        } catch (Exception e) {
            log.warn("唤醒去重记账失败，本次按首次处理 key={}: {}", key, e.toString());
            return WakeOffer.FIRST;
        }
    }

    public long dedupeWindowSeconds() {
        return dedupeWindowSeconds;
    }

    private void appendLog(String threadId, String orderNo, String action, String actor, String note) {
        MailThreadStateLog l = new MailThreadStateLog();
        l.setThreadId(threadId);
        l.setOrderNo(orderNo);
        l.setAction(action);
        l.setActor(actor == null ? "unknown" : actor);
        l.setNote(note);
        logMapper.insert(l);
    }
}
