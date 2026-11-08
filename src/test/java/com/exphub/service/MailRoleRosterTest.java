package com.exphub.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.when;

/**
 * 「名单只用于展示」的运行时出口（2026-11-09 阁主 T261109054529-699）。
 *
 * <p>三条硬要求，每条对应一个会真出事的坏法：
 * <ol>
 *   <li><b>名单只含 ACTIVE</b> —— 停用/待复核混进去，人按名单发信会被拒第二次；</li>
 *   <li><b>全量名单不塞进错误响应</b> —— 角名单会长到 100+，每次发错收件人都带一份，
 *       日志和报文被撑爆；必须走 {@code mail_start(roles=true)} 按需查；</li>
 *   <li><b>名单只给人看</b> —— 回执必须写明「不得依据名单自动改写收件人并重发」，
 *       否则调用方会把「猜名字」写成自动逻辑，一次拼错变成一次乱发。</li>
 * </ol>
 */
class MailRoleRosterTest {

    @Test
    @DisplayName("名单按名字排序，只含 ACTIVE（从查询里漏出来的 DISABLED/PENDING_REVIEW 一律剔掉）")
    void rosterIsSortedAndActiveOnly() {
        MailRoleCatalog catalog = MailRoleFixtures.catalogWithLeakedInactive(
                Set.of("烛龙", "阁主", "白泽"),
                Map.of("夫诸", "DISABLED", "legacy_guy", "PENDING_REVIEW"));

        List<String> roster = catalog.roster();

        assertEquals(List.of("烛龙", "白泽", "阁主"), roster,
                "必须是 ACTIVE 全量且稳定排序：" + roster);
        assertFalse(roster.contains("夫诸"), "停用角色不许出现");
        assertFalse(roster.contains("legacy_guy"), "待复核脏代号不许出现");
    }

    @Test
    @DisplayName("显示名与正名不同时写成「正名（中文名）」；相同时只给正名，不重复")
    void rosterLabelsShowChineseNameOnlyWhenItDiffers() {
        MailRoleCatalog catalog = MailRoleFixtures.catalog(
                Set.of("烛龙", "zhu-long"), Map.of(),
                Map.of("烛龙", "烛龙", "zhu-long", "烛龙（长）"));

        List<String> labels = catalog.rosterLabels();

        assertTrue(labels.contains("烛龙"), "正名与显示名相同就只写一次：" + labels);
        assertTrue(labels.contains("zhu-long（烛龙（长））"), "显示名不同时才补括号：" + labels);
    }

    @Test
    @DisplayName("清单读不到：roster 为空，roleDirectoryMessage 明说是服务端故障，不说「没有角色」")
    void unavailableCatalogSaysServiceFailure() {
        MailRoleCatalog catalog = MailRoleFixtures.catalog("烛龙");
        when(MailRoleFixtures.roleMapperOf(catalog).selectActive())
                .thenThrow(new RuntimeException("db down"));
        catalog.setForceReloadThrottleMs(0L);

        MailboxService svc = new MailboxService();
        ReflectionTestUtils.setField(svc, "roleCatalog", catalog);

        assertTrue(catalog.roster().isEmpty(), "读不到就不该编名单");
        String msg = svc.roleDirectoryMessage();
        assertTrue(msg.contains("服务端故障"), msg);
        assertTrue(msg.contains("不是「没有角色」"), msg);
        assertFalse(msg.contains("- 烛龙"), "故障时不许列出任何名字：" + msg);
    }

    @Test
    @DisplayName("按需查名单：列出全部 ACTIVE + 加载时刻 + TTL，并写明「不得据此自动改写收件人并重发」")
    void directoryListsActiveRolesAndDisplayOnlyWarning() {
        MailRoleCatalog catalog = MailRoleFixtures.catalog("烛龙", "阁主", "白泽");
        MailboxService svc = new MailboxService();
        ReflectionTestUtils.setField(svc, "roleCatalog", catalog);

        String msg = svc.roleDirectoryMessage();

        assertTrue(msg.contains("当前可投递角色：3 个（只含 status=ACTIVE）"), msg);
        assertTrue(msg.contains("- 烛龙"), msg);
        assertTrue(msg.contains("- 阁主"), msg);
        assertTrue(msg.contains("- 白泽"), msg);
        assertTrue(msg.contains("每 30 秒刷新一次"), "必须给加载时刻与 TTL：" + msg);
        assertTrue(msg.contains("停用（DISABLED）与待复核（PENDING_REVIEW）一律不出现"), msg);
        assertTrue(msg.contains("不得依据本名单自动改写收件人并重发"), msg);
    }
}
