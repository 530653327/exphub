package com.exphub.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.exphub.entity.DispatchEvent;
import com.exphub.entity.DispatchHeader;
import com.exphub.mapper.DispatchEventMapper;
import com.exphub.mapper.DispatchHeaderMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.function.Function;

/**
 * 历史事件回填（2026-11-20 W-20261120-000025）。
 *
 * <p>现有单子没有事件流水。回填**只做最低限度**：每张单按 header 已有时间戳补一条 CREATE
 * （created_at = 建单时间），再按当前 status 补一条到当前状态的事件（取得到时间才补）。
 *
 * <p><b>三条硬约束</b>：
 * <ol>
 *   <li><b>不编时间</b>：取不到 reviewed_at / accepted_at / verified_at / updated_at 的那条就不补，
 *       绝不用 now() 顶 —— 否则 50 张旧单全挤在同一秒，流水顺序整个错乱。</li>
 *   <li><b>幂等</b>：按 order_no + event_type + created_at 判存，重复跑第二遍不新增行
 *       （DDL 里还有同键唯一索引兜底）。</li>
 *   <li><b>默认不自动跑</b>：实现 {@link ApplicationRunner} 但由开关控制，默认 false —— 建表 + 写库
 *       是数据变更，必须按发版单流程显式打开，绝不因为「应用启动」就悄悄改历史数据。</li>
 * </ol>
 */
@Component
public class DispatchEventBackfillService implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(DispatchEventBackfillService.class);

    @Autowired
    private DispatchHeaderMapper headerMapper;

    @Autowired
    private DispatchEventMapper eventMapper;

    /** 默认关闭。要回填时显式置 true 启动一次，跑完改回 false。 */
    @Value("${exphub.dispatch.event-backfill-on-start:false}")
    private boolean backfillOnStart;

    @Override
    public void run(ApplicationArguments args) {
        if (!backfillOnStart) return;
        try {
            BackfillResult r = backfill();
            log.info("派单事件回填完成：单 {} 张，补写 {} 条，跳过 {} 条", r.orders(), r.created(), r.skipped());
        } catch (Exception e) {
            // 回填是维护动作，失败不能阻止应用启动；但要打 ERROR 让人发现（不同于副作用静默降级）。
            log.error("派单事件回填失败，应用继续启动：{}", e.getMessage(), e);
        }
    }

    public record BackfillResult(int orders, int created, int skipped) {}

    /**
     * 回填（可重复执行）。抽成 public 供运维/单测显式调用，不依赖 ApplicationRunner 开关。
     */
    @Transactional
    public BackfillResult backfill() {
        List<DispatchHeader> all = headerMapper.selectList(new QueryWrapper<DispatchHeader>());
        int created = 0;
        int skipped = 0;
        if (all == null) return new BackfillResult(0, 0, 0);
        for (DispatchHeader h : all) {
            // ① CREATE：to=REVIEWING（建单一律先落待审核，见 DispatchService.create），时间=建单时间
            if (h.getCreatedAt() != null) {
                if (insertIfAbsent(h.getOrderNo(), DispatchEvent.EV_CREATE,
                        null, DispatchHeader.ST_REVIEWING,
                        h.getCreator(), actorTypeOfOrigin(h.getOrigin()), "历史回填", h.getCreatedAt())) {
                    created++;
                } else {
                    skipped++;
                }
            } else {
                skipped++;
            }

            // ② 到当前状态的一条。status=REVIEWING 时 CREATE 已到 REVIEWING，不重复补；DRAFT 无对应动作。
            EventSeed seed = seedForStatus(h.getStatus());
            if (seed == null) continue;
            LocalDateTime at = seed.time().apply(h);
            if (at == null) {
                skipped++;   // 取不到时间就不补，不编
                continue;
            }
            if (insertIfAbsent(h.getOrderNo(), seed.eventType(), seed.fromStatus(), h.getStatus(),
                    seed.actor().apply(h), seed.actorType(), "历史回填", at)) {
                created++;
            } else {
                skipped++;
            }
        }
        return new BackfillResult(all.size(), created, skipped);
    }

    /** 当前 status → 补哪条事件、从哪个状态来、操作者是谁、时间取哪个字段。纯映射，可单测。 */
    record EventSeed(String eventType, String fromStatus, String actorType,
                     Function<DispatchHeader, String> actor,
                     Function<DispatchHeader, LocalDateTime> time) {}

    static EventSeed seedForStatus(String status) {
        if (status == null) return null;
        Function<DispatchHeader, String> human = h -> DispatchService.HUMAN_OPERATOR;
        Function<DispatchHeader, String> assignee = h -> h.getAccepter() != null && !h.getAccepter().isBlank()
                ? h.getAccepter() : h.getAssignee();
        switch (status) {
            case DispatchHeader.ST_REVIEWING:
                return null; // CREATE 已 to=REVIEWING
            case DispatchHeader.ST_READY:
                return new EventSeed(DispatchEvent.EV_REVIEW_PASS, DispatchHeader.ST_REVIEWING,
                        DispatchEvent.ACTOR_HUMAN, human, h -> first(h.getReviewedAt(), h.getUpdatedAt()));
            case DispatchHeader.ST_RUNNING:
                // RUNNING 可能来自 CLAIM / REPLY / 验收驳回，最低限度按认领补，时间取 accepted_at → updated_at
                return new EventSeed(DispatchEvent.EV_CLAIM, DispatchHeader.ST_READY,
                        DispatchEvent.ACTOR_AI, assignee, h -> first(h.getAcceptedAt(), h.getUpdatedAt()));
            case DispatchHeader.ST_CONFIRMING:
                return new EventSeed(DispatchEvent.EV_NEED_CONFIRM, DispatchHeader.ST_RUNNING,
                        DispatchEvent.ACTOR_AI, assignee, h -> h.getUpdatedAt());
            case DispatchHeader.ST_VERIFYING:
                return new EventSeed(DispatchEvent.EV_SUBMIT_ACCEPT, DispatchHeader.ST_RUNNING,
                        DispatchEvent.ACTOR_AI, assignee, h -> h.getUpdatedAt());
            case DispatchHeader.ST_DONE:
                return new EventSeed(DispatchEvent.EV_ACCEPT_PASS, DispatchHeader.ST_VERIFYING,
                        DispatchEvent.ACTOR_HUMAN, human, h -> first(h.getVerifiedAt(), h.getUpdatedAt()));
            case DispatchHeader.ST_CANCELLED:
                return new EventSeed(DispatchEvent.EV_CANCEL, null,
                        DispatchEvent.ACTOR_HUMAN, human, h -> h.getUpdatedAt());
            default:
                return null;
        }
    }

    private static LocalDateTime first(LocalDateTime a, LocalDateTime b) {
        return a != null ? a : b;
    }

    private static String actorTypeOfOrigin(String origin) {
        return DispatchHeader.ORIGIN_HUMAN.equalsIgnoreCase(origin)
                ? DispatchEvent.ACTOR_HUMAN : DispatchEvent.ACTOR_AI;
    }

    /**
     * 按 order_no + event_type + created_at 判存后插入。DDL 同键唯一索引兜并发。
     *
     * @return true=本次新写入；false=已存在（重复回填）
     */
    private boolean insertIfAbsent(String orderNo, String eventType, String fromStatus, String toStatus,
                                   String actor, String actorType, String note, LocalDateTime at) {
        Long exists = eventMapper.selectCount(new QueryWrapper<DispatchEvent>()
                .eq("order_no", orderNo)
                .eq("event_type", eventType)
                .eq("created_at", at));
        if (exists != null && exists > 0) return false;
        DispatchEvent e = new DispatchEvent();
        e.setOrderNo(orderNo);
        e.setEventType(eventType);
        e.setFromStatus(fromStatus);
        e.setToStatus(toStatus);
        e.setActor(actor);
        e.setActorType(actorType);
        e.setNote(note);
        e.setCreatedAt(at);
        eventMapper.insert(e);
        return true;
    }
}
