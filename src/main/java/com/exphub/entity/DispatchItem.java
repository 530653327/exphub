package com.exphub.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 派单执行记录：一次模型唤醒一行。
 *
 * <p><b>创建时机</b>（2026-11-16 越哥拍板、烛龙建议）：**一开工就建**（status=RUNNING），
 * 干完再更新它（DONE + finishedAt + 结论），这时才算把球传出去。
 * 若改成「干完才建」，RUNNING 与 ERROR 两个状态永远用不上 —— 进程被 kill 也不留痕迹，
 * 等于回到旧信箱「不知道它到底跑没跑」的老路。
 *
 * <p><b>为什么不存「接收方」</b>：全系统只有两方（阁主 ↔ 某个哨兵）+ 一个人类（越哥），
 * 接收方由「创建人是否等于 header.assignee」推算：
 * <pre>
 *   接收方 = (item.创建人 == header.指派人) ? header.创建人 : header.指派人
 * </pre>
 */
@Data
@TableName("dispatch_item")
public class DispatchItem {

    // ===== 指令常量 =====
    /** 执行：可读可写可执行，范围受 header 授权位约束 */
    public static final String CMD_EXEC = "EXEC";
    /** 确认：只能读，不能写、不能执行 */
    public static final String CMD_CONFIRM = "CONFIRM";
    /** 终止：不唤醒大模型，代码层直接拦住；header 同时进 CONFIRMING */
    public static final String CMD_TERMINATE = "TERMINATE";

    // ===== 状态常量 =====
    public static final String ST_RUNNING = "RUNNING";
    public static final String ST_DONE = "DONE";
    public static final String ST_ERROR = "ERROR";

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long headerId;
    private String orderNo;
    /** order_no + 两位序号，如 W-20261116-000001-03 */
    private String itemNo;
    /** 第几轮，从 1 开始 */
    private Integer seq;
    /** 越哥 / 阁主 / 哨兵 */
    private String creator;
    /** 开工时间 */
    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createdAt;
    private LocalDateTime finishedAt;
    /** EXEC / CONFIRM / TERMINATE */
    private String command;
    /** RUNNING / DONE / ERROR */
    private String status;
    /** true=需要回复 */
    private Boolean replyRequired;
    /** 结论：给对方看的那一句结果 */
    private String conclusion;
    /** 详细内容 */
    private String content;
    /** 哪个工位/机器跑的，排查用 */
    private String clientId;
}
