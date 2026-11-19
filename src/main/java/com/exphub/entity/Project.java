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
    /**
     * 项目当前线上版本（如忆阁 3.4.98）。派单勾「发版」但没填版本号时，
     * 取号的第一步基准就是它 —— 见 {@code DispatchService.suggestVersionInfo}。
     *
     * <p>两条写入路径：① 后台项目页手动维护；② 带版本的发版单验收通过时自动回写（只升不降）。
     */
    private String currentVersion;
    private LocalDateTime currentVersionUpdatedAt;
    /** 当前版本从哪来：后台手填 / 哪张派单验收回写。 */
    private String currentVersionSource;
    private String defaultTestCmd;   // 默认测试命令，工作流模板用
    private String defaultConstraints; // 默认约束，工作流模板用
    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createdAt;
    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updatedAt;
}
