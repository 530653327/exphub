package com.exphub.service;

import com.exphub.entity.MailRole;
import com.exphub.entity.MailRoleAlias;
import com.exphub.mapper.MailRoleAliasMapper;
import com.exphub.mapper.MailRoleMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.LongSupplier;

/**
 * 信箱可投递角色清单的<b>运行时快照</b>（数据来自 {@code mail_roles} / {@code mail_role_aliases}）。
 *
 * <p><b>为什么不是每次查库</b>：{@code mail_send} 每次都校验收件人，收件箱每条也要按别名并查。
 * 追一条 SELECT 到每次发信/读信上，等于是用「毫秒级一致性」换「每次多一跳」——
 * 名单改动是人工低频动作（加人/停用），30 秒生效完全够。
 *
 * <p><b>为什么不是定时任务</b>：定时任务失败是静默的（参考 systemd 挂两个月没人发现）。
 * {@code volatile} 快照 + 按需加载 + 写后失效，失败当场看得见。
 *
 * <p><b>三条降级规则</b>（比「返回空名单」更要紧）：
 * <ol>
 *   <li>有旧快照、这次刷新失败 → <b>继续用旧的</b>并标记 degraded（宁可名单旧 30 秒，也别让全站发不了信）。</li>
 *   <li>冷启动就读不到（表不存在 / 库连不上）→ <b>明确报「清单不可用」</b>，不许把这个状态说成
 *       「收件人不存在」——那是 #1459 的老毛病：把服务端故障说成对方写错，排查方向从一开始就是错的。</li>
 *   <li>被拒前<b>强制重读一次</b>（节流 2 秒）：后台刚加的角色不该让人等满 30 秒才生效。</li>
 * </ol>
 *
 * <p>2026-11-09 阁主 T261109053740-701（P9 定案）：代号校验<b>只在 ExpHub 做</b>，
 * 忆阁不取、不存、不校验、不做拼错纠正；拒绝回执是结构化错误（被拒收件人 + ≤5 个候选 + 一句提示）。
 *
 * <p><b>2026-11-09 阁主 T261109054529-699：名单只用于展示，不许被当成自动重发的依据。</b>
 * 拒绝回执里带<b>当前生效名单</b>（只有 {@code ACTIVE}），但条数超过
 * {@value #MAX_ROLE_LIST_IN_ERROR} 时不给正文、只给计数 + 查询入口 ——
 * 一百多个代号塞进每次错误响应会把日志和报文撑爆。停用（{@code DISABLED}）与
 * 待复核（{@code PENDING_REVIEW}）在任何出口都不出现。
 *
 * <p><b>2026-11-09 阁主 T261109054938-679：正名改汉字，拼音降为别名。</b>
 * {@code mail_roles.role} 现在存「烛龙 / 白泽 / 重明鸟」这类汉字，拼音（含 zhuolong 这类错拼）
 * 全部落在 {@code mail_role_aliases}。<b>兼容期入参汉字和拼音都收</b>（入口统一归一到汉字），
 * 出口（应答、候选、名单、错误文案、日志、审计）一律汉字 —— 不允许「库里汉字、日志拼音」这种半套。
 */
@Service
public class MailRoleCatalog {

    private static final Logger log = LoggerFactory.getLogger(MailRoleCatalog.class);

    public static final String STATUS_ACTIVE = "ACTIVE";

    /** 拒绝时最多给几个候选。给全清单会撑成一屏、客户端还会截断（阁主 P9 第 2 条）。 */
    public static final int MAX_CANDIDATES = 5;

    /**
     * 拒绝回执里最多直接列出多少个生效角色。
     *
     * <p>超过这个数就只报计数 + 指向 {@code mail_start(roles=true)}：名单会随角色增长到 100+，
     * 每次发错收件人都把整份名单塞进错误响应，日志和报文都会被撑爆
     * （阁主 T261109054529-699 第二条）。
     */
    public static final int MAX_ROLE_LIST_IN_ERROR = 20;

    @Autowired
    private MailRoleMapper roleMapper;

    @Autowired
    private MailRoleAliasMapper aliasMapper;

    /** 快照 TTL：后台/迁移改了名单最多 30 秒生效。 */
    private long ttlMs = 30_000L;

    /** 「被拒前强制重读」的节流窗口：连续拒绝不把库打穿，又能让刚加的角色立刻生效。 */
    private long forceReloadThrottleMs = 2_000L;

    /** 时钟：单测要验 TTL，不能让真实时间等 30 秒。 */
    private LongSupplier clock = System::currentTimeMillis;

    private volatile Snapshot cached;

    /** 最近一次尝试加载的时刻（与「数据是哪一刻的」分开：刷新失败时数据仍是旧的）。 */
    private volatile long cachedAt = Long.MIN_VALUE / 2;

    // ------------------------------------------------------------ 快照

    /**
     * 一刻的角色清单。{@code activeRoles} 只含 {@code status='ACTIVE'}；
     * 别名表全量装入（一半用途是按正名反查）。
     */
    public static final class Snapshot {

        private final Set<String> activeRoles;          // 正名（汉字）
        private final Map<String, String> displayNames; // 显示名 → 正名（正名本身已是汉字，保留兼容后台旧数据）
        private final Map<String, String> labels;       // 正名 → 给人看的名字（后台旧数据可能与正名不同）
        private final Map<String, String> aliases;      // 别名（拼音/错拼）→ 正名（汉字）
        private final long loadedAt;
        private final boolean available;
        private final boolean degraded;
        private final String error;

        Snapshot(Set<String> activeRoles, Map<String, String> displayNames, Map<String, String> labels,
                 Map<String, String> aliases,
                 long loadedAt, boolean available, boolean degraded, String error) {
            this.activeRoles = activeRoles;
            this.displayNames = displayNames;
            this.labels = labels;
            this.aliases = aliases;
            this.loadedAt = loadedAt;
            this.available = available;
            this.degraded = degraded;
            this.error = error;
        }

        public Set<String> activeRoles() { return activeRoles; }

        public Map<String, String> displayNames() { return displayNames; }

        /** 正名 → 给人看的名字；没有单独显示名时由调用方回落到正名。 */
        public Map<String, String> labels() { return labels; }

        public Map<String, String> aliases() { return aliases; }

        /** 这份数据是哪一刻从库里读出来的（degraded 时可能远早于当前）。 */
        public long loadedAt() { return loadedAt; }

        /** false = 冷启动就没读到，任何代号都无法校验（≠ 代号写错）。 */
        public boolean available() { return available; }

        /** true = 这次刷新失败、正在用更早的数据。 */
        public boolean degraded() { return degraded; }

        public String error() { return error; }
    }

    private static Snapshot empty(long now, boolean degraded, String error) {
        return new Snapshot(Collections.emptySet(), Collections.emptyMap(), Collections.emptyMap(),
                Collections.emptyMap(), now, false, degraded, error);
    }

    public Snapshot snapshot() {
        Snapshot s = cached;
        if (s != null && clock.getAsLong() - cachedAt < ttlMs) return s;
        return reload();
    }

    /**
     * 立刻重新加载。失败时：有旧数据就继续用旧数据并标 degraded；没有就返回 available=false。
     */
    public synchronized Snapshot reload() {
        long now = clock.getAsLong();
        Snapshot prev = cached;
        try {
            Set<String> roles = new LinkedHashSet<>();
            Map<String, String> display = new LinkedHashMap<>();
            Map<String, String> labels = new LinkedHashMap<>();
            List<MailRole> rows = roleMapper.selectActive();
            if (rows != null) {
                for (MailRole r : rows) {
                    if (r == null || r.getRole() == null) continue;
                    // 防御性再筛一次：SQL 已经只取 ACTIVE，但这里不能把「停用/待复核不出现在任何出口」
                    // 押在一句 WHERE 上 —— 名单一旦混进不可投递的值，人按名单发信会被拒第二次。
                    if (r.getStatus() != null && !STATUS_ACTIVE.equals(r.getStatus().trim())) continue;
                    String code = norm(r.getRole());
                    if (code.isEmpty()) continue;
                    roles.add(code);
                    if (r.getDisplayName() != null && !r.getDisplayName().isBlank()) {
                        String shown = r.getDisplayName().trim();
                        display.putIfAbsent(shown, code);
                        if (!shown.equals(code)) labels.putIfAbsent(code, shown);
                    }
                }
            }
            Map<String, String> aliases = new LinkedHashMap<>();
            List<MailRoleAlias> aliasRows = aliasMapper.selectAllAliases();
            if (aliasRows != null) {
                for (MailRoleAlias a : aliasRows) {
                    if (a == null || a.getAlias() == null || a.getRole() == null) continue;
                    String k = norm(a.getAlias());
                    String v = norm(a.getRole());
                    if (!k.isEmpty() && !v.isEmpty() && !k.equals(v)) aliases.putIfAbsent(k, v);
                }
            }
            Snapshot fresh = new Snapshot(Collections.unmodifiableSet(roles),
                    Collections.unmodifiableMap(display), Collections.unmodifiableMap(labels),
                    Collections.unmodifiableMap(aliases), now, true, false, null);
            cached = fresh;
            cachedAt = now;
            return fresh;
        } catch (Exception e) {
            String why = e.getClass().getSimpleName() + ": " + e.getMessage();
            log.warn("MailRoleCatalog: 读取角色清单失败（{}），{}", why,
                    prev != null && prev.available() ? "继续用 " + prev.loadedAt() + " 的旧快照" : "当前无法校验任何代号");
            cachedAt = now; // 失败也计一次尝试，避免每次发信都打库
            if (prev != null && prev.available()) {
                Snapshot stale = new Snapshot(prev.activeRoles, prev.displayNames, prev.labels, prev.aliases,
                        prev.loadedAt, true, true, why);
                cached = stale;
                return stale;
            }
            return empty(now, true, why);
        }
    }

    /**
     * 被拒前的强制重读（节流 {@code forceReloadThrottleMs}）。
     *
     * <p>不加这一步，「后台刚加完角色、模型立刻发信」必然被拒一次 —— 而拒绝文案比真相更响，
     * 人会以为是自己写错了。节流是为了「连续被拒」时不把库打穿。
     */
    public Snapshot snapshotForRejection() {
        long now = clock.getAsLong();
        Snapshot cur = snapshot();
        if (now - cachedAt >= forceReloadThrottleMs) return reload();
        return cur;
    }

    // ------------------------------------------------------------ 查询

    /** 可投递正名集合（{@code status='ACTIVE'}）。清单读不到时为空集 —— 判可用性请看 {@link #snapshot()}。 */
    public Set<String> activeRoles() {
        return snapshot().activeRoles();
    }

    /**
     * 当前生效名单（汉字正名，按名字排序），<b>只含 {@code status='ACTIVE'}</b>。
     *
     * <p>停用（{@code DISABLED}）与待复核（{@code PENDING_REVIEW}）一律不出现 ——
     * 这份名单是给人挑收件人的，混进不可投递的值比不给更糟（阁主 T261109054529-699）。
     * 清单读不到时返回空列表，调用方要先看 {@link Snapshot#available()} 再决定怎么说。
     */
    public List<String> roster() {
        return rosterOf(snapshot());
    }

    static List<String> rosterOf(Snapshot s) {
        if (s == null || !s.available()) return List.of();
        List<String> out = new ArrayList<>(s.activeRoles());
        out.sort(Comparator.naturalOrder());
        return out;
    }

    /**
     * 名单的展示条目：有单独显示名时写成「正名（中文名）」，否则就是正名。
     *
     * <p>当前正名本身已是汉字，绝大多数条目就是「烛龙」；保留这个口子是为了后台旧数据
     * 里 {@code display_name} 与正名不一致时，人不至于看不懂那行是什么。
     */
    public List<String> rosterLabels() {
        return labelsOf(snapshot());
    }

    /** 名单的展示条目（与 {@link #rosterLabels()} 同口径，但用调用方手里那份快照，避免二次读）。 */
    static List<String> labelsOf(Snapshot s) {
        List<String> out = new ArrayList<>();
        if (s == null || !s.available()) return out;
        for (String role : rosterOf(s)) {
            String label = s.labels().get(role);
            out.add(label == null || label.isBlank() ? role : role + "（" + label + "）");
        }
        return out;
    }

    public Map<String, String> aliases() {
        return snapshot().aliases();
    }

    /** 别名 → 正名（只走一跳、去空白转小写）；不认识的原样返回，交给校验去拒。 */
    public String canonicalRole(String role) {
        if (role == null) return null;
        String v = norm(role);
        if (v.isEmpty()) return v;
        String mapped = snapshot().aliases().get(v);
        return mapped == null ? v : mapped;
    }

    /**
     * 「这个写法能不能投递」：先归一，再看是否在 ACTIVE 集合里。
     *
     * <p>清单不可用（冷启动读不到）时返回 false —— <b>调用方必须先看
     * {@link #snapshot()}.available()</b>，否则会把服务端故障说成对方写错。
     */
    public boolean isDeliverable(String role) {
        if (role == null || role.isBlank()) return false;
        Snapshot s = snapshot();
        if (!s.available()) return false;
        return s.activeRoles().contains(canonicalRole(role));
    }

    /**
     * 某个代号的详细校验结果（发信入口用）。
     *
     * <p>把「归一后的名字、候选、快照状态」一次性算出来并带回，避免调用方算完又重读一次快照，
     * 出现「按 A 快照拒绝、按 B 快照给候选」这种自相矛盾。
     */
    public static final class Check {
        public enum Kind { OK, UNKNOWN, UNAVAILABLE }

        private final Kind kind;
        private final String canonical;
        private final List<String> candidates;
        private final Snapshot snapshot;

        Check(Kind kind, String canonical, List<String> candidates, Snapshot snapshot) {
            this.kind = kind;
            this.canonical = canonical;
            this.candidates = candidates;
            this.snapshot = snapshot;
        }

        public Kind kind() { return kind; }

        public String canonical() { return canonical; }

        public List<String> candidates() { return candidates; }

        public Snapshot snapshot() { return snapshot; }
    }

    /**
     * 发信前的统一入口：命中不了就<b>强制重读一次</b>再判（{@link #snapshotForRejection()}）。
     *
     * <p>返回的候选与判定用的是<b>同一份快照</b>，不会出现「按 A 快照拒绝、按 B 快照给候选」。
     */
    public Check check(String raw) {
        Snapshot s = snapshot();
        if (!s.available()) {
            // 冷启动读不到：再试一次（可能赶在启动早期），仍失败就明确报「不可用」
            s = reload();
        }
        Check ok = okOf(raw, s);
        if (ok != null) return ok;
        if (!s.available()) return new Check(Check.Kind.UNAVAILABLE, canonicalIn(raw, s), List.of(), s);

        // 到这个分支才强制重读：正常（能发出去）的路径一次都不多查
        s = snapshotForRejection();
        ok = okOf(raw, s);
        if (ok != null) return ok;
        if (!s.available()) return new Check(Check.Kind.UNAVAILABLE, canonicalIn(raw, s), List.of(), s);
        return new Check(Check.Kind.UNKNOWN, canonicalIn(raw, s), candidates(raw, s), s);
    }

    private static Check okOf(String raw, Snapshot s) {
        String canon = canonicalIn(raw, s);
        if (canon == null || canon.isEmpty() || !s.activeRoles().contains(canon)) return null;
        return new Check(Check.Kind.OK, canon, List.of(), s);
    }

    /** 在<b>指定快照</b>上归一（与 {@link #canonicalRole} 同口径，区别只是不重读快照）。 */
    private static String canonicalIn(String raw, Snapshot s) {
        if (raw == null) return null;
        String v = norm(raw);
        if (v.isEmpty()) return v;
        String mapped = s.aliases().get(v);
        return mapped == null ? v : mapped;
    }

    /** 某个收件人「能收到什么」的全部写法 = 正名（汉字）∪ 指向它的别名（拼音/错拼）。 */
    public Set<String> variants(String role) {
        LinkedHashSet<String> out = new LinkedHashSet<>();
        String canon = canonicalRole(role);
        if (canon == null || canon.isBlank()) return out;
        out.add(canon);
        // 清单读不到时别名表为空，只能返回正名 —— 但这仍然够用：
        // 「发过信/收过信的人都有一行历史」，收件箱不会因为清单故障而整体变空。
        for (Map.Entry<String, String> e : snapshot().aliases().entrySet()) {
            if (canon.equals(e.getValue())) out.add(e.getKey());
        }
        return out;
    }

    /** 拒绝时候选（最多 {@link #MAX_CANDIDATES} 个汉字正名，绝不是全清单）。 */
    public List<String> candidates(String raw) {
        return candidates(raw, snapshot());
    }

    List<String> candidates(String raw, Snapshot s) {
        if (raw == null) return List.of();
        String rawTrim = raw.trim();
        String v = norm(rawTrim);
        if (v.isEmpty() || !s.available()) return List.of();

        LinkedHashSet<String> out = new LinkedHashSet<>();
        // ① 显示名精确命中（后台旧数据可能只填了 display_name）
        String byDisplay = s.displayNames().get(rawTrim);
        if (byDisplay != null && s.activeRoles().contains(byDisplay)) out.add(byDisplay);

        // ② 编辑距离（含前缀）：正名（汉字）与别名键（拼音/错拼）都参与模糊匹配。
        //    为什么要连别名一起比：用户把「zhulong」打成「zhulnog」时，只跟汉字比距离
        //    会一个候选都算不出来（汉字与拉丁字符的距离天然很大）。候选出口仍只给汉字。
        Map<String, String> pool = new LinkedHashMap<>(); // 参与比较的写法 → 汉字正名
        for (String r : s.activeRoles()) pool.putIfAbsent(r, r);
        for (Map.Entry<String, String> e : s.aliases().entrySet()) {
            if (s.activeRoles().contains(e.getValue())) pool.putIfAbsent(e.getKey(), e.getValue());
        }

        // 阈值随长度放宽，但不能宽到「谁都能当候选」。
        // 另加一条：距离必须小于较长那个写法 —— 两个等长的 2 字中文名互相距离恒为 2，
        // 不比长度更小就变成「输入任意两字，全库都来当候选」，那是硬凑。
        int threshold = Math.max(2, Math.min(3, Math.max(v.length(), 3) / 3));
        List<String> keys = new ArrayList<>(pool.keySet());
        keys.sort(Comparator.comparingInt((String k) -> distance(v, k)).thenComparing(Comparator.naturalOrder()));
        for (String k : keys) {
            if (out.size() >= MAX_CANDIDATES) break;
            int d = distance(v, k);
            int longer = Math.max(v.length(), k.length());
            boolean prefix = v.length() >= 3 && (k.startsWith(v) || v.startsWith(k));
            if ((d <= threshold && d < longer) || prefix) out.add(pool.get(k));
        }
        return new ArrayList<>(out);
    }

    /** Levenshtein 距离；代号都很短（≤32），O(n·m) 足够。 */
    static int distance(String a, String b) {
        int n = a.length();
        int m = b.length();
        if (n == 0) return m;
        if (m == 0) return n;
        int[] prev = new int[m + 1];
        int[] cur = new int[m + 1];
        for (int j = 0; j <= m; j++) prev[j] = j;
        for (int i = 1; i <= n; i++) {
            cur[0] = i;
            char ca = a.charAt(i - 1);
            for (int j = 1; j <= m; j++) {
                int cost = ca == b.charAt(j - 1) ? 0 : 1;
                cur[j] = Math.min(Math.min(cur[j - 1] + 1, prev[j] + 1), prev[j - 1] + cost);
            }
            int[] t = prev;
            prev = cur;
            cur = t;
        }
        return prev[m];
    }

    private static String norm(String s) {
        return s == null ? "" : s.trim().toLowerCase(Locale.ROOT);
    }

    // ------------------------------------------------------------ 单测用

    void setClock(LongSupplier clock) {
        this.clock = clock;
    }

    void setTtlMs(long ttlMs) {
        this.ttlMs = ttlMs;
    }

    void setForceReloadThrottleMs(long ms) {
        this.forceReloadThrottleMs = ms;
    }
}
