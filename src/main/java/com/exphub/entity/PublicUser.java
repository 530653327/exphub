package com.exphub.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;
import java.time.LocalDateTime;

@Data
@TableName("public_users")
public class PublicUser {
    @TableId(type = IdType.AUTO)
    private Long id;
    private String email;
    private String password;
    private String apiKey;
    private String portalToken;
    private String displayName;
    private Boolean enabled;
    private LocalDateTime lastLoginAt;
    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createdAt;
    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updatedAt;
}
