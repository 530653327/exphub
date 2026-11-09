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
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
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
     * 可投递角色清单（DB 唯一真源，表 {@code mail_roles} / {@code mail_role_aliases}）。
     *
     * <p><b>2026-11-09 阁主 T261109053740-701（P9 定案）：代号校验只在 ExpHub 做。</b>
     * 忆阁不取、不存、不校验，也不做拼错纠正 —— 名字对不对由这里说了算。
     *
     * <p>旧实现读 {@code application.yml} 的 {@code known-roles} 并 ∪「库里出现过的 role」，
     * 两处都有洞：停用一个角色后，只要历史信里还留着它的 {@code to_role}，并集就把它原地复活
     * （停用永远停不掉）；而 {@code zhuolong} 这种拼错因为「历史上真存在过」被当成合法代号，
     * 于是信入库、收件箱精确匹配不上、发件方全程无感（#22/#45/#72，#1459 同机制）。
     * 现在历史脏代号只以 {@code OBSERVED → PENDING_REVIEW} 落库等人工复核，<b>不再作为合法来源</b>。
     */
    @Autowired
    private MailRoleCatalog roleCatalog;

    /**
     * 授权链标识的长度上限，<b>与 {@code mail_messages.chain_id VARCHAR(64)} 对齐</b>。
     *
     * <p>超长直接拒，<b>不截断</b>：截断后两条不同的链可能折成同一个标识，
     * 而「第几跳」是按链算的 —— 静默截断会让深度校验作用在错误的链上（AGENTS 9c）。
     */
    private static final int MAX_CHAIN_ID_LEN = 64;

    /** 当前可投递正名（{@code mail_roles} 里 {@code status='ACTIVE'} 的汉字行，已去空白）。 */
    public Set<String> knownRoles() {
        return roleCatalog.activeRoles();
    }

    /**
     * 收件代号是否合法（大小写不敏感、别名先归一）。
     *
     * <p>校的是「这个代号现在能不能投递」，<b>不是权限</b> —— 信箱本身是委托信任。
     * 目的是拦住「传了显示名 / 拼错了 / 角色已停用」这一类<b>静默投丢</b>。
     *
     * <p>⚠️ 清单<b>读不到</b>时本方法一律返回 false。发信入口不要只调它，
     * 否则会把「服务端故障」显示成「收件人写错」—— 请走 {@link MailRoleCatalog#check}。
     */
    public boolean isKnownRole(String role) {
        return roleCatalog.isDeliverable(role);
    }

    /** 别名表（别名 → 正名），全部小写；读不到时返回空表，不抛错。 */
    public Map<String, String> roleAliases() {
        return roleCatalog.aliases();
    }

    /**
     * 把收件人归一成正名（去空白、转小写、查别名表）。
     *
     * <p>不认识的原样返回（交给校验去拒），所以本方法只「修已知的拼错」，不负责放行。
     * 别名链只走一跳：a=b、b=c 时 a 归一到 b，不再递归 —— 配错循环不会把信吞进死胡同。
     */
    public String canonicalRole(String role) {
        return roleCatalog.canonicalRole(role);
    }

    /**
     * 某个代号「能收到什么」的全部写法 = 正名 ∪ 指向它的别名（小写）。
     *
     * <p><b>为什么光在发信时归一还不够（2026-11-08 阁主 #119）</b>：发信归一只能保证**新信**
     * 落在正名下；历史行（如 #110 的 {@code to_role=piyao}）按「只追加不可改」原则不动，
     * 而 {@code inbox()} 按 {@code to_role} 匹配 —— 只归一读侧不并查的话，那封信照样烂在库里，
     * 当事人永远看不见。
     */
    public Set<String> roleVariants(String role) {
        return roleCatalog.variants(role);
    }

    /**
     * 收件人被拒时的<b>结构化错误</b>（2026-11-09 阁主 P9 第 2 条 + T261109054938-679 + T261109054529-699）。
     *
     * <p>四块：被拒收件人（原样回显）/ 不超过 {@value MailRoleCatalog#MAX_CANDIDATES} 个候选
     * / 当前生效名单（全量）/ 一句提示。<b>不拼历史脏代号</b> —— 停用与待复核在任何出口都不出现。
     *
     * <p><b>出口一律汉字</b>：候选、提示、名单都写「烛龙 / 貔貅」。被拒值原样回显（用户传了什么就显示什么），
     * 但它不是正名，不会写进库里。
     *
     * <p><b>候选和名单只用于展示</b>（阁主 T261109054529-699）：调用方不得据此自动改写收件人并重发。
     *
     * <p>清单读不到时（冷启动 / 库连不上）走单独一支：<b>明说这是 ExpHub 的故障</b>，
     * 不许说成「收件人不存在」—— 那是 #1459 把服务端故障说成用户写错的老毛病。
     */
    public String recipientRejectionMessage(String raw, MailRoleCatalog.Check check) {
        // 阁主 T261109054529-699：rejectedRole 原样回，不要清洗。trim 会让「传了什么」失真。
        String shown = raw == null ? "" : raw;
        StringBuilder sb = new StringBuilder();
        if (check.kind() == MailRoleCatalog.Check.Kind.UNAVAILABLE) {
            sb.append("收件人无法校验（未发出任何信）\n");
            sb.append("- rejectedRole（被拒收件人）：").append(code(shown)).append("\n");
            sb.append("- candidates（最多 ").append(MailRoleCatalog.MAX_CANDIDATES).append(" 个）：—\n");
            sb.append("- roleList：—（角色清单读不到，没法给名单）\n");
            sb.append("- hint：ExpHub 的角色清单暂时读不到，这不是你把收件人写错了；")
              .append("这是服务端故障，请稍后重试并报维护者，别靠改名字去试。\n");
            appendDisplayOnlyWarning(sb);
            return sb.toString();
        }

        List<String> cands = check.candidates();
        sb.append("收件人无效（未发出任何信）\n");
        sb.append("- rejectedRole（被拒收件人）：").append(code(shown)).append("\n");
        sb.append("- candidates（最多 ").append(MailRoleCatalog.MAX_CANDIDATES).append(" 个）：");
        if (cands.isEmpty()) {
            sb.append("— （没有足够接近的正名；当前可投递 ")
              .append(check.snapshot().activeRoles().size()).append(" 个）");
        } else {
            sb.append(cands.stream().map(c -> "`" + c + "`").collect(Collectors.joining("、")));
        }
        sb.append("\n");
        appendRoleList(sb, check.snapshot());
        sb.append("- hint：没有这个收件人：").append(shown.isEmpty() ? "（空）" : shown).append("。");
        if (!cands.isEmpty()) sb.append("你是想发 ").append(cands.get(0)).append("？");
        sb.append("收件人正名是汉字（如「烛龙」）；拼音别名（如 zhulong）仍可投递，会被归一到汉字。");
        sb.append("本份清单加载于 ").append(stamp(check.snapshot().loadedAt()))
          .append("（每 30 秒刷新一次，刚加的角色可能还没生效）");
        if (check.snapshot().degraded()) {
            sb.append("；⚠️ 最近一次刷新失败，用的仍是这份旧名单");
        }
        sb.append("；回信（带 inReplyTo）不走这条校验，收件人取父消息的发件人。\n");
        appendDisplayOnlyWarning(sb);
        return sb.toString();
    }

    /**
     * 拒绝回执里的「当前生效名单」。
     *
     * <p><b>全量列出，不截断</b>（阁主 T261109114542-413，越哥 2026-11-09 定）：
     * 拒绝回执本身就要能一次看全，不做条数上限、不做分页、不加省略号。
     * 停用/待复核仍由 {@link MailRoleCatalog#labelsOf} 过滤掉。
     */
    private void appendRoleList(StringBuilder sb, MailRoleCatalog.Snapshot snapshot) {
        List<String> labels = MailRoleCatalog.labelsOf(snapshot);
        if (labels.isEmpty()) {
            sb.append("- roleList：—（当前没有 ACTIVE 角色，名单为空）\n");
            return;
        }
        sb.append("- roleList（当前生效 ").append(labels.size()).append(" 个，仅展示）：")
          .append(labels.stream().map(r -> "`" + r + "`").collect(Collectors.joining("、")))
          .append("\n");
    }

    /** 「名单只用于展示」这句话只能有一处出处：成功拒绝和故障两支都调它。 */
    private static void appendDisplayOnlyWarning(StringBuilder sb) {
        sb.append("- ⚠️ 调用方不得依据本响应自动改写收件人并重发：名单是给人看的，不是给程序猜的。");
    }

    /**
     * 按需查全量角色名单（{@code mail_start(roles=true)} 的唯一出口）。
     *
     * <p>只列 {@code status='ACTIVE'}；停用与待复核一律不出现，历史脏代号不从这里漏出去。
     * 清单读不到时<b>明说是服务端故障</b>，不许显示成「没有角色」。
     */
    public String roleDirectoryMessage() {
        MailRoleCatalog.Snapshot s = roleCatalog.snapshot();
        StringBuilder sb = new StringBuilder();
        if (s == null || !s.available()) {
            sb.append("❌ 读不到角色清单（未列出任何名字）：这是 ExpHub 的服务端故障，不是「没有角色」。\n");
            sb.append("请稍后重试并报维护者。\n");
            return sb.toString();
        }
        List<String> labels = MailRoleCatalog.labelsOf(s);
        sb.append("📇 当前可投递角色：").append(labels.size()).append(" 个（只含 status=ACTIVE）\n");
        for (String label : labels) {
            sb.append("- ").append(label).append("\n");
        }
        sb.append("\n> 清单加载于 ").append(stamp(s.loadedAt()))
          .append("（每 30 秒刷新一次，刚加的角色可能还没生效）");
        if (s.degraded()) sb.append("；⚠️ 最近一次刷新失败，用的仍是这份旧名单");
        sb.append("\n> 停用（DISABLED）与待复核（PENDING_REVIEW）一律不出现在这份名单里。\n");
        sb.append("> ⚠️ 名单只用于展示：调用方不得依据本名单自动改写收件人并重发。\n");
        return sb.toString();
    }

    /** 毫秒时间戳 → {@code MM-dd HH:mm}（与信封列表同一口径）。 */
    private static String stamp(long epochMillis) {
        return TS.format(java.time.Instant.ofEpochMilli(epochMillis)
                .atZone(java.time.ZoneId.systemDefault()));
    }

    private String code(String shown) {
        return shown.isEmpty() ? "（空）" : "`" + shown + "`";
    }

    // ---------------------------------------------------------------- 发信

    /**
     * 发一封信。{@code fromRole} 由调用方（工具层）按配置来源填，不接受模型传入。
     *
     * <p>保留这个 8 参重载是为了<b>现有调用零改动</b>：老路径明确按「不属于任何授权链」
     * （chainId=null / auto=false / depth=0）入库，与加字段前逐字段一致。
     *
     * @param inReplyTo 父消息 ID；有值时线程从父消息推导，无值时服务端生成新线程
     */
    public MailMessage send(String fromRole, String toRole, String subject, String body,
                            Long inReplyTo, String msgType, String clientMsgId,
                            String configSourceId) {
        return send(fromRole, toRole, subject, body, inReplyTo, msgType, clientMsgId,
                configSourceId, null, null, null);
    }

    /**
     * 发一封信（带可选的授权链元数据）。
     *
     * <p><b>为什么加这三个字段</b>（阁主 T261109035247-364）：忆阁代理人模式要做授权链深度控制，
     * 但服务端原本既没有链字段、也分不出「代理自动回合发的信」和「阁主本人发的信」——
     * 两者的 {@code from_role} 都是「阁主」。所以只能由调用方在发信时自报，
     * 服务端记住并回显，<b>不做任何推导</b>。
     *
     * @param chainId 授权链根标识；null=不属于任何授权链。传空白或超过 64 字符直接拒（不静默修正）
     * @param auto    是否自动回合发出；null 视为 false
     * @param depth   当前跳数，null 视为 0；负数直接拒（不静默改成 0）
     */
    public MailMessage send(String fromRole, String toRole, String subject, String body,
                            Long inReplyTo, String msgType, String clientMsgId,
                            String configSourceId, String chainId, Boolean auto, Integer depth) {
        if (fromRole == null || fromRole.isBlank()) {
            throw new IllegalArgumentException("没有角色，发不了信：客户端配置里没有声明角色");
        }
        // 2026-11-09 阁主 T261109054938-679：入口统一归一到汉字正名再入库。
        // 客户端配置里可能仍是拼音（zhulong），这里统一换成「烛龙」——出口/日志/审计一律汉字。
        String from = roleCatalog == null ? fromRole.trim() : roleCatalog.canonicalRole(fromRole);
        if (from == null || from.isBlank()) from = fromRole.trim();
        // 收件人可以为空 <b>仅限回信</b>：带 inReplyTo 时收件人由父消息发件人决定（见下方 resolve）。
        if (subject == null || subject.isBlank()) {
            throw new IllegalArgumentException("主题是空的");
        }
        // 授权链元数据：校验在入库前，非法值明拒，不截断、不折成默认值（AGENTS 9c）。
        String chain = normalizeChainId(chainId);
        int hop = normalizeDepth(depth);
        boolean autoRound = Boolean.TRUE.equals(auto);

        // 信箱正文也要过同一道闸：历史上真发生过 Key 明文扩散到消息表（#1313 第 4 条）。
        // 与 docs 共用判据，但字段名分开，回执里能一眼看出是哪一处。
        Map<String, String> mailFields = new LinkedHashMap<>();
        mailFields.put("信箱主题", subject);
        mailFields.put("信箱正文", body == null ? "" : body);
        SensitiveContentGuard.check(mailFields);

        String rawTo = toRole == null ? "" : toRole.trim();

        // 幂等：重试不重复发（工具超时重试很常见）。放在收件人解析之前 ——
        // 重试命中时父消息已存在，没必要再跑一遍校验/解析。
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

            // 2026-11-09 阁主 P9 第 3 条（白泽查出来的漏）：<b>回信的收件人必须取父消息的发件人</b>。
            // 旧实现「回信不校验收件人、to 按入参入库」，于是一封 to 写错的回信照样入库、
            // 线程里看得见，对方收件箱里却没有 —— 发件方还以为送到了。
            String parentFrom = parent.getFromRole() == null ? "" : parent.getFromRole().trim();
            if (parentFrom.isEmpty()) {
                throw new IllegalArgumentException("父消息 #" + inReplyTo + " 没有记录发件人，无法确定回信收件人；"
                        + "请不传 inReplyTo 新开线程并指定 to（未发出任何信）。");
            }
            String derived = canonicalRole(parentFrom);
            if (!rawTo.isEmpty()) {
                String given = canonicalRole(rawTo);
                if (given == null || !given.equalsIgnoreCase(derived)) {
                    // 不静默改成父消息发件人：调用方以为自己发给了 A，实际给了 B，比报错更危险（AGENTS 9c）
                    throw new IllegalArgumentException("回信的收件人不能自己指定：带 inReplyTo 时收件人取父消息 #"
                            + inReplyTo + " 的发件人【" + derived + "】，而这次传的 to 是「" + rawTo + "」（归一为 "
                            + given + "）。要发给 " + given + " 请新开线程（不传 inReplyTo）；要回给 "
                            + derived + " 就删掉 to 再发（未发出任何信）。");
                }
            }
            toRole = derived;
            // 回信**不校验** ACTIVE 名单：父消息的发件人可能是已停用 / 历史代号，
            // 拦掉会让老线程永久卡死（把回信拒掉比投错更伤）。线程归属仍由父消息决定。
        } else {
            if (rawTo.isEmpty()) {
                throw new IllegalArgumentException("收件人是空的：开新线程必须传 to（回信可留空，收件人取父消息发件人）");
            }
            // 2026-11-09 阁主 P9 第 1 条：**只在 DB 清单（status=ACTIVE）里校验**，不再与「库里出现过的代号」并集。
            // 并集会把已停用的角色按历史信原地复活（停用永远停不掉），也会让拼错代号因「历史上存在过」而合法（#1459）。
            MailRoleCatalog.Check check = roleCatalog.check(rawTo);
            if (check.kind() != MailRoleCatalog.Check.Kind.OK) {
                throw new IllegalArgumentException(recipientRejectionMessage(rawTo, check));
            }
            toRole = check.canonical();
            if (!rawTo.equalsIgnoreCase(toRole)) {
                log.info("MailboxService.send: 收件人别名归一 {} -> {}", rawTo, toRole);
            }
            threadId = newThreadId();
        }

        MailMessage m = new MailMessage();
        m.setThreadId(threadId);
        m.setInReplyTo(inReplyTo);
        m.setFromRole(from);
        m.setToRole(toRole.trim());
        m.setMsgType(normalizeType(msgType));
        m.setSubject(subject.trim());
        m.setBody(body == null ? "" : body);
        m.setClientMsgId(blankToNull(clientMsgId));
        m.setConfigSourceId(blankToNull(configSourceId));
        m.setChainId(chain);
        m.setAutoFlag(autoRound);
        m.setChainDepth(hop);
        msgMapper.insert(m);

        // 发件人自己的游标推到这条：自己写的不用再「未读」给自己
        advanceCursor(from, threadId, m.getId());
        log.info("MailboxService.send: {} -> {}, thread={}, id={}", from, toRole, threadId, m.getId());
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

    /**
     * 校验并规整 chainId。
     *
     * <p>三种输入分清：<b>没传</b>（null → 不属于任何链）是合法的；
     * <b>传了空白</b>与<b>超长</b>是非法输入，直接拒。空白不折成 null ——
     * 否则调用方以为「我标了链」，库里却是「没链」，正是 AGENTS 9c 禁止的静默修正。
     */
    private String normalizeChainId(String raw) {
        if (raw == null) return null;
        String v = raw.trim();
        if (v.isEmpty()) {
            throw new IllegalArgumentException("chainId 是空白值（ExpHub 实际收到的是空白字符串）。"
                    + "不传表示「不属于任何授权链」；传空白既不是「没传」也不是有效链标识，已拒绝（未发出任何信）。");
        }
        if (v.length() > MAX_CHAIN_ID_LEN) {
            throw new IllegalArgumentException("chainId 超长：ExpHub 实际收到 " + v.length()
                    + " 字符，上限 " + MAX_CHAIN_ID_LEN + "（列 mail_messages.chain_id VARCHAR(64)）。"
                    + "不会截断 —— 截断会把两条不同的链折成同一个标识，请缩短后再发。");
        }
        return v;
    }

    /** 校验 depth：只接受非负整数；null 按 0 处理（不传=链根），负数明拒。 */
    private int normalizeDepth(Integer depth) {
        if (depth == null) return 0;
        if (depth < 0) {
            throw new IllegalArgumentException("depth 必须是非负整数（0 = 链根），ExpHub 实际收到 "
                    + depth + "，已拒绝（未发出任何信；不静默改成 0）。");
        }
        return depth;
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
        // 读侧并查别名：历史行的 from_role 可能是拼音（迁移前的信 / 刷不动的脏值），
        // 只按汉字精确匹配会让发件箱少一段历史。
        Set<String> myVariants = roleVariants(role);
        if (myVariants.isEmpty()) myVariants = Set.of(role.trim());
        List<MailMessage> mine = msgMapper.selectList(new QueryWrapper<MailMessage>()
                .in("from_role", myVariants)
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
        return thread(role, threadId, full, limit, false);
    }

    /**
     * 读一根线程（可选只读）。
     *
     * @param full true=从头发（分页），false=只给游标之后的新消息
     * @param peek true=<b>只读</b>：读完不推游标。调用方消费成功后再用
     *             {@link #advanceCursorTo} 显式推进 —— 服务端无法知道调用方是否真的用上了正文，
     *             只能把「推进」这个动作交给调用方
     * @return 消息（按 id 升序）
     */
    public List<MailMessage> thread(String role, String threadId, boolean full, int limit, boolean peek) {
        long cursor = full ? 0 : cursorOf(role.trim(), threadId);
        List<MailMessage> list = msgMapper.selectList(new QueryWrapper<MailMessage>()
                .eq("thread_id", threadId).gt("id", cursor)
                .orderByAsc("id").last("LIMIT " + limit));
        // 读完推游标：去重靠服务端，不靠模型记性；peek 时由调用方后续显式推进
        if (!peek && !list.isEmpty()) {
            advanceCursor(role.trim(), threadId, list.get(list.size() - 1).getId());
        }
        return list;
    }

    /** 线程里的总条数（用于「还有几封」提示）。 */
    public long countThread(String threadId) {
        return msgMapper.selectCount(new QueryWrapper<MailMessage>().eq("thread_id", threadId));
    }

    /** 游标之后还有几封（只读；用于回执自证「推进后还剩多少没读」）。 */
    public long countAfterCursor(String role, String threadId) {
        long cursor = cursorOf(role.trim(), threadId);
        return msgMapper.selectCount(new QueryWrapper<MailMessage>()
                .eq("thread_id", threadId).gt("id", cursor));
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

    /**
     * 显式把游标推进到某条消息，<b>不返回正文</b>。
     *
     * <p>给「{@code mail_thread(peek=true)} 读完 → 确认处理成功 → 再推进」这条链路用：
     * 服务端看不到调用方是否真的把正文用上了，推进时机只能由调用方决定。
     *
     * <p>校验放在服务端：消息必须存在、必须属于该线程、只前进不后退。
     * 「跨线程推进」会把另一个线程的信静默标成已读，宁可明拒。
     *
     * @return 1=已推进；0=目标不比当前游标新（幂等，不是错误）
     * @throws IllegalArgumentException 缺角色 / 消息不存在 / 消息不属于该线程
     */
    public int advanceCursorTo(String role, String threadId, long messageId) {
        if (role == null || role.isBlank()) {
            throw new IllegalArgumentException("缺角色，无法推进游标");
        }
        if (threadId == null || threadId.isBlank()) {
            throw new IllegalArgumentException("缺 threadId，无法推进游标");
        }
        MailMessage m = msgMapper.selectById(messageId);
        if (m == null) {
            throw new IllegalArgumentException("消息 #" + messageId + " 不存在（ID 写错？）");
        }
        if (!threadId.trim().equals(m.getThreadId())) {
            throw new IllegalArgumentException("#" + messageId + " 属于线程 " + m.getThreadId()
                    + "，不是 " + threadId + " —— 跨线程推进会把别的线程的信标成已读，拒绝");
        }
        long cur = cursorOf(role.trim(), threadId.trim());
        if (messageId <= cur) return 0;
        advanceCursor(role.trim(), threadId.trim(), messageId);
        // 回读实际值再返回：并发下别的会话可能已推得更远，不能按「我传了」就说推到了
        return cursorOf(role.trim(), threadId.trim()) >= messageId ? 1 : 0;
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
