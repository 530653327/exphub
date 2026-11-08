package com.exphub.service;

import com.exphub.entity.MailRole;
import com.exphub.entity.MailRoleAlias;
import com.exphub.mapper.MailRoleAliasMapper;
import com.exphub.mapper.MailRoleMapper;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 单测用的角色清单构造器：把「几行 mail_roles / mail_role_aliases」注进一个真的
 * {@link MailRoleCatalog}（mapper 用 mock），<b>不打库、不起 Spring</b>。
 *
 * <p>为什么不做成「mock 整个 MailRoleCatalog」：那样 TTL / 强制重读 / 降级这些
 * <b>真正会出事的分支</b>全被 mock 掉了，测试只能证明「参数传进去了」。
 * 这里保留真的 catalog、只把数据源换成内存，才能验「后台刚加的角色立刻能发」这类行为。
 */
public final class MailRoleFixtures {

    private MailRoleFixtures() {
    }

    /** 只有正名、没有别名/显示名。 */
    public static MailRoleCatalog catalog(String... roles) {
        Set<String> set = new LinkedHashSet<>(List.of(roles));
        return catalog(set, Map.of(), Map.of());
    }

    public static MailRoleCatalog catalog(Set<String> roles, Map<String, String> aliases,
                                          Map<String, String> displayNames) {
        List<MailRole> rows = new ArrayList<>();
        for (String r : roles) {
            MailRole row = new MailRole();
            row.setRole(r);
            row.setStatus(MailRoleCatalog.STATUS_ACTIVE);
            row.setDisplayName(displayNames.get(r));
            rows.add(row);
        }
        Map<String, MailRoleAlias> aliasRows = new LinkedHashMap<>();
        aliases.forEach((a, t) -> {
            MailRoleAlias row = new MailRoleAlias();
            row.setAlias(a);
            row.setRole(t);
            aliasRows.put(a, row);
        });

        MailRoleMapper roleMapper = mock(MailRoleMapper.class);
        MailRoleAliasMapper aliasMapper = mock(MailRoleAliasMapper.class);
        when(roleMapper.selectActive()).thenReturn(rows);
        when(aliasMapper.selectAllAliases()).thenReturn(new ArrayList<>(aliasRows.values()));

        MailRoleCatalog catalog = new MailRoleCatalog();
        ReflectionTestUtils.setField(catalog, "roleMapper", roleMapper);
        ReflectionTestUtils.setField(catalog, "aliasMapper", aliasMapper);
        return catalog;
    }

    /** 拿到底层 mock（验查询次数用）。 */
    public static MailRoleMapper roleMapperOf(MailRoleCatalog catalog) {
        return (MailRoleMapper) ReflectionTestUtils.getField(catalog, "roleMapper");
    }

    /**
     * 故意让 {@code selectActive()} 多返回不可投递的行（DISABLED / PENDING_REVIEW）。
     *
     * <p>用来验「名单出口不会漏出停用/待复核角色」—— 真实 SQL 已经过滤，但守卫要看的是
     * catalog 自己有没有第二道筛子（别把这条不变量押在唯一一句 WHERE 上）。
     */
    public static MailRoleCatalog catalogWithLeakedInactive(Set<String> active, Map<String, String> inactiveWithStatus) {
        List<MailRole> rows = new ArrayList<>();
        for (String r : active) {
            MailRole row = new MailRole();
            row.setRole(r);
            row.setStatus(MailRoleCatalog.STATUS_ACTIVE);
            row.setDisplayName(r);
            rows.add(row);
        }
        for (Map.Entry<String, String> e : inactiveWithStatus.entrySet()) {
            MailRole row = new MailRole();
            row.setRole(e.getKey());
            row.setStatus(e.getValue());
            row.setDisplayName(e.getValue());
            rows.add(row);
        }

        MailRoleMapper roleMapper = mock(MailRoleMapper.class);
        MailRoleAliasMapper aliasMapper = mock(MailRoleAliasMapper.class);
        when(roleMapper.selectActive()).thenReturn(rows);
        when(aliasMapper.selectAllAliases()).thenReturn(new ArrayList<>());

        MailRoleCatalog catalog = new MailRoleCatalog();
        ReflectionTestUtils.setField(catalog, "roleMapper", roleMapper);
        ReflectionTestUtils.setField(catalog, "aliasMapper", aliasMapper);
        return catalog;
    }
}
