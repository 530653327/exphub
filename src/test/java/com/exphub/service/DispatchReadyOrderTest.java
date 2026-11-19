package com.exphub.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.exphub.entity.DispatchHeader;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 待接单排序（先进先出）的守卫（2026-11-20 加，W-20261120-000010）。
 *
 * <p><b>为什么必须钉住</b>：排序是「看不见的行为」。原来统一 {@code id DESC}，
 * 哨兵每 20 秒轮询 {@code dispatch_list(status=READY)} 取首张认领，结果最新建的单
 * 一直插队 —— 老单永远排在队尾，界面上没有任何异常提示。
 *
 * <p>这里只盯四件事，都不连库：
 * <ol>
 *   <li>READY 分支是 {@code priority ASC, created_at ASC, id ASC}（先急后缓、同级先进先出）</li>
 *   <li>其他状态与不带 status 仍是 {@code id DESC}（后台/阁主查单要看最新的）</li>
 *   <li>排序只有 {@link DispatchService#applyOrder} 一个出口 —— 在 list 里再写死一份必然漂</li>
 *   <li>回执必须回显当前排序，且文案只从 {@link DispatchService#orderNote} 取</li>
 * </ol>
 */
class DispatchReadyOrderTest {

    private static final Path SERVICE =
            Path.of("src/main/java/com/exphub/service/DispatchService.java");
    private static final Path TOOLS =
            Path.of("src/main/java/com/exphub/mcp/ExpHubTools.java");

    /** 剥注释再断言（AGENTS 红线 10：注释里写着「iid DESC」也能把测试喂绿）。 */
    private static String stripComments(String src) {
        return src.replaceAll("(?s)/\\*.*?\\*/", "").replaceAll("(?m)//.*$", "");
    }

    private static String orderSql(String status) {
        QueryWrapper<DispatchHeader> q = new QueryWrapper<>();
        DispatchService.applyOrder(q, status);
        return q.getTargetSql();
    }

    @Test
    @DisplayName("「待接单」判定要归一：大小写、前后空格都算 READY")
    void readyStatusIsNormalized() {
        assertTrue(DispatchService.isReadyQueue("READY"));
        assertTrue(DispatchService.isReadyQueue(" ready "));
        assertFalse(DispatchService.isReadyQueue("RUNNING"));
        assertFalse(DispatchService.isReadyQueue("VERIFYING"));
        assertFalse(DispatchService.isReadyQueue(null));
        assertFalse(DispatchService.isReadyQueue("  "));
    }

    @Test
    @DisplayName("READY 按优先级升序 + 建单时间升序（1=高，先做急的；同级先进先出）")
    void readyQueueIsPriorityThenFifo() {
        String sql = orderSql("READY");

        assertTrue(sql.replace(" ", "").contains("priorityASC"), "没按优先级排 —— 急单会被缓单压住：" + sql);
        assertTrue(sql.replace(" ", "").contains("created_atASC"), "没按建单时间升序 —— 老单仍被新单压住：" + sql);
        assertTrue(sql.replace(" ", "").contains("idASC"), "同一时间的兜底次序缺失，结果不稳定：" + sql);
        assertFalse(sql.contains("DESC"), "READY 列表仍有一列倒序，先进先出会被破坏：" + sql);
    }

    @Test
    @DisplayName("其他状态与不带 status 保持 id 倒序（行为不变）")
    void otherQueriesKeepIdDesc() {
        for (String status : new String[]{"RUNNING", "VERIFYING", "DONE", null, "", "  "}) {
            String sql = orderSql(status);
            assertTrue(sql.replace(" ", "").contains("idDESC"), "状态=" + status + " 的排序被改掉了：" + sql);
        }
    }

    @Test
    @DisplayName("排序只有一个出口：list 必须调 applyOrder，不许自己再写一份 orderBy")
    void listMustUseTheSingleOrderHelper() throws Exception {
        String src = stripComments(Files.readString(SERVICE, StandardCharsets.UTF_8));

        int sig = src.indexOf("int limit, int offset, String apiKey) {");
        assertTrue(sig > 0, "找不到 list(..., limit, offset, apiKey) 的方法签名（改签名了？）");
        int end = src.indexOf("headerMapper.selectList(q);", sig);
        assertTrue(end > sig, "找不到 list 方法体的 selectList 结尾");
        String body = src.substring(sig, end);

        assertTrue(body.contains("applyOrder(q, status)"),
                "list 没有走 applyOrder —— 排序规则必须只有一处出处，否则 READY/后台/MCP 会各排各的");
        assertFalse(body.contains("orderByDesc"),
                "list 里还自己写了 orderByDesc，会绕过 applyOrder 的状态分支");
    }

    @Test
    @DisplayName("回执回显排序规则，且文案只从 orderNote 取（不许在 ExpHubTools 再写一份）")
    void receiptEchoesOrderNoteFromOneSource() throws Exception {
        assertTrue(DispatchService.orderNote("READY").contains("先进先出"),
                "READY 的排序说明里没写「先进先出」—— 人看不出这轮排序到底变没变");
        assertTrue(DispatchService.orderNote("READY").contains("优先级"),
                "READY 的排序说明里没提优先级");
        assertTrue(DispatchService.orderNote(null).contains("倒序"),
                "不带 status 时排序说明没写倒序，与 orderSql 不一致");

        String tools = stripComments(Files.readString(TOOLS, StandardCharsets.UTF_8));
        int start = tools.indexOf("@Tool(name = \"dispatch_list\"");
        assertTrue(start >= 0, "找不到 dispatch_list 的 @Tool 注解");
        int next = tools.indexOf("@Tool(", start + 6);
        String block = next > start ? tools.substring(start, next) : tools.substring(start);

        assertTrue(block.contains("DispatchService.orderNote("),
                "dispatch_list 回执没有调 DispatchService.orderNote —— 文案自己另写一份必然会与排序实现漂");
        assertTrue(block.contains("先进先出"),
                "dispatch_list 的工具描述没说 READY 是先进先出，模型/哨兵不知道排序已生效");
    }
}
