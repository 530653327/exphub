package com.exphub.config;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.exphub.entity.Doc;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.apache.ibatis.reflection.MetaObject;
import org.apache.ibatis.reflection.SystemMetaObject;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 锁定 MyBatis-Plus 的 strict fill 语义：**只在字段为 null 时填充，非 null 原样写回**。
 *
 * <p>🔴 <b>本测试锁的是框架行为，不是我们的代码</b>。版本：<b>MyBatis-Plus 3.5.7</b>
 * （见 {@code pom.xml} 的 {@code mybatis-plus.version}）。升级 MP 时这条可能失效 ——
 * 一旦 {@code strictUpdateFill} 改成「无条件覆盖」，下面第 2 个用例会立刻变红，
 * 那正是我们要的提醒：升级后 {@code updateById} 会把每一条从库里读出来的实体
 * 的 {@code updated_at} 刷成当前时刻（docs/projects/users 等 5 张带 ON UPDATE 的表全部受影响）。
 *
 * <p>为什么需要这条：全仓 27 处 {@code updateById} 里，9 处传的是「从库里读出来的实体」，
 * 它们之所以不刷新 {@code updated_at}，靠的就是这条框架语义。代码里没有任何一行能表达这件事，
 * 静态扫描也扫不出来（判断依据是实体来源，不是源码文本）。把它钉成测试，
 * 至少升级时不会静默改行为。
 */
@DisplayName("MyBatis-Plus 3.5.7 strict fill 语义：null 才填，非 null 原样写回")
class MyBatisPlusFillSemanticsTest {

    private final MyBatisPlusMetaObjectHandler handler = new MyBatisPlusMetaObjectHandler();

    /**
     * strict fill 会先看实体的 TableInfo（{@code isWithUpdateFill()}），
     * 单测里没有 Spring/MyBatis 启动流程，必须手动注册一次，否则 TableInfo 为 null -> NPE。
     */
    @BeforeAll
    static void registerTableInfo() {
        MybatisConfiguration configuration = new MybatisConfiguration();
        MapperBuilderAssistant assistant = new MapperBuilderAssistant(configuration, "");
        assistant.setCurrentNamespace("com.exphub.mapper.DocMapper");
        TableInfoHelper.initTableInfo(assistant, Doc.class);
    }

    @Test
    @DisplayName("新建的 patch 实体（updatedAt 为 null）→ updateFill 填成现在")
    void fillsWhenNull() {
        Doc freshPatch = new Doc();
        LocalDateTime before = LocalDateTime.now().minusSeconds(1);

        MetaObject meta = SystemMetaObject.forObject(freshPatch);
        handler.updateFill(meta);

        assertNotNull(freshPatch.getUpdatedAt(), "null 字段必须被填上，否则 updateById 不会写这一列");
        assertTrue(freshPatch.getUpdatedAt().isAfter(before), "填的应该是当前时间");
    }

    @Test
    @DisplayName("从库里读出来的实体（updatedAt 非 null）→ 原样写回旧值，不刷新")
    void keepsExistingValue() {
        LocalDateTime readFromDb = LocalDateTime.of(2026, 1, 2, 3, 4, 5);
        Doc loaded = new Doc();
        loaded.setUpdatedAt(readFromDb);

        handler.updateFill(SystemMetaObject.forObject(loaded));

        assertEquals(readFromDb, loaded.getUpdatedAt(),
                "strictUpdateFill 若开始覆盖非 null 值，27 处 updateById 里那 9 处「读-改-写回」"
                        + "会全部把 updated_at 刷成当前时刻 —— 这不是我们的代码变了，是框架变了");
    }
}
