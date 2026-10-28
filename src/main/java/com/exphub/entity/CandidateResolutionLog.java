package com.exphub.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 候选判定的**审计流水**（2026-10-29 阁主验收后新建）。
 *
 * <p>为什么单独建表：原来把「撤回历史」拼进 {@code review_candidates.note}，
 * 一次往返多 184 字左右，2000 字只够 8 次，第 9 次又会被截断。
 * 根因不是列宽不够，是**拿一个标量字段装一条无限增长的审计流**。
 *
 * <p>现在职责分开：
 * <ul>
 *   <li>{@code review_candidates.note} = <b>当前</b>判定的理由，一判定一覆盖，长度天然有界</li>
 *   <li>本表 = <b>每一次</b>动作的流水，只追加不修改，{@code note} 用 TEXT 不限长</li>
 * </ul>
 *
 * <p>红线：本表只记录「谁在什么时候做了什么、说了什么」，不存正文快照、不触发任何合并删除。
 */
@Data
@TableName("candidate_resolution_log")
public class CandidateResolutionLog {

    /** 动作：判为重复 */
    public static final String ACT_DUPLICATE = "duplicate";
    /** 动作：标取代 */
    public static final String ACT_SUPERSEDED = "superseded";
    /** 动作：判为不同 */
    public static final String ACT_DISTINCT = "distinct";
    /** 动作：本次忽略 */
    public static final String ACT_SKIP = "skip";
    /** 动作：撤回为待判断（reopen） */
    public static final String ACT_REOPEN = "reopen";

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long candidateId;       // 对应 review_candidates.id
    private String action;          // duplicate / superseded / distinct / skip / reopen
    private String prevStatus;      // 动作前的状态（reopen 才有意义，其余存 status 以便还原）
    private String newStatus;       // 动作后的状态
    private String operator;        // 谁做的
    private String note;            // 本次说明（TEXT，不截断）
    private LocalDateTime createdAt;
}
