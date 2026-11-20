package com.exphub.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.exphub.entity.DispatchEvent;
import com.exphub.entity.DispatchHeader;
import com.exphub.mapper.DispatchEventMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 派单燃尽图聚合（2026-11-20 W-20261120-000026）。
 *
 * <p><b>越哥定的口径，不要自行「改进」</b>：纵轴 = 未完成单数，不搞工时（没人估工，估出来是假数据）；
 * 里程碑是 dispatch_header 上的一个可选字段；不做子任务表、不表达依赖。
 *
 * <p><b>未完成 = 状态不是 DONE 且不是 CANCELLED</b>。取消的单从分母里剔除，不占坑、也不算「已完成」。
 *
 * <p><b>按天序列的数据来源</b>：优先读 dispatch_event 里该单 ACCEPT_PASS / CANCEL 事件的时间；
 * 事件缺失时退回 dispatch_header 上真实存在的 verified_at / updated_at（不编 now）。
 * 退回条数在结果里如实报出，避免「图上最后一天的点」与「汇总的未完成数」对不上却没人知道为什么。
 */
@Service
public class DispatchBurndownService {

    /** 序列最多画多少天。超了明拒并提示收窄 from/to，不静默改起点（错误要刺眼，别伪装成正常）。 */
    public static final int MAX_SERIES_DAYS = 1000;

    @Autowired
    private DispatchService dispatchService;

    @Autowired
    private DispatchEventMapper eventMapper;

    /** 单子的生命周期跨度（纯数据，不含 DB）。finished=null 表示至今未完成。 */
    public record OrderSpan(String orderNo, LocalDate created, LocalDate finished, boolean cancelled) {}

    /** 图上的一个点：某天结束时的未完成单数。 */
    public record DayPoint(String date, long remaining) {}

    public record Result(String milestone, long total, long done, long remaining,
                         LocalDate from, LocalDate to, List<DayPoint> series,
                         int timeFallbackCount) {}

    /** 非法输入（缺里程碑 / 日期倒置 / 跨度过大）。Controller 转成 400。 */
    public static class InvalidInput extends IllegalArgumentException {
        public InvalidInput(String message) { super(message); }
    }

    public Result compute(String milestone, LocalDate fromReq, LocalDate toReq) {
        if (milestone == null || milestone.isBlank()) {
            throw new InvalidInput("缺少必填参数 milestone（不返回全库数据）");
        }
        String ms = milestone.trim();
        LocalDate today = LocalDate.now();

        List<DispatchHeader> headers = dispatchService.byMilestone(ms);
        Map<String, DispatchHeader> byNo = new HashMap<>();
        for (DispatchHeader h : headers) {
            if (h.getOrderNo() != null) byNo.put(h.getOrderNo(), h);
        }

        // 完成/取消事件时间：一单取最早的一条（同一状态真发生两次按首次记，重入不该把完成日往后推）
        Map<String, LocalDate> finishFromEvent = new HashMap<>();
        if (!byNo.isEmpty()) {
            List<DispatchEvent> evs = eventMapper.selectList(new QueryWrapper<DispatchEvent>()
                    .in("order_no", byNo.keySet())
                    .in("event_type", List.of(DispatchEvent.EV_ACCEPT_PASS, DispatchEvent.EV_CANCEL))
                    .orderByAsc("created_at").orderByAsc("id"));
            for (DispatchEvent e : evs) {
                if (e.getCreatedAt() != null) {
                    finishFromEvent.putIfAbsent(e.getOrderNo(), e.getCreatedAt().toLocalDate());
                }
            }
        }

        List<OrderSpan> spans = new ArrayList<>();
        int fallback = 0;
        for (DispatchHeader h : headers) {
            boolean cancelled = DispatchHeader.ST_CANCELLED.equals(h.getStatus());
            boolean done = DispatchHeader.ST_DONE.equals(h.getStatus());
            LocalDate created = h.getCreatedAt() == null ? today : h.getCreatedAt().toLocalDate();
            LocalDate finished = null;
            if (done || cancelled) {
                finished = finishFromEvent.get(h.getOrderNo());
                if (finished == null) {
                    // 事件缺失：退回 header 上真实存在的时刻，绝不 new LocalDate.now() 冒充事件时间
                    if (done && h.getVerifiedAt() != null) finished = h.getVerifiedAt().toLocalDate();
                    if (finished == null && h.getUpdatedAt() != null) finished = h.getUpdatedAt().toLocalDate();
                    fallback++;
                }
                if (finished == null) finished = today; // 最后兜底：最晚今天完成，保证序列末点与汇总自洽
            }
            spans.add(new OrderSpan(h.getOrderNo(), created, finished, cancelled));
        }

        LocalDate earliest = spans.stream().map(OrderSpan::created).min(LocalDate::compareTo).orElse(today);
        LocalDate from = fromReq != null ? fromReq : earliest;
        LocalDate to = toReq != null ? toReq : today;
        if (from.isAfter(to)) {
            throw new InvalidInput("from 不能晚于 to（收到 from=" + from + ", to=" + to + "）");
        }
        long days = ChronoUnit.DAYS.between(from, to) + 1;
        if (days > MAX_SERIES_DAYS) {
            throw new InvalidInput("时间跨度 " + days + " 天超过上限 " + MAX_SERIES_DAYS
                    + "，请收窄 from/to（不会静默改起点）");
        }

        long total = 0, doneCount = 0;
        for (DispatchHeader h : headers) {
            if (DispatchHeader.ST_CANCELLED.equals(h.getStatus())) continue; // 取消单从分母剔除
            total++;
            if (DispatchHeader.ST_DONE.equals(h.getStatus())) doneCount++;
        }
        return new Result(ms, total, doneCount, total - doneCount,
                from, to, series(spans, from, to), fallback);
    }

    /**
     * 按天算「截至当天结束时仍未完成的单数」——纯函数，不碰 DB，可单测。
     *
     * <p>当天建、当天完成：当天结束时算完成，不计入当天的未完成时点（finished == d 即剔除）。
     * 取消单整个从序列里剔除，与汇总口径一致（都不占分母）。
     */
    static List<DayPoint> series(List<OrderSpan> spans, LocalDate from, LocalDate to) {
        List<DayPoint> out = new ArrayList<>();
        for (LocalDate d = from; !d.isAfter(to); d = d.plusDays(1)) {
            long remaining = 0;
            for (OrderSpan s : spans) {
                if (s.cancelled()) continue;
                if (s.created().isAfter(d)) continue;                       // 还没建
                if (s.finished() != null && !s.finished().isAfter(d)) continue; // 当天或之前已完成
                remaining++;
            }
            out.add(new DayPoint(d.toString(), remaining));
        }
        return out;
    }
}
