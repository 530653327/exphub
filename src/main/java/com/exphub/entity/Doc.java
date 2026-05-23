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
    private String status;           // ACTIVE/BROKEN/DEPRECATED
    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createdAt;
    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updatedAt;
}