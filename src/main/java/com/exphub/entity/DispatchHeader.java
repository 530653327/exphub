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
     *
     * <p><b>2026-11-19 起人工单不再留空</b>：后台建单没有 Key 身份（后台登录是 users 表），
     * 所以 {@code DispatchService.create} 在 origin=HUMAN 且未显式传 Key 时，
     * 自动归属「派单共享 Key」（最近一张 AI 单用的那把）。留空的单 MCP 侧看不见，
     * 哨兵永远不会接 —— W-20261119-000001 就是这么挂在「待接单」的。
     * 只有库里一张 AI 单都没有、取不到共享 Key 时才会是空串，此时建单回执会明确警告。
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

    /**
     * 发版版本号。只有 {@code authRelease=1} 时有意义。
     *
     * <p>建单时没填就自动 +1（末尾数字加一，前面的原样保留）。
     * 基准见 {@code DispatchService.suggestVersionInfo}：项目当前版本与项目历史发版单里数值最大的那个。
     */
    private String version;

    /**
     * 这一版号是怎么来的（「项目当前版本 3.4.98 → 3.4.99」「项目历史发版单 W-… = 1.0.2 → 1.0.3」）。
     *
     * <p>必须在建单时落库，不能在审核页面重新算 —— 项目当前版本会变，重算出来的依据
     * 与当时建单的依据可能不是一回事，审核的人就没法判断这个号当初为什么这么给。
     */
    private String versionBasis;

    /**
     * 里程碑（2026-11-20 W-20261120-000026，越哥定的燃尽图口径）。
     *
     * <p>一单挂一个，可选；空 = 独立单，不归任何里程碑。老单不回填。
     * 燃尽图按这个字段精确匹配聚合，迁移脚本里加了索引。建单后不提供修改入口
     * （与授权位一致：同一件事的单归属不能中途换族）。
     */
    private String milestone;
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
