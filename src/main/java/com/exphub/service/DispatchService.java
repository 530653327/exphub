package com.exphub.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.exphub.entity.DispatchHeader;
import com.exphub.entity.DispatchEvent;
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
     *
     * <p>可见性为包级（不是 private）：{@code DispatchRoleAliasTest} 要读值集合，
     * 断言「{@link #ASSIGNABLE_ROLES} 里每个角色都有映射」—— 两处漏一处，测试就红。
     */
    static final java.util.Map<String, String> ROLE_ALIASES = java.util.Map.ofEntries(
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
            // 2026-11-19 W-20261119-000010：应龙是第 12 个角色，加角色时漏了这张表，
            // 派单按 role=yinglong 查「指派给谁」一直落空（W-20261119-000007 卡在待接单）。
            java.util.Map.entry("yinglong", "应龙"),
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

    /** 派单操作流水（W-20261120-000025）：只在状态转移成功后追加，绝不 update/delete。 */
    @Autowired
    private com.exphub.mapper.DispatchEventMapper eventMapper;

    /**
     * 项目（发版取号要读 current_version）。
     * {@code required=false}：手工构造的单测没有它也能跑，只是取号会明确报「无基准」。
     */
    @Autowired(required = false)
    private com.exphub.mapper.ProjectMapper projectMapper;

    /**
     * 事件发布器（2026-11-19，用于「待验收 → 发邮件」）。
     * 用 {@code required=false}：手工构造的单测里没有它也能跑，缺它只是不发提醒。
     */
    @Autowired(required = false)
    private org.springframework.context.ApplicationEventPublisher events;

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
                          Integer priority, Long projectId, java.util.Map<String, Boolean> auths,
                          String apiKey, String version) {
        return create(creator, origin, assignee, title, description,
                priority, projectId, auths, apiKey, version, null);
    }

    /** 里程碑字符上限：与 DDL 的 VARCHAR(64) 对齐，按 Unicode 码点计（emoji 算 1 个）。 */
    public static final int MAX_MILESTONE_CHARS = 64;

    /**
     * 建单（带里程碑，W-20261120-000026）。milestone 可空：空串等同独立单。
     *
     * <p><b>超长明拒，绝不静默截断</b>：把「里程碑A很长…」截成 64 字可能与另一单截成同一串，
     * 于是两张不相干的单被聚合到同一张燃尽图上 —— 静默修正把错误伪装成正常，比直接失败糟。
     * 收到什么、为什么拒，全部回显给调用方。
     */
    @Transactional
    public Outcome create(String creator, String origin, String assignee, String title, String description,
                          Integer priority, Long projectId, java.util.Map<String, Boolean> auths,
                          String apiKey, String version, String milestone) {
        String ms = milestone == null ? null : milestone.trim();
        int msChars = ms == null ? 0 : ms.codePointCount(0, ms.length());
        if (msChars > MAX_MILESTONE_CHARS) {
            String shown = ms.length() <= 80 ? ms : ms.substring(0, 80) + "…";
            return Outcome.fail("里程碑超过 " + MAX_MILESTONE_CHARS + " 字符（收到 " + msChars
                    + "，内容：" + shown + "）—— 已拒绝建单，不会静默截断"
                    + "（截断会把两个不同里程碑拼成同一个，燃尽图数据串味）");
        }
        if (ms != null && ms.isEmpty()) ms = null;
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

        // 归属 Key（2026-11-19 修）：后台人工建单没有 Key 身份（后台登录是 users 表，没有 api_key 列），
        // 但 MCP 查单按 Key 隔离（buildFilter / getForApi），空 Key 的单哨兵永远看不见 ——
        // W-20261119-000001 就是这么挂在 READY 没人接的。
        // 所以人工单若没显式给 Key，就归属到「派单共享 Key」（最近一张 AI 单用的那把，
        // 全系统 13 个助手共用同一把）。取不到时保持空串并在回执里明说，不许静默。
        String owner = apiKey == null ? "" : apiKey.trim();
        boolean ownerAutoFilled = false;
        if (human && owner.isEmpty()) {
            String shared = sharedDispatchKey();
            if (!shared.isEmpty()) {
                owner = shared;
                ownerAutoFilled = true;
            }
        }
        h.setApiKey(owner);
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
        h.setMilestone(ms);
        // 版本号：只有勾了「发版」才有意义。没填就按「基准末尾 +1」自动算；
        // 算不出来就明拒，不许静默给一个 1.0.0（W-20261119-000006 就是错号被静默接受）。
        boolean willRelease = auths != null && Boolean.TRUE.equals(auths.get("release"));
        if (willRelease) {
            if (version != null && !version.isBlank()) {
                h.setVersion(version.trim());
                h.setVersionBasis("手动指定");
            } else {
                VersionBasis b = suggestVersionInfo(projectId);
                if (!b.ok()) {
                    return Outcome.fail("勾了发版但没填版本号，且无法自动推算：" + b.problem()
                            + "（不会静默按 1.0.0 兜底）");
                }
                h.setVersion(b.value());
                h.setVersionBasis(b.basis());
            }
        }
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
        // W-20261120-000025：建单事件。AI 建单落 REVIEWING 也是 CREATE（to=REVIEWING），
        // 审核通过是后续单独一条 REVIEW_PASS —— 不要混成一条，否则流水里看不到「谁建的、谁审的」。
        recordEvent(h.getOrderNo(), DispatchEvent.EV_CREATE, null, DispatchHeader.ST_REVIEWING,
                h.getCreator(), human ? DispatchEvent.ACTOR_HUMAN : DispatchEvent.ACTOR_AI,
                ms == null ? null : "里程碑：" + ms, h.getCreatedAt());
        log.info("DispatchService.create: order={}, origin={}, creator={}, assignee={}, ownerKey={}",
                h.getOrderNo(), h.getOrigin(), h.getCreator(), h.getAssignee(), maskKey(h.getApiKey()));
        String versionNote = willRelease
                ? "。发版版本 " + h.getVersion() + "（" + h.getVersionBasis() + "）"
                : "";
        return Outcome.success("已建单 " + h.getOrderNo() + "（状态：待审核）"
                + ownerNote(human, h.getApiKey(), ownerAutoFilled) + versionNote, h, null);
    }

    /**
     * 建单回执里说明「归属哪把 Key」，否则「写进去了没有」只能查库才知道。
     *
     * <p>人工单要特别解释：后台没有 Key 身份，归属的是派单共享 Key。
     * 取不到共享 Key 时必须明说「哨兵看不见」，不能让人以为建单成功就等于能派出去。
     */
    private static String ownerNote(boolean human, String owner, boolean autoFilled) {
        if (!human) return "";
        if (owner == null || owner.isBlank()) {
            return "。⚠️ 库里还没有任何 AI 单，找不到派单共享 Key —— 本单 api_key 仍为空，"
                 + "哨兵在 MCP 侧看不见；请先用 dispatch_create 建一张 AI 单，再重建本单";
        }
        return "。归属 Key " + maskKey(owner)
             + (autoFilled ? "（后台没有 Key 身份，人工单自动归属派单共享 Key，哨兵才看得见）" : "（你显式传入，按传入的写）");
    }

    /**
     * 派单共享 Key：取**最近一张 AI 单**的 api_key。
     *
     * <p>为什么不用配置：全系统 13 个助手共用同一把 Key，它已经写在历史单里；
     * 再引一份配置就要改服务器 env，且可能与调用方实际用的那把不一致（那才是更难查的错）。
     * 库里还没有 AI 单时返回空串，由调用方把「没法归属」如实报出来。
     */
    public String sharedDispatchKey() {
        DispatchHeader last = headerMapper.selectOne(new QueryWrapper<DispatchHeader>()
                .select("api_key")
                .eq("origin", DispatchHeader.ORIGIN_AI)
                .ne("api_key", "")
                .orderByDesc("id")
                .last("LIMIT 1"));
        return last == null || last.getApiKey() == null ? "" : last.getApiKey().trim();
    }

    /**
     * Key 只露尾 4 位：回执要能核对「是不是这把」，但不能把完整 Key 打进日志、页面或经验。
     */
    public static String maskKey(String key) {
        if (key == null || key.isBlank()) return "（空）";
        String k = key.trim();
        return k.length() <= 4 ? "…" + k : "…" + k.substring(k.length() - 4);
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
     * 单次查询最多返回多少张。MCP 的 limit 与后台每页条数共用这一个上限 ——
     * 两处各拍一个数（后台曾传 100、被这里悄悄截成 50），界面和意图就对不上了。
     */
    public static final int MAX_PAGE_SIZE = 50;

    /**
     * 列单。**不写任何账本、不推任何游标** —— 这是与旧信箱最大的区别，不要在这里加任何写操作。
     *
     * @param role 只看与我有关的：我指派的（assignee）或我创建的（creator）；null=不限
     */
    public List<DispatchHeader> list(String role, String status, String orderNo, int limit, String apiKey) {
        return list(role, status, orderNo, null, null, limit, 0, apiKey);
    }

    /** 带偏移量的重载（后台翻页用，不搜关键词）。 */
    public List<DispatchHeader> list(String role, String status, String orderNo,
                                     int limit, int offset, String apiKey) {
        return list(role, status, orderNo, null, null, limit, offset, apiKey);
    }

    /**
     * 列单 + 关键词模糊搜索（2026-11-17 越哥要求：后台与手机端派单列表要能搜）。
     *
     * <p>搜索口径：关键词按空白切词，**词之间 AND、词语内部 OR 全列** ——
     * 搜「派单 手机」= 同时含有这两个词的单，任一词落在哪一列都算命中。
     * 可搜列：单号 / 标题 / 描述 / 指派 / 创建人 / 接单人（不含日志类长文本 item）。
     *
     * <p>状态与关键词是「且」的关系，筛「已完成」再搜词，只在已完成的单里找。
     *
     * @param keyword 可为空；空 = 不过滤
     */
    public List<DispatchHeader> list(String role, String status, String orderNo, String keyword,
                                     int limit, String apiKey) {
        return list(role, status, orderNo, keyword, null, limit, 0, apiKey);
    }

    /** 带分页的重载（后台派单列表用），不含里程碑。 */
    public List<DispatchHeader> list(String role, String status, String orderNo, String keyword,
                                     int limit, int offset, String apiKey) {
        return list(role, status, orderNo, keyword, null, limit, offset, apiKey);
    }

    /**
     * 列单 + 搜索 + 里程碑筛选 + 分页（2026-11-21 加，后台派单列表要按里程碑筛）。
     *
     * <p>里程碑与状态、关键词是「且」的关系；milestone 空 = 不过滤（「全部」含未填里程碑的老单）。
     *
     * @param limit  每页条数；<=0 表示不限条数（MCP 传 0 就是要不截断）；上限 {@link #MAX_PAGE_SIZE}
     * @param offset 跳过前 N 条，从 0 开始；越界不报错，返回空列表
     */
    public List<DispatchHeader> list(String role, String status, String orderNo, String keyword,
                                     String milestone, int limit, int offset, String apiKey) {
        QueryWrapper<DispatchHeader> q = buildFilter(role, status, orderNo, keyword, milestone, apiKey);
        applyOrder(q, status);
        String page = limitClause(limit, offset);
        if (page != null) q.last(page);
        return headerMapper.selectList(q);
    }

    /** 带发版态筛选的重载（后台列表页发版下拉用），与 {@link #list} 同一套条件，只多一个 release_flag。 */
    public List<DispatchHeader> list(String role, String status, String orderNo, String keyword,
                                     String milestone, String releaseFlag, int limit, int offset, String apiKey) {
        QueryWrapper<DispatchHeader> q = buildFilter(role, status, orderNo, keyword, milestone, releaseFlag, apiKey);
        applyOrder(q, status);
        String page = limitClause(limit, offset);
        if (page != null) q.last(page);
        return headerMapper.selectList(q);
    }

    /**
     * 这个查询是不是「只看待接单」。归一规则与 {@link #buildFilter} 一致（trim + 忽略大小写）。
     */
    static boolean isReadyQueue(String status) {
        return status != null && DispatchHeader.ST_READY.equalsIgnoreCase(status.trim());
    }

    /**
     * 列表排序（唯一出口，后台 / 手机端 / MCP 三处共用）。
     *
     * <p><b>待接单必须先进先出</b>（2026-11-20 越哥定，W-20261120-000010）：
     * 哨兵的 watch.js 每 20 秒轮询 {@code dispatch_list(status=READY)} 并取首张认领，
     * 原来统一 {@code id DESC} 导致**后进先出** —— 新单一直插队，老单饿死在队尾。
     * 现在 READY 按 {@code priority ASC（1=高）+ created_at ASC + id ASC}：先做急的，
     * 同级按建单先后。其余状态保持 {@code id DESC}（后台和阁主查单要看最新的）。
     *
     * <p>为什么不靠调用方传排序参数：哨兵的 watch.js 不改、不发版就要生效，
     * 排序只能在服务端按状态决定。
     */
    static void applyOrder(QueryWrapper<DispatchHeader> q, String status) {
        if (isReadyQueue(status)) {
            q.orderByAsc("priority", "created_at", "id");
        } else {
            q.orderByDesc("id");
        }
    }

    /**
     * 当前查询生效的排序说明，**只此一份**（MCP 回执、后台页头都从这里取）。
     *
     * <p>为什么必须回显：排序是「看不见的行为」，2026-11-20 之前没人能从上一次回执里
     * 判断首张为什么是它。回执说清规则，人才对得上「老单终于排前面了」。
     */
    public static String orderNote(String status) {
        return isReadyQueue(status)
                ? "排序：待接单按优先级升序（1=高，先做急的）+ 建单时间升序（先进先出）"
                : "排序：id 倒序（最新建的单在最前）";
    }

    /**
     * 分页窗口：页码、总页数、本页显示的起止序号。
     *
     * <p>抽成纯函数而不是在两个 Controller 里各算一遍 —— 页头写的「第 a–b 张 / 共 N 张」
     * 与页码、与 SQL 的 offset 必须来自同一个算式，否则迟早出现「共 41 张、翻到第 3 页是空的」。
     */
    public record PageWindow(int page, int totalPages, long total,
                             int from, int to, int offset, int size) {}

    /** 越界页码**不报错、不返回空页**，落到最后一页；调用方拿 {@code page()} 回写 URL 保持地址与内容一致。 */
    public static PageWindow pageWindow(long total, int page, int size) {
        int sz = size <= 0 ? MAX_PAGE_SIZE : size;
        int totalPages = (int) Math.max(1, (total + sz - 1) / sz);
        int p = Math.min(Math.max(1, page), totalPages);
        int offset = (p - 1) * sz;
        int from = total == 0 ? 0 : offset + 1;
        int to = (int) Math.min(total, (long) offset + sz);
        return new PageWindow(p, totalPages, total, from, to, offset, sz);
    }

    /**
     * 后台可选的每页条数。**只认这几档**，别的一律回落到默认值 ——
     * {@code ?size=100000} 不该让一次请求把整张表塞进内存。
     *
     * <p>2026-11-19 加这个选择器的原因：库里只有 19 张单、PC 每页 20 张，
     * 于是「分页」在页面上根本看不见（页脚整块被 {@code totalPages > 1} 藏掉），
     * 越哥打开就只会说「还是没有分页」。**能看见、能验，才算做出来了。**
     */
    public static final java.util.List<Integer> PAGE_SIZE_CHOICES = java.util.List.of(10, 20, 50);

    /** 每页条数白名单校验：非白名单/空值 → 用 {@code defaultSize}。 */
    public static int normalizePageSize(Integer size, int defaultSize) {
        return (size != null && PAGE_SIZE_CHOICES.contains(size)) ? size : defaultSize;
    }

    /**
     * 与 {@link #list} **同一套过滤条件**的总数。
     *
     * <p>为什么必须同源：页头「共 N 张」与页码都读它。两处各拼一份 WHERE，
     * 迟早出现「页头说 51 张、翻到底只有 3 页」这种对不上的数（2026-11-19 之前的实际形状）。
     */
    public long count(String role, String status, String orderNo, String keyword, String apiKey) {
        return count(role, status, orderNo, keyword, null, apiKey);
    }

    /** 与带里程碑的 {@link #list} 同一套过滤条件的总数；页头「共 N 张」与翻页都读它，口径不漂。 */
    public long count(String role, String status, String orderNo, String keyword,
                      String milestone, String apiKey) {
        Long c = headerMapper.selectCount(buildFilter(role, status, orderNo, keyword, milestone, apiKey));
        return c == null ? 0L : c;
    }

    /** 与带发版态筛选的 {@link #list} 同源的总数（页头「共 N 张」与翻页都读它）。 */
    public long count(String role, String status, String orderNo, String keyword,
                      String milestone, String releaseFlag, String apiKey) {
        Long c = headerMapper.selectCount(buildFilter(role, status, orderNo, keyword, milestone, releaseFlag, apiKey));
        return c == null ? 0L : c;
    }

    /**
     * 库里已有的全部里程碑名：去重、去空、按名称排序。
     * 派单列表页下拉与燃尽图页下拉共用这一个数据源，避免两处 distinct 口径漂移；
     * 只查 milestone 一列，不拉整表正文。
     */
    public List<String> distinctMilestones() {
        return headerMapper.selectList(new QueryWrapper<DispatchHeader>()
                        .select("DISTINCT milestone")
                        .isNotNull("milestone").ne("milestone", ""))
                .stream().map(DispatchHeader::getMilestone)
                .filter(java.util.Objects::nonNull).sorted().toList();
    }

    /**
     * 里程碑下的全部单（按建单先后）。空/空白里程碑返回空表，
     * **绝不退化成「不加 WHERE 查全部」** —— 那是燃尽图最危险的错：漏传参数却返回全库。
     */
    public List<DispatchHeader> byMilestone(String milestone) {
        if (milestone == null || milestone.isBlank()) return List.of();
        return headerMapper.selectList(new QueryWrapper<DispatchHeader>()
                .eq("milestone", milestone.trim()).orderByAsc("id"));
    }

    /** 与 {@link #byMilestone} 同一口径的分页与计数（右栏「展开里程碑看单头」用）。 */
    public long countByMilestone(String milestone) {
        return countByMilestone(milestone, null);
    }

    public long countByMilestone(String milestone, String status) {
        if (milestone == null || milestone.isBlank()) return 0L;
        QueryWrapper<DispatchHeader> q = new QueryWrapper<DispatchHeader>().eq("milestone", milestone.trim());
        applyStatus(q, status);
        Long c = headerMapper.selectCount(q);
        return c == null ? 0L : c;
    }

    public List<DispatchHeader> listByMilestone(String milestone, int limit, int offset) {
        return listByMilestone(milestone, null, limit, offset);
    }

    public List<DispatchHeader> listByMilestone(String milestone, String status, int limit, int offset) {
        if (milestone == null || milestone.isBlank()) return List.of();
        QueryWrapper<DispatchHeader> q = new QueryWrapper<DispatchHeader>()
                .eq("milestone", milestone.trim()).orderByAsc("id");
        applyStatus(q, status);
        String page = limitClause(limit, offset);
        if (page != null) q.last(page);
        return headerMapper.selectList(q);
    }

    /** 列表与统计共用的过滤条件（不含排序、不含分页）。 */
    /** 状态过滤唯一出口：列表/计数/里程碑筛选共用，不许各拼一份（否则页头与翻页口径会漂）。 */
    static void applyStatus(QueryWrapper<DispatchHeader> q, String status) {
        if (status != null && !status.isBlank()) {
            q.eq("status", status.trim().toUpperCase(java.util.Locale.ROOT));
        }
    }

    static QueryWrapper<DispatchHeader> buildFilter(String role, String status, String orderNo,
                                                    String keyword, String apiKey) {
        return buildFilter(role, status, orderNo, keyword, null, apiKey);
    }

    /** 列表与统计共用的过滤条件（含里程碑），不含排序、不含分页。 */
    static QueryWrapper<DispatchHeader> buildFilter(String role, String status, String orderNo,
                                                    String keyword, String milestone, String apiKey) {
        return buildFilter(role, status, orderNo, keyword, milestone, null, apiKey);
    }

    /** 带发版态的过滤条件唯一出口；旧 6 参签名委托到这里，保证筛选口径只有一份。 */
    static QueryWrapper<DispatchHeader> buildFilter(String role, String status, String orderNo,
                                                     String keyword, String milestone, String releaseFlag, String apiKey) {
        QueryWrapper<DispatchHeader> q = new QueryWrapper<>();
        // Key 隔离：null = 后台视角（看全部）；非 null = 只看这个 Key 的
        if (apiKey != null) q.eq("api_key", apiKey);
        if (orderNo != null && !orderNo.isBlank()) {
            q.eq("order_no", orderNo.trim());
        }
        applyStatus(q, status);
        if (role != null && !role.isBlank()) {
            String r = role.trim();
            q.and(w -> w.eq("assignee", r).or().eq("creator", r));
        }
        applyKeyword(q, keyword);
        applyMilestone(q, milestone);
        applyReleaseFlag(q, releaseFlag);
        return q;
    }

    /**
     * 生成 MySQL 的 `LIMIT offset,size`；
     * limit&lt;=0（不限条数）返回 null，调用方据此决定要不要拼这一段。
     */
    static String limitClause(int limit, int offset) {
        if (limit <= 0) return null;
        return "LIMIT " + Math.max(0, offset) + "," + Math.min(limit, MAX_PAGE_SIZE);
    }

    /**
     * 把关键词条件拼进 QueryWrapper。抽成独立方法是为了能**不开数据库就测**：
     * 单测直接看生成的 SQL 片段，确认括号分组（词之间 AND）没拼错 ——
     * 拼错的表现是「筛了状态又搜词，结果把别的状态也捞回来了」，线上很难一眼看出来。
     */
    static void applyKeyword(QueryWrapper<DispatchHeader> q, String keyword) {
        if (keyword == null || keyword.isBlank()) return;
        for (String token : keyword.trim().split("\\s+")) {
            if (token.isEmpty()) continue;
            String k = escapeLike(token);
            q.and(w -> w.like("order_no", k).or().like("title", k).or()
                    .like("assignee", k).or().like("creator", k).or()
                    .like("accepter", k).or().like("description", k));
        }
    }

    /**
     * 里程碑筛选条件**唯一出口**：空/空白不加条件（「全部」要把 NULL 老单一起算进来）。
     * 非空按完整值精确匹配（eq），与 status / keyword 天然是「且」关系，
     * 不在 controller 或模板里另拼一份 —— 两处各写一份迟早口径漂移。
     */
    static void applyMilestone(QueryWrapper<DispatchHeader> q, String milestone) {
        if (milestone == null || milestone.isBlank()) return;
        q.eq("milestone", milestone.trim());
    }

    /** 未标记筛选用这个值（四态里「未标记」对应 NULL 或空串）。 */
    public static final String RF_UNMARKED_FILTER = "UNMARKED";

    /** 发版态筛选唯一出口：空 = 不过滤；UNMARKED 单筛未标记；其余精确匹配；与状态/关键词天然「且」。 */
    static void applyReleaseFlag(QueryWrapper<DispatchHeader> q, String releaseFlag) {
        if (releaseFlag == null || releaseFlag.isBlank()) return;
        String f = releaseFlag.trim().toUpperCase(java.util.Locale.ROOT);
        if (RF_UNMARKED_FILTER.equals(f)) {
            q.and(w -> w.isNull("release_flag").or().eq("release_flag", ""));
        } else {
            q.eq("release_flag", f);
        }
    }

    /**
     * LIKE 通配符转义：用户输入里的百分号、下划线、反斜杠一律当字面量。
     * MySQL 的 LIKE 默认转义符就是反斜杠，所以这里加反斜杠就够，不用额外写 ESCAPE 子句。
     *
     * <p>实现已抽到 {@link com.exphub.util.LikeEscape}，这里只保留旧签名给既有调用方与测试。</p>
     */
    static String escapeLike(String raw) {
        return com.exphub.util.LikeEscape.escape(raw);
    }

    /** 内部取单，不做 Key 校验。只有后台与「已确定归属」的内部逻辑可以调。 */
    public DispatchHeader get(String orderNo) {
        if (orderNo == null || orderNo.isBlank()) return null;
        return headerMapper.selectOne(new QueryWrapper<DispatchHeader>().eq("order_no", orderNo.trim()));
    }

    /**
     * 带 Key 校验取单。归属不符时返回 null —— **对外表现成「不存在」，不泄露单子存在性**。
     *
     * <p>MCP 路径一律走这个，不要直接调 {@link #get}。
     */
    public DispatchHeader getForApi(String orderNo, String apiKey) {
        DispatchHeader h = get(orderNo);
        if (h == null) return null;
        String owner = h.getApiKey() == null ? "" : h.getApiKey();
        String caller = apiKey == null ? "" : apiKey.trim();
        return owner.equals(caller) ? h : null;
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
    public Outcome claim(String orderNo, String role, String apiKey) {
        role = canonicalRole(role);
        if (role == null || role.isBlank()) return Outcome.fail("认领必须先确定你是谁（角色头未配置？）");
        DispatchHeader h = getForApi(orderNo, apiKey);
        if (h == null) return Outcome.fail("未找到单号 " + orderNo + "（或不属于你）。");
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
        recordEvent(h.getOrderNo(), DispatchEvent.EV_CLAIM, DispatchHeader.ST_READY,
                DispatchHeader.ST_RUNNING, role.trim(), DispatchEvent.ACTOR_AI, null, now);

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
    public Outcome needConfirm(String orderNo, String role, String question, String proposal, String apiKey) {
        if (proposal == null || proposal.isBlank()) {
            return Outcome.fail("提问必须自带建议方案：只抛问题不许过（防 ping-pong 的硬规矩）。");
        }
        role = canonicalRole(role);
        DispatchHeader h = getForApi(orderNo, apiKey);
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
        recordEvent(h.getOrderNo(), DispatchEvent.EV_NEED_CONFIRM, DispatchHeader.ST_RUNNING,
                DispatchHeader.ST_CONFIRMING, role.trim(), DispatchEvent.ACTOR_AI, body, now);
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
    public Outcome reply(String orderNo, String actor, String text, String apiKey) {
        actor = canonicalRole(actor);
        DispatchHeader h = getForApi(orderNo, apiKey);
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
        recordEvent(h.getOrderNo(), DispatchEvent.EV_REPLY, DispatchHeader.ST_CONFIRMING,
                DispatchHeader.ST_RUNNING, actor.trim(), DispatchEvent.ACTOR_AI, text, now);

        DispatchItem it = newItem(h, actor.trim(), DispatchItem.CMD_EXEC, false, "继续执行", text,
                DispatchItem.ST_RUNNING);
        return Outcome.success("已回复并继续执行，球回到「" + h.getAssignee() + "」。item " + it.getItemNo(), get(orderNo), it);
    }

    /**
     * 提交验收：RUNNING → VERIFYING。之后只有越哥能在后台点通过/驳回。
     *
     * <p><b>结论 ≠ 内容（2026-11-17 越哥定）：</b>结论是**对内容的总结**，一两句话，
     * 越哥照它判断通过还是驳回；内容是**详细正文**，做过什么/改了哪些文件/怎么验证的，可以很长。
     * 改之前这里两个参数传的是同一个 {@code conclusion}，导致 item 的结论和内容一字不差 ——
     * 不是 AI 抄，是程序把同一个值写了两栏。
     */
    @Transactional
    public Outcome submitAccept(String orderNo, String role, String conclusion, String content, String apiKey) {
        role = canonicalRole(role);
        DispatchHeader h = getForApi(orderNo, apiKey);
        Outcome guard = guardAssigneeAndStatus(h, role, DispatchHeader.ST_RUNNING, "提交验收");
        if (guard != null) return guard;
        if (conclusion == null || conclusion.isBlank()) {
            return Outcome.fail("提交验收必须写结论：越哥要照它判断通过还是驳回。");
        }
        String concl = clampConclusion(conclusion);
        String body = (content == null || content.isBlank()) ? null : content.trim();
        // 结论写成了正文的复制品 → 拒。结论就是总结，一字不差说明没总结。
        if (body != null && body.equals(concl)) {
            return Outcome.fail("结论和内容不能一样：结论写一两句总结（越哥只看这一行），"
                    + "内容写详细过程（改了什么、怎么验证的），长一点没问题。请分开写再提交。");
        }

        // 关闭当前工作轮时不回填结论（传 null）：那一刻的结论属于下面新建的「提交验收」item，
        // 工作轮自己只有「认领开工」这一条流水。不回填，页面上两条 item 才不会顶着一模一样的结论。
        // 注意：needConfirm / terminate 那两处照旧回填 —— 那两轮是真的结束了，得有结论。
        closeOpenItem(h, role, null, DispatchItem.ST_DONE);
        LocalDateTime now = LocalDateTime.now();
        int rows = headerMapper.update(null, new UpdateWrapper<DispatchHeader>()
                .eq("order_no", h.getOrderNo())
                .eq("status", DispatchHeader.ST_RUNNING)
                .set("status", DispatchHeader.ST_VERIFYING)
                .set("updated_at", now));
        if (rows == 0) return Outcome.fail("状态已变化，提交验收未生效，请重新查一次。");
        recordEvent(h.getOrderNo(), DispatchEvent.EV_SUBMIT_ACCEPT, DispatchHeader.ST_RUNNING,
                DispatchHeader.ST_VERIFYING, role.trim(), DispatchEvent.ACTOR_AI, concl, now);

        DispatchItem it = newItem(h, role.trim(), DispatchItem.CMD_EXEC, true, concl, body,
                DispatchItem.ST_DONE);
        // 2026-11-19：进入待验收时给越哥发邮件。事务内只发事件，监听器 AFTER_COMMIT 才真发信
        // （回滚了不该发「待验收」邮件）；通知失败也不许影响这次状态转移。
        publishVerifying(h, concl, body, it, now);
        return Outcome.success("已提交验收，等越哥判断。item " + it.getItemNo(), get(orderNo), it);
    }

    /**
     * 发「待验收」事件。异常一律吞掉 —— 通知是纯副作用，不能拖垮状态转移（AGENTS.md 第 2 章）。
     * {@code events} 为空（手工构造的单测）时安静跳过。
     */
    private void publishVerifying(DispatchHeader h, String conclusion, String content,
                                  DispatchItem it, LocalDateTime at) {
        if (events == null) {
            return;
        }
        try {
            events.publishEvent(new com.exphub.notify.DispatchVerifyingEvent(
                    h.getOrderNo(), h.getTitle(), h.getAssignee(), h.getAccepter(),
                    conclusion, content, it == null ? null : it.getItemNo(), at));
        } catch (Exception e) {
            log.warn("派单 {} 的验收事件发布失败，已忽略：{}", h.getOrderNo(), e.getClass().getSimpleName());
        }
    }

    /**
     * 终止本轮：item 终止（不唤醒大模型），header 同时进 CONFIRMING 等通知。
     *
     * <p>越哥 2026-11-16 定：「item 的终止只是不要有下一步的操作了，等待通知」。
     * 所以终止之后 header 必须离开 RUNNING，否则球在哨兵手里却禁止动作，单子悬死。
     */
    @Transactional
    public Outcome terminate(String orderNo, String role, String reason, String apiKey) {
        role = canonicalRole(role);
        DispatchHeader h = getForApi(orderNo, apiKey);
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
        recordEvent(h.getOrderNo(), DispatchEvent.EV_TERMINATE, DispatchHeader.ST_RUNNING,
                DispatchHeader.ST_CONFIRMING, role.trim(), DispatchEvent.ACTOR_AI, reason, now);

        DispatchItem it = newItem(h, role.trim(), DispatchItem.CMD_TERMINATE, true,
                "已终止本轮", reason == null ? "" : reason, DispatchItem.ST_DONE);
        return Outcome.success("本轮已终止，header 已进「待确认」等通知（单子没有结束）。item " + it.getItemNo(),
                get(orderNo), it);
    }

    // ==================== 发版标记 release_flag（W-20261121-000014，与九态状态机正交）====================

    /** release_flag 四态归一：null/空白统一成空串（= 未标记），其余 trim + 大写。 */
    public static String normalizeReleaseFlag(String raw) {
        if (raw == null || raw.isBlank()) return "";
        return raw.trim().toUpperCase(java.util.Locale.ROOT);
    }

    /** 四态中文标签：列表/回执/详情共用一处，避免各端文案漂移。 */
    public static String releaseFlagLabel(String raw) {
        String f = normalizeReleaseFlag(raw);
        return switch (f) {
            case DispatchHeader.RF_NEED -> "需发版";
            case DispatchHeader.RF_RELEASED -> "已发版";
            case DispatchHeader.RF_NONE -> "无需发版";
            default -> "未标记";
        };
    }

    /**
     * AI 侧写发版标记：只有 NEED / NONE 两个目标态能过，RELEASED 与清空一律拒绝。
     *
     * <p><b>权限分离硬闸门</b>（W-20261121-000014 红线）：MCP 的 dispatch_act(mark_release)
     * 只能走到这里，物理上没有任何路径能让 AI 写 RELEASED —— 发版是越哥点头的事，
     * 状态不能由 AI 自己宣布完成。目标态白名单在 Service 层强制，不靠调用方自觉，
     * 也不接受枚举外的值（静默修正会把上游错误伪装成正常）。
     */
    @Transactional
    public Outcome markReleaseFlagAi(String orderNo, String targetFlag, String reason, String role, String apiKey) {
        String flag = normalizeReleaseFlag(targetFlag);
        boolean allowed = DispatchHeader.RF_NEED.equals(flag) || DispatchHeader.RF_NONE.equals(flag);
        if (!allowed) {
            String got = targetFlag == null ? "(null)" : targetFlag;
            return Outcome.fail("AI 只能标记「需发版 NEED」或「无需发版 NONE」，收到「" + got
                    + "」已拒绝。已发版 RELEASED 只能由越哥在后台点「已发布」按钮写，AI 不能自己宣布发版完成。");
        }
        role = canonicalRole(role);
        if (role == null || role.isBlank()) return Outcome.fail("无法确定你是谁（角色头未配置？）。");
        DispatchHeader h = getForApi(orderNo, apiKey);
        if (h == null) return Outcome.fail("未找到单号 " + orderNo + "（或不属于你）。");
        return applyReleaseFlag(h, flag, reason, role.trim(), DispatchEvent.ACTOR_AI);
    }

    /**
     * 后台侧写发版标记（越哥）：四态都能写，含 RELEASED 与清空回未标记。
     *
     * <p>只由后台 Controller 调，没有 MCP 入口 —— 与审核/验收同一套物理隔离。
     * 标错了要能救，所以允许越哥手工改回 NONE / 未标记。
     */
    @Transactional
    public Outcome markReleaseFlagHuman(String orderNo, String targetFlag, String reason) {
        String flag = normalizeReleaseFlag(targetFlag);
        boolean allowed = flag.isEmpty()
                || DispatchHeader.RF_NEED.equals(flag)
                || DispatchHeader.RF_RELEASED.equals(flag)
                || DispatchHeader.RF_NONE.equals(flag);
        if (!allowed) {
            String got = targetFlag == null ? "(null)" : targetFlag;
            return Outcome.fail("发版标记只能是 未标记 / NEED / RELEASED / NONE，收到「" + got + "」已拒绝。");
        }
        DispatchHeader h = get(orderNo);
        if (h == null) return Outcome.fail("未找到单号 " + orderNo + "。");
        return applyReleaseFlag(h, flag, reason, HUMAN_OPERATOR, DispatchEvent.ACTOR_HUMAN);
    }

    /** 两个写入口共用的落地：同事务写 header + 一条 RELEASE_FLAG 事件。 */
    private Outcome applyReleaseFlag(DispatchHeader h, String flag, String reason,
                                     String actor, String actorType) {
        String old = normalizeReleaseFlag(h.getReleaseFlag());
        LocalDateTime now = LocalDateTime.now();
        // 清空用 set(col,null)：MyBatis-Plus 默认忽略实体里的 null，UpdateWrapper.set 会显式写 NULL
        int rows = headerMapper.update(null, new UpdateWrapper<DispatchHeader>()
                .eq("order_no", h.getOrderNo())
                .set("release_flag", flag.isEmpty() ? null : flag)
                .set("updated_at", now));
        if (rows == 0) return Outcome.fail("发版标记未生效，请刷新重试。");
        String note = (reason == null || reason.isBlank()) ? null
                : "发版标记 " + (old.isEmpty() ? "未标记" : old) + " → " + (flag.isEmpty() ? "未标记" : flag) + "；" + reason.trim();
        recordEvent(h.getOrderNo(), DispatchEvent.EV_RELEASE_FLAG,
                old.isEmpty() ? null : old, flag.isEmpty() ? null : flag,
                actor, actorType, note, now);
        log.info("DispatchService.releaseFlag: order={} {}→{} by {} ({})",
                h.getOrderNo(), old, flag, actor, actorType);
        return Outcome.success("发版标记已由「" + releaseFlagLabel(old) + "」改为「" + releaseFlagLabel(flag)
                + "」。", get(h.getOrderNo()), null);
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
        // 2026-11-16 修：只在**原本没有结论**时补一句，不覆盖已有结论。
        // 之前无条件覆盖，导致「回复继续」建的那条 item（结论=继续执行）
        // 被下一轮 closeOpenItem 改写成「请求确认，本轮结束」—— 审计流水被篡改。
        boolean noConclusion = open.getConclusion() == null || open.getConclusion().isBlank();
        if (noConclusion && conclusion != null && !conclusion.isBlank()) {
            open.setConclusion(conclusion);
        }
        itemMapper.updateById(open);
        return open;
    }

    /** conclusion 列是 varchar(2000)，写超了 MySQL 直接报错；结论本来就该短，超长截断并留痕。 */
    private String clampConclusion(String conclusion) {
        String c = conclusion.trim();
        if (c.length() <= 1900) return c;
        log.warn("DispatchService: 结论 {} 字超过列宽，已截断到 1900 字（正文请放 content）", c.length());
        return c.substring(0, 1900) + "…（结论超长已截断，详细的请放内容）";
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

    /**
     * 追加一条操作流水，必须与状态 UPDATE 在**同一事务**里（调用方都标了 @Transactional）。
     *
     * <p><b>为什么不 try-catch 吞掉</b>：事件是审计事实，不是「邮件通知」那种可降级的副作用。
     * 派单明确要「状态变了事件没写、或事件写了状态没变」都不允许 —— 所以事件写失败必须让事务回滚，
     * 与状态变更要么全成、要么全不成（这点和 {@link #publishVerifying} 的邮件恰好相反）。
     *
     * <p>eventMapper 为 null（手工构造、没注入的单测）时安静跳过 —— 不影响既有只测状态转移的单测。
     */
    private void recordEvent(String orderNo, String eventType, String fromStatus, String toStatus,
                             String actor, String actorType, String note, LocalDateTime at) {
        if (eventMapper == null) return;
        DispatchEvent e = new DispatchEvent();
        e.setOrderNo(orderNo);
        e.setEventType(eventType);
        e.setFromStatus(fromStatus);
        e.setToStatus(toStatus);
        e.setActor(actor);
        e.setActorType(actorType);
        e.setNote(note == null || note.isBlank() ? null : note);
        e.setCreatedAt(at == null ? LocalDateTime.now() : at);
        eventMapper.insert(e);
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
            "重明鸟", "九尾狐", "帝江", "穷奇", "陆吾", "饕餮", "应龙");

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
        recordEvent(h.getOrderNo(), DispatchEvent.EV_REVIEW_PASS, DispatchHeader.ST_REVIEWING,
                DispatchHeader.ST_READY, HUMAN_OPERATOR, DispatchEvent.ACTOR_HUMAN, note, now);
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
        recordEvent(h.getOrderNo(), DispatchEvent.EV_REVIEW_REJECT, DispatchHeader.ST_REVIEWING,
                DispatchHeader.ST_CANCELLED, HUMAN_OPERATOR, DispatchEvent.ACTOR_HUMAN, why, now);
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
        recordEvent(h.getOrderNo(), DispatchEvent.EV_ACCEPT_PASS, DispatchHeader.ST_VERIFYING,
                DispatchHeader.ST_DONE, HUMAN_OPERATOR, DispatchEvent.ACTOR_HUMAN, note, now);
        closeOpenItem(h, HUMAN_OPERATOR, "验收通过", DispatchItem.ST_DONE);
        rememberProjectVersion(h);
        log.info("DispatchService.verifyPass: order={} 验收通过 → DONE", orderNo);
        return Outcome.success("验收通过，单子已完成。", get(orderNo), null);
    }

    /**
     * 发版单验收通过后，把这一版记成项目当前版本（只升不降）。
     *
     * <p>为什么在「验收通过」而不是「提交验收」：提交验收只是哨兵说做完了，
     * 越哥点通过才是这版真的上线定稿。提前回写会让下一张单基于一个还没上线的号取号。
     *
     * <p>异常一律吞掉：项目版本更新是附带动作，不能因为写它失败就把验收本身回滚。
     * 失败的后果只是下一张单少一个基准，比「验收点不动」轻得多。
     */
    private void rememberProjectVersion(DispatchHeader h) {
        if (projectMapper == null || h == null) return;
        if (h.getProjectId() == null) return;
        if (h.getVersion() == null || h.getVersion().isBlank()) return;
        try {
            com.exphub.entity.Project p = projectMapper.selectById(h.getProjectId());
            if (p == null) return;
            if (p.getCurrentVersion() != null && !p.getCurrentVersion().isBlank()
                    && compareVersions(h.getVersion(), p.getCurrentVersion()) <= 0) {
                return;
            }
            LocalDateTime now = LocalDateTime.now();
            projectMapper.update(null, new UpdateWrapper<com.exphub.entity.Project>()
                    .eq("id", p.getId())
                    .set("current_version", h.getVersion().trim())
                    .set("current_version_updated_at", now)
                    .set("current_version_source", "派单 " + h.getOrderNo() + " 验收通过")
                    .set("updated_at", now));
            log.info("DispatchService.rememberProjectVersion: project={} current_version={}（来自 {}）",
                    p.getId(), h.getVersion(), h.getOrderNo());
        } catch (Exception e) {
            log.warn("DispatchService.rememberProjectVersion: order={} 回写项目版本失败，已忽略：{}",
                    h.getOrderNo(), e.getClass().getSimpleName());
        }
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
        recordEvent(h.getOrderNo(), DispatchEvent.EV_ACCEPT_REJECT, DispatchHeader.ST_VERIFYING,
                DispatchHeader.ST_RUNNING, HUMAN_OPERATOR, DispatchEvent.ACTOR_HUMAN, reason, now);

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
        String cancelNote = (reason == null || reason.isBlank()) ? "人工取消" : reason;
        recordEvent(h.getOrderNo(), DispatchEvent.EV_CANCEL, h.getStatus(),
                DispatchHeader.ST_CANCELLED, HUMAN_OPERATOR, DispatchEvent.ACTOR_HUMAN, cancelNote, now);
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
        recordEvent(h.getOrderNo(), DispatchEvent.EV_REPLY, DispatchHeader.ST_CONFIRMING,
                DispatchHeader.ST_RUNNING, HUMAN_OPERATOR, DispatchEvent.ACTOR_HUMAN, text, now);

        DispatchItem it = newItem(h, HUMAN_OPERATOR, DispatchItem.CMD_EXEC, false, "继续执行",
                text == null ? "" : text, DispatchItem.ST_RUNNING);
        return Outcome.success("已回复并继续执行，球回到「" + h.getAssignee() + "」。item " + it.getItemNo(),
                get(orderNo), it);
    }

    /**
     * 发版版本号的推算依据。**结果和依据必须一起返回**，否则「1.0.3 是怎么算出来的」
     * 只能事后查库反推（W-20261119-000006 就是这么一路错到发版前才被发现）。
     *
     * <p>{@code value} 为空表示**算不出来**，{@code problem} 写明为什么、下一步怎么办。
     * 调用方必须把这个 problem 原样告诉人，不许自己补一个 1.0.0 兜底。
     */
    public record VersionBasis(String value, String basis, String problem) {
        public boolean ok() { return value != null && !value.isBlank() && problem == null; }
        /** 回执/页面用的一行话。 */
        public String oneLine() {
            return ok() ? "基准：" + basis + " → " + value : "无法自动取号：" + problem;
        }
    }

    /**
     * 猜一个版本号：基准（项目当前版本 / 项目历史发版单里数值最大的）末尾数字 +1。
     *
     * <p><b>为什么基准不再取「最近一张」</b>（2026-11-19 W-20261119-000008）：
     * 「最近」是按 id 排的，而历史单里的版本号本身可能是错的。
     * 忆阁项目的派单号段一路从 1.0.0 自我繁殖到 1.0.3，而线上实际是 3.4.x ——
     * 于是每新建一张单，都把错号再往前推一格。按 id 取最近 = 把错误当基准。
     *
     * <p><b>为什么还要项目当前版本</b>：历史发版单可能全都错，或者压根没有带版本的单。
     * 这时只能由项目维护一个真实基准（发版单验收通过会自动回写，后台也可手填）。
     * 两者都没有就**明确报错要求手填**，绝不静默给 1.0.0。
     *
     * <p><b>为什么不再「项目为空退到全局」</b>：全局最大版本是别的项目的号段
     * （exphub 的 2.5.x 与忆阁的 3.4.x 混在一起），拿它给没有项目的单取号
     * 只会制造下一个 1.0.3。没有项目就没有基准，必须手填。
     */
    public VersionBasis suggestVersionInfo(Long projectId) {
        if (projectId == null) {
            return new VersionBasis(null, null,
                    "没有指定项目，无法确定版本号基准。请先选项目（MCP 传 projectId / 后台选项目），或手填版本号。");
        }
        com.exphub.entity.Project p = projectMapper == null ? null : projectMapper.selectById(projectId);
        if (p == null) {
            return new VersionBasis(null, null,
                    "项目 #" + projectId + " 不存在，无法确定版本号基准。请确认 projectId，或手填版本号。");
        }

        String best = null;
        String basis = null;
        // ① 项目当前版本（真实基准）
        if (p.getCurrentVersion() != null && !p.getCurrentVersion().isBlank()) {
            best = p.getCurrentVersion().trim();
            basis = "项目「" + p.getName() + "」当前版本 " + best;
        }
        // ② 项目历史发版单里数值最大的一张（不是 id 最大）
        DispatchHeader maxOrder = maxVersionedOrder(projectId);
        if (maxOrder != null && !maxOrder.getVersion().isBlank()
                && (best == null || compareVersions(maxOrder.getVersion(), best) > 0)) {
            best = maxOrder.getVersion().trim();
            basis = "项目「" + p.getName() + "」历史发版单 " + maxOrder.getOrderNo() + " = " + best;
        }

        if (best == null) {
            return new VersionBasis(null, null,
                    "项目「" + p.getName() + "」还没有当前版本，也没有带版本号的历史发版单。"
                    + "请先在项目页填「当前版本」，或手填版本号。");
        }
        String next = bumpTail(best);
        return new VersionBasis(next, basis + "（末尾 +1）", null);
    }

    /**
     * 兼容旧调用：只要字符串（模板 placeholder）。算不出来时返回空串 ——
     * 页面必须同时说明「无基准，必须手填」，不能拿空串当 1.0.0。
     */
    public String suggestVersion(Long projectId) {
        VersionBasis b = suggestVersionInfo(projectId);
        return b.ok() ? b.value() : "";
    }

    /** 该项目所有带版本号的历史单里，版本数值最大的那张（同值取 id 大的）。 */
    private DispatchHeader maxVersionedOrder(Long projectId) {
        QueryWrapper<DispatchHeader> q = new QueryWrapper<DispatchHeader>()
                .select("id", "order_no", "version", "created_at")
                .isNotNull("version").ne("version", "");
        if (projectId != null) q.eq("project_id", projectId);
        List<DispatchHeader> rows = headerMapper.selectList(q);
        DispatchHeader best = null;
        for (DispatchHeader r : rows) {
            if (r.getVersion() == null || r.getVersion().isBlank()) continue;
            if (best == null
                    || compareVersions(r.getVersion(), best.getVersion()) > 0
                    || (compareVersions(r.getVersion(), best.getVersion()) == 0
                        && (r.getId() == null ? -1 : r.getId()) > (best.getId() == null ? -1 : best.getId()))) {
                best = r;
            }
        }
        return best;
    }

    /**
     * 按数字段比较版本号：1.0.10 &gt; 1.0.9、3.4.98 &gt; 3.4.64、v2 &gt; v1。
     *
     * <p>纯字符串比较会把 1.0.10 排到 1.0.9 前面（'1' &lt; '9'），
     * 取「最大历史版本」时会退回到更旧的号上 —— 这是取号的另一半根因。
     * 数字段用 BigInteger 比，多余段按 0 补（3.4 与 3.4.0 相等）。
     */
    public static int compareVersions(String a, String b) {
        java.util.List<java.math.BigInteger> pa = versionSegments(a);
        java.util.List<java.math.BigInteger> pb = versionSegments(b);
        int n = Math.max(pa.size(), pb.size());
        for (int i = 0; i < n; i++) {
            java.math.BigInteger x = i < pa.size() ? pa.get(i) : java.math.BigInteger.ZERO;
            java.math.BigInteger y = i < pb.size() ? pb.get(i) : java.math.BigInteger.ZERO;
            int c = x.compareTo(y);
            if (c != 0) return c;
        }
        return 0;
    }

    private static java.util.List<java.math.BigInteger> versionSegments(String v) {
        java.util.List<java.math.BigInteger> out = new java.util.ArrayList<>();
        if (v == null || v.isBlank()) return out;
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("\\d+").matcher(v);
        while (m.find()) out.add(new java.math.BigInteger(m.group()));
        return out;
    }

    /**
     * 把版本号末尾那段连续数字 +1，前面原样保留。
     *
     * <p>2.5.6 → 2.5.7、3.4.51 → 3.4.52、v1 → v2；末尾不是数字就补一个 {@code .1}。
     * 前导零保留位数（01 → 02），避免把 01 变成 2 这种跳格式。
     */
    public static String bumpTail(String v) {
        if (v == null || v.isBlank()) return "1.0.0";
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("^(.*?)(\\d+)$").matcher(v.trim());
        if (m.matches()) {
            String head = m.group(1);
            String num = m.group(2);
            java.math.BigInteger n = new java.math.BigInteger(num).add(java.math.BigInteger.ONE);
            String out = n.toString();
            if (num.startsWith("0") && out.length() < num.length()) {
                out = "0".repeat(num.length() - out.length()) + out;
            }
            return head + out;
        }
        return v.trim() + ".1";
    }

    /**
     * 待审核时改授权位 / 版本号（越哥 2026-11-16：「授权位在待审核的时候我可以修改，这一点很重要」）。
     *
     * <p><b>只有 REVIEWING 能改</b>。一旦审核通过进了 READY，授权位重新锁死 ——
     * 否则 AI 可以在执行途中想办法让人「顺手改一下」，那就等于授权位可动态扩权。
     *
     * <p>改动本身就是审核动作的一部分，不额外触发退回：这一步本来就发生在审核之前。
     */
    @Transactional
    public Outcome updateAuths(String orderNo, java.util.Map<String, Boolean> auths, String version) {
        DispatchHeader h = get(orderNo);
        if (h == null) return Outcome.fail("未找到单号 " + orderNo);
        if (!DispatchHeader.ST_REVIEWING.equals(h.getStatus())) {
            return Outcome.fail("只有「待审核」能改授权位。当前状态是「" + statusLabel(h.getStatus())
                    + "」—— 审核通过后就锁死了，真要加权限得取消重开。");
        }
        java.util.Map<String, Boolean> a = auths == null ? java.util.Map.of() : auths;
        boolean willRelease = Boolean.TRUE.equals(a.get("release"));
        String v = null;
        String vb = null;
        if (willRelease) {
            if (version != null && !version.isBlank()) {
                v = version.trim();
                vb = "手动指定";
            } else {
                VersionBasis b = suggestVersionInfo(h.getProjectId());
                if (!b.ok()) {
                    return Outcome.fail("勾了发版但没填版本号，且无法自动推算：" + b.problem()
                            + "（不会静默按 1.0.0 兜底）");
                }
                v = b.value();
                vb = b.basis();
            }
        }

        LocalDateTime now = LocalDateTime.now();
        int rows = headerMapper.update(null, new UpdateWrapper<DispatchHeader>()
                .eq("order_no", h.getOrderNo())
                .eq("status", DispatchHeader.ST_REVIEWING)
                .set("auth_modify_code", flag(a, "modifyCode"))
                .set("auth_commit", flag(a, "commit"))
                .set("auth_push", flag(a, "push"))
                .set("auth_release", flag(a, "release"))
                .set("auth_data_write", flag(a, "dataWrite"))
                .set("auth_migration", flag(a, "migration"))
                .set("auth_credential", flag(a, "credential"))
                .set("auth_prod_config", flag(a, "prodConfig"))
                .set("auth_security_group", flag(a, "securityGroup"))
                .set("auth_firewall", flag(a, "firewall"))
                .set("auth_spend", flag(a, "spend"))
                .set("version", willRelease ? v : null)
                .set("version_basis", willRelease ? vb : null)
                .set("updated_at", now));
        if (rows == 0) return Outcome.fail("状态已变化（可能刚被审过），授权位没改成，请刷新看看。");
        log.info("DispatchService.updateAuths: order={} 授权位已改，release={}, version={}", orderNo, willRelease, v);
        return Outcome.success("授权位已更新" + (willRelease ? "，版本号 " + v + "（" + vb + "）" : "") + "。",
                get(orderNo), null);
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
