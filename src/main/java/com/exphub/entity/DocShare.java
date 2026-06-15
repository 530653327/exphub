package com.exphub.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;
import java.time.LocalDateTime;

@Data
@TableName("doc_shares")
public class DocShare {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long docId;
    private String token;
    private LocalDateTime expireAt;
    private Long createdBy;
    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createdAt;
}
