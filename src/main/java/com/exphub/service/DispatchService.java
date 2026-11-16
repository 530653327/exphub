package com.exphub.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.exphub.entity.DispatchHeader;
import com.exphub.entity.DispatchItem;
import com.exphub.mapper.DispatchHeaderMapper;
import com.exphub.mapper.DispatchItemMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;

/**
 * 派单系统（替代已删除的协作信箱）。设计基线见 ExpHub #3308。
 *
 * <p><b>三条不可动的设计</b>：
 * <ol>
 *   <li><b>查询零副作用</b>：{@link #list} / {@link #items} 无论调多少次都不写任何账本。
 *       旧信箱死在这里 —— 它的 mail_start 一查就写去重表，查一次就算投递过一次。</li>
 *   <li><b>状态写在字段里</b>：没有「未读」「水位」「封存」这类需要推断的概念。</li>
 *   <li><b>状态转移靠原子 UPDATE 的条件</b>：{@code WHERE status=期望值}，看影响行数决定谁赢，
 *       不靠应用层先查后改。</li>
 * </ol>
 *
 * <p><b>谁能做什么</b>（越哥 2026-11-16 口述，不在表里的转移一律拒）：
 * <pre>
 *   审核 / 验收 / 取消 → 只越哥，且走后台网页，**不在本 Service 的 MCP 路径里**
 *   认领               → 被指派的哨兵：READY → RUNNING
 *   请求确认           → 哨兵：RUNNING → CONFIRMING
 *   回复继续           → AI 单=阁主（人工单必须走后台）：CONFIRMING → RUNNING
 *   提交验收           → 哨兵：RUNNING → VERIFYING
 *   终止本轮           → 哨兵：item 终止 + header RUNNING → CONFIRMING
 * </pre>
 */
@Service
public class DispatchService {

    private static final Logger log = LoggerFactory.getLogger(DispatchService.class);

    /** 非终态才允许取消 */
    public static final List<String> LIVE_STATUSES = List.of(
            DispatchHeader.ST_DRAFT, DispatchHeader.ST_REVIEWING, DispatchHeader.ST_READY,
            DispatchHeader.ST_RUNNING, DispatchHeader.ST_CONFIRMING, DispatchHeader.ST_VERIFYING);

    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("yyyyMMdd");

    /**
     * 角色别名 → 汉字正名。
     *
     * <p><b>为什么必须有这张表</b>（2026-11-16 实测）：HTTP 头只能放 ASCII，
     * 客户端配 {@code x-exphub-role: 穷奇} 会被 Netty 直接 400 拒掉；配拼音 {@code qiongqi}
     * 能进来，但和单子上的「穷奇」永远匹配不上 —— 认领、回复全会被判「不是你」。
     * 所以入口必须归一。
     *
     * <p><b>这是权宜</b>：正解是角色名落库（旧的 mail_roles 已随信箱删除）。
     * 新增角色时**必须同步这里**，否则新角色一律匹配不上。
     */
    private static final java.util.Map<String, String> ROLE_ALIASES = java.util.Map.ofEntries(
            java.util.Map.entry("zhulong", "烛龙"),
            java.util.Map.entry("baize", "白泽"),
            java.util.Map.entry("gezhu", "阁主"),
            java.util.Map.entry("pixiu", "貔貅"),
            java.util.Map.entry("yazi", "睚眦"),
            java.util.Map.entry("qilin", "麒麟"),
            java.util.Map.entry("fenghuang", "凤凰"),
            java.util.Map.entry("chongmingniao", "重明鸟"),
            java.util.Map.entry("jiuweihu", "九尾狐"),
            java.util.Map.entry("dijiang", "帝江"),
            java.util.Map.entry("qiongqi", "穷奇"),
            java.util.Map.entry("luwu", "陆吾"),
            java.util.Map.entry("taotie", "饕餮"),
            java.util.Map.entry("yuege", "越哥"),
            java.util.Map.entry("human", "越哥"));

    /**
     * 把客户端传来的角色（拼音或汉字）归一成汉字正名。认不出就原样返回。
     *
     * <p>所有入口（建单 / 认领 / 动作 / 回复）都必须过这一层，否则会出现
     * 「库里存 qiongqi、单子上写穷奇」这种半套数据，之后谁都对不上。
     */
    public static String canonicalRole(String raw) {
        if (raw == null) return null;
        String t = raw.trim();
        if (t.isEmpty()) return t;
        return ROLE_ALIASES.getOrDefault(t.toLowerCase(java.util.Locale.ROOT), t);
    }

    @Autowired
    private DispatchHeaderMapper headerMapper;

    @Autowired
    private DispatchItemMapper itemMapper;

    // ==================== 结果封装 ====================

    /** 一次操作的结果：成功与否 + 给人看的那句话 + 落地后的对象。 */
    public record Outcome(boolean ok, String message, DispatchHeader header, DispatchItem item) {
        static Outcome fail(String msg) { return new Outcome(false, msg, null, null); }
        static Outcome success(String msg, DispatchHeader h, DispatchItem i) { return new Outcome(true, msg, h, i); }
    }

    // ==================== 建单 ====================

    /**
     * 建单。AI 建的单只能落 REVIEWING（等越哥在后台审）；人建单由后台传 origin=HUMAN 并可直接进 READY。
     *
     * <p>授权位在这里一次性写入，之后任何路径都不得再改 —— 见 {@link DispatchHeader} 类注释。
     */
    @Transactional
    public Outcome create(String creator, String origin, String assignee, String title, String description,
                          Integer priority, Long projectId, java.util.Map<String, Boolean> auths) {
        creator = canonicalRole(creator);
        assignee = canonicalRole(assignee);
        if (creator == null || creator.isBlank()) return Outcome.fail("创建人不能为空");
        if (assignee == null || assignee.isBlank()) return Outcome.fail("必须指派一个哨兵（assignee）");
        if (title == null || title.isBlank()) return Outcome.fail("标题不能为空");
        if (description == null || description.isBlank()) return Outcome.fail("内容描述不能为空");
        boolean human = DispatchHeader.ORIGIN_HUMAN.equalsIgnoreCase(origin);
        if (!human && !DispatchHeader.ORIGIN_AI.equalsIgnoreCase(origin)) {
            return Outcome.fail("来源只能是 HUMAN 或 AI（收到：" + origin + "）");
        }

        DispatchHeader h = new DispatchHeader();
        h.setOrderNo(nextOrderNo());
        h.setOrigin(human ? DispatchHeader.ORIGIN_HUMAN : DispatchHeader.ORIGIN_AI);
        h.setCreator(creator.trim());
        h.setCreatedAt(LocalDateTime.now());
        // 人建单在后台可直接过审进 READY；AI 建单一律先到 REVIEWING，没有第二条出口
        h.setStatus(DispatchHeader.ST_REVIEWING);
        h.setTitle(title.trim());
        h.setDescription(description);
        h.setAssignee(assignee.trim());
        h.setPriority(priority == null ? 3 : priority);
        h.setProjectId(projectId);
        h.setUpdatedAt(LocalDateTime.now());

        java.util.Map<String, Boolean> a = auths == null ? java.util.Map.of() : auths;
        h.setAuthModifyCode(flag(a, "modifyCode"));
        h.setAuthCommit(flag(a, "commit"));
        h.setAuthPush(flag(a, "push"));
        h.setAuthRelease(flag(a, "release"));
        h.setAuthDataWrite(flag(a, "dataWrite"));
        h.setAuthMigration(flag(a, "migration"));
        h.setAuthCredential(flag(a, "credential"));
        h.setAuthProdConfig(flag(a, "prodConfig"));
        h.setAuthSecurityGroup(flag(a, "securityGroup"));
        h.setAuthFirewall(flag(a, "firewall"));
        h.setAuthSpend(flag(a, "spend"));

        headerMapper.insert(h);
        log.info("DispatchService.create: order={}, origin={}, creator={}, assignee={}",
                h.getOrderNo(), h.getOrigin(), h.getCreator(), h.getAssignee());
        return Outcome.success("已建单 " + h.getOrderNo() + "（状态：待审核）", h, null);
    }

    private static Boolean flag(java.util.Map<String, Boolean> m, String key) {
        return Boolean.TRUE.equals(m.get(key));
    }

    /**
     * 生成单号：W-yyyyMMdd-000001，按天递增，序号 6 位（旧系统 3 位会截断、封错单）。
     *
     * <p>并发下靠唯一索引兜底：撞了就重试，最多 5 次。
     */
    private String nextOrderNo() {
        String prefix = "W-" + LocalDate.now().format(DAY) + "-";
        for (int attempt = 0; attempt < 5; attempt++) {
            DispatchHeader last = headerMapper.selectOne(new QueryWrapper<DispatchHeader>()
                    .likeRight("order_no", prefix)
                    .orderByDesc("order_no")
                    .last("LIMIT 1"));
            int seq = 1;
            if (last != null && last.getOrderNo() != null) {
                String tail = last.getOrderNo().substring(prefix.length());
                try {
                    seq = Integer.parseInt(tail) + 1;
                } catch (NumberFormatException ignore) {
                    // 脏数据不阻断，退回 1；唯一索引会拦住真重复
                }
            }
            String candidate = prefix + String.format("%06d", seq);
            Long exists = headerMapper.selectCount(new QueryWrapper<DispatchHeader>()
                    .eq("order_no", candidate));
            if (exists == null || exists == 0) return candidate;
        }
        throw new IllegalStateException("单号生成失败：连续 5 次撞号，请检查 dispatch_header.order_no");
    }

    // ==================== 查询（零副作用）====================

    /**
     * 列单。**不写任何账本、不推任何游标** —— 这是与旧信箱最大的区别，不要在这里加任何写操作。
     *
     * @param role 只看与我有关的：我指派的（assignee）或我创建的（creator）；null=不限
     */
    public List<DispatchHeader> list(String role, String status, String orderNo, int limit) {
        QueryWrapper<DispatchHeader> q = new QueryWrapper<>();
        if (orderNo != null && !orderNo.isBlank()) {
            q.eq("order_no", orderNo.trim());
        }
        if (status != null && !status.isBlank()) {
            q.eq("status", status.trim().toUpperCase(java.util.Locale.ROOT));
        }
        if (role != null && !role.isBlank()) {
            String r = role.trim();
            q.and(w -> w.eq("assignee", r).or().eq("creator", r));
        }
        q.orderByDesc("id");
        if (limit > 0) q.last("LIMIT " + Math.min(limit, 50));
        return headerMapper.selectList(q);
    }

    public DispatchHeader get(String orderNo) {
        if (orderNo == null || orderNo.isBlank()) return null;
        return headerMapper.selectOne(new QueryWrapper<DispatchHeader>().eq("order_no", orderNo.trim()));
    }

    /** 一单的全部 item（按轮次升序）。零副作用。 */
    public List<DispatchItem> items(Long headerId) {
        if (headerId == null) return List.of();
        return itemMapper.selectList(new QueryWrapper<DispatchItem>()
                .eq("header_id", headerId).orderByAsc("seq"));
    }

    /** 最后一轮 item，用于回执里显示「现在球在谁那儿」。 */
    public DispatchItem lastItem(Long headerId) {
        if (headerId == null) return null;
        return itemMapper.selectOne(new QueryWrapper<DispatchItem>()
                .eq("header_id", headerId).orderByDesc("seq").last("LIMIT 1"));
    }

    // ==================== 状态转移 ====================

    /**
     * 认领：READY → RUNNING，只有被指派的哨兵能接，靠条件 UPDATE 抢锁。
     *
     * <p>认领成功同时建第一条 item（seq=1，command=EXEC，status=RUNNING）——
     * 「一开工就建 item」是 2026-11-16 定的，否则 ERROR 状态永远无处落地。
     */
    @Transactional
    public Outcome claim(String orderNo, String role) {
        role = canonicalRole(role);
        if (role == null || role.isBlank()) return Outcome.fail("认领必须先确定你是谁（角色头未配置？）");
        DispatchHeader h = get(orderNo);
        if (h == null) return Outcome.fail("未找到单号 " + orderNo);
        if (!role.trim().equals(h.getAssignee())) {
            return Outcome.fail("这张单指派给「" + h.getAssignee() + "」，不是你（" + role + "），不能接。");
        }
        if (!DispatchHeader.ST_READY.equals(h.getStatus())) {
            return Outcome.fail("当前状态是「" + statusLabel(h.getStatus()) + "」，只有「待接单」能认领。");
        }

        LocalDateTime now = LocalDateTime.now();
        int rows = headerMapper.update(null, new UpdateWrapper<DispatchHeader>()
                .eq("order_no", h.getOrderNo())
                .eq("status", DispatchHeader.ST_READY)
                .set("status", DispatchHeader.ST_RUNNING)
                .set("accepter", role.trim())
                .set("accepted_at", now)
                .set("updated_at", now));
        if (rows == 0) {
            return Outcome.fail("认领失败：这张单刚被别人接走或状态已变，请重新查一次。");
        }

        DispatchItem it = newItem(h, role.trim(), DispatchItem.CMD_EXEC, false, null, "认领开工",
                DispatchItem.ST_RUNNING);
        return Outcome.success("已认领 " + h.getOrderNo() + "，状态 → 进行中。item " + it.getItemNo() + " 已开工。",
                get(h.getOrderNo()), it);
    }

    /**
     * 哨兵请求确认：RUNNING → CONFIRMING，球交给 header 创建人那边的处理方
     * （人工单=越哥走后台，AI 单=阁主走 MCP）。
     */
    @Transactional
    public Outcome needConfirm(String orderNo, String role, String question, String proposal) {
        if (proposal == null || proposal.isBlank()) {
            return Outcome.fail("提问必须自带建议方案：只抛问题不许过（防 ping-pong 的硬规矩）。");
        }
        role = canonicalRole(role);
        DispatchHeader h = get(orderNo);
        Outcome guard = guardAssigneeAndStatus(h, role, DispatchHeader.ST_RUNNING, "请求确认");
        if (guard != null) return guard;

        // 先把上一轮 item 收尾（如果有还开着的），再开新一轮
        closeOpenItem(h, role, "请求确认，本轮结束", DispatchItem.ST_DONE);
        LocalDateTime now = LocalDateTime.now();
        int rows = headerMapper.update(null, new UpdateWrapper<DispatchHeader>()
                .eq("order_no", h.getOrderNo())
                .eq("status", DispatchHeader.ST_RUNNING)
                .set("status", DispatchHeader.ST_CONFIRMING)
                .set("updated_at", now));
        if (rows == 0) return Outcome.fail("状态已变化，请求确认未生效，请重新查一次。");

        String body = (question == null ? "" : question)
                + "\n\n**我的建议方案**：" + proposal;
        DispatchItem it = newItem(h, role.trim(), DispatchItem.CMD_CONFIRM, true, "需要确认", body,
                DispatchItem.ST_DONE);
        return Outcome.success("已把球传给「" + ballOwnerLabel(h) + "」，等待确认。item " + it.getItemNo(), get(orderNo), it);
    }

    /**
     * 回复并继续：「待确认」→ 进行中。
     *
     * <p><b>只允许 AI 单走这条路</b>。人工单的回复必须由越哥在后台点，
     * 不能由 AI 代回 —— 越哥 2026-11-16 明确「人工单中间那段是我和他确认」。
     */
    @Transactional
    public Outcome reply(String orderNo, String actor, String text) {
        actor = canonicalRole(actor);
        DispatchHeader h = get(orderNo);
        if (h == null) return Outcome.fail("未找到单号 " + orderNo);
        if (DispatchHeader.ORIGIN_HUMAN.equals(h.getOrigin())) {
            return Outcome.fail("这是人工创建的单，「待确认」必须由越哥本人在后台回复，AI 不能代回。");
        }
        if (actor == null || !actor.trim().equals(h.getCreator())) {
            return Outcome.fail("只有这张单的创建人（" + h.getCreator() + "）能回复，你是 " + actor + "。");
        }
        if (!DispatchHeader.ST_CONFIRMING.equals(h.getStatus())) {
            return Outcome.fail("当前状态是「" + statusLabel(h.getStatus()) + "」，只有「待确认」能回复。");
        }

        LocalDateTime now = LocalDateTime.now();
        int rows = headerMapper.update(null, new UpdateWrapper<DispatchHeader>()
                .eq("order_no", h.getOrderNo())
                .eq("status", DispatchHeader.ST_CONFIRMING)
                .set("status", DispatchHeader.ST_RUNNING)
                .set("updated_at", now));
        if (rows == 0) return Outcome.fail("状态已变化，回复未生效，请重新查一次。");

        DispatchItem it = newItem(h, actor.trim(), DispatchItem.CMD_EXEC, false, "继续执行", text,
                DispatchItem.ST_RUNNING);
        return Outcome.success("已回复并继续执行，球回到「" + h.getAssignee() + "」。item " + it.getItemNo(), get(orderNo), it);
    }

    /**
     * 提交验收：RUNNING → VERIFYING。之后只有越哥能在后台点通过/驳回。
     */
    @Transactional
    public Outcome submitAccept(String orderNo, String role, String conclusion) {
        role = canonicalRole(role);
        DispatchHeader h = get(orderNo);
        Outcome guard = guardAssigneeAndStatus(h, role, DispatchHeader.ST_RUNNING, "提交验收");
        if (guard != null) return guard;
        if (conclusion == null || conclusion.isBlank()) {
            return Outcome.fail("提交验收必须写结论：越哥要照它判断通过还是驳回。");
        }

        closeOpenItem(h, role, conclusion, DispatchItem.ST_DONE);
        LocalDateTime now = LocalDateTime.now();
        int rows = headerMapper.update(null, new UpdateWrapper<DispatchHeader>()
                .eq("order_no", h.getOrderNo())
                .eq("status", DispatchHeader.ST_RUNNING)
                .set("status", DispatchHeader.ST_VERIFYING)
                .set("updated_at", now));
        if (rows == 0) return Outcome.fail("状态已变化，提交验收未生效，请重新查一次。");

        DispatchItem it = newItem(h, role.trim(), DispatchItem.CMD_EXEC, true, conclusion, conclusion,
                DispatchItem.ST_DONE);
        return Outcome.success("已提交验收，等越哥判断。item " + it.getItemNo(), get(orderNo), it);
    }

    /**
     * 终止本轮：item 终止（不唤醒大模型），header 同时进 CONFIRMING 等通知。
     *
     * <p>越哥 2026-11-16 定：「item 的终止只是不要有下一步的操作了，等待通知」。
     * 所以终止之后 header 必须离开 RUNNING，否则球在哨兵手里却禁止动作，单子悬死。
     */
    @Transactional
    public Outcome terminate(String orderNo, String role, String reason) {
        role = canonicalRole(role);
        DispatchHeader h = get(orderNo);
        Outcome guard = guardAssigneeAndStatus(h, role, DispatchHeader.ST_RUNNING, "终止本轮");
        if (guard != null) return guard;

        DispatchItem open = closeOpenItem(h, role, reason == null ? "本轮终止" : reason, DispatchItem.ST_DONE);

        LocalDateTime now = LocalDateTime.now();
        int rows = headerMapper.update(null, new UpdateWrapper<DispatchHeader>()
                .eq("order_no", h.getOrderNo())
                .eq("status", DispatchHeader.ST_RUNNING)
                .set("status", DispatchHeader.ST_CONFIRMING)
                .set("updated_at", now));
        if (rows == 0) return Outcome.fail("状态已变化，终止未生效，请重新查一次。");

        DispatchItem it = newItem(h, role.trim(), DispatchItem.CMD_TERMINATE, true,
                "已终止本轮", reason == null ? "" : reason, DispatchItem.ST_DONE);
        return Outcome.success("本轮已终止，header 已进「待确认」等通知（单子没有结束）。item " + it.getItemNo(),
                get(orderNo), it);
    }

    // ==================== 内部工具 ====================

    /** 认领类动作的统一前置校验：单存在、是指派给你、状态对。返回 null 表示通过。 */
    private Outcome guardAssigneeAndStatus(DispatchHeader h, String roleIn, String expectStatus, String action) {
        String role = canonicalRole(roleIn);
        if (h == null) return Outcome.fail("未找到该单号");
        if (role == null || role.isBlank()) return Outcome.fail("无法确定你是谁（角色头未配置？）");
        if (!role.trim().equals(h.getAssignee())) {
            return Outcome.fail("这张单指派给「" + h.getAssignee() + "」，不是你（" + role + "），不能" + action + "。");
        }
        if (!expectStatus.equals(h.getStatus())) {
            return Outcome.fail("当前状态是「" + statusLabel(h.getStatus()) + "」，不能" + action + "。");
        }
        return null;
    }

    /**
     * 关掉当前还开着的 item（status=RUNNING）：标 DONE + finishedAt + 结论。
     * 没有开着的就跳过 —— 不报错，因为「没建过 item」是合法状态（比如后台直接改单）。
     */
    private DispatchItem closeOpenItem(DispatchHeader h, String role, String conclusion, String finalStatus) {
        DispatchItem open = itemMapper.selectOne(new QueryWrapper<DispatchItem>()
                .eq("header_id", h.getId())
                .eq("status", DispatchItem.ST_RUNNING)
                .orderByDesc("seq")
                .last("LIMIT 1"));
        if (open == null) return null;
        open.setStatus(finalStatus);
        open.setFinishedAt(LocalDateTime.now());
        if (conclusion != null && !conclusion.isBlank()) open.setConclusion(conclusion);
        itemMapper.updateById(open);
        return open;
    }

    /** 为一单开新一轮 item，轮次 = 当前最大 seq + 1。 */
    private DispatchItem newItem(DispatchHeader h, String creator, String command,
                                 boolean replyRequired, String conclusion, String content,
                                 String initialStatus) {
        DispatchItem last = lastItem(h.getId());
        int seq = last == null || last.getSeq() == null ? 1 : last.getSeq() + 1;
        DispatchItem it = new DispatchItem();
        it.setHeaderId(h.getId());
        it.setOrderNo(h.getOrderNo());
        it.setItemNo(h.getOrderNo() + "-" + String.format("%02d", seq));
        it.setSeq(seq);
        it.setCreator(creator);
        it.setCreatedAt(LocalDateTime.now());
        it.setCommand(command);
        it.setStatus(initialStatus);
        it.setReplyRequired(replyRequired);
        it.setConclusion(conclusion);
        it.setContent(content);
        itemMapper.insert(it);
        return it;
    }

    /** 「待确认」时球在谁手里：人工单给越哥，AI 单给创建人（阁主）。 */
    public String ballOwnerLabel(DispatchHeader h) {
        if (DispatchHeader.ORIGIN_HUMAN.equals(h.getOrigin())) return "越哥";
        return h.getCreator();
    }

    public static String statusLabel(String code) {
        if (code == null) return "未知";
        return switch (code) {
            case DispatchHeader.ST_DRAFT -> "草稿";
            case DispatchHeader.ST_REVIEWING -> "待审核";
            case DispatchHeader.ST_READY -> "待接单";
            case DispatchHeader.ST_RUNNING -> "进行中";
            case DispatchHeader.ST_CONFIRMING -> "待确认";
            case DispatchHeader.ST_VERIFYING -> "待验收";
            case DispatchHeader.ST_DONE -> "完成";
            case DispatchHeader.ST_CANCELLED -> "取消";
            default -> code;
        };
    }

    // ==================== 后台动作（只有越哥，只走网页，不做 MCP 入口）====================

    /**
     * 后台操作人固定记为「越哥」。
     *
     * <p>系统里只有一个人用后台（越哥 2026-11-16 明确：「这个系统中使用的人类只有我一个」）。
     * 不取 Spring Security 的登录名，那个是 admin，写进业务表反而对不上。
     */
    public static final String HUMAN_OPERATOR = "越哥";

    /**
     * 后台建单时可选的被指派对象（哨兵）。
     *
     * <p>不含「阁主」：阁主是 AI 单的创建方/答复方，不是干活的哨兵。
     * 新增角色要同时改 {@link #ROLE_ALIASES} 和这里，否则要么建不了单、要么认领匹配不上。
     */
    public static final List<String> ASSIGNABLE_ROLES = List.of(
            "白泽", "烛龙", "貔貅", "睚眦", "麒麟", "凤凰",
            "重明鸟", "九尾狐", "帝江", "穷奇", "陆吾", "饕餮");

    /**
     * 审核通过：REVIEWING → READY。
     *
     * <p><b>这是 AI 建单唯一的出口</b>，只由后台网页调用 —— MCP 里没有这个动作，
     * 所以 AI 物理上没法自己批自己。
     */
    @Transactional
    public Outcome approve(String orderNo, String note) {
        DispatchHeader h = get(orderNo);
        if (h == null) return Outcome.fail("未找到单号 " + orderNo);
        if (!DispatchHeader.ST_REVIEWING.equals(h.getStatus())) {
            return Outcome.fail("当前状态是「" + statusLabel(h.getStatus()) + "」，只有「待审核」能审。");
        }
        LocalDateTime now = LocalDateTime.now();
        int rows = headerMapper.update(null, new UpdateWrapper<DispatchHeader>()
                .eq("order_no", h.getOrderNo())
                .eq("status", DispatchHeader.ST_REVIEWING)
                .set("status", DispatchHeader.ST_READY)
                .set("reviewer", HUMAN_OPERATOR)
                .set("reviewed_at", now)
                .set("updated_at", now)
                .set("remark", note == null || note.isBlank() ? h.getRemark() : note));
        if (rows == 0) return Outcome.fail("状态已变化，审核未生效，请刷新重试。");
        log.info("DispatchService.approve: order={} 已审核通过 → READY", orderNo);
        return Outcome.success("已审核通过，单子进入「待接单」，哨兵可以接了。", get(orderNo), null);
    }

    /** 审核驳回：REVIEWING → CANCELLED。要重提就新建一张，不要在旧单上改 —— 审核绑的是当时那份内容。 */
    @Transactional
    public Outcome rejectReview(String orderNo, String reason) {
        DispatchHeader h = get(orderNo);
        if (h == null) return Outcome.fail("未找到单号 " + orderNo);
        if (!DispatchHeader.ST_REVIEWING.equals(h.getStatus())) {
            return Outcome.fail("当前状态是「" + statusLabel(h.getStatus()) + "」，只有「待审核」能驳回。");
        }
        String why = (reason == null || reason.isBlank()) ? "审核未通过（未填原因）" : reason;
        LocalDateTime now = LocalDateTime.now();
        int rows = headerMapper.update(null, new UpdateWrapper<DispatchHeader>()
                .eq("order_no", h.getOrderNo())
                .eq("status", DispatchHeader.ST_REVIEWING)
                .set("status", DispatchHeader.ST_CANCELLED)
                .set("reviewer", HUMAN_OPERATOR)
                .set("reviewed_at", now)
                .set("cancel_reason", "审核驳回：" + why)
                .set("updated_at", now));
        if (rows == 0) return Outcome.fail("状态已变化，驳回未生效，请刷新重试。");
        return Outcome.success("已驳回，单子已取消。要重提请新建一张。", get(orderNo), null);
    }

    /** 验收通过：VERIFYING → DONE。终态，后面没有动作了。 */
    @Transactional
    public Outcome verifyPass(String orderNo, String note) {
        DispatchHeader h = get(orderNo);
        if (h == null) return Outcome.fail("未找到单号 " + orderNo);
        if (!DispatchHeader.ST_VERIFYING.equals(h.getStatus())) {
            return Outcome.fail("当前状态是「" + statusLabel(h.getStatus()) + "」，只有「待验收」能验。");
        }
        LocalDateTime now = LocalDateTime.now();
        int rows = headerMapper.update(null, new UpdateWrapper<DispatchHeader>()
                .eq("order_no", h.getOrderNo())
                .eq("status", DispatchHeader.ST_VERIFYING)
                .set("status", DispatchHeader.ST_DONE)
                .set("verifier", HUMAN_OPERATOR)
                .set("verified_at", now)
                .set("verify_note", note == null || note.isBlank() ? "验收通过" : note)
                .set("updated_at", now));
        if (rows == 0) return Outcome.fail("状态已变化，验收未生效，请刷新重试。");
        closeOpenItem(h, HUMAN_OPERATOR, "验收通过", DispatchItem.ST_DONE);
        log.info("DispatchService.verifyPass: order={} 验收通过 → DONE", orderNo);
        return Outcome.success("验收通过，单子已完成。", get(orderNo), null);
    }

    /**
     * 验收驳回：VERIFYING → RUNNING，**必须填理由**（哨兵照它改）。
     *
     * <p>驳回要建一条 item：这是一次传球，球回到哨兵手上，它得知道这一轮要干什么。
     */
    @Transactional
    public Outcome verifyReject(String orderNo, String reason) {
        DispatchHeader h = get(orderNo);
        if (h == null) return Outcome.fail("未找到单号 " + orderNo);
        if (reason == null || reason.isBlank()) {
            return Outcome.fail("驳回必须写理由，否则哨兵不知道要改什么。");
        }
        if (!DispatchHeader.ST_VERIFYING.equals(h.getStatus())) {
            return Outcome.fail("当前状态是「" + statusLabel(h.getStatus()) + "」，只有「待验收」能驳回。");
        }
        LocalDateTime now = LocalDateTime.now();
        int rows = headerMapper.update(null, new UpdateWrapper<DispatchHeader>()
                .eq("order_no", h.getOrderNo())
                .eq("status", DispatchHeader.ST_VERIFYING)
                .set("status", DispatchHeader.ST_RUNNING)
                .set("verifier", HUMAN_OPERATOR)
                .set("verified_at", now)
                .set("verify_note", reason)
                .set("updated_at", now));
        if (rows == 0) return Outcome.fail("状态已变化，驳回未生效，请刷新重试。");

        DispatchItem it = newItem(h, HUMAN_OPERATOR, DispatchItem.CMD_EXEC, false,
                "验收驳回", reason, DispatchItem.ST_RUNNING);
        return Outcome.success("已驳回，球回到「" + h.getAssignee() + "」。item " + it.getItemNo(), get(orderNo), it);
    }

    /** 取消整单：任意非终态 → CANCELLED。和 item 终止不是一回事，这里是整单结束。 */
    @Transactional
    public Outcome cancel(String orderNo, String reason) {
        DispatchHeader h = get(orderNo);
        if (h == null) return Outcome.fail("未找到单号 " + orderNo);
        if (!LIVE_STATUSES.contains(h.getStatus())) {
            return Outcome.fail("当前状态是「" + statusLabel(h.getStatus()) + "」，已经是终态，不能再取消。");
        }
        LocalDateTime now = LocalDateTime.now();
        int rows = headerMapper.update(null, new UpdateWrapper<DispatchHeader>()
                .eq("order_no", h.getOrderNo())
                .in("status", LIVE_STATUSES)
                .set("status", DispatchHeader.ST_CANCELLED)
                .set("cancel_reason", (reason == null || reason.isBlank()) ? "人工取消" : reason)
                .set("updated_at", now));
        if (rows == 0) return Outcome.fail("状态已变化，取消失未生效，请刷新重试。");
        closeOpenItem(h, HUMAN_OPERATOR, "整单取消", DispatchItem.ST_ERROR);
        log.info("DispatchService.cancel: order={} 已取消", orderNo);
        return Outcome.success("整单已取消，后面没有任何动作了。", get(orderNo), null);
    }

    /**
     * 人工单在「待确认」时的回复（越哥本人在后台点）。
     *
     * <p>与 MCP 的 {@link #reply} 对称，但**不校验 actor** —— 这个入口只有后台能调，
     * 而 MCP 那条对人工单是明确拒的。
     */
    @Transactional
    public Outcome humanReply(String orderNo, String text) {
        DispatchHeader h = get(orderNo);
        if (h == null) return Outcome.fail("未找到单号 " + orderNo);
        if (!DispatchHeader.ST_CONFIRMING.equals(h.getStatus())) {
            return Outcome.fail("当前状态是「" + statusLabel(h.getStatus()) + "」，只有「待确认」能回复。");
        }
        LocalDateTime now = LocalDateTime.now();
        int rows = headerMapper.update(null, new UpdateWrapper<DispatchHeader>()
                .eq("order_no", h.getOrderNo())
                .eq("status", DispatchHeader.ST_CONFIRMING)
                .set("status", DispatchHeader.ST_RUNNING)
                .set("updated_at", now));
        if (rows == 0) return Outcome.fail("状态已变化，回复未生效，请刷新重试。");

        DispatchItem it = newItem(h, HUMAN_OPERATOR, DispatchItem.CMD_EXEC, false, "继续执行",
                text == null ? "" : text, DispatchItem.ST_RUNNING);
        return Outcome.success("已回复并继续执行，球回到「" + h.getAssignee() + "」。item " + it.getItemNo(),
                get(orderNo), it);
    }

    /**
     * 补写备注（只给人看，不进任何业务判断）。
     *
     * <p>备注不放进 {@link #create} 的签名：AI 建单不需要它，多一个参数就多一个
     * 让 AI 往单子里塞话的口子。人要写就后台写。
     */
    @Transactional
    public void updateRemark(String orderNo, String remark) {
        if (orderNo == null || orderNo.isBlank()) return;
        headerMapper.update(null, new UpdateWrapper<DispatchHeader>()
                .eq("order_no", orderNo.trim())
                .set("remark", remark)
                .set("updated_at", LocalDateTime.now()));
    }

    /** 后台列表用：按状态统计各多少张，给页面顶部做角标。 */
    public java.util.Map<String, Long> countByStatus() {
        java.util.Map<String, Long> m = new java.util.LinkedHashMap<>();
        for (String st : List.of(DispatchHeader.ST_REVIEWING, DispatchHeader.ST_READY,
                DispatchHeader.ST_RUNNING, DispatchHeader.ST_CONFIRMING,
                DispatchHeader.ST_VERIFYING, DispatchHeader.ST_DONE, DispatchHeader.ST_CANCELLED)) {
            Long c = headerMapper.selectCount(new QueryWrapper<DispatchHeader>().eq("status", st));
            m.put(st, c == null ? 0L : c);
        }
        return m;
    }
}
