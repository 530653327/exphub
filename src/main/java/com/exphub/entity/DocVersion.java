package com.exphub.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;
import java.time.LocalDateTime;

@Data
public class DocVersion {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long docId;
    private Integer version;
    private String content;
    private String aliases;
    private String summary;
    private String updatedBy;
    private String updatedName;
    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createdAt;
}