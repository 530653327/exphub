package com.exphub.controller;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.exphub.entity.DispatchEvent;
import com.exphub.entity.DispatchHeader;
import com.exphub.mapper.DispatchEventMapper;
import com.exphub.service.DispatchBurndownService;
import com.exphub.service.DispatchService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 派单只读 JSON API（2026-11-20 W-20261120-000025），供忆阁右栏「派单操作流水」控制台调用。
 *
 * <p><b>只读，零写能力</b>：整个类只有两个 {@code @GetMapping}，没有任何 POST/PUT/DELETE。
 * 鉴权走 {@link com.exphub.interceptor.DispatchReadTokenInterceptor} 的专用 token，不复用 MCP Key。
 *
 * <p><b>人工单可见</b>：{@code /orders} 用 {@code DispatchService.list(..., apiKey=null)} 的后台视角看全部，
 * 包括 origin=HUMAN、api_key 为空的单。MCP 侧 dispatch_list 的隔离规则一行不动 —— 人工单对哨兵仍不可见。
 */
@RestController
@RequestMapping("/api/dispatch")
public class DispatchApiController {

    private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    /** 单次最多返回多少条。防止一次请求把整张表拉进内存。 */
    private static final int MAX_LIMIT = 500;

    @Autowired
    private DispatchEventMapper eventMapper;

    @Autowired
    private DispatchService dispatchService;

    @Autowired
    private DispatchBurndownService burndownService;

    /**
     * 事件流水。默认按 created_at 正序（老在上，像控制台日志往下滚）。
     *
     * @param orderNo 精确筛单号；空 = 全部
     */
    @GetMapping("/events")
    public Map<String, Object> events(@RequestParam(required = false) String orderNo,
                                      @RequestParam(defaultValue = "100") int limit,
                                      @RequestParam(defaultValue = "0") int offset) {
        int lim = normalizeLimit(limit);
        int off = Math.max(0, offset);

        QueryWrapper<DispatchEvent> q = new QueryWrapper<>();
        if (orderNo != null && !orderNo.isBlank()) {
            q.eq("order_no", orderNo.trim());
        }
        Long total = eventMapper.selectCount(q);
        // 同一秒多条时按 id 兜底，保证翻页顺序稳定、不重复不漏
        q.orderByAsc("created_at").orderByAsc("id").last("LIMIT " + off + "," + lim);
        List<DispatchEvent> rows = eventMapper.selectList(q);

        List<Map<String, Object>> data = new ArrayList<>();
        for (DispatchEvent e : rows) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", e.getId());
            m.put("order_no", e.getOrderNo());
            m.put("event_type", e.getEventType());
            m.put("from_status", e.getFromStatus());
            m.put("to_status", e.getToStatus());
            m.put("actor", e.getActor());
            m.put("actor_type", e.getActorType());
            m.put("note", e.getNote());
            m.put("created_at", e.getCreatedAt() == null ? null : e.getCreatedAt().format(TS));
            data.add(m);
        }
        return envelope(total == null ? 0 : total, lim, off, data);
    }

    /**
     * 单头列表，供右栏做单号筛选下拉。
     *
     * <p>apiKey 传 null = 后台视角看全部（含人工单）；这是与 MCP dispatch_list 唯一的差别。
     */
    @GetMapping("/orders")
    public Map<String, Object> orders(@RequestParam(required = false) String status,
                                      @RequestParam(required = false) String orderNo,
                                      @RequestParam(required = false) String milestone,
                                      @RequestParam(defaultValue = "50") int limit,
                                      @RequestParam(defaultValue = "0") int offset) {
        int lim = normalizeLimit(limit);
        int off = Math.max(0, offset);

        boolean byMs = milestone != null && !milestone.isBlank();
        long total = byMs ? dispatchService.countByMilestone(milestone, status)
                          : dispatchService.count(null, status, orderNo, null, null);
        List<DispatchHeader> rows = byMs
                ? dispatchService.listByMilestone(milestone, status, lim, off)
                : dispatchService.list(null, status, orderNo, null, lim, off, null);

        List<Map<String, Object>> data = new ArrayList<>();
        for (DispatchHeader h : rows) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("order_no", h.getOrderNo());
            m.put("title", h.getTitle());
            m.put("status", h.getStatus());
            m.put("assignee", h.getAssignee());
            m.put("creator", h.getCreator());
            m.put("origin", h.getOrigin());
            m.put("milestone", h.getMilestone());
            m.put("created_at", h.getCreatedAt() == null ? null : h.getCreatedAt().format(TS));
            m.put("updated_at", h.getUpdatedAt() == null ? null : h.getUpdatedAt().format(TS));
            data.add(m);
        }
        return envelope(total, lim, off, data);
    }

    /**
     * 里程碑燃尽图（W-20261120-000026）。纵轴 = 未完成单数，不搞工时；取消单从分母剔除。
     *
     * <p><b>缺 milestone 一律 400</b>，绝不退化成「不加 WHERE 查全库」——
     * 那会让调用方以为拿到了某个里程碑的数据，实际是全库混在一起。
     * 鉴权沿用 025 的 X-Dispatch-Token（拦截器按 /api/dispatch/** 整段拦，本接口自动覆盖）。
     */
    @GetMapping("/burndown")
    public ResponseEntity<Map<String, Object>> burndown(
            @RequestParam(required = false) String milestone,
            @RequestParam(required = false) String from,
            @RequestParam(required = false) String to) {
        if (milestone == null || milestone.isBlank()) {
            return badRequest("缺少必填参数 milestone（不返回全库数据）");
        }
        LocalDate fromDate = null, toDate = null;
        try {
            if (from != null && !from.isBlank()) fromDate = LocalDate.parse(from.trim());
            if (to != null && !to.isBlank()) toDate = LocalDate.parse(to.trim());
        } catch (DateTimeParseException e) {
            return badRequest("from/to 必须是 yyyy-MM-dd（收到 from=" + from + ", to=" + to + "）");
        }
        try {
            DispatchBurndownService.Result r = burndownService.compute(milestone, fromDate, toDate);
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("code", 0);
            m.put("milestone", r.milestone());
            m.put("total", r.total());           // 剔除取消
            m.put("done", r.done());
            m.put("remaining", r.remaining());   // total - done
            m.put("from", r.from().toString());
            m.put("to", r.to().toString());
            m.put("series", r.series().stream().map(p -> {
                Map<String, Object> pt = new LinkedHashMap<>();
                pt.put("date", p.date());
                pt.put("remaining", p.remaining());
                return pt;
            }).toList());
            m.put("time_fallback_count", r.timeFallbackCount());
            m.put("note", "纵轴=未完成单数（不含工时）；取消单已从分母剔除。"
                    + "time_fallback_count=缺少事件时间、改用 header 真实时间兜底的单数（不编 now）。");
            return ResponseEntity.ok(m);
        } catch (DispatchBurndownService.InvalidInput e) {
            return badRequest(e.getMessage());
        }
    }

    private static ResponseEntity<Map<String, Object>> badRequest(String msg) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("code", 400);
        m.put("message", msg);
        return ResponseEntity.badRequest().body(m);
    }

    private static int normalizeLimit(int limit) {
        if (limit <= 0) return MAX_LIMIT > 0 ? Math.min(100, MAX_LIMIT) : 100;
        return Math.min(limit, MAX_LIMIT);
    }

    private static Map<String, Object> envelope(long total, int limit, int offset,
                                                 List<Map<String, Object>> data) {
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("code", 0);
        r.put("total", total);
        r.put("limit", limit);
        r.put("offset", offset);
        r.put("data", data);
        return r;
    }
}
