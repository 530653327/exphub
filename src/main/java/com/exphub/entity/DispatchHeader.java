package com.exphub.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 派单表头：一张单一行，状态与授权位都挂在它上面。
 *
 * <p><b>为什么授权位是 11 个独立字段而不是 JSON</b>（2026-11-16 越哥定的）：
 * 审核时人要在后台一项一项看得见、勾得动。塞成 JSON 等于看不见，而看不见就等于没审。
 *
 * <p><b>授权位建单后锁死</b>：任何 UPDATE 都不许碰 auth_* 列。没勾 push 就先做完这单、
 * 再开一单去 push —— 这是越哥明确的选择，不要「优化」成可改。
 *
 * <p>设计基线见 ExpHub #3308，表结构见 sql/migrations/migration_20261116a_dispatch_tables.sql。
 */
@Data
@TableName("dispatch_header")
public class DispatchHeader {

    // ===== 状态常量（库里存英文码，回执显中文）=====
    public static final String ST_DRAFT = "DRAFT";
    public static final String ST_REVIEWING = "REVIEWING";
    public static final String ST_READY = "READY";
    public static final String ST_RUNNING = "RUNNING";
    public static final String ST_CONFIRMING = "CONFIRMING";
    public static final String ST_VERIFYING = "VERIFYING";
    public static final String ST_DONE = "DONE";
    public static final String ST_CANCELLED = "CANCELLED";

    // ===== 来源常量 =====
    public static final String ORIGIN_HUMAN = "HUMAN";
    public static final String ORIGIN_AI = "AI";

    @TableId(type = IdType.AUTO)
    private Long id;

    /**
     * 归属 API Key —— 数据隔离的唯一依据。
     *
     * <p>同一 Key 下的助手共享派单，不同 Key 互相看不见（与 docs / projects 惯例一致）。
     * 空串 = 后台人工建的单，MCP 侧看不到（人下的单由人在后台管）。
     *
     * <p>2026-11-16 补：第一版漏了这个字段，等于任何持有有效 Key 的调用方都能看到全库派单。
     */
    private String apiKey;

    /** 单号 W-YYYYMMDD-NNNNNN，序号留 6 位 */
    private String orderNo;
    /** HUMAN / AI —— 决定「待确认」时球给谁 */
    private String origin;
    private String creator;
    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createdAt;
    private String status;
    private String title;
    private String description;
    /** 指派的哨兵（角色正名），建单后不改 */
    private String assignee;
    /** 接单人，认领时回填 */
    private String accepter;
    private LocalDateTime acceptedAt;
    /** 审核人（系统里只有越哥一个人） */
    private String reviewer;
    private LocalDateTime reviewedAt;
    /** 验收人 */
    private String verifier;
    private LocalDateTime verifiedAt;
    /** 验收结论；未通过时必填驳回理由 */
    private String verifyNote;
    private String remark;
    private String cancelReason;
    /** 1=高 3=中 5=低 */
    private Integer priority;
    private Long projectId;
    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updatedAt;

    // ===== 授权位 11 个（建单后锁死，见类注释）=====
    private Boolean authModifyCode;
    private Boolean authCommit;
    private Boolean authPush;
    private Boolean authRelease;
    private Boolean authDataWrite;
    private Boolean authMigration;
    private Boolean authCredential;
    private Boolean authProdConfig;
    private Boolean authSecurityGroup;
    private Boolean authFirewall;
    private Boolean authSpend;
}
