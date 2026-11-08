package com.exphub.service;

import com.exphub.mapper.MailRoleMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * 角色清单快照（{@code mail_roles}）的三条运行时规则：
 * TTL 30 秒 / 被拒前强制重读（节流 2 秒）/ 读不到时明确报「不可用」。
 *
 * <p>这三条都是<b>不出声就出错</b>的：TTL 太长 → 后台加完人要等一分钟才生效；
 * 不强制重读 → 刚加的人第一次发信必被拒，而拒绝文案看起来像对方写错；
 * 读不到时返回空名单 → 把服务端故障说成「收件人不存在」（#1459 的老毛病）。
 *
 * <p>2026-11-09 #679 起正名是汉字、拼音是别名：这里同时守「拼音入参归一成汉字」和
 * 「候选只出口汉字」两条。
 */
class MailRoleCatalogTest {

    @Test
    @DisplayName("TTL 内不重复查库；过期后重读（加人最多 30 秒生效）")
    void snapshotIsCachedThenReloadedAfterTtl() {
        MailRoleCatalog c = MailRoleFixtures.catalog("烛龙", "阁主");
        MailRoleMapper mapper = MailRoleFixtures.roleMapperOf(c);
        long[] now = {1_000_000L};
        c.setClock(() -> now[0]);
        c.setTtlMs(10_000L);

        assertEquals(Set.of("烛龙", "阁主"), c.activeRoles());
        now[0] += 9_999L;
        c.activeRoles();
        verify(mapper, times(1)).selectActive();

        now[0] += 1L;
        c.activeRoles();
        verify(mapper, times(2)).selectActive();
    }

    @Test
    @DisplayName("被拒前强制重读：后台刚加的角色立刻能发，不用等满 TTL")
    void rejectionForcesReloadSoNewRoleWorksImmediately() {
        MailRoleCatalog c = MailRoleFixtures.catalog("烛龙", "阁主");
        MailRoleMapper mapper = MailRoleFixtures.roleMapperOf(c);
        c.setTtlMs(30_000L);
        c.setForceReloadThrottleMs(0L); // 关掉节流，专测「被迫重读」

        assertEquals(MailRoleCatalog.Check.Kind.UNKNOWN, c.check("夫诸").kind(),
                "库里还没有夫诸，应当判未知");

        // 后台/迁移刚加完夫诸（TTL 还没到）
        when(mapper.selectActive()).thenReturn(List.of(role("烛龙"), role("阁主"), role("夫诸")));

        MailRoleCatalog.Check after = c.check("夫诸");
        assertEquals(MailRoleCatalog.Check.Kind.OK, after.kind(),
                "被拒前必须重读一次，否则「刚加的角色」第一次发信必被拒，看起来像对方写错");
        assertEquals("夫诸", after.canonical());
    }

    @Test
    @DisplayName("连续被拒不把库打穿：节流窗口内只读一次")
    void repeatedRejectionsAreThrottled() {
        MailRoleCatalog c = MailRoleFixtures.catalog("烛龙");
        MailRoleMapper mapper = MailRoleFixtures.roleMapperOf(c);
        c.setForceReloadThrottleMs(2_000L);

        c.check("没有甲");
        c.check("没有乙");
        c.check("没有丙");
        verify(mapper, times(1)).selectActive();
    }

    @Test
    @DisplayName("刷新失败但有旧快照：继续用旧的并标 degraded（宁可名单旧，也别全站发不了信）")
    void refreshFailureKeepsPreviousSnapshot() {
        MailRoleCatalog c = MailRoleFixtures.catalog("烛龙");
        MailRoleMapper mapper = MailRoleFixtures.roleMapperOf(c);
        long[] now = {1_000_000L};
        c.setClock(() -> now[0]);
        c.setTtlMs(10_000L);
        c.snapshot();

        when(mapper.selectActive()).thenThrow(new RuntimeException("db down"));
        now[0] += 10_000L;

        MailRoleCatalog.Snapshot s = c.snapshot();
        assertTrue(s.available(), "有旧数据就不能报不可用");
        assertTrue(s.degraded(), "必须标明这是旧快照");
        assertTrue(s.activeRoles().contains("烛龙"), "旧名单要照用");
    }

    @Test
    @DisplayName("冷启动就读不到：明报「不可用」，不许说成「收件人不存在」")
    void coldStartFailureIsReportedAsUnavailable() {
        MailRoleCatalog c = MailRoleFixtures.catalog("烛龙");
        when(MailRoleFixtures.roleMapperOf(c).selectActive())
                .thenThrow(new RuntimeException("Table 'mail_roles' doesn't exist"));
        c.setForceReloadThrottleMs(0L);

        MailRoleCatalog.Check chk = c.check("烛龙");
        assertEquals(MailRoleCatalog.Check.Kind.UNAVAILABLE, chk.kind(),
                "清单读不到时，连正确的收件人也无法确认 —— 这是不可用，不是未知");
        assertFalse(chk.snapshot().available());
    }

    @Test
    @DisplayName("拼音先归一：zhulong / zhuolong 都能发，归一结果是汉字「烛龙」")
    void aliasIsCanonicalisedBeforeCheck() {
        MailRoleCatalog c = MailRoleFixtures.catalog(Set.of("烛龙", "阁主"),
                Map.of("zhulong", "烛龙", "zhuolong", "烛龙"), Map.of());
        assertEquals("烛龙", c.canonicalRole(" ZhuoLong "));
        MailRoleCatalog.Check chk = c.check("zhulong");
        assertEquals(MailRoleCatalog.Check.Kind.OK, chk.kind());
        assertEquals("烛龙", chk.canonical(), "归一结果必须是汉字，否则库里又出现拼音");
        assertEquals("烛龙", c.check("zhuolong").canonical());
    }

    @Test
    @DisplayName("停用/待复核不是合法来源：不在 ACTIVE 里就拒")
    void nonActiveRolesAreNotDeliverable() {
        MailRoleCatalog c = MailRoleFixtures.catalog("烛龙");
        assertFalse(c.isDeliverable("legacy_guy"),
                "历史脏值迁成 PENDING_REVIEW 后不再是合法来源（旧实现的实时并集正是在这里出错）");
        assertEquals(MailRoleCatalog.Check.Kind.UNKNOWN, c.check("legacy_guy").kind());
    }

    @Test
    @DisplayName("候选：汉字拼错给汉字候选、拼音拼错也指到汉字正名、太不沾边就一个都不给")
    void candidatesAreFewAndRelevant() {
        MailRoleCatalog c = MailRoleFixtures.catalog(Set.of("烛龙", "阁主", "白泽", "重明鸟"),
                Map.of("zhulong", "烛龙", "chongmingniao", "重明鸟"), Map.of());

        assertEquals(List.of("重明鸟"), c.candidates("重明乌"), "汉字拼错要给汉字候选");
        assertTrue(c.candidates("zhulnog").contains("烛龙"),
                "拼音拼错要能指到汉字正名（只跟汉字比距离算不出来）");
        assertTrue(c.candidates("nobody").isEmpty(), "毫不相干的输入不许硬凑候选");
        for (String cand : c.candidates("zhulnog")) {
            assertTrue(cand.matches("\\p{IsHan}+"), "候选出口只能是汉字：" + cand);
        }
    }

    @Test
    @DisplayName("候选最多 5 个（给全清单会撑成一屏，客户端还会截断）")
    void candidatesAreCappedAtFive() {
        Set<String> roles = new LinkedHashSet<>();
        for (int i = 1; i <= 9; i++) roles.add("角色" + i);
        MailRoleCatalog c = MailRoleFixtures.catalog(roles, Map.of(), Map.of());
        assertTrue(c.candidates("角色").size() <= MailRoleCatalog.MAX_CANDIDATES);
    }

    @Test
    @DisplayName("两个等长的 2 字中文名互相距离恒为 2：不能因此把全库都当候选")
    void shortChineseInputMustNotMatchEveryName() {
        MailRoleCatalog c = MailRoleFixtures.catalog(
                Set.of("烛龙", "阁主", "白泽", "貔貅", "睚眦", "麒麟"), Map.of(), Map.of());
        List<String> cands = c.candidates("扑猊");
        assertTrue(cands.size() <= 1,
                "等长 2 字之间距离没有区分度，只凭距离会把全库都列出来：" + cands);
    }

    @Test
    @DisplayName("读侧别名并查：貔貅 也要能看到 to_role=piyao 的老信")
    void variantsIncludeHistoricalAliases() {
        MailRoleCatalog c = MailRoleFixtures.catalog(Set.of("貔貅", "睚眦"),
                Map.of("piyao", "貔貅", "pixiu", "貔貅", "yaci", "睚眦"), Map.of());
        assertEquals(Set.of("貔貅", "piyao", "pixiu"), c.variants("貔貅"));
        assertEquals(Set.of("貔貅", "piyao", "pixiu"), c.variants("PIYAO"), "当事人用别名调用也要落到同一组");
        assertEquals(Set.of("nobody"), c.variants(" nobody "), "不认识的原样返回，别凭空造别名");
    }

    @Test
    @DisplayName("编辑距离：一字符错拼必须能认出来（否则候选等于没有）")
    void distanceCatchesSimpleTypos() {
        assertEquals(1, MailRoleCatalog.distance("zhulong", "zhulon"));
        assertEquals(1, MailRoleCatalog.distance("zhulong", "zhuolong"), "多一个 o 只差一次插入");
        assertEquals(0, MailRoleCatalog.distance("baize", "baize"));
    }

    private static com.exphub.entity.MailRole role(String code) {
        com.exphub.entity.MailRole r = new com.exphub.entity.MailRole();
        r.setRole(code);
        r.setStatus(MailRoleCatalog.STATUS_ACTIVE);
        return r;
    }
}
