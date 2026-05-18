package com.exphub.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;
import java.time.LocalDateTime;

@Data
@TableName("call_logs")
public class CallLog {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long docId;
    private String docTitle;
    private String assistantId;
    private String assistantName;
    private Boolean success;
    private String errorMsg;
    private Integer executionTime;
    private String feedback;         // 问题反馈
    private String fixedSolution;    // 修复方案
    private String fixedBy;          // 提供修复者ID
    private String fixedByName;      // 提供修复者名称
    private Integer rating;         // 评分
    private String ipAddress;
    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createdAt;
}