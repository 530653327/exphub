package com.exphub.mcp;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.util.EnumSet;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 部署脚本<b>行为</b>测试：故意让一条迁移报 ERROR 1052，真跑 deploy.sh，
 * 断言「非零退出 + 不重启 + 退出码/步骤落 deploy.log + 失败文件不记账」。
 *
 * <p><b>为什么不能只扫源码</b>（2026-11-09 阁主派单 T261109062429-732）：
 * 2.4.0 首发那次故障，既不是 SQL 语法错、也不是列名拼错，而是<b>脚本在失败之后照样重启</b>。
 * 源码扫描只能证明「分支里写了 exit 1」，证明不了「运行时真的没走到 systemctl restart」；
 * 而后来任何一次顺序调整（比如把 restart 提到迁移前面）都可能让扫描型测试继续绿。
 *
 * <p>做法：临时仓库 + 假 {@code mysql}/{@code mvn}/{@code systemctl}/... 全部前置到 PATH，
 * 唯一一条迁移被假 mysql 以 ERROR 1052 打断。整个测试不连库、不碰系统服务、不重启任何进程。
 */
class DeployMigrationFailureBehaviorTest {

    private static final String FAILED = "migration_20261109test_fail.sql";

    @TempDir
    Path tmp;

    @Test
    @DisplayName("真跑 deploy.sh：迁移失败必须 exit=1、systemctl restart 0 次、deploy.log 记 exit/step")
    void failingMigrationMustAbortWithoutRestart() throws Exception {
        Path repo = Files.createDirectories(tmp.resolve("repo"));
        Path migrations = Files.createDirectories(repo.resolve("sql/migrations"));
        Files.writeString(migrations.resolve(FAILED),
                "ALTER TABLE mail_roles ADD COLUMN selftest_col INT;\n");

        Path bin = Files.createDirectories(tmp.resolve("bin"));
        Path systemctlLog = tmp.resolve("systemctl.log");
        Path mysqlLog = tmp.resolve("mysql.log");
        Path deployLog = tmp.resolve("deploy.log");
        Path migLog = tmp.resolve("mig.log");

        writeExecutable(bin.resolve("mvn"), "#!/bin/sh\nexit 0\n");
        writeExecutable(bin.resolve("pkill"), "#!/bin/sh\nexit 0\n");
        writeExecutable(bin.resolve("netstat"), "#!/bin/sh\nexit 0\n");
        writeExecutable(bin.resolve("pgrep"), "#!/bin/sh\nexit 1\n");
        writeExecutable(bin.resolve("curl"), "#!/bin/sh\nexit 1\n");
        writeExecutable(bin.resolve("journalctl"), "#!/bin/sh\nexit 0\n");
        writeExecutable(bin.resolve("systemctl"), """
                #!/bin/sh
                printf '%s\\n' "$*" >> "$FAKE_SYSTEMCTL_LOG"
                exit 0
                """);
        writeExecutable(bin.resolve("md5sum"), """
                #!/bin/sh
                printf '0123456789abcdef0123456789abcdef  %s\\n' "${1:--}"
                """);
        // 假 mysql：建表/查 checksum/记账都成功，唯独读迁移文件时以真实事故的 ERROR 1052 失败。
        writeExecutable(bin.resolve("mysql"), """
                #!/bin/sh
                printf '%s\\n' "$*" >> "$FAKE_MYSQL_LOG"
                case "$*" in
                  *"CREATE TABLE IF NOT EXISTS schema_migrations"*) exit 0 ;;
                esac
                case "${1:-}" in
                  -N) exit 0 ;;
                esac
                case "$*" in
                  *INSERT*|*UPDATE*) exit 0 ;;
                esac
                cat >/dev/null 2>&1 || true
                printf "ERROR 1052 (23000) at line 2: Column 'role' in field list is ambiguous\\n" >&2
                exit 1
                """);

        Files.copy(Path.of("deploy.sh"), repo.resolve("deploy.sh"));

        Path deployOut = tmp.resolve("deploy.out");
        ProcessBuilder pb = new ProcessBuilder("bash", "deploy.sh");
        pb.directory(repo.toFile());
        pb.redirectErrorStream(true);
        // 不接管道：脚本输出直接落文件，避免「父进程读管道」与「子进程等 stdin」互相等死。
        pb.redirectOutput(deployOut.toFile());
        pb.redirectInput(ProcessBuilder.Redirect.from(new java.io.File("/dev/null")));
        Map<String, String> env = pb.environment();
        env.put("PATH", bin + ":" + env.getOrDefault("PATH", "/usr/bin:/bin"));
        env.put("EXPHUB_REPO", repo.toString());
        env.put("EXPHUB_SKIP_PULL", "1");
        env.put("EXPHUB_DEPLOY_LOG", deployLog.toString());
        env.put("EXPHUB_ENTRY_DIR", tmp.resolve("entry").toString());
        env.put("EXPHUB_MIG_LOG", migLog.toString());
        env.put("EXPHUB_DB_USERNAME", "selftest");
        env.put("EXPHUB_DB_PASSWORD", "selftest");
        env.put("EXPHUB_DB_NAME", "selftest");
        env.put("FAKE_SYSTEMCTL_LOG", systemctlLog.toString());
        env.put("FAKE_MYSQL_LOG", mysqlLog.toString());
        env.remove("EXPHUB_SELF_REEXEC");

        Process p = pb.start();
        boolean finished = p.waitFor(60, java.util.concurrent.TimeUnit.SECONDS);
        if (!finished) {
            p.destroyForcibly();
            String partial = Files.exists(deployOut) ? Files.readString(deployOut, StandardCharsets.UTF_8) : "(无输出)";
            fail("deploy.sh 60 秒未退出（脚本在等 stdin/管道）；卡住前输出：\n" + partial);
        }
        String out = Files.readString(deployOut, StandardCharsets.UTF_8);
        int code = p.exitValue();

        assertEquals(1, code, "迁移失败必须非零退出（旧行为是打一行 ⚠️ 后继续重启）；实际输出：\n" + out);
        assertTrue(out.contains("数据库迁移失败"), "失败摘要必须点明「数据库迁移失败」：\n" + out);
        assertTrue(out.contains(FAILED), "摘要必须点名是哪个迁移文件：\n" + out);
        assertTrue(out.contains("ERROR 1052"), "摘要必须带 mysql 原始错误码，否则还得翻日志：\n" + out);
        assertTrue(out.contains("不重启服务"), "摘要必须写清「按设计不重启服务」：\n" + out);

        String log = Files.readString(deployLog, StandardCharsets.UTF_8);
        assertEquals(1, Files.readAllLines(deployLog).size(), "一次部署只许一行退出记录：\n" + log);
        assertTrue(log.contains("exit=1"), "deploy.log 必须落退出码：\n" + log);
        assertTrue(log.contains("step=2.5/5 数据库迁移"), "deploy.log 必须写清停在哪一步：\n" + log);
        assertTrue(log.contains("phase=迁移失败 " + FAILED), "失败时还要带 phase，直接定位文件：\n" + log);

        String systemctl = Files.exists(systemctlLog) ? Files.readString(systemctlLog) : "";
        assertFalse(systemctl.contains("restart"),
                "迁移失败后一次都不许调用 systemctl restart（这正是 2.4.0 装死的根因）；实际：\n" + systemctl);

        String mysql = Files.readString(mysqlLog, StandardCharsets.UTF_8);
        assertFalse(mysql.contains("INSERT INTO schema_migrations"),
                "失败的迁移文件不许记账，否则下次部署会按 md5 跳过它：\n" + mysql);
    }

    private static void writeExecutable(Path path, String body) throws IOException {
        Files.writeString(path, body, StandardCharsets.UTF_8);
        Files.setPosixFilePermissions(path, EnumSet.of(
                PosixFilePermission.OWNER_READ,
                PosixFilePermission.OWNER_WRITE,
                PosixFilePermission.OWNER_EXECUTE,
                PosixFilePermission.GROUP_READ,
                PosixFilePermission.GROUP_EXECUTE,
                PosixFilePermission.OTHERS_READ,
                PosixFilePermission.OTHERS_EXECUTE));
    }
}
