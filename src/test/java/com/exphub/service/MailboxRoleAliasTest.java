package com.exphub.service;

import com.exphub.entity.MailMessage;
import com.baomidou.mybatisplus.core.conditions.AbstractWrapper;
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
import static org.mockito.Mockito.*;

/**
 * 收件人别名归一（2026-11-08，白泽 #75 查库实锤、阁主 #76 报越哥批准）。
 *
 * <p><b>真事故</b>：`zhuolong`（多一个 o）在库里出现过 3 次（#22 / #45 / #72），
 * 于是它被 {@code knownRoles()} 的 dbRoles 兜底加进 accept 集合 → 收件人校验放行 →
 * 信正常入库 → {@code inbox()} 按 {@code to_role} 精确匹配 → **谁都收不到，且发件方全程无感**。
 *
 * <p><b>为什么不能靠「从配置表删掉」</b>：accept 集合是「配置 ∪ 库里出现过的」，
 * 库里那三行还在，删配置下一轮照样加回来。所以只能在发信时把别名折成正名。
 *
 * <p><b>为什么不改历史数据</b>：破了「消息只追加不可改」。而 {@code thread()} 只按 thread_id 查、
 * 不校验 to_role，老线程照样读得出来 —— 不需要改历史也能善后。
 */
class MailboxRoleAliasTest {

    private MailboxService svc;
    private MailMessageMapper msgMapper;

    @BeforeEach
    void setUp() {
        svc = new MailboxService();
        msgMapper = mock(MailMessageMapper.class);
        MailCursorMapper cursorMapper = mock(MailCursorMapper.class);
        when(msgMapper.distinctRoles()).thenReturn(List.of("zhulong", "gezhu", "zhuolong"));
        ReflectionTestUtils.setField(svc, "msgMapper", msgMapper);
        ReflectionTestUtils.setField(svc, "cursorMapper", cursorMapper);
        ReflectionTestUtils.setField(svc, "knownRolesConfig", "zhulong,gezhu,baize");
        ReflectionTestUtils.setField(svc, "roleAliasesConfig", "zhuolong=zhulong");
    }

    @Test
    @DisplayName("别名归一到正名，且容忍大小写与首尾空白")
    void aliasIsCanonicalized() {
        assertEquals("zhulong", svc.canonicalRole("zhuolong"));
        assertEquals("zhulong", svc.canonicalRole(" ZhuoLong "), "trim + 小写要一起做");
        assertEquals("gezhu", svc.canonicalRole("GEZHU"), "本来正确的代号也要统一成小写落库");
    }

    @Test
    @DisplayName("不在别名表里的代号原样返回（去空白转小写），由 isKnownRole 去拒，不在这里放行")
    void unknownRolePassesThroughForValidator() {
        assertEquals("zhulnog", svc.canonicalRole(" zhulnog "));
        assertFalse(svc.isKnownRole(svc.canonicalRole("zhulnog")),
                "归一只修已知拼错，不认识的要留给校验拒掉");
    }

    @Test
    @DisplayName("别名指向的代号若不在 accept 集合，仍然要被拒（别让别名成为绕过校验的后门）")
    void aliasToUnknownRoleIsStillRejected() {
        ReflectionTestUtils.setField(svc, "roleAliasesConfig", "oldname=nobody");
        assertEquals("nobody", svc.canonicalRole("oldname"));
        assertFalse(svc.isKnownRole("nobody"));
    }

    @Test
    @DisplayName("发信时真的按正名落库：to_role 不再是 zhuolong")
    void sendStoresCanonicalRecipient() {
        MailMessage saved = new MailMessage();
        saved.setId(1L);
        saved.setThreadId("T-test");
        when(msgMapper.insert(any(MailMessage.class))).thenAnswer(inv -> {
            MailMessage m = inv.getArgument(0);
            m.setId(1L);
            return 1;
        });

        MailMessage m = svc.send("gezhu", "zhuolong", "主题", "正文", null, "note", null, "cfg-test");

        assertEquals("zhulong", m.getToRole(),
                "落库必须是正名，否则收件箱仍然匹配不上 —— 这就是本次要修的那件事");
        verify(msgMapper).insert(any(MailMessage.class));
    }

    @Test
    @DisplayName("配置为空时不能抛错、也不能把所有人当成别名")
    void emptyAliasConfigIsSafe() {
        ReflectionTestUtils.setField(svc, "roleAliasesConfig", "");
        assertTrue(svc.roleAliases().isEmpty());
        assertEquals("zhuolong", svc.canonicalRole("zhuolong"), "没配别名就保持原样，交给校验拒");
        assertNull(svc.canonicalRole(null));
    }

    @Test
    @DisplayName("别名链只走一跳：a=b、b=c 时 a 停在本层，避免配错循环把信吞掉")
    void aliasChainOnlyWalksOneHop() {
        ReflectionTestUtils.setField(svc, "roleAliasesConfig", "a=b,b=c");
        assertEquals("b", svc.canonicalRole("a"), "多跳会让人猜不到最终落在哪，只允许一跳");
        assertEquals("c", svc.canonicalRole("b"));
    }

    @Test
    @DisplayName("收件箱查询要带上历史别名：to_role=piyao 的老信仍能进貔貅(pixiu)收件箱")
    void inboxQueryIncludesHistoricalAliasRows() {
        // 2026-11-08 阁主 #119：只改 known-roles + 发信归一，历史行（#110 to_role=piyao）还是读不到
        ReflectionTestUtils.setField(svc, "roleAliasesConfig", "piyao=pixiu");
        List<Object> queriedRoles = new ArrayList<>();
        when(msgMapper.selectList(any())).thenAnswer(inv -> {
            AbstractWrapper<?, ?, ?> w = inv.getArgument(0);
            // MP 3.5.7 的 Wrapper 是惰性的：不先算一次 SQL，参数还没落进 paramNameValuePairs
            w.getSqlSegment();
            queriedRoles.addAll(w.getParamNameValuePairs().values());
            return List.of();
        });

        svc.inbox("pixiu", null, 20, true);

        assertTrue(queriedRoles.contains("pixiu"), "正名要在查询里：" + queriedRoles);
        assertTrue(queriedRoles.contains("piyao"),
                "只查 pixiu 的话 #110 那封（to_role=piyao，至今没人读）永远归不到貔貅：" + queriedRoles);
    }

    @Test
    @DisplayName("当事人自己用别名调用时，variants 落到同一组（大小写不敏感）")
    void roleVariantsAreCanonicalised() {
        ReflectionTestUtils.setField(svc, "roleAliasesConfig", "piyao=pixiu,yaci=yazi");
        assertEquals(Set.of("pixiu", "piyao"), svc.roleVariants("PIYAO"));
        assertEquals(Set.of("yazi", "yaci"), svc.roleVariants("yazi"));
        assertEquals(Set.of("nobody"), svc.roleVariants(" nobody "),
                "不认识的代号只返回自身，别凭空造别名");
        assertTrue(svc.roleVariants(null).isEmpty());
    }

    @Test
    @DisplayName("markProcessed 也要认别名，否则老信永远标不了「处理过」")
    void markProcessedAcceptsAliasRows() {
        ReflectionTestUtils.setField(svc, "roleAliasesConfig", "piyao=pixiu");
        List<Object> whereValues = new ArrayList<>();
        when(msgMapper.update(isNull(), any())).thenAnswer(inv -> {
            AbstractWrapper<?, ?, ?> w = inv.getArgument(1);
            // 同上：SET 与 WHERE 的参数都要先算一次 SQL 才会落进 map
            w.getSqlSet();
            w.getSqlSegment();
            whereValues.addAll(w.getParamNameValuePairs().values());
            return 1;
        });

        assertEquals(1, svc.markProcessed(110L, "pixiu"));

        assertTrue(whereValues.contains("pixiu") && whereValues.contains("piyao"),
                "更新条件要把别名一起带上：" + whereValues);
    }

    @Test
    @DisplayName("脏配置不能把服务打挂（缺等号 / 空值 / 自己映射自己）")
    void dirtyConfigIsIgnored() {
        ReflectionTestUtils.setField(svc, "roleAliasesConfig", "zhuolong, =zhulong,baize=,x=x,,alt=zhulong");
        assertEquals(1, svc.roleAliases().size(), "只有 alt=zhulong 是合法项：" + svc.roleAliases());
        assertEquals("zhulong", svc.canonicalRole("alt"));
        assertEquals("zhuolong", svc.canonicalRole("zhuolong"), "脏项不该生效");
    }
}
