package com.exphub.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 信箱角色代号表的 <b>配置守卫</b>（2026-11-08 阁主 #119，越哥已批）。
 *
 * <p><b>真事故</b>：{@code application.yml} 把貔貅/睚眦写成了 {@code piyao} / {@code yaci}，
 * 而忆阁闸门与桥头身份用的是正名 {@code pixiu} / {@code yazi} —— 两边名单对不上，
 * yazi 被 ExpHub 拒、貔貅收不到新信。**而且没有任何测试会变红**：名单是配置，不是代码。
 * 所以这里直接断言配置值。
 *
 * <p>读文件时先剔注释行。注释里本来就写着这几个代号（本次修改的注释就是），
 * 用「全文包含」判会把注释当配置 —— 真值改回错拼，测试照样绿（AGENTS.md 红线 10）。
 */
class MailRoleCatalogConfigTest {

    @Test
    @DisplayName("known-roles 用正名 pixiu / yazi，错拼 piyao / yaci 不许留在基准表里")
    void knownRolesUseCorrectSpelling() throws Exception {
        List<String> roles = knownRoles();
        assertTrue(roles.contains("pixiu"), "貔貅的正名是 pixiu，当前表：" + roles);
        assertTrue(roles.contains("yazi"), "睚眦的正名是 yazi，当前表：" + roles);
        assertFalse(roles.contains("piyao"), "piyao 是错拼，只允许出现在 role-aliases 里：" + roles);
        assertFalse(roles.contains("yaci"), "yaci 是错拼，只允许出现在 role-aliases 里：" + roles);
    }

    @Test
    @DisplayName("历史错拼进别名表：piyao→pixiu、yaci→yazi（只改配置不归一，老信仍烂在库里）")
    void legacyMisspellingsAreCanonicalised() throws Exception {
        Map<String, String> aliases = roleAliases();
        assertEquals("pixiu", aliases.get("piyao"),
                "库里 #110 那封 to_role=piyao 的信要靠它归到貔貅：" + aliases);
        assertEquals("yazi", aliases.get("yaci"), "睚眦的旧拼写：" + aliases);
    }

    @Test
    @DisplayName("别名不许指向不存在的代号（否则发信时归一到没人能收的地方，比不归一更糟）")
    void aliasTargetsMustExistInKnownRoles() throws Exception {
        Set<String> roles = new LinkedHashSet<>(knownRoles());
        for (Map.Entry<String, String> e : roleAliases().entrySet()) {
            assertTrue(roles.contains(e.getValue()),
                    "别名 " + e.getKey() + " 指向的 " + e.getValue() + " 不在 known-roles 里：" + roles);
        }
    }

    @Test
    @DisplayName("代号一律小写英文——大小写不一致会让两张名单再次对不上")
    void roleCodesAreLowercaseAscii() throws Exception {
        for (String r : knownRoles()) {
            assertTrue(r.matches("[a-z0-9_-]+"), "代号「" + r + "」不是小写英文/数字：" + knownRoles());
        }
        for (String a : roleAliases().keySet()) {
            assertTrue(a.matches("[a-z0-9_-]+"), "别名「" + a + "」不是小写英文/数字");
        }
    }

    // ---------------------------------------------------------------- 读配置

    /** 剔掉注释行后的配置文本 —— 注释里的同名代号不算配置。 */
    private static String configText() {
        InputStream in = MailRoleCatalogConfigTest.class.getClassLoader()
                .getResourceAsStream("application.yml");
        assertNotNull(in, "classpath 上找不到 application.yml");
        try (BufferedReader r = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
            return r.lines()
                    .filter(l -> !l.trim().startsWith("#"))
                    .collect(Collectors.joining("\n"));
        } catch (Exception e) {
            fail("读 application.yml 失败：" + e.getMessage());
            return "";
        }
    }

    private static String value(String key) {
        Matcher m = Pattern.compile("(?m)^\\s*" + Pattern.quote(key) + ":\\s*(.+?)\\s*$")
                .matcher(configText());
        assertTrue(m.find(), "application.yml 里没有 " + key + "（可能改成了多行写法，请同步本测试）");
        return m.group(1);
    }

    private static List<String> knownRoles() {
        return Arrays.stream(value("known-roles").split(","))
                .map(String::trim).filter(s -> !s.isEmpty()).toList();
    }

    private static Map<String, String> roleAliases() {
        Map<String, String> out = new LinkedHashMap<>();
        for (String pair : value("role-aliases").split(",")) {
            int eq = pair.indexOf('=');
            if (eq <= 0) continue;
            out.put(pair.substring(0, eq).trim(), pair.substring(eq + 1).trim());
        }
        return out;
    }
}
