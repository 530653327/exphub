package com.exphub.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 信箱线程封存/解锁留痕（只追加，2026-11-14 阁主 W-20261114-12）。
 *
 * <p>为什么状态表之外还要一张流水表：状态表只有「当前值」，而
 * <b>谁在什么时候解过锁</b>必须能按时间回看。W-06 的事故里，
 * 事后分不清「哪封信是重扫唤醒的」「哪封是人主动发的」—— 那张流水就是为这件事留的。
 *
 * <p>{@code action} 只认两个值：{@code SEAL} / {@code UNSEAL}。
 * 一行一旦写入不回改（与 mail_messages 同口径）。
 */
@Data
@TableName("mail_thread_state_log")
public class MailThreadStateLog {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String threadId;

    /** 所属委托单号；历史线程显式封存时可能为 NULL。 */
    private String orderNo;

    /** SEAL / UNSEAL。 */
    private String action;

    /** 操作者：角色名 / auto:receipt-delivered / 后台账号。 */
    private String actor;

    private String note;

    private LocalDateTime createdAt;
}
