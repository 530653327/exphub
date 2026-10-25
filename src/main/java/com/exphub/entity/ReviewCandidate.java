package com.exphub.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 记忆整理候选对（D1 施工包 3）。
 *
 * <p>为什么要有这张表：L0/L1/L2 跑出的「这两条可能是一回事」如果只出现在某次
 * 工具返回的文本里，下一轮就丢了，人也没机会判断。候选必须落库、可排队、可回溯。
 *
 * <p>红线：本表只记录「待判断」，**任何状态都不触发自动合并或删除**。
 * 判定为重复只是打标记，判定为取代只是写一条指针，旧条原文永远保留。
 */
@Data
@TableName("review_candidates")
public class ReviewCandidate {

    /** 状态：待判断 */
    public static final String PENDING = "PENDING";
    /** 状态：确为重复（已给两条打 duplicate 标记，未合并） */
    public static final String DUPLICATE = "DUPLICATE";
    /** 状态：已标取代（较旧那条的 superseded_by 指向较新那条） */
    public static final String SUPERSEDED = "SUPERSEDED";
    /** 状态：判为不同（以后不再重复入队打扰） */
    public static final String DISTINCT = "DISTINCT";
    /** 状态：本次忽略（以后可能再提） */
    public static final String SKIPPED = "SKIPPED";

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long docA;              // 候选对主体（通常较旧）
    private Long docB;              // 候选对对照（通常较新）
    private BigDecimal score;       // 相似度得分 0~1，只用于排序，不是判决
    private String reasons;         // 判为相似的理由（可解释，便于人快速判断）
    private String source;          // L0_CREATE / L1_DAILY / L2_WEEKLY / MANUAL
    private String status;          // 见上方常量
    private String note;            // 判断说明
    private String resolvedBy;      // 处理人（助手代号）
    private LocalDateTime createdAt;
    private LocalDateTime resolvedAt;
}
