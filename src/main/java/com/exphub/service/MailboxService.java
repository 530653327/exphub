package com.exphub.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.exphub.entity.MailCursor;
import com.exphub.entity.MailMessage;
import com.exphub.mapper.MailCursorMapper;
import com.exphub.mapper.MailMessageMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;
import java.util.stream.Collectors;

/**
 * 协作信箱：消息只追加、不回改，线程靠 {@code thread_id} 自聚合。
 *
 * <p>三条实现纪律（#1324 C114–C121 封口）：
 * <ol>
 *   <li><b>推信只认游标</b>：未读只是计数展示，ack 只记「处理过」，不参与去重。
 *       原因是 AI 会不会调 ack 我们控制不了。</li>
 *   <li><b>认领靠数据库原子更新</b>：并发下应用层判不出先后。</li>
 *   <li><b>信封搜索只搜信封</b>（主题/发件人/类型），不搜正文全文 ——
 *       全文检索跟语义检索一起做，不进第一版。</li>
 * </ol>
 */
@Service
public class MailboxService {

    private static final Logger log = LoggerFactory.getLogger(MailboxService.class);

    /** 信封列表一次最多扫多少条原始消息做聚合。信箱是低频通信，200 条足够覆盖近期。 */
    private static final int SCAN_BATCH = 200;

    private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("MM-dd HH:mm");

    @Autowired
    private MailMessageMapper msgMapper;

    @Autowired
    private MailCursorMapper cursorMapper;

    // ---------------------------------------------------------------- 发信

    /**
     * 发一封信。{@code fromRole} 由调用方（工具层）按配置来源填，不接受模型传入。
     *
     * @param inReplyTo 父消息 ID；有值时线程从父消息推导，无值时服务端生成新线程
     */
    public MailMessage send(String fromRole, String toRole, String subject, String body,
                            Long inReplyTo, String msgType, String clientMsgId,
                            String configSourceId) {
        if (fromRole == null || fromRole.isBlank()) {
            throw new IllegalArgumentException("没有角色，发不了信：客户端配置里没有声明角色");
        }
        if (toRole == null || toRole.isBlank()) {
            throw new IllegalArgumentException("收件人是空的");
        }
        if (subject == null || subject.isBlank()) {
            throw new IllegalArgumentException("主题是空的");
        }

        // 幂等：重试不重复发（工具超时重试很常见）
        if (clientMsgId != null && !clientMsgId.isBlank()) {
            MailMessage exist = msgMapper.selectOne(new QueryWrapper<MailMessage>()
                    .eq("client_msg_id", clientMsgId).last("LIMIT 1"));
            if (exist != null) {
                log.info("MailboxService.send: 幂等命中 clientMsgId={}, id={}", clientMsgId, exist.getId());
                return exist;
            }
        }

        String threadId;
        if (inReplyTo != null) {
            MailMessage parent = msgMapper.selectById(inReplyTo);
            if (parent == null) {
                throw new IllegalArgumentException("父消息不存在：#" + inReplyTo + "（不新建线程，避免回信开成孤儿）");
            }
            threadId = parent.getThreadId();
        } else {
            threadId = newThreadId();
        }

        MailMessage m = new MailMessage();
        m.setThreadId(threadId);
        m.setInReplyTo(inReplyTo);
        m.setFromRole(fromRole.trim());
        m.setToRole(toRole.trim());
        m.setMsgType(normalizeType(msgType));
        m.setSubject(subject.trim());
        m.setBody(body == null ? "" : body);
        m.setClientMsgId(blankToNull(clientMsgId));
        m.setConfigSourceId(blankToNull(configSourceId));
        msgMapper.insert(m);

        // 发件人自己的游标推到这条：自己写的不用再「未读」给自己
        advanceCursor(fromRole.trim(), threadId, m.getId());
        log.info("MailboxService.send: {} -> {}, thread={}, id={}", fromRole, toRole, threadId, m.getId());
        return m;
    }

    private String newThreadId() {
        return "T" + LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyMMddHHmmss"))
                + "-" + String.format("%03d", ThreadLocalRandom.current().nextInt(1000));
    }

    private String normalizeType(String t) {
        if (t == null || t.isBlank()) return "note";
        String v = t.trim().toLowerCase();
        switch (v) {
            case "dispatch": case "派单": return "dispatch";
            case "receipt": case "回执": return "receipt";
            case "accept": case "验收": return "accept";
            case "fix": case "更正": return "fix";
            default: return "note";
        }
    }

    private String blankToNull(String s) {
        return (s == null || s.isBlank()) ? null : s.trim();
    }

    // ---------------------------------------------------------------- 收件箱

    /** 一根线程的信封（列表行）。 */
    public static class Envelope {
        public String threadId;
        public String subject;
        public String fromRole;
        public String msgType;
        public LocalDateTime latestAt;
        public String latestSnippet;
        public int freshCount;   // 游标之后的新消息数
        public boolean claimed;
        public String claimedBy;
    }

    /**
     * 开工信封列表：按线程聚合，只给「游标之后有新消息」的线程。
     *
     * @param q 可选关键词，只匹配主题 / 发件人 / 类型，不搜正文
     */
    public List<Envelope> inbox(String role, String q, int limit, boolean includeRead) {
        if (role == null || role.isBlank()) return new ArrayList<>();

        // 收件箱只关心「发给我的」
        List<MailMessage> all = msgMapper.selectList(new QueryWrapper<MailMessage>()
                .eq("to_role", role.trim())
                .orderByDesc("id")
                .last("LIMIT " + SCAN_BATCH));
        if (all.isEmpty()) return new ArrayList<>();

        // 自己发的也算线程的一部分（不然回信时看不到自己上一封），单独查一次近期自己发的
        List<MailMessage> mine = msgMapper.selectList(new QueryWrapper<MailMessage>()
                .eq("from_role", role.trim())
                .orderByDesc("id")
                .last("LIMIT " + SCAN_BATCH));

        Map<String, List<MailMessage>> byThread = new LinkedHashMap<>();
        for (MailMessage m : all) {
            byThread.computeIfAbsent(m.getThreadId(), k -> new ArrayList<>()).add(m);
        }
        Map<String, MailMessage> latestMine = new LinkedHashMap<>();
        for (MailMessage m : mine) {
            latestMine.putIfAbsent(m.getThreadId(), m);
        }

        List<Envelope> out = new ArrayList<>();
        for (Map.Entry<String, List<MailMessage>> e : byThread.entrySet()) {
            String threadId = e.getKey();
            List<MailMessage> msgs = e.getValue();
            MailMessage latest = msgs.get(0); // 已按 id desc
            MailMessage newestAny = latest;
            MailMessage mineInThread = latestMine.get(threadId);
            if (mineInThread != null && mineInThread.getId() > newestAny.getId()) {
                newestAny = mineInThread;
            }

            long cursor = cursorOf(role.trim(), threadId);
            int fresh = (int) msgs.stream().filter(m -> m.getId() > cursor).count();
            if (fresh == 0 && !includeRead) continue;

            String kw = q == null ? null : q.trim().toLowerCase();
            if (kw != null && !kw.isEmpty()) {
                boolean hit = contains(latest.getSubject(), kw)
                        || contains(latest.getFromRole(), kw)
                        || contains(latest.getMsgType(), kw);
                if (!hit) continue;
            }

            Envelope env = new Envelope();
            env.threadId = threadId;
            env.subject = latest.getSubject();
            env.fromRole = latest.getFromRole();
            env.msgType = latest.getMsgType();
            env.latestAt = newestAny.getCreatedAt();
            env.latestSnippet = snippet(newestAny.getBody());
            env.freshCount = fresh;
            env.claimed = latest.getClaimedBy() != null;
            env.claimedBy = latest.getClaimedBy();
            out.add(env);
        }

        out.sort(Comparator.comparingLong((Envelope x) -> idOfThread(x.threadId, byThread, latestMine)).reversed());
        if (out.size() > limit) out = out.subList(0, limit);
        return out;
    }

    private long idOfThread(String threadId, Map<String, List<MailMessage>> byThread,
                            Map<String, MailMessage> latestMine) {
        long id = 0;
        List<MailMessage> msgs = byThread.get(threadId);
        if (msgs != null && !msgs.isEmpty()) id = msgs.get(0).getId();
        MailMessage mine = latestMine.get(threadId);
        if (mine != null && mine.getId() > id) id = mine.getId();
        return id;
    }

    /** 全库未读计数（只展示，不参与推信）。 */
    public int unreadCount(String role) {
        if (role == null || role.isBlank()) return 0;
        List<MailMessage> all = msgMapper.selectList(new QueryWrapper<MailMessage>()
                .eq("to_role", role.trim()).orderByDesc("id").last("LIMIT " + SCAN_BATCH));
        int n = 0;
        for (MailMessage m : all) {
            if (m.getId() > cursorOf(role.trim(), m.getThreadId())) n++;
        }
        return n;
    }

    // ---------------------------------------------------------------- 读线程

    public String threadSubject(String threadId) {
        MailMessage first = msgMapper.selectOne(new QueryWrapper<MailMessage>()
                .eq("thread_id", threadId).orderByAsc("id").last("LIMIT 1"));
        return first == null ? "" : first.getSubject();
    }

    /**
     * 读一根线程。
     *
     * @param full true=从头发（分页），false=只给游标之后的新消息
     * @return 消息（按 id 升序）
     */
    public List<MailMessage> thread(String role, String threadId, boolean full, int limit) {
        long cursor = full ? 0 : cursorOf(role.trim(), threadId);
        List<MailMessage> list = msgMapper.selectList(new QueryWrapper<MailMessage>()
                .eq("thread_id", threadId).gt("id", cursor)
                .orderByAsc("id").last("LIMIT " + limit));
        // 读完推游标：去重靠服务端，不靠模型记性
        if (!list.isEmpty()) {
            advanceCursor(role.trim(), threadId, list.get(list.size() - 1).getId());
        }
        return list;
    }

    /** 线程里的总条数（用于「还有几封」提示）。 */
    public long countThread(String threadId) {
        return msgMapper.selectCount(new QueryWrapper<MailMessage>().eq("thread_id", threadId));
    }

    // ---------------------------------------------------------------- 认领

    /** @return 1=认领成功；0=已被别人认领（或消息不存在） */
    public int claim(Long messageId, String role) {
        return msgMapper.claim(messageId, role);
    }

    /**
     * 标记「我处理过」。只写 processed_at，<b>不推游标、不参与去重</b>。
     * 限定只标发给自己的信，避免顺手标了别人的。
     */
    public int markProcessed(Long messageId, String role) {
        if (messageId == null || role == null || role.isBlank()) return 0;
        return msgMapper.update(null, new UpdateWrapper<MailMessage>()
                .eq("id", messageId)
                .eq("to_role", role.trim())
                .isNull("processed_at")
                .set("processed_at", LocalDateTime.now()));
    }

    public MailMessage byId(Long id) {
        return msgMapper.selectById(id);
    }

    // ---------------------------------------------------------------- 游标

    public long cursorOf(String role, String threadId) {
        MailCursor c = cursorMapper.selectOne(new QueryWrapper<MailCursor>()
                .eq("role", role).eq("thread_id", threadId).last("LIMIT 1"));
        return c == null || c.getLastReadId() == null ? 0 : c.getLastReadId();
    }

    /** 只前进不后退；并发下用条件更新，最多插一条。 */
    public void advanceCursor(String role, String threadId, long messageId) {
        if (role == null || role.isBlank()) return;
        int n = cursorMapper.update(null, new UpdateWrapper<MailCursor>()
                .eq("role", role).eq("thread_id", threadId)
                .lt("last_read_id", messageId)
                .set("last_read_id", messageId));
        if (n > 0) return;
        MailCursor exist = cursorMapper.selectOne(new QueryWrapper<MailCursor>()
                .eq("role", role).eq("thread_id", threadId).last("LIMIT 1"));
        if (exist != null) return; // 已存在且不小于目标值
        try {
            MailCursor c = new MailCursor();
            c.setRole(role);
            c.setThreadId(threadId);
            c.setLastReadId(messageId);
            cursorMapper.insert(c);
        } catch (Exception dup) {
            // 并发插入撞主键：说明别的请求已建，条件更新那条路已经覆盖
            log.debug("advanceCursor: concurrent insert ignored, role={}, thread={}", role, threadId);
        }
    }

    // ---------------------------------------------------------------- 小工具

    private boolean contains(String s, String kw) {
        return s != null && s.toLowerCase().contains(kw);
    }

    private String snippet(String body) {
        if (body == null) return "";
        String flat = body.replaceAll("\\s+", " ").trim();
        return flat.length() <= 100 ? flat : flat.substring(0, 100) + "…";
    }

    public static String time(LocalDateTime t) {
        return t == null ? "-" : t.format(TS);
    }

    /** 给工具层用的角色去重（逗号分隔）辅助。 */
    public static List<String> splitRoles(String s) {
        if (s == null || s.isBlank()) return new ArrayList<>();
        return java.util.Arrays.stream(s.split("[,，\\s]+"))
                .filter(x -> !x.isBlank()).distinct().collect(Collectors.toList());
    }
}
