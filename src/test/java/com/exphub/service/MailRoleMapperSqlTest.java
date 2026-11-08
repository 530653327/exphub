package com.exphub.service;

import com.exphub.mapper.MailRoleAliasMapper;
import com.exphub.mapper.MailRoleMapper;
import org.apache.ibatis.mapping.MappedStatement;
import org.apache.ibatis.session.Configuration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 角色表 mapper 的<b>启动期可解析</b>守卫。
 *
 * <p><b>为什么单独一条</b>（2026-11-06 a0ab1d3 真实事故）：{@code @Select} 里直接写 {@code <>}
 * 会让应用<b>启动即</b> {@code SAXParseException}，而当时 620 条测试全绿 ——
 * 因为没有一条测试真的让 MyBatis 去解析这些注解。
 *
 * <p>这里用 MyBatis 自己的 {@link Configuration#addMapper} 解析两个接口：
 * 不入库、不连库，只证明「注解 SQL 能被解析成 MappedStatement」。解析不过 = 线上起不来。
 */
class MailRoleMapperSqlTest {

    @Test
    @DisplayName("两个 mapper 的 @Select 能被 MyBatis 解析成 MappedStatement（解析不过 = 启动即死）")
    void mapperAnnotationsAreParsed() {
        Configuration cfg = new Configuration();
        assertDoesNotThrow(() -> cfg.addMapper(MailRoleMapper.class),
                "MailRoleMapper 解析失败，线上会启动即死");
        assertDoesNotThrow(() -> cfg.addMapper(MailRoleAliasMapper.class),
                "MailRoleAliasMapper 解析失败，线上会启动即死");

        assertStatement(cfg, MailRoleMapper.class, "selectActive");
        assertStatement(cfg, MailRoleMapper.class, "countNotActive");
        assertStatement(cfg, MailRoleAliasMapper.class, "selectAllAliases");
    }

    @Test
    @DisplayName("@Select 里不许直写 <>（必须写 != 或 &lt;&gt;，否则启动即 SAXParseException）")
    void selectAnnotationsAvoidAngleBrackets() throws IOException {
        for (String f : new String[]{
                "src/main/java/com/exphub/mapper/MailRoleMapper.java",
                "src/main/java/com/exphub/mapper/MailRoleAliasMapper.java"}) {
            // 先剥注释：注释里写着「不许写 <>」这句提醒，全局字符串包含会把自己判红（AGENTS 红线 10）
            String src = stripComments(Files.readString(Path.of(f), StandardCharsets.UTF_8));
            assertFalse(src.contains("<>"),
                    f + " 的代码（非注释）里出现了 <> —— 改写成 != 或 &lt;&gt;（AGENTS 红线 9）");
        }
    }

    private static String stripComments(String src) {
        String noBlock = src.replaceAll("(?s)/\\*.*?\\*/", "");
        return noBlock.replaceAll("(?m)//.*$", "");
    }

    private static void assertStatement(Configuration cfg, Class<?> mapper, String method) {
        MappedStatement ms = cfg.getMappedStatement(mapper.getName() + "." + method);
        assertNotNull(ms, method + " 没被注册成 MappedStatement");
        assertTrue(ms.getSqlSource().getBoundSql(null).getSql().toLowerCase().contains("mail_role"),
                method + " 的 SQL 没指向 mail_roles* 表：" + ms.getSqlSource().getBoundSql(null).getSql());
    }
}
