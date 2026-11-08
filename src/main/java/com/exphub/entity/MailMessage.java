package com.exphub.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 协作信箱消息（只追加、不回改）。
 *
 * <p>设计定稿见 ExpHub #1324（阁主↔烛龙协作信箱⑥ C114–C121）。三条边界：
 * <ul>
 *   <li><b>只追加</b>：发出即不可编辑，纠错用新消息引用原信 —— 这是它跟 docs 的根本区别，
 *       也是「不再产生全文快照、不再撞 3 万字上限」的原因。</li>
 *   <li><b>不进 docs 索引</b>：独立表，天然不参与经验搜索排名。</li>
 *   <li><b>委托信任，不是鉴权</b>：{@code configSourceId} 只证明「这封信出自哪份客户端配置」，
 *       <b>不证明「谁写的」</b>。谁都能配成烛龙，服务端查不出来，别拿它当安全边界。</li>
 * </ul>
 */
@Data
@TableName("mail_messages")
public class MailMessage {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 线程号，服务端生成；不接受模型传入（少一个参数少一处「回信开新线程」）。 */
    private String threadId;

    /** 父消息 ID；有值即回信，线程从父消息推导。 */
    private Long inReplyTo;

    /** 发件角色，服务端按当前配置来源填，不接受模型传入。 */
    private String fromRole;

    private String toRole;

    /** dispatch 派单 / receipt 回执 / accept 验收 / note 通知 / fix 更正。 */
    private String msgType;

    private String subject;

    private String body;

    /** 幂等键：工具超时重试不会发两封。 */
    private String clientMsgId;

    /** 认领人；原子更新抢锁，先到先得。 */
    private String claimedBy;

    private LocalDateTime claimedAt;

    /** 处理过的时间。只做展示，不参与去重（去重只认游标）。 */
    private LocalDateTime processedAt;

    /** 配置来源标识：证明出自哪份配置，不证明是谁写的。 */
    private String configSourceId;

    /**
     * 授权链根标识（可选元数据，2026-11-09 阁主 T261109035247-364）。
     *
     * <p>{@code null} = 不属于任何授权链 —— 老数据与「不传此参数」的新调用都是 null，
     * 所以加这一列不改变任何既有调用的行为。
     */
    private String chainId;

    /**
     * 是否自动回合发出。
     *
     * <p><b>为什么列名不叫 auto</b>：{@code AUTO} 在 SQL 里是 {@code AUTO_INCREMENT} 的前缀关键字，
     * 直接拿它当列名在不同方言/工具里表现不一致；{@code auto_flag} 没有歧义。
     * 老数据与不传时都是 0（人工）。
     */
    @TableField("auto_flag")
    private Boolean autoFlag;

    /**
     * 授权链当前跳数，0 起（0 = 链根 / 无链）。
     *
     * <p>老数据与不传时都是 0。服务端只存不推：跳数是调用方（忆阁代理人循环）报上来的，
     * 服务端无法自行推导。
     */
    private Integer chainDepth;

    private LocalDateTime createdAt;
}
