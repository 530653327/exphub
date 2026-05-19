package com.exphub.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;
import java.time.LocalDateTime;

@Data
@TableName("ai_assistants")
public class AiAssistant {
    @TableId(type = IdType.AUTO)
    private Long id;
    private String assistantId;      // 唯一标识
    private String assistantName;   // 显示名称
    private String description;      // 描述
    private String apiKey;          // API Key
    private String apiKeySecret;    // API Key 签名密钥
    private Boolean enabled;         // 是否启用
    private Boolean canCreate;       // 可创建经验
    private Boolean canUpdate;       // 可编辑经验
    private Boolean canSearch;       // 可查询经验（默认开启）
    private Integer totalCalls;     // 总调用次数
    private Integer successCalls;   // 成功调用次数
    private Integer failCalls;      // 失败调用次数
    private LocalDateTime lastCallAt; // 最后调用时间
    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createdAt;
    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updatedAt;
}