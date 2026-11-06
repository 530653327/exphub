package com.exphub.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 信箱阅读游标：按「角色 + 线程」记读到哪一条。
 *
 * <p>推信<b>只认它</b>：{@code ack} 只记「处理过」，不推进游标、不参与去重。
 * 原因是 AI 会不会调 ack 我们控制不了，把去重挂在模型不自觉的行为上一定出事（#1324 C113）。
 */
@Data
@TableName("mail_cursors")
public class MailCursor {

    private String role;

    private String threadId;

    private Long lastReadId;

    private LocalDateTime updatedAt;
}
