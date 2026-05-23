package com.exphub.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;
import java.time.LocalDateTime;

@Data
@TableName("doc_templates")
public class DocTemplate {
    @TableId(type = IdType.AUTO)
    private Long id;
    private String type;            // 模板类型标识，如：problem_solution、knowledge_doc、todo_list
    private String name;           // 模板名称
    private String platformField; // 环境字段名，如"操作系统"
    private String description;    // 模板描述（适用场景说明）
    private String instruction;   // AI 填写指南
    private String templateContent; // 模板结构
    private Boolean isDefault;    // 是否默认模板
    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createdAt;
    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updatedAt;
}
