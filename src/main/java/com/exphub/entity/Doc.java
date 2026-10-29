package com.exphub.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;
import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
@TableName("docs")
public class Doc {
    @TableId(type = IdType.AUTO)
    private Long id;
    private String title;           // 标题
    private String category;         // 分类
    private String content;          // 内容（Markdown）
    private String aliases;          // 别名
    private String tags;             // 标签
    private String summary;          // 摘要
    private Integer version;         // 版本号
    private String authorId;         // 作者ID
    private String authorName;      // 作者名称
    private String apiKey;           // 创建时使用的API Key（同一Key下的助手共享经验）
    private String templateType;      // 使用的模板类型，如：problem_solution、knowledge_doc、todo_list
    private Integer callCount;       // 调用次数
    private Integer successCount;    // 成功次数
    private Integer failCount;      // 失败次数
    private BigDecimal rating;       // 评分
    private Integer ratingCount;     // 评分次数
    private String relatedIds;       // 关联经验ID，逗号分隔（互相引用的替代经验）
    private String status;           // ACTIVE/BROKEN/DEPRECATED
    // ===== 项目空间扩展字段（2026-09-20）=====
    private Long projectId;          // 所属项目ID，NULL=全局经验
    private String docRole;          // MAIN(索引)/CHAPTER(章节)/NORMAL(普通经验)
    private String chapterNo;        // 章节号，如 "1"、"14"
    private String chapterTitle;     // 章节标题，如 "项目概述"
    private String loadStrategy;     // ALWAYS(每次必读)/ON_DEMAND(按需)
    private Integer sortOrder;       // 排序
    private String visibility;       // PRIVATE(仅同Key可见)/PUBLIC(所有Key可见)
    // ===== M10 标记待删（2026-10-25）=====
    // 状态值用 status=PENDING_DELETE 承载，搜索默认只查 ACTIVE 因而天然排除
    private String deleteReason;     // 标记待删的原因（AI 填写）
    private String deleteMarkedBy;   // 标记待删的助手名
    private LocalDateTime deleteMarkedAt; // 标记时间
    // ===== set_flags 机器可读标记（2026-10-25）=====
    // 为什么不复用 tags：tags 参与 ngram 全文索引与搜索权重，塞机器标记会污染检索；
    // 且 tags 是人的分类，flags 是机器的状态。flags 变更不递增 version、不写版本快照。
    private String flags;            // needs_review/duplicate/superseded/stale/low_quality，逗号分隔
    private String flagNote;         // 标记说明
    private String flagMarkedBy;     // 打标记的助手名
    private LocalDateTime flagMarkedAt; // 打标记的时间
    // ===== D1 取代链（2026-10-25）=====
    // 只存「被谁取代」这一个方向：反向关系（本条取代了谁）用 WHERE superseded_by=id 反查。
    // 存两列必然出现「A 说被 B 取代、B 的列表里没有 A」这种双写漂移。
    // 与 flags 的关系：flags=superseded 表示「疑似被取代但不知道是谁」，
    // superseded_by 一旦有值就是确定关系，展示时以它为准。
    private Long supersededBy;       // 取代本条的文档 ID，NULL=未被取代
    // ===== 取代关系留痕（2026-10-26）=====
    // 为什么补这两列：阁主实测发现 clear_superseded 回执有「操作人」，
    // mark_superseded 一个字都没有。根源不是忆阁（那两个工具都是直接透传），
    // 而是 DocService.markSuperseded 把 by 只写了日志、既没落库也没进回执。
    // 三兄弟留痕形态必须一致：
    //   set_flags        → flag_marked_by / flag_marked_at
    //   mark_for_deletion → delete_marked_by / delete_marked_at
    //   mark_superseded  → superseded_marked_by / superseded_marked_at  ← 本次补
    private String supersededMarkedBy;   // 标记取代关系的操作人
    private LocalDateTime supersededMarkedAt; // 标记取代关系的时间
    // ===== 时效（2026-10-29）=====
    // updated_at 回答「什么时候被改过」，last_verified_at 回答「结论最后一次被确认仍然成立」。
    // 两者必须分开：改错字、修摘要都会刷新 updated_at，但它们不构成「重新验证」。
    // 搜索回执的「超期 N 条」只认这一列 —— 没有它就只能拿 updated_at 凑，等于拿「谁最近动过」
    // 冒充「谁最近确认过」。
    private LocalDateTime lastVerifiedAt;
    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createdAt;
    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updatedAt;
}