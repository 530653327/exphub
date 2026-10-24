package com.exphub.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;
import java.time.LocalDateTime;

@Data
@TableName("call_logs")
public class CallLog {
    @TableId(type = IdType.AUTO)
    private Long id;
    
    private String apiKey;           // AppKey
    private String callerName;       // 调用者名字（来自助手）
    private String action;           // 操作类型：SEARCH/CREATE/UPDATE/DELETE/HELPFUL/TOOL
    private String toolName;         // MCP 工具名（action=TOOL 时记录，用于工具维度统计）
    private Integer costMs;          // 工具耗时毫秒（action=TOOL 时记录）
    private Integer success;         // 工具调用成败 1成功 0失败（action=TOOL 时记录）
    private String keyword;          // 检索关键字（SEARCH 时用）
    private Integer hitCount;        // 命中数据条数（SEARCH 时用）
    private Long docId;             // 文档ID（CREATE/UPDATE/DELETE 时用）
    private String docTitle;         // 文档标题（CREATE/UPDATE 时用）
    private String detail;           // 详细信息（如更新内容摘要）
    
    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createdAt; // 调用时间
}