package com.exphub.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 唤醒去重账本（2026-11-14 阁主 W-20261114-12 第 5 条）。
 *
 * <p>键 = 委托单号 + clientMsgId。判的是「<b>这封信在本单里是否已经进过唤醒源</b>」：
 * 异常重扫会让同一封信在几十秒内被反复算成「新信」，账本在窗口内把它挡住，
 * 过了窗口仍允许重试 —— 无窗口的「永久只唤醒一次」会把「提供过但回合崩了」的信变成静默死信。
 *
 * <p>为什么键里带单号：{@code client_msg_id} 本身有全局唯一键（{@code uk_mail_client}），
 * 带上单号是让「同一单里的同一封信」在库里可读、可按单审计（排查「这单为什么没被叫醒」时要能一路查）。
 */
@Data
@TableName("mail_wake_offers")
public class MailWakeOffer {

    /** order_no + '|' + client_msg_id。 */
    @TableId(type = IdType.INPUT)
    private String dedupeKey;

    private String orderNo;

    private String clientMsgId;

    private String threadId;

    private Long messageId;

    private LocalDateTime firstOfferedAt;

    private LocalDateTime lastOfferedAt;

    /** 累计进入唤醒源次数；>1 表示过了去重窗口后又重试过。 */
    private Integer offerCount;
}
