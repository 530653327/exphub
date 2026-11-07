package com.exphub.mcp;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 守卫：scope=all 默认排除「项目内的进度流水」（2026-11-05 实测回归）。
 *
 * <p>为什么会有这条规则：scope 默认改 all 之后，项目里的 project_progress 文档大量涌入主排序 ——
 * 实测「部署流程 deploy.sh」在 scope=global 下 #95 排第 1，切到 all 后两条忆阁进度流水
 * （#729 / #698）挤进前 4，把 #95 顶到第 2。进度是施工日志，不是答案，而且它数量远超项目经验
 * （exphub #1：34 条进度 vs 8 条经验）。
 *
 * <p>这条守卫拦的是「好心办坏事」：下一个人发现「搜不到项目进度」，
 * 顺手把这个条件删掉 —— 于是全局经验又被流水挤下去一遍。
 */
class ScopeProgressFilterTest {

    private String read(String rel) throws IOException {
        return Files.readString(Path.of(rel), StandardCharsets.UTF_8);
    }

    @Test
    @DisplayName("守卫：两条搜索 SQL 都带「排除项目内进度」条件，计数与结果必须同口径")
    void bothSearchSqlExcludeProjectProgress() throws IOException {
        String mapper = read("src/main/java/com/exphub/mapper/DocMapper.java");
        // 判据只认「SQL 里真的用了这个开关」，不数 @Param / javadoc 出现次数 ——
        // 数总数那种判据，加一行注释就红、少一个 @Param 反而绿。
        assertEquals(2, countOf(mapper, "<if test='excludeProjectProgress'>"),
                "排除条件必须同时出现在搜索与计数两条 SQL 里（只改一边会让「匹配 N 条」与实际不符）");

        String cond = "AND (template_type IS NULL OR template_type NOT IN ('project_progress','todo_list'))";
        // 2026-11-08 实测 3 处，不是 2 处。多出来的是 DocMapper.countGlobalRegularDocs
        // （仪表盘「全局知识文档数」，project_id IS NULL 且非过程流水）—— 它无条件排除，没有 excludeProjectProgress 开关，
        // 所以上面 <if> 那条仍然是 2。两处搜索 SQL 仍然各一份，这条判据要守的没变。
        assertEquals(3, countOf(mapper, cond), "两条搜索 SQL 各需一份排除条件（2026-11-06 扩到 todo_list）；"
                + "第 3 处是 countGlobalRegularDocs 的口径，加进来是为了让仪表盘计数与搜索同口径");
    }

    @Test
    @DisplayName("守卫：排除条件不能写成裸 <>（NULL 会被一起排掉）")
    void nullTemplateTypeNotSwallowed() throws IOException {
        // 2026-11-06 改单条件时踩到的：SQL 三值逻辑里 `<> 'x'` 对 NULL 求值为 NULL 而不是 TRUE，
        // 裸写会把所有 template_type 为空的文档静默排掉。库里现在没有 NULL 行，所以这个错
        // 在线上不会立刻显形 —— 正是那种「测试全绿、半年后炸」的形态。
        String mapper = read("src/main/java/com/exphub/mapper/DocMapper.java");
        assertTrue(mapper.contains("template_type IS NULL OR template_type NOT IN ('project_progress','todo_list')"),
                "排除条件必须带 IS NULL 分支（NOT IN 对 NULL 同样求值为 NULL，不是 TRUE）");

        String svc = read("src/main/java/com/exphub/service/DocService.java");
        assertTrue(svc.contains("isNull(\"template_type\")"),
                "LIKE 兜底同理：必须先 isNull 再 notIn，且与 DocMapper 同口径（只看类型，不看 project_id）");
    }

    @Test
    @DisplayName("守卫：显式搜 templateType=project_progress / todo_list 时不排除（否则那条路就死了）")
    void explicitProgressSearchIsNotExcluded() throws IOException {
        String svc = read("src/main/java/com/exphub/service/DocService.java");
        // 2026-11-06：排除集合扩到 todo_list 后，判据必须先重构成「集合包含」。
        // 若还写死 !"project_progress".equals(tt)，显式查 todo_list 会被自己的排除条件反杀 ——
        // 本轮的预计错误就是这样：扩完一个类型，另一个类型的入口静默变空。
        assertTrue(svc.contains("EXCLUDED_FROM_RANKING.contains(tt)"),
                "排除条件必须挂「没显式要这个类型」这个前提上，且用集合判断，不能写死单个类型");
        assertTrue(svc.contains("Set.of(\"project_progress\", \"todo_list\")"),
                "排除集合要同时含 project_progress 与 todo_list");
    }

    @Test
    @DisplayName("守卫：tt 为空时不得进集合判断（Set.of.contains(null) 会抛 NPE）")
    void nullTemplateTypeMustNotReachSetContains() throws IOException {
        // 2026-11-06 真实事故：把判据改成 EXCLUDED_FROM_RANKING.contains(tt) 后部署，
        // 默认搜索（tt 为空）全部 500 —— 因为 Set.of(...) 是不可变集合，contains(null) 抛 NPE。
        // mvn test 612 全绿，因为没有任何测试以「默认参数」真的走一遍 search()。
        String svc = read("src/main/java/com/exphub/service/DocService.java");
        assertTrue(svc.contains("tt == null || !EXCLUDED_FROM_RANKING.contains(tt)"),
                "必须先判 tt != null，再进集合判断");
    }

    @Test
    @DisplayName("守卫：0 结果兜底（findSimilar）不得排除过程记录，否则「昨天踩什么坑」永远查不到")
    void similarFallbackMustKeepProgress() throws IOException {
        // #1290 C71-3：主排名排掉过程记录后，答案只存在于施工日志里的问题会变成 0 结果。
        // 兜底若跟着排，用户得到的是「查无此经验」，然后重复建一条新的 —— 砍过头。
        String svc = read("src/main/java/com/exphub/service/DocService.java");
        assertTrue(svc.contains("boolean excludeProgress = false;"),
                "findSimilar 必须不排除过程记录（主排名干净、兜底仍能捞到）");
    }

    @Test
    @DisplayName("守卫：LIKE 兜底与全文检索口径一致")
    void likeFallbackUsesSameRule() throws IOException {
        String svc = read("src/main/java/com/exphub/service/DocService.java");
        assertTrue(svc.contains("notIn(\"template_type\", \"project_progress\", \"todo_list\")"),
                "LIKE 兜底也要排除过程记录与待办，否则换个档位结果就变了（同一个查询两个答案）");
    }

    private static int countOf(String haystack, String needle) {
        int n = 0;
        int i = 0;
        while ((i = haystack.indexOf(needle, i)) >= 0) {
            n++;
            i += needle.length();
        }
        return n;
    }
}
