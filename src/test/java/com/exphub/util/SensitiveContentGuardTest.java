package com.exphub.util;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 写入口凭据拦截的行为测试（2026-11-08，阁主 #81 的过线判据）。
 *
 * <p>这两条是验收线，不是「测试绿」：
 * ① 真凭据全拦；② 说明性文本零误伤。
 * 判据是照生产库全库普查的实测结果调出来的（1312 篇文档、含 password=/token= 的 16 篇、
 * 三篇文档里的 `sk-placeholder...` 占位值），不是拍脑袋写正则。
 */
class SensitiveContentGuardTest {

    private static final String[] TRUE_SECRETS = {
            "sk-proj-AbCdEfGh1234567890XyZ",
            "xoxb-123456789012-abcdefghijk",
            "AKIAIOSFODNN7EXAMPLE",
            "-----BEGIN RSA PRIVATE KEY-----",
            "jdbc:mysql://admin:S3cretP@ss@db.internal:3306/app",
            "db.password=Xk9#mQ2!vL",
            "api.token=Ab12cd34Ef56"
    };

    private static final String[] BENIGN = {
            "password= 这个字段名不要写进日志",
            "密码配置写成 password=<你的密码>，别提交",
            "token=your_token_here 只是占位示例",
            "password=${DB_PASSWORD} 由环境变量注入",
            "注意：不要把 password= 明文存进去",
            "token 是令牌，password 是口令，二者都别写死",
            "var TOKEN  = document.querySelector('meta[name=\"_csrf\"]').content;",
            "api-key: ${DEEPSEEK_API_KEY:sk-placeholder-value}   # 永不硬编码",
            "| parse_error 脱敏 | 裸写 `token=abc123SECRET` 也抹 |",
            "if (apiKey.isBlank() || apiKey.startsWith(\"sk-placeholder\")) {"
    };

    @Test
    @DisplayName("真凭据 7 条：每一条都必须被拒，且回执带字段/行号/类别")
    void allTrueSecretsAreRejected() {
        for (String s : TRUE_SECRETS) {
            IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                    () -> SensitiveContentGuard.check("正文", s), "没拦住：" + s);
            assertTrue(ex.getMessage().contains("已拒绝写入"), ex.getMessage());
            assertTrue(ex.getMessage().contains("正文 第 1 行"), ex.getMessage());
            assertTrue(ex.getMessage().contains("片段"), ex.getMessage());
        }
    }

    @Test
    @DisplayName("说明性文本 10 条：零误伤（含代码赋值、环境变量占位、脱敏样例）")
    void benignDocsAreNeverRejected() {
        for (String s : BENIGN) {
            assertDoesNotThrow(() -> SensitiveContentGuard.check("正文", s),
                    "误拒了说明性文本：" + s);
        }
    }

    @Test
    @DisplayName("拒绝回执绝不能把原值带出去，否则拦下的凭据又进了日志和模型上下文")
    void receiptNeverLeaksRawValue() {
        String secret = "sk-proj-AbCdEfGh1234567890XyZ";
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> SensitiveContentGuard.check("正文", secret));
        assertFalse(ex.getMessage().contains(secret), "回执里出现原值：" + ex.getMessage());
        assertFalse(ex.getMessage().contains("AbCdEfGh1234567890"), ex.getMessage());
        assertTrue(ex.getMessage().contains("sk***Z"), "应给前 2 后 1 的掩码：" + ex.getMessage());
    }

    @Test
    @DisplayName("行号要准：第三行里的凭据报第 3 行，不是第 1 行")
    void lineNumberIsAccurate() {
        String text = "# 标题\n\n配置：db.password=Xk9#mQ2!vL";
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> SensitiveContentGuard.check("正文", text));
        assertTrue(ex.getMessage().contains("正文 第 3 行"), ex.getMessage());
    }

    @Test
    @DisplayName("多字段扫描：摘要里带凭据也要拦，不能只扫正文")
    void allFieldsAreScanned() {
        Map<String, String> fields = new LinkedHashMap<>();
        fields.put("正文", "正常的说明文本");
        fields.put("摘要", "顺手记一下 token=Ab12cd34Ef56");
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> SensitiveContentGuard.check(fields));
        assertTrue(ex.getMessage().contains("摘要"), ex.getMessage());
    }

    @Test
    @DisplayName("KV 三条限定缺一不可：太短 / 纯字母 / 中文 / 占位 / 代码表达式都要放过")
    void kvShapeMustBeFullySatisfied() {
        assertDoesNotThrow(() -> SensitiveContentGuard.check("正文", "password=abc123"));       // <8
        assertDoesNotThrow(() -> SensitiveContentGuard.check("正文", "password=abcdefghij"));   // 纯字母
        assertDoesNotThrow(() -> SensitiveContentGuard.check("正文", "password=我的口令1234"));  // 中文
        assertDoesNotThrow(() -> SensitiveContentGuard.check("正文", "password=12345678"));     // 占位词
        assertDoesNotThrow(() -> SensitiveContentGuard.check("正文", "password=config.value()")); // 代码表达式
        // 三条都满足 → 拒
        assertThrows(IllegalArgumentException.class,
                () -> SensitiveContentGuard.check("正文", "password=Xk9#mQ2!vL"));
    }

    @Test
    @DisplayName("空字段/空 map 不能抛错，否则局部更新会被全量拦死")
    void nullAndEmptyAreSafe() {
        assertDoesNotThrow(() -> SensitiveContentGuard.check((Map<String, String>) null));
        assertDoesNotThrow(() -> SensitiveContentGuard.check(new LinkedHashMap<>()));
        assertDoesNotThrow(() -> SensitiveContentGuard.check("正文", null));
        assertDoesNotThrow(() -> SensitiveContentGuard.check("正文", ""));
    }

    @Test
    @DisplayName("拒绝文案一眼看出「改内容才有救」；且必须是专用异常，工具层才不会包成「创建失败」")
    void rejectionWordingIsUnmistakable() {
        SensitiveContentGuard.Rejected ex = assertThrows(SensitiveContentGuard.Rejected.class,
                () -> SensitiveContentGuard.check("正文", "sk-proj-AbCdEfGh1234567890XyZ"));
        assertTrue(ex.getMessage().startsWith("❌ 已拒绝写入：疑似凭据（第 1 行，类别："),
                ex.getMessage());
        assertTrue(ex.getMessage().contains("重试同样内容不会通过"),
                "去掉这半句人还是会重试：" + ex.getMessage());
        assertTrue(ex.getMessage().contains("请改成类别名或掩码"), ex.getMessage());
        assertTrue(ex.getMessage().contains("别贴凭据字面值"), "约定要写在最需要的时刻：" + ex.getMessage());
    }

    @Test
    @DisplayName("scan 只返回命中不抛错，便于事后跑全库误伤对账")
    void scanIsNonThrowing() {
        assertEquals(0, SensitiveContentGuard.scan("正文", "普通文本").size());
        assertEquals(1, SensitiveContentGuard.scan("正文", "sk-proj-AbCdEfGh1234567890XyZ").size());
    }
}
