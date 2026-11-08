package com.exphub.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 收件人被拒时的<b>结构化错误</b>（2026-11-09 阁主 T261109053740-701 P9 第 2 条；
 * T261109054938-679 起正名改汉字）。
 *
 * <p><b>要防的三种错</b>：
 * <ol>
 *   <li><b>把历史脏代号/停用角色拼进回执</b> —— 名单只许出现 {@code status='ACTIVE'}；
 *       而且条数超过阈值就只报计数 + 指向 {@code mail_start(roles=true)}，别把 100+ 代号塞进每次错误响应；</li>
 *   <li>把「服务端读不到名单」说成「收件人不存在」—— 那是 #1459 的老毛病：
 *       错误的解释比没有解释更糟，它把排查方向从一开始就带偏；</li>
 *   <li>出口写拼音（zhulong / pixiu）—— 越哥 #679 定：正名是汉字，<b>应答、候选、名单、提示一律汉字</b>。</li>
 * </ol>
 *
 * <p><b>口径</b>：只认 {@code mail_roles} 里 {@code status='ACTIVE'} 的行 ∪ 别名表归一；
 * 历史脏代号迁成 {@code PENDING_REVIEW} 后<b>不再是合法来源</b>（旧实现的「配置 ∪ 历史库」
 * 实时并集会让停用永远停不掉）。
 */
class MailboxRecipientValidationTest {

    private MailboxService svc;
    private MailRoleCatalog catalog;

    @BeforeEach
    void setUp() {
        svc = new MailboxService();
        catalog = MailRoleFixtures.catalog(
                Set.of("烛龙", "阁主", "白泽", "貔貅", "睚眦", "麒麟", "重明鸟"),
                Map.of("zhulong", "烛龙", "zhuolong", "烛龙", "gezhu", "阁主",
                        "piyao", "貔貅", "yaci", "睚眦", "baize", "白泽"),
                Map.of());
        org.springframework.test.util.ReflectionTestUtils.setField(svc, "roleCatalog", catalog);
    }

    @Test
    @DisplayName("汉字正名可发；拼音与大小写/首尾空白也会归一到汉字")
    void activeRolesAreAccepted() {
        assertTrue(svc.isKnownRole("烛龙"));
        assertTrue(svc.isKnownRole("ZhuLong"), "兼容期拼音仍收");
        assertTrue(svc.isKnownRole("  阁主  "));
        assertEquals("烛龙", svc.canonicalRole(" ZhuLong "), "入口统一归一成汉字");
        assertEquals("阁主", svc.canonicalRole("GEZHU"));
    }

    @Test
    @DisplayName("历史别名归一后可发（zhuolong → 烛龙）")
    void aliasIsAcceptedAndCanonicalised() {
        assertTrue(svc.isKnownRole("zhuolong"));
        assertEquals("烛龙", svc.canonicalRole(" ZhuoLong "));
    }

    @Test
    @DisplayName("不在 ACTIVE 里的值一律拒：待复核/停用的历史值不再是合法来源")
    void nonActiveRolesAreRejected() {
        assertFalse(svc.isKnownRole("legacy_guy"));
        assertFalse(svc.isKnownRole("piyao2"));
        assertFalse(svc.isKnownRole("扑猊"));
    }

    @Test
    @DisplayName("拒绝回执是结构化四块：被拒值原样回显 / ≤5 个汉字候选 / 当前生效名单 / 一句提示")
    void rejectionMessageIsStructured() {
        String msg = svc.recipientRejectionMessage("zhulnog", catalog.check("zhulnog"));

        assertTrue(msg.startsWith("收件人无效（未发出任何信）"), msg);
        assertTrue(msg.contains("- rejectedRole（被拒收件人）：`zhulnog`"), "必须回显它传的原值：" + msg);
        assertTrue(msg.contains("- candidates（最多 5 个）："), msg);
        assertTrue(msg.contains("`烛龙`"), "要给出最近的正名：" + msg);
        assertTrue(msg.contains("- roleList（当前生效 7 个，仅展示）："), "名单要标明是 ACTIVE 全量：" + msg);
        assertTrue(msg.contains("- hint：没有这个收件人：zhulnog。你是想发 烛龙？"), msg);
    }

    @Test
    @DisplayName("rejectedRole 原样回，不清洗（首尾空白也照原样，别让「传了什么」失真）")
    void rejectedRoleIsEchoedVerbatim() {
        String msg = svc.recipientRejectionMessage("  zhulnog  ", catalog.check("  zhulnog  "));
        assertTrue(msg.contains("`  zhulnog  `"), "必须逐字回显：" + msg);
    }

    @Test
    @DisplayName("roleList 超过 20 个就不列正文：只报计数 + 指向 mail_start(roles=true)")
    void roleListIsBoundedWhenRosterIsLarge() {
        java.util.Set<String> many = new java.util.LinkedHashSet<>();
        for (int i = 0; i < 25; i++) many.add("角色" + i);
        MailRoleCatalog big = MailRoleFixtures.catalog(many, Map.of(), Map.of());

        String msg = svc.recipientRejectionMessage("zzzzz", big.check("zzzzz"));

        assertTrue(msg.contains("- roleList：当前生效 25 个，未随本响应给出"), msg);
        assertTrue(msg.contains("mail_start(roles=true)"), "要指出按需查的入口：" + msg);
        assertFalse(msg.contains("`角色0`"), "超过阈值不许把名单塞进错误响应：" + msg);
    }

    @Test
    @DisplayName("名单只含 ACTIVE：停用/待复核行就算从查询里漏出来，也不许进拒绝回执")
    void roleListNeverLeaksInactiveRoles() {
        MailRoleCatalog mixed = MailRoleFixtures.catalogWithLeakedInactive(
                Set.of("烛龙", "阁主", "白泽"),
                Map.of("夫诸", "DISABLED", "legacy_guy", "PENDING_REVIEW"));
        org.springframework.test.util.ReflectionTestUtils.setField(svc, "roleCatalog", mixed);

        String msg = svc.recipientRejectionMessage("zzzz", mixed.check("zzzz"));

        assertTrue(msg.contains("- roleList（当前生效 3 个，仅展示）："), msg);
        assertFalse(msg.contains("夫诸"), "停用的角色不许出现在名单：" + msg);
        assertFalse(msg.contains("legacy_guy"), "待复核的历史脏代号不许出现在名单：" + msg);
    }

    @Test
    @DisplayName("汉字拼错：候选也必须是汉字（重明乌 → 重明鸟），提示里不许冒出拼音正名")
    void chineseTypoGetsChineseCandidate() {
        String msg = svc.recipientRejectionMessage("重明乌", catalog.check("重明乌"));

        assertTrue(msg.contains("重明乌"), "要回显它传的原值：" + msg);
        assertTrue(msg.contains("`重明鸟`"), "要给出汉字候选：" + msg);
        assertTrue(msg.contains("正名是汉字"), "要告诉它正确形态：" + msg);
        for (String pinyin : Set.of("`zhulong`", "`chongmingniao`", "`gezhu`")) {
            assertFalse(msg.contains(pinyin), "候选出口不许出现拼音：" + pinyin + " in " + msg);
        }
    }

    @Test
    @DisplayName("候选只用于展示：回执必须写明「不得据此自动改写收件人并重发」")
    void candidatesAreDisplayOnly() {
        String msg = svc.recipientRejectionMessage("zhulnog", catalog.check("zhulnog"));
        assertTrue(msg.contains("不得依据本响应自动改写收件人并重发"), msg);
    }

    @Test
    @DisplayName("清单读不到：明说这是 ExpHub 的故障，不许说成「收件人不存在」")
    void unavailableCatalogSaysServiceFailure() {
        whenBroken();
        String msg = svc.recipientRejectionMessage("烛龙", catalog.check("烛龙"));

        assertTrue(msg.startsWith("收件人无法校验（未发出任何信）"), msg);
        assertTrue(msg.contains("- rejectedRole（被拒收件人）：`烛龙`"), msg);
        assertTrue(msg.contains("- candidates（最多 5 个）：—"), msg);
        assertTrue(msg.contains("- roleList：—（角色清单读不到，没法给名单）"), msg);
        assertTrue(msg.contains("服务端故障"), msg);
        assertTrue(msg.contains("不是你把收件人写错了"), msg);
        assertFalse(msg.contains("收件人无效"), "服务端故障不能显示成用户写错：" + msg);
    }

    @Test
    @DisplayName("回执要说明回信口径：带 inReplyTo 时不走这条校验")
    void messageExplainsReplyScope() {
        String msg = svc.recipientRejectionMessage("nobody", catalog.check("nobody"));
        assertTrue(msg.contains("回信"), msg);
        assertTrue(msg.contains("父消息"), msg);
    }

    private void whenBroken() {
        org.mockito.Mockito.when(MailRoleFixtures.roleMapperOf(catalog).selectActive())
                .thenThrow(new RuntimeException("db down"));
        catalog.setForceReloadThrottleMs(0L);
    }
}
