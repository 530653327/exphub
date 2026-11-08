package com.exphub.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.exphub.entity.MailCursor;
import com.exphub.entity.MailMessage;
import com.exphub.mapper.MailCursorMapper;
import com.exphub.mapper.MailMessageMapper;
import com.exphub.util.SensitiveContentGuard;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
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

    /**
     * 角色代号基准表。**单一数据源在 application.yml 的 {@code exphub.mail.known-roles}**，
     * 不写成 Java 常量 —— 名单散在代码里没人知道该改哪（2026-11-08 阁主定）。
     */
    @Value("${exphub.mail.known-roles:}")
    private String knownRolesConfig;

    /**
     * 收件人别名表，格式 {@code 别名=正名,别名2=正名2}。单一数据源在 application.yml 的
     * {@code exphub.mail.role-aliases}。
     *
     * <p><b>为什么需要它（2026-11-08，阁主 #72 / 白泽 #75 查出的真事故）</b>：`knownRoles()` 是
     * 「配置表 ∪ 库里出现过的 role」，所以一个**拼错但历史上真存在过**的代号（`zhuolong`，多一个 o）
     * 会被 dbRoles 兜底加进 accept 集合 —— 校验放行、信入库、`inbox()` 按 `to_role` 精确匹配，
     * 于是谁都收不到，且全程无声。删配置表没用（库里还有），所以只能归一。
     *
     * <p>归一只作用于**发信时**，不动历史数据：老信的 `to_role` 保持原样（改历史破「只追加不可改」），
     * 但 `thread()` 只按 thread_id 查、不校验 to_role，所以老线程照样能展开。
     */
    @Value("${exphub.mail.role-aliases:}")
    private String roleAliasesConfig;

    /** 动态角色集合的缓存（库里出现过的 role 不常变，缓存 60 秒够用）。 */
    private volatile Set<String> cachedDbRoles;
    private volatile long cachedDbRolesAt;

    private static final long ROLE_CACHE_MS = 60_000L;

    /**
     * 可用角色代号 = 配置基准表 ∪ 库里真实出现过的 role。
     *
     * <p><b>为什么还要并上库里的</b>：只认配置的话，新增助手必须先改配置、发版、重启才能收信 ——
     * 而它只要发过一封或收过一封，库里就有记录。反过来，只认库里的也不行：新助手第一次发信时
     * 库里还没有它，会把自己也拒掉（鸡生蛋）。两边并起来才没有这个死结。
     */
    public Set<String> knownRoles() {
        LinkedHashSet<String> set = new LinkedHashSet<>();
        if (knownRolesConfig != null) {
            for (String s : knownRolesConfig.split(",")) {
                String v = s.trim().toLowerCase(Locale.ROOT);
                if (!v.isEmpty()) set.add(v);
            }
        }
        for (String r : dbRoles()) {
            if (r != null && !r.isBlank()) set.add(r.trim().toLowerCase(Locale.ROOT));
        }
        return set;
    }

    /** 只取配置表的代号（**给人看的**列表）。 */
    public Set<String> displayRoles() {
        LinkedHashSet<String> set = new LinkedHashSet<>();
        if (knownRolesConfig != null) {
            for (String s : knownRolesConfig.split(",")) {
                String v = s.trim().toLowerCase(Locale.ROOT);
                if (!v.isEmpty()) set.add(v);
            }
        }
        return set;
    }

    private Set<String> dbRoles() {
        long now = System.currentTimeMillis();
        Set<String> cached = cachedDbRoles;
        if (cached != null && now - cachedDbRolesAt < ROLE_CACHE_MS) return cached;
        Set<String> fresh = new LinkedHashSet<>();
        try {
            List<String> rs = msgMapper.distinctRoles();
            if (rs != null) for (String r : rs) if (r != null) fresh.add(r);
        } catch (Exception e) {
            // 查不出来不能阻断发信：退化成「只认配置表」，并留日志
            log.warn("MailboxService.dbRoles: 查询历史角色失败，退化为仅用配置表：{}", e.getMessage());
        }
        cachedDbRoles = fresh;
        cachedDbRolesAt = now;
        return fresh;
    }

    /**
     * 收件代号是否合法（大小写不敏感）。
     *
     * <p>校的是「这个代号有没有人用」，**不是权限** —— 信箱本身是委托信任。
     * 目的是拦住「传了显示名导致信投进没人收的地方」这一类静默失败。
     */
    public boolean isKnownRole(String role) {
        if (role == null || role.isBlank()) return false;
        return knownRoles().contains(role.trim().toLowerCase(Locale.ROOT));
    }

    /** 别名表（别名 → 正名），全部小写；配置为空时返回空表，不抛错。 */
    public Map<String, String> roleAliases() {
        Map<String, String> map = new LinkedHashMap<>();
        if (roleAliasesConfig == null || roleAliasesConfig.isBlank()) return map;
        for (String pair : roleAliasesConfig.split("[,，]")) {
            if (pair == null || pair.isBlank()) continue;
            int eq = pair.indexOf('=');
            if (eq <= 0 || eq == pair.length() - 1) continue;
            String k = pair.substring(0, eq).trim().toLowerCase(Locale.ROOT);
            String v = pair.substring(eq + 1).trim().toLowerCase(Locale.ROOT);
            if (!k.isEmpty() && !v.isEmpty() && !k.equals(v)) map.put(k, v);
        }
        return map;
    }

    /**
     * 把收件人归一成正名（去空白、转小写、查别名表）。
     *
     * <p>只在空值上返回原样的空串；不认识的代号原样返回（交给 {@link #isKnownRole} 去拒），
     * 所以本方法只「修已知的拼错」，不负责放行。
     */
    public String canonicalRole(String role) {
        if (role == null) return null;
        String v = role.trim().toLowerCase(Locale.ROOT);
        if (v.isEmpty()) return v;
        String mapped = roleAliases().get(v);
        // 别名链只走一跳：a=b、b=c 时 a 归一到 b，不再递归 —— 配错循环不会把信吞进死胡同
        return mapped == null ? v : mapped;
    }

    /**
     * 某个代号「能收到什么」的全部写法 = 正名 ∪ 指向它的别名（小写）。
     *
     * <p><b>为什么光在发信时归一还不够（2026-11-08 阁主 #119）</b>：发信归一只能保证**新信**
     * 落在正名下；历史行（如 #110 的 {@code to_role=piyao}）按「只追加不可改」原则不动，
     * 而 {@code inbox()} 原本按 {@code to_role} 精确匹配 —— 只改配置的话，那封信照样烂在库里，
     * 当事人永远看不见。所以读侧也要按别名并查。
     */
    public Set<String> roleVariants(String role) {
        LinkedHashSet<String> out = new LinkedHashSet<>();
        String canon = canonicalRole(role);
        if (canon == null || canon.isBlank()) return out;
        out.add(canon);
        for (Map.Entry<String, String> e : roleAliases().entrySet()) {
            if (canon.equals(e.getValue())) out.add(e.getKey());
        }
        return out;
    }

    /** 收件人不合法时的拒绝文案：中文显示名要单独说清，不然对方下次还写中文。 */
    public String unknownRecipientMessage(String to) {
        String t = to == null ? "" : to.trim();
        StringBuilder sb = new StringBuilder();
        boolean nonAscii = t.codePoints().anyMatch(c -> c > 127);
        if (nonAscii) {
            sb.append("收件人写成了显示名「").append(t).append("」，已拒绝（未发出任何信）。\n\n");
            sb.append("角色代号一律**小写英文**、不带引号、不带中文。你要找的大概是下面某一个。\n");
        } else {
            sb.append("收件人「").append(t).append("」不是已知角色代号，已拒绝（未发出任何信）。\n\n");
        }
        sb.append("可用代号：").append(String.join(" / ", displayRoles())).append("\n\n");
        sb.append("代号表是单一数据源：`application.yml` 的 `exphub.mail.known-roles`（另并上库里已出现过的代号）。\n");
        sb.append("这条校验只在**开新线程**时生效；回信（带 inReplyTo）不拦，避免历史线程被卡死。");
        return sb.toString();
    }

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

        // 信箱正文也要过同一道闸：历史上真发生过 Key 明文扩散到消息表（#1313 第 4 条）。
        // 与 docs 共用判据，但字段名分开，回执里能一眼看出是哪一处。
        Map<String, String> mailFields = new LinkedHashMap<>();
        mailFields.put("信箱主题", subject);
        mailFields.put("信箱正文", body == null ? "" : body);
        SensitiveContentGuard.check(mailFields);

        // 2026-11-08（阁主 #76，越哥批准）：**发信时归一别名，历史数据不动**。
        // 事故链：`zhuolong`（多一个 o）在库里存在过 → 被 dbRoles 兜底进 accept 集合 →
        // 校验放行 → 入库 → inbox() 按 to_role 精确匹配 → 谁都收不到，且发件方全程无感（#22/#45/#72）。
        // 删配置表无效（库里还有），所以只能在这里把别名折成正名。
        String rawTo = toRole.trim();
        toRole = canonicalRole(rawTo);
        if (!rawTo.equalsIgnoreCase(toRole)) {
            log.info("MailboxService.send: 收件人别名归一 {} -> {}", rawTo, toRole);
        }

        // 2026-11-08（阁主 #51 拍板）：**只在校验开新线程**。
        // 根因：to 是自由文本，传了显示名（如「烛龙」）时服务端既不校验也不报错，
        // 只会存一条谁都匹配不上的记录 —— 发件方全程黑盒，还得靠对方反馈才知道发丢了（#45）。
        // 回信不校验：带 inReplyTo 时收件人已由父消息确定，把回信拒掉比发丢更伤，
        // 而且历史里已存在的拼错 role 会把老线程永久卡死。
        if (inReplyTo == null && !isKnownRole(toRole)) {
            throw new IllegalArgumentException(unknownRecipientMessage(toRole));
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
        /** 收件人。收件箱里通常是我自己；发件箱里是对方 —— 两种视角共用同一个结构，靠调用方决定怎么念。 */
        public String toRole;
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

        // 收件箱只关心「发给我的」；历史别名行（to_role=piyao）也要算进来（见 roleVariants）
        Set<String> meVariants = roleVariants(role);
        if (meVariants.isEmpty()) return new ArrayList<>();
        List<MailMessage> all = msgMapper.selectList(new QueryWrapper<MailMessage>()
                .in("to_role", meVariants)
                .orderByDesc("id")
                .last("LIMIT " + SCAN_BATCH));
        if (all.isEmpty()) return new ArrayList<>();

        // 自己发的也算线程的一部分（不然回信时看不到自己上一封），单独查一次近期自己发的
        List<MailMessage> mine = msgMapper.selectList(new QueryWrapper<MailMessage>()
                .in("from_role", meVariants)
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
            env.toRole = latest.getToRole();
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

    /**
     * 发件视角：自己发出去的线程（2026-11-08 阁主 #51 派单）。
     *
     * <p><b>为什么不加新工具</b>：MCP 工具总数冻结在 50（AGENTS.md 红线 13），
     * 所以走 {@code mail_start(sent=true)}，默认 false —— 现有调用方行为一个字不变。
     *
     * <p><b>为什么需要</b>：只有收件视角时，发完的信自己回看不到，连 to 原文都查不了。
     * #45 那次排查卡了半天，根因就是「发信方无法自证发了什么」。
     */
    public List<Envelope> outbox(String role, String q, int limit) {
        if (role == null || role.isBlank()) return new ArrayList<>();
        List<MailMessage> mine = msgMapper.selectList(new QueryWrapper<MailMessage>()
                .eq("from_role", role.trim())
                .orderByDesc("id").last("LIMIT " + SCAN_BATCH));
        if (mine.isEmpty()) return new ArrayList<>();

        // 已按 id desc，每个线程第一次出现的那条就是该线程最新的一封
        Map<String, MailMessage> latestByThread = new LinkedHashMap<>();
        for (MailMessage m : mine) latestByThread.putIfAbsent(m.getThreadId(), m);

        String kw = q == null ? null : q.trim().toLowerCase();
        List<Envelope> out = new ArrayList<>();
        for (MailMessage latest : latestByThread.values()) {
            if (kw != null && !kw.isEmpty()) {
                // 发件箱按「收件人」搜才有意义（收件箱那边按发件人搜）
                boolean hit = contains(latest.getSubject(), kw)
                        || contains(latest.getToRole(), kw)
                        || contains(latest.getMsgType(), kw);
                if (!hit) continue;
            }
            Envelope env = new Envelope();
            env.threadId = latest.getThreadId();
            env.subject = latest.getSubject();
            env.fromRole = latest.getFromRole();
            env.toRole = latest.getToRole();
            env.msgType = latest.getMsgType();
            env.latestAt = latest.getCreatedAt();
            env.latestSnippet = snippet(latest.getBody());
            env.freshCount = 0;
            env.claimed = latest.getClaimedBy() != null;
            env.claimedBy = latest.getClaimedBy();
            out.add(env);
        }
        if (out.size() > limit) out = out.subList(0, limit);
        return out;
    }

    /** 全库未读计数（只展示，不参与推信）。历史别名行同样算我的（见 roleVariants）。 */
    public int unreadCount(String role) {
        if (role == null || role.isBlank()) return 0;
        Set<String> meVariants = roleVariants(role);
        if (meVariants.isEmpty()) return 0;
        List<MailMessage> all = msgMapper.selectList(new QueryWrapper<MailMessage>()
                .in("to_role", meVariants).orderByDesc("id").last("LIMIT " + SCAN_BATCH));
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
        Set<String> meVariants = roleVariants(role);
        if (meVariants.isEmpty()) return 0;
        return msgMapper.update(null, new UpdateWrapper<MailMessage>()
                .eq("id", messageId)
                .in("to_role", meVariants)
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
