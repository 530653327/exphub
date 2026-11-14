package com.exphub.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 代发（{@code onBehalfOf}）的<b>口径守卫</b> —— 2026-11-14 阁主 W-20261114-09 验收第 1、2 条。
 *
 * <p>阁主在验收信（线程 T261114134802-723 的 #2083）里定了三条要照着守的，其中两条能落成测试：
 * <ol>
 *   <li><b>口径别写歪</b>：这套锁挡的是「哨兵配错角色/配错配置来源」这类真实误用，<b>挡不住改 http 头的人</b>。
 *       任何文档、注释、回执里都不许写成「鉴权已就位」「只有忆阁能代发」。
 *       原话：「我这边回音阁 #51 已经按这个口径记了」。</li>
 *   <li><b>展示串先留「越哥（经阁主代发）」</b>，不改裸显示 —— 裸显示是越哥明确提的产品要求，
 *       得他本人点头。他若定裸「越哥」，前提是 from_role 一律不动、回信一律走 inReplyTo，
 *       这两条要跟哨兵阁一起核一遍，核完再动展示层。</li>
 * </ol>
 *
 * <p><b>为什么这条守卫必须存在</b>：第一版交付里 {@code rejectConfigSource} 真的写着
 * 「只有忆阁（阁主）的配置能代发」—— 代码是对的（只查前缀），文案却把弱锁说成了鉴权。
 * 这种漂移编译不报错、测试不报错，只有人读回执时才会发现，而读的人会据此以为「已经隔离了」。
 *
 * <p><b>⚠️ 必须先剥注释再断言</b>（AGENTS 红线 10）：{@code src.contains("只有忆阁")} 会被
 * 本文件自己的注释和"禁止写这句话"的说明喂饱 —— 改了真文案测试仍绿。
 */
class DelegationWordingGuardTest {

    private static final Path MAIL_DELEGATION =
            Path.of("src/main/java/com/exphub/service/MailDelegation.java");
    private static final Path MAILBOX_SERVICE =
            Path.of("src/main/java/com/exphub/service/MailboxService.java");
    private static final Path EXP_HUB_TOOLS =
            Path.of("src/main/java/com/exphub/mcp/ExpHubTools.java");
    private static final Path APP_YML =
            Path.of("src/main/resources/application.yml");

    /** 剥掉 Java 注释（块注释 + 行注释），只留真正会出现在回执/展示里的代码文本。 */
    private static String code(Path p) throws IOException {
        return stripJavaComments(Files.readString(p, StandardCharsets.UTF_8));
    }

    /**
     * 会让人误以为「已经做了身份隔离」的说法。这些字符串<b>一个都不许</b>出现在代码字面量里。
     *
     * <p>判定原则：句子里出现「只有 X 能」这种<b>排他断言</b>，而 X 是自报的，就是错的口径。
     * 允许出现的写法是「只接受…前缀」「声明角色须为…」这类<b>规则描述</b>，不承诺不可绕过。
     */
    private static final String[] FORBIDDEN_CLAIMS = {
            "只有忆阁（阁主）的配置能代发",
            "只有忆阁能代发",
            "只有阁主能代发",
            "鉴权已就位",
            "只有忆阁能以",
    };

    @Test
    @DisplayName("代码字面量里不许出现「只有…能代发」「鉴权已就位」这类排他断言")
    void noExclusiveClaimInCode() throws IOException {
        List<String> hits = new ArrayList<>();
        for (Path p : List.of(MAIL_DELEGATION, MAILBOX_SERVICE, EXP_HUB_TOOLS)) {
            String src = code(p);
            for (String bad : FORBIDDEN_CLAIMS) {
                if (src.contains(bad)) hits.add(p.getFileName() + " → 「" + bad + "」");
            }
        }
        assertTrue(hits.isEmpty(),
                "代发是「默认拒绝 + 留痕」，不是身份隔离。排他断言会把弱锁说成鉴权（阁主 W-20261114-09 验收第 1 条）：\n  "
                        + String.join("\n  ", hits));
    }

    @Test
    @DisplayName("拒绝文案必须自曝边界：说明配置来源/角色头是自报的、挡不住改头的人")
    void rejectionTextMustAdmitTheBoundary() {
        String cfgReject = MailDelegation.rejectConfigSource("cfg-baize-watch-mac", "cfg-gezhu-");
        assertTrue(cfgReject.contains("不是鉴权") || cfgReject.contains("挡不住"),
                "配置来源拒绝必须写清这是弱锁，不许让人以为已隔离：" + cfgReject);
        assertTrue(cfgReject.contains("cfg-baize-watch-mac"), "要回显实际收到的配置来源：" + cfgReject);

        String roleReject = MailDelegation.rejectNotDelegator("白泽", "越哥", "cfg-baize-watch-mac");
        assertTrue(roleReject.contains("自报的、不是鉴权"), "角色拒绝必须写清角色头是自报的：" + roleReject);
        assertTrue(roleReject.contains("白泽"), "要回显实际声明角色：" + roleReject);
        assertTrue(roleReject.contains("未发出任何信"), "不许让人以为发出去了：" + roleReject);
    }

    @Test
    @DisplayName("展示串不许退化成裸「越哥」：真实发件人缺失时也要写明代发")
    void displayNeverDegradesToBarePrincipal() {
        // 正常代发：括号里必须是真实发件人，回信才能回到能处理的人
        assertEquals("越哥（经阁主代发）", MailDelegation.display("阁主", "越哥"));

        // 真实发件人缺失（历史脏数据 / 调用方漏填）：也不许只印「越哥」——
        // 那会让收件人以为是越哥亲手发的，且无从判断回信该回给谁
        String degraded = MailDelegation.display(null, "越哥");
        assertNotEquals("越哥", degraded, "真实发件人缺失时不许退化成裸代发对象（阁主验收第 2 条）");
        assertTrue(degraded.contains("越哥"), "仍要说明代发对象：" + degraded);
        assertTrue(degraded.contains("代发"), "必须标出这是代发：" + degraded);

        // 无代发：原样返回，不许凭空多出括号
        assertEquals("阁主", MailDelegation.display("阁主", null));
        assertEquals("阁主", MailDelegation.display("阁主", "   "));
    }

    @Test
    @DisplayName("application.yml 的版本说明不许把代发描述成鉴权")
    void ymlVersionNoteKeepsTheBoundary() throws IOException {
        String yml = Files.readString(APP_YML, StandardCharsets.UTF_8);
        int i = yml.indexOf("2.5.5 = 信箱代发");
        assertTrue(i >= 0, "application.yml 里必须保留 2.5.5 的变更说明");
        String note = yml.substring(i, Math.min(yml.length(), i + 900));
        assertTrue(note.contains("鉴权口径未变") || note.contains("委托信任"),
                "2.5.5 说明里必须写明「鉴权口径未变 / 委托信任」，否则读的人以为代发带来了隔离：" + note);
    }

    // ------------------------------------------------------------------ 工具

    /**
     * 剥 Java 注释。块注释按跨行状态机处理，行注释按第一个 {@code //} 截断。
     * 不处理字符串字面量里的 {@code //}（如 URL）—— 本项目这几处的回执文案里没有裸 URL。
     */
    static String stripJavaComments(String src) {
        StringBuilder out = new StringBuilder(src.length());
        boolean inBlock = false;
        for (String line : src.split("\n", -1)) {
            String t = line;
            if (inBlock) {
                int end = t.indexOf("*/");
                if (end < 0) { out.append('\n'); continue; }
                t = t.substring(end + 2);
                inBlock = false;
            }
            int blk = t.indexOf("/*");
            if (blk >= 0) {
                int end = t.indexOf("*/", blk + 2);
                if (end < 0) { t = t.substring(0, blk); inBlock = true; }
                else { t = t.substring(0, blk) + t.substring(end + 2); }
            }
            int lc = t.indexOf("//");
            if (lc >= 0) t = t.substring(0, lc);
            out.append(t).append('\n');
        }
        return out.toString();
    }
}
