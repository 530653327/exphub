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
    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createdAt;
    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updatedAt;
}