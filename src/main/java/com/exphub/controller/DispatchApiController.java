package com.exphub.controller;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.exphub.entity.DispatchEvent;
import com.exphub.entity.DispatchHeader;
import com.exphub.mapper.DispatchEventMapper;
import com.exphub.service.DispatchService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.format.DateTimeFormatter;
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
                                      @RequestParam(defaultValue = "50") int limit,
                                      @RequestParam(defaultValue = "0") int offset) {
        int lim = normalizeLimit(limit);
        int off = Math.max(0, offset);

        long total = dispatchService.count(null, status, orderNo, null, null);
        List<DispatchHeader> rows = dispatchService.list(null, status, orderNo, null, lim, off, null);

        List<Map<String, Object>> data = new ArrayList<>();
        for (DispatchHeader h : rows) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("order_no", h.getOrderNo());
            m.put("title", h.getTitle());
            m.put("status", h.getStatus());
            m.put("assignee", h.getAssignee());
            m.put("creator", h.getCreator());
            m.put("origin", h.getOrigin());
            m.put("created_at", h.getCreatedAt() == null ? null : h.getCreatedAt().format(TS));
            m.put("updated_at", h.getUpdatedAt() == null ? null : h.getUpdatedAt().format(TS));
            data.add(m);
        }
        return envelope(total, lim, off, data);
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
