package com.exphub.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 角色别名表的守卫。
 *
 * <p>背景（W-20261119-000010 / #3539）：W-000003 给应龙上岗时只对齐了哨兵阁本机
 * （roles.json / 桥 / 面板），漏了服务端两个常量，于是 dispatch_list({role:'yinglong'})
 * 把 code 当字面名字匹配 assignee='应龙'，永远查不到单 —— 应龙上着岗却零认领。
 *
 * <p>这里钉三件事：① 应龙的 code 能归一；② 后台可指派清单包含应龙；
 * ③ 两个常量不许再出现「一边有、一边没有」。
 */
class DispatchRoleAliasTest {

    @Test
    @DisplayName("yinglong 归一成「应龙」，大小写与空白都要吃")
    void yinglongMapsToYinglong() {
        assertEquals("应龙", DispatchService.canonicalRole("yinglong"));
        assertEquals("应龙", DispatchService.canonicalRole("  YingLong "));
    }

    @Test
    @DisplayName("ASSIGNABLE_ROLES 里每个角色都必须有拼音映射，漏一个就红")
    void everyAssignableRoleHasAlias() {
        Set<String> canonical = new HashSet<>(DispatchService.ROLE_ALIASES.values());
        List<String> missing = DispatchService.ASSIGNABLE_ROLES.stream()
                .filter(role -> !canonical.contains(role))
                .toList();
        assertTrue(missing.isEmpty(),
                "这些角色在 ASSIGNABLE_ROLES 里，但 ROLE_ALIASES 没有对应拼音 code：" + missing);
    }

    @Test
    @DisplayName("应龙在后台可指派清单里，否则人工建单选不到它")
    void yinglongIsAssignable() {
        assertTrue(DispatchService.ASSIGNABLE_ROLES.contains("应龙"));
    }

    @Test
    @DisplayName("未知角色原样返回 —— 不许静默改成别人，否则认领会走到错的人身上")
    void unknownRolePassesThrough() {
        assertEquals("zzz-not-a-role", DispatchService.canonicalRole("zzz-not-a-role"));
        assertNull(DispatchService.canonicalRole(null));
    }
}
