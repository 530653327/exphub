package com.exphub.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;
import java.time.LocalDateTime;

@Data
@TableName("doc_versions")
public class DocVersion {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long docId;
    private Integer version;
    /** 历史版本正文；null = 本次仅元数据变更（A3），正文未变故未存副本 */
    private String content;
    /** CONTENT=正文变更（有全文）/ META=仅元数据变更（content 为 null） */
    private String changeType;
    private String aliases;
    private String summary;
    private String updatedBy;
    private String updatedName;
    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createdAt;
}