package com.exphub.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 信箱线程封存状态（2026-11-14 阁主 W-20261114-12）。
 *
 * <p><b>封存解决的是什么</b>：委托单进入待验收后，它散落的多根线程不该再唤醒任何人 ——
 * 但「信照常入库、收件人能在信箱里看到」这条不能破。所以封存只做一件事：
 * 把这些线程挡在<b>唤醒源</b>（{@code mail_start(unreadOnly=true)}）之外，
 * 默认信箱列表 / includeRead 分页 / mail_thread 展开照常可见，并在行首标 🔒。
 *
 * <p><b>为什么不删行</b>：{@code state='OPEN'} 的行本身就是审计证据 ——
 * 「这根线程曾经被封存过、谁在什么时候解的」必须能回看，删了就只能去翻日志。
 *
 * <p>留痕主体在 {@link MailThreadStateLog}（只追加）；本表只存当前状态。
 */
@Data
@TableName("mail_thread_states")
public class MailThreadState {

    /** 线程号；与 mail_messages.thread_id 对齐，不是自增。 */
    @TableId(type = IdType.INPUT)
    private String threadId;

    /** 所属委托单号（W-YYYYMMDD-NN）。历史线程显式封存时可能为 NULL —— 那就是「未标注」。 */
    private String orderNo;

    /** SEALED=已封存（不进唤醒源）/ OPEN=已解锁。 */
    private String state;

    /**
     * 封存水位（消息 id）：**id 大于它的消息不再唤醒，id 小于等于它的仍唤醒一次**。
     *
     * <p>为什么需要它：触发封存的那封往往就是「delivered 回执」—— 它必须唤醒收件人一次
     * （阁主要据此推进验收，忆阁的回执桥也挂在这一次唤醒里）。一刀切「整根线程都不唤醒」
     * 会把回执本身吞掉，单就永远停在 running。
     */
    private Long sealFromId;

    private LocalDateTime sealedAt;

    /** 谁封的：角色名 / {@code auto:receipt-delivered} / 后台账号。 */
    private String sealedBy;

    private String sealReason;

    private LocalDateTime unsealedAt;

    private String unsealedBy;

    private String unsealNote;

    private LocalDateTime updatedAt;
}
