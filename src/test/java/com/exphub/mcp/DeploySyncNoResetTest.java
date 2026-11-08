package com.exphub.mcp;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 部署同步守卫：不许再用 {@code git reset --hard} 把服务器工作区「静默抹平」。
 *
 * <p><b>为什么</b>（2026-11-09 阁主派单 T261109062914-652 / 越哥已批准）：
 * 部署脚本 [1/5] 原来有一行 {@code git reset --hard origin/master}，入口薄壳里也有一行。
 * 它的作用是「让服务器工作区等于 origin/master」，但实现方式是**无条件丢弃本地改动** ——
 * 服务器上任何未提交的热修（migration .sql、临时补丁）都会被抹掉，且不留一句提示。
 *
 * <p>它<b>不碰</b> {@code schema_migrations}（那是库表），所以并不存在「把已完成迁移打回
 * NOT_STARTED」这回事；迁移重跑的真实原因是「失败的文件没记账」或「文件 md5 与记账不一致」。
 * 但这行仍然是雷：它让「服务器上有一个没提交的修复」变成「悄悄没了」。
 *
 * <p>现在的要求（两条一起，少一条都不算改对）：
 * <ol>
 *   <li>工作区有未提交改动 → <b>明拒</b>（exit 1 + 指出哪些文件），不静默丢弃；</li>
 *   <li>干净工作区 → 只允许 {@code git merge --ff-only}；本地有提交/历史分叉也明拒。</li>
 * </ol>
 *
 * <p>⚠️ 断言前必须剥注释（红线 10）：为什么删掉 reset 的说明本身就写着这个词，
 * 不剥的话「注释里提到 reset」会让测试两头误判。
 */
class DeploySyncNoResetTest {

    private static final Path DEPLOY = Path.of("deploy.sh");
    private static final Path ENTRY = Path.of("deploy/exphub-run.sh");

    /** 剥掉整行注释（本文件里没有以 # 开头的代码行，够用） */
    private static String stripComments(String src) {
        return Arrays.stream(src.split("\n", -1))
                .map(String::strip)
                .filter(l -> !l.startsWith("#"))
                .collect(Collectors.joining("\n"));
    }

    private static String code(Path p) throws IOException {
        return stripComments(Files.readString(p, StandardCharsets.UTF_8));
    }

    @Test
    @DisplayName("deploy.sh 代码里不许再出现 git reset --hard，也不许把它换个写法加回来")
    void deployScriptMustNotHardReset() throws IOException {
        String c = code(DEPLOY);
        assertFalse(c.contains("git reset --hard"),
                "git reset --hard 已在 2026-11-09 清掉，不许加回来（会静默丢弃服务器本地改动）");
        assertFalse(c.contains("git checkout -f") || c.contains("git checkout --force"),
                "别用 git checkout -f 换皮：同样是静默覆盖本地改动");
        assertTrue(c.contains("--ff-only"),
                "同步必须只允许快进（git merge --ff-only），否则本地提交会被强并/覆盖");
    }

    @Test
    @DisplayName("--ff-only 不够：还必须显式比较 HEAD 与 origin/master")
    void mustVerifyHeadEqualsOrigin() throws IOException {
        // 实测踩到：本地提交在 origin/master 之上时，git merge --ff-only 认为
        // 「Already up to date」直接返回 0 —— 本地提交会被静默保留并部署。
        // 所以「只加 --ff-only」这种改法看似安全，实际上把 reset 的兜底也一起拿掉了。
        for (Path p : new Path[]{DEPLOY, ENTRY}) {
            String c = code(p);
            assertTrue(c.contains("rev-parse HEAD") && c.contains("rev-parse origin/master"),
                    p + " 必须显式比较 HEAD 与 origin/master；--ff-only 挡不住「本地领先」");
        }
    }

    @Test
    @DisplayName("仓库外入口同样不许 reset —— 生产路径走的是入口那一行")
    void entryMustNotHardResetEither() throws IOException {
        String c = code(ENTRY);
        assertFalse(c.contains("git reset --hard"),
                "入口是真实部署路径（它先同步再调 deploy.sh），只改内层等于没改");
        assertTrue(c.contains("--ff-only"),
                "入口也必须只允许快进；否则服务器上未提交的改动仍会被静默抹掉");
    }

    @Test
    @DisplayName("脏工作区必须明拒：检查在前、exit 1、点名文件，不许静默继续")
    void dirtyWorktreeMustAbortLoudly() throws IOException {
        String deploy = code(DEPLOY);
        int check = deploy.indexOf("git status --porcelain");
        int merge = deploy.indexOf("git merge --ff-only");
        assertTrue(check > 0, "缺少「工作区是否干净」的检查");
        assertTrue(merge > check, "必须**先**查脏工作区再快进，否则等于先覆盖再报错");

        String block = deploy.substring(check, merge);
        assertTrue(block.contains("exit 1"),
                "脏工作区必须 exit 1：静默继续等于让部署跑在未知的工作区上");
        assertTrue(block.contains("未提交"),
                "报错必须说清是「未提交的改动」，别让人去猜为什么不动");
        assertTrue(deploy.contains("git status --porcelain --untracked-files=no")
                        || deploy.contains("git status --porcelain -uno"),
                "只检查**被跟踪文件**的改动：部署目录里的 deploy.log / 运行产物是未跟踪的，不该挡住部署");

        String entry = code(ENTRY);
        int entryCheck = entry.indexOf("git status --porcelain");
        int entryMerge = entry.indexOf("git merge --ff-only");
        assertTrue(entryCheck > 0 && entryMerge > entryCheck,
                "入口也要先查脏工作区再快进");
        assertTrue(entry.substring(entryCheck, entryMerge).contains("exit 1"),
                "入口的脏工作区同样必须明拒");
    }

    @Test
    @DisplayName("入口仍保持薄壳：≤20 行，且不掺构建/重启/迁移逻辑")
    void entryStaysThinAfterChange() throws IOException {
        var lines = Files.readAllLines(ENTRY, StandardCharsets.UTF_8);
        assertTrue(lines.size() <= 20, "入口约定 ≤20 行，当前 " + lines.size() + " 行");
        String c = code(ENTRY);
        assertTrue(c.contains("exec bash \"$REPO/deploy.sh\""), "入口终点必须是调仓库内 deploy.sh");
        assertFalse(c.contains("mvn ") || c.contains("systemctl") || c.contains("mysql"),
                "入口不许掺构建/重启/迁移逻辑（自举风险）");
    }
}
