package com.exphub.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;
import java.time.LocalDateTime;

/**
 * 项目实体：exphub 项目空间
 */
@Data
@TableName("projects")
public class Project {
    @TableId(type = IdType.AUTO)
    private Long id;
    private String projectCode;      // 项目编码，唯一
    private String name;             // 项目名称
    private String aliases;          // 别名，逗号分隔（用于模糊搜索）
    private String description;      // 项目描述
    private String apiKey;           // 归属 API Key，用于数据隔离
    private String status;           // ACTIVE / ARCHIVED
    private String defaultTestCmd;   // 默认测试命令，工作流模板用
    private String defaultConstraints; // 默认约束，工作流模板用
    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createdAt;
    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updatedAt;
}
