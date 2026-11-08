package com.exphub.service;

import com.baomidou.mybatisplus.core.conditions.AbstractWrapper;
import com.exphub.entity.MailMessage;
import com.exphub.mapper.MailCursorMapper;
import com.exphub.mapper.MailMessageMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.*;

/**
 * 收件人别名归一（2026-11-08 白泽 #75 查库实锤、阁主 #76 报越哥批准；
 * 2026-11-09 迁到 DB 表；同日 #679 正名改汉字、拼音降为别名）。
 *
 * <p><b>真事故</b>：{@code zhuolong}（多一个 o）在库里出现过 3 次（#22 / #45 / #72），
 * 旧实现的「配置 ∪ 库里出现过的 role」把它当成合法代号 → 信入库 → 收件箱按 {@code to_role}
 * 精确匹配 → <b>谁都收不到，且发件方全程无感</b>。
 *
 * <p><b>现在</b>：别名只存在于 {@code mail_role_aliases}，<b>不是</b>合法来源 ——
 * 发信时先归一再校验，指向的正名必须是 {@code ACTIVE} 的汉字正名。
 * 历史行的 {@code to_role} 由迁移脚本按别名刷成汉字；读侧仍按别名并查兜底。
 */
class MailboxRoleAliasTest {

    private MailboxService svc;
    private MailMessageMapper msgMapper;

    @BeforeEach
    void setUp() {
        svc = new MailboxService();
        msgMapper = mock(MailMessageMapper.class);
        MailCursorMapper cursorMapper = mock(MailCursorMapper.class);
        MailRoleCatalog catalog = MailRoleFixtures.catalog(
                Set.of("烛龙", "阁主", "白泽", "貔貅", "睚眦"),
                Map.ofEntries(Map.entry("zhuolong", "烛龙"), Map.entry("zhulogn", "烛龙"),
                        Map.entry("zhulon", "烛龙"), Map.entry("zhulong", "烛龙"),
                        Map.entry("beize", "白泽"), Map.entry("baize", "白泽"),
                        Map.entry("gezu", "阁主"), Map.entry("gezhu", "阁主"),
                        Map.entry("piyao", "貔貅"), Map.entry("pixiu", "貔貅"),
                        Map.entry("yaci", "睚眦"), Map.entry("yazi", "睚眦")),
                Map.of());
        ReflectionTestUtils.setField(svc, "msgMapper", msgMapper);
        ReflectionTestUtils.setField(svc, "cursorMapper", cursorMapper);
        ReflectionTestUtils.setField(svc, "roleCatalog", catalog);
    }

    @Test
    @DisplayName("拼音/错拼归一到汉字正名，且容忍大小写与首尾空白")
    void aliasIsCanonicalized() {
        assertEquals("烛龙", svc.canonicalRole("zhuolong"));
        assertEquals("烛龙", svc.canonicalRole(" ZhuoLong "), "trim + 小写要一起做");
        assertEquals("烛龙", svc.canonicalRole("zhulong"), "拼音本身就是别名");
        assertEquals("阁主", svc.canonicalRole("GEZHU"), "拼音要统一成汉字正名");
        assertEquals("烛龙", svc.canonicalRole("烛龙"), "汉字正名原样通过");
    }

    @Test
    @DisplayName("不在别名表里的值原样返回（去空白转小写），由校验去拒，不在这里放行")
    void unknownRolePassesThroughForValidator() {
        assertEquals("zhulnog", svc.canonicalRole(" zhulnog "));
        assertFalse(svc.isKnownRole(svc.canonicalRole("zhulnog")),
                "归一只修已知拼错，不认识的要留给校验拒掉");
    }

    @Test
    @DisplayName("别名指向的正名若不在 ACTIVE 集合，仍然要被拒（别让别名成为绕过校验的后门）")
    void aliasToUnknownRoleIsStillRejected() {
        MailRoleCatalog c = MailRoleFixtures.catalog(Set.of("烛龙"), Map.of("oldname", "nobody"), Map.of());
        ReflectionTestUtils.setField(svc, "roleCatalog", c);
        assertEquals("nobody", svc.canonicalRole("oldname"));
        assertFalse(svc.isKnownRole("nobody"));
    }

    @Test
    @DisplayName("发信时真的按汉字正名落库：to_role / from_role 都不是拼音")
    void sendStoresCanonicalRecipient() {
        when(msgMapper.insert(any(MailMessage.class))).thenAnswer(inv -> {
            ((MailMessage) inv.getArgument(0)).setId(1L);
            return 1;
        });

        MailMessage m = svc.send("gezhu", "zhuolong", "主题", "正文", null, "note", null, "cfg-test");

        assertEquals("烛龙", m.getToRole(),
                "落库必须是汉字正名，否则收件箱仍然匹配不上 —— 这就是本次要修的那件事");
        assertEquals("阁主", m.getFromRole(), "发件人也必须是汉字：出口/日志/审计一律汉字");
        verify(msgMapper).insert(any(MailMessage.class));
    }

    @Test
    @DisplayName("没有别名时：原样返回（交给校验拒），null/空值不抛错")
    void emptyAliasTableIsSafe() {
        MailRoleCatalog c = MailRoleFixtures.catalog("烛龙");
        ReflectionTestUtils.setField(svc, "roleCatalog", c);
        assertTrue(svc.roleAliases().isEmpty());
        assertEquals("zhuolong", svc.canonicalRole("zhuolong"), "没别名就保持原样，交给校验拒");
        assertNull(svc.canonicalRole(null));
    }

    @Test
    @DisplayName("别名链只走一跳：a→b、b→c 时 a 停在本层，避免配错循环把信吞掉")
    void aliasChainOnlyWalksOneHop() {
        MailRoleCatalog c = MailRoleFixtures.catalog(Set.of("b", "c"), Map.of("a", "b", "b", "c"), Map.of());
        ReflectionTestUtils.setField(svc, "roleCatalog", c);
        assertEquals("b", svc.canonicalRole("a"), "多跳会让人猜不到最终落在哪，只允许一跳");
        assertEquals("c", svc.canonicalRole("b"));
    }

    @Test
    @DisplayName("自指别名（a→a）被忽略，不会造出无限归一")
    void selfAliasIsIgnored() {
        MailRoleCatalog c = MailRoleFixtures.catalog(Set.of("烛龙"), Map.of("烛龙", "烛龙"), Map.of());
        ReflectionTestUtils.setField(svc, "roleCatalog", c);
        assertTrue(svc.roleAliases().isEmpty(), "自指项不是别名：" + svc.roleAliases());
    }

    @Test
    @DisplayName("收件箱查询要带上历史别名：to_role=piyao 的老信仍能进 貔貅 收件箱")
    void inboxQueryIncludesHistoricalAliasRows() {
        // 2026-11-08 阁主 #119：只改名单 + 发信归一，历史行（#110 to_role=piyao）还是读不到
        List<Object> queriedRoles = new ArrayList<>();
        when(msgMapper.selectList(any())).thenAnswer(inv -> {
            AbstractWrapper<?, ?, ?> w = inv.getArgument(0);
            // MP 3.5.7 的 Wrapper 是惰性的：不先算一次 SQL，参数还没落进 paramNameValuePairs
            w.getSqlSegment();
            queriedRoles.addAll(w.getParamNameValuePairs().values());
            return List.of();
        });

        svc.inbox("貔貅", null, 20, true);

        assertTrue(queriedRoles.contains("貔貅"), "正名要在查询里：" + queriedRoles);
        assertTrue(queriedRoles.contains("piyao"),
                "只查 貔貅 的话 #110 那封（to_role=piyao，至今没人读）永远归不到貔貅：" + queriedRoles);
    }

    @Test
    @DisplayName("当事人自己用拼音调用时，variants 落到同一组（大小写不敏感）")
    void roleVariantsAreCanonicalised() {
        assertEquals(Set.of("貔貅", "piyao", "pixiu"), svc.roleVariants("PIYAO"));
        assertEquals(Set.of("睚眦", "yaci", "yazi"), svc.roleVariants("睚眦"));
        assertEquals(Set.of("nobody"), svc.roleVariants(" nobody "),
                "不认识的值只返回自身，别凭空造别名");
        assertTrue(svc.roleVariants(null).isEmpty());
    }

    @Test
    @DisplayName("markProcessed 也要认别名，否则老信永远标不了「处理过」")
    void markProcessedAcceptsAliasRows() {
        List<Object> whereValues = new ArrayList<>();
        when(msgMapper.update(isNull(), any())).thenAnswer(inv -> {
            AbstractWrapper<?, ?, ?> w = inv.getArgument(1);
            // 同上：SET 与 WHERE 的参数都要先算一次 SQL 才会落进 map
            w.getSqlSet();
            w.getSqlSegment();
            whereValues.addAll(w.getParamNameValuePairs().values());
            return 1;
        });

        assertEquals(1, svc.markProcessed(110L, "貔貅"));

        assertTrue(whereValues.contains("貔貅") && whereValues.contains("piyao"),
                "更新条件要把别名一起带上：" + whereValues);
    }
}
