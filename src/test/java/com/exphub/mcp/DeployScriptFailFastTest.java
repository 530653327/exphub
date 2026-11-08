package com.exphub.mcp;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 部署脚本守卫：迁移失败必须「停住」而不是「装死重启」。
 *
 * <p><b>为什么需要</b>（2026-11-09 阁主派单 T261109062441-879）：
 * 2.4.0 首发时游标迁移在 MySQL 5.7.44 上报 ERROR 1052 中途断了，旧脚本只打一行
 * {@code ⚠️ ... 执行有报错（若是幂等脚本可忽略重复列提示）} 就继续重启。
 * 服务起来「看着一切正常」，实际列没加、功能半坏 —— 故障被脚本自己盖住了。
 *
 * <p>这类缺陷没法靠跑单测发现（shell 脚本没有行为测试），只能钉源码结构。钉的三条：
 * <ol>
 *   <li>迁移分支失败必须 {@code exit 1}，且不能留「报错可忽略」的分支；</li>
 *   <li>重启必须排在迁移之后（迁移成功才继续）；</li>
 *   <li>deploy.log 的退出码行只能由一个地方写（EXIT trap），否则一次部署两行、结论还可能打架。</li>
 * </ol>
 *
 * <p>⚠️ 断言前必须剥注释（红线 10）：旧措辞本来就写在说明性注释里，
 * 不剥的话「改了真文案测试仍绿」或「只删注释测试就红」两头都可能。
 */
class DeployScriptFailFastTest {

    private static final Path DEPLOY = Path.of("deploy.sh");
    private static final Path ENTRY = Path.of("deploy/exphub-run.sh");

    /** 剥掉整行注释与行尾注释（本文件里没有含 # 的字符串字面量，够用） */
    private static String stripComments(String src) {
        return Arrays.stream(src.split("\n", -1))
                .map(String::strip)
                .filter(l -> !l.startsWith("#"))
                .collect(Collectors.joining("\n"));
    }

    private static String deployCode() throws IOException {
        return stripComments(Files.readString(DEPLOY, StandardCharsets.UTF_8));
    }

    private static String section(String code, String from, String to) {
        int a = code.indexOf(from);
        int b = code.indexOf(to);
        assertTrue(a >= 0, "找不到段落起点：" + from);
        assertTrue(b > a, "找不到段落终点（或顺序不对）：" + to);
        return code.substring(a, b);
    }

    @Test
    @DisplayName("迁移失败必须非零退出，不许留「报错可忽略」的分支")
    void migrationFailureMustAbort() throws IOException {
        String code = deployCode();
        String mig = section(code, "执行数据库迁移", "2.6/5");

        assertFalse(mig.contains("若是幂等脚本可忽略"),
                "「若是幂等脚本可忽略重复列提示」这种放行分支必须消失："
                        + "mysql 遇第一个错误就中断，后面的语句根本没跑，放行等于把半截迁移伪装成成功");
        assertTrue(mig.contains("exit 1"),
                "迁移失败分支必须 exit 1（旧版只打 ⚠️ 然后继续重启）");
        assertTrue(mig.contains("数据库迁移失败"),
                "失败摘要必须点明「数据库迁移失败」，别让人再去 app.log 里猜");
        assertTrue(mig.contains("不重启服务"),
                "摘要必须写清「按设计不重启服务」，否则读者无法判断线上是旧版还是半坏的新版");
        assertTrue(mig.contains("MIG_IDX") && mig.contains("MIG_TOTAL"),
                "失败摘要必须带「第几条 / 共几条」，否则不知道该从哪一条重试");
    }

    @Test
    @DisplayName("重启必须排在迁移之后：迁移没成功就不许走到 systemctl restart")
    void restartComesAfterMigration() throws IOException {
        String code = deployCode();
        int mig = code.indexOf("执行数据库迁移");
        int restart = code.indexOf("systemctl restart exphub");
        assertTrue(mig > 0 && restart > 0);
        assertTrue(mig < restart, "迁移步骤必须在重启之前（DDL 晚于重启 = Unknown column 起不来）");

        // 迁移段落里不能出现 systemctl restart：一旦出现，说明失败路径也走到了重启
        String migSection = section(code, "执行数据库迁移", "2.6/5");
        assertFalse(migSection.contains("systemctl restart"),
                "迁移段落里不许有重启动作 —— 失败必须直接退出");
    }

    @Test
    @DisplayName("deploy.log 的退出码行只能有一个写入口（EXIT trap）")
    void deployLogHasSingleWriterWithExitCode() throws IOException {
        String code = deployCode();

        assertEquals(1, countOf(code, ">> \"$DEPLOY_LOG\""),
                "deploy.log 只允许 EXIT trap 一处追加：多处写会出现「一行说成功、一行说失败」");
        assertTrue(code.contains("trap _on_exit EXIT"),
                "必须用 EXIT trap 兜住所有退出路径（set -e 中途失败、健康检查失败、systemctl 失败）");
        assertTrue(code.contains("exit=%d"),
                "deploy.log 那行必须带退出码，否则下次还得靠人翻日志判断成败");
        assertTrue(code.contains("commit=%s") && code.contains("step=%s"),
                "退出码行至少要带 时间 + commit + 退出码 + 停在哪一步");

        // 旧的 2.7 步写日志必须已移除（否则首次失败仍会留一条「看着正常」的记录）
        assertFalse(code.contains("/usr/local/exphub/deploy.log"),
                "代码里不该再有写死的 deploy.log 路径，日志位置统一走 $DEPLOY_LOG");
    }

    @Test
    @DisplayName("仓库外入口保持薄壳：只 git 同步 + 调内层，且 ≤20 行")
    void entryStaysThin() throws IOException {
        List<String> lines = Files.readAllLines(ENTRY, StandardCharsets.UTF_8);
        assertTrue(lines.size() <= 20,
                "入口不在版本控制内，越复杂越难救（约定 ≤20 行），当前 " + lines.size() + " 行");

        String code = stripComments(String.join("\n", lines));
        assertTrue(code.contains("exec bash \"$REPO/deploy.sh\""),
                "入口的终点必须是调仓库内 deploy.sh —— 业务逻辑不许上移，否则下次 git reset 后两边分裂");
        assertFalse(code.contains("mvn ") || code.contains("systemctl") || code.contains("mysql"),
                "入口不许掺构建/重启/迁移逻辑");
    }

    private static int countOf(String s, String sub) {
        int n = 0, i = 0;
        while ((i = s.indexOf(sub, i)) >= 0) { n++; i += sub.length(); }
        return n;
    }
}
