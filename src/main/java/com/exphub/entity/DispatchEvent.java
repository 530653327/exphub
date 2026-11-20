package com.exphub.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 派单操作流水：一动作一行，**永不 UPDATE、永不 DELETE**（2026-11-20 W-20261120-000025）。
 *
 * <p><b>为什么要单独一张表</b>：dispatch_header 只存当前状态，dispatch_item 存的是每轮唤醒记录。
 * 谁在什么时候把状态从 A 推到 B 没有独立记录，驳回一次、来回两轮，前面的痕迹直接被覆盖，
 * 事后拼不出来。本表把「角色名 / 单号 / 状态」分成独立字段，供忆阁右栏按字段上色显示成控制台日志。
 *
 * <p><b>只追加</b>：代码里只允许 insert，不许 update / delete。取消整单也不删事件，只加一条 CANCEL。
 * 幂等只用于历史回填（order_no + event_type + created_at），运行期动作不做幂等去重 ——
 * 同一个动作真发生两次就该留两条，去重等于篡改审计。
 *
 * <p>设计基线见 ExpHub #3308 / #3323，建表见 sql/migrations/migration_20261120a_dispatch_event.sql。
 */
@Data
@TableName("dispatch_event")
public class DispatchEvent {

    // ===== event_type（11 个，与状态转移矩阵一一对应）=====
    public static final String EV_CREATE = "CREATE";
    public static final String EV_REVIEW_PASS = "REVIEW_PASS";
    public static final String EV_REVIEW_REJECT = "REVIEW_REJECT";
    public static final String EV_CLAIM = "CLAIM";
    public static final String EV_NEED_CONFIRM = "NEED_CONFIRM";
    public static final String EV_REPLY = "REPLY";
    public static final String EV_SUBMIT_ACCEPT = "SUBMIT_ACCEPT";
    public static final String EV_ACCEPT_PASS = "ACCEPT_PASS";
    public static final String EV_ACCEPT_REJECT = "ACCEPT_REJECT";
    public static final String EV_CANCEL = "CANCEL";
    public static final String EV_TERMINATE = "TERMINATE";

    // ===== actor_type：前端按这个区分人和助手 =====
    public static final String ACTOR_HUMAN = "HUMAN";
    public static final String ACTOR_AI = "AI";

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 单号 W-YYYYMMDD-NNNNNN（前端按单号精确筛 + 上色） */
    private String orderNo;

    /** 11 个 event_type 之一（前端按类型上色） */
    private String eventType;

    /** 转移前状态；建单时为空 */
    private String fromStatus;

    /** 转移后状态（前端按状态上色） */
    private String toStatus;

    /** 操作者标识：越哥 / 阁主 / 穷奇 / 白泽 / 应龙 …（前端按角色上色） */
    private String actor;

    /** HUMAN / AI */
    private String actorType;

    /** 可选：驳回理由 / 终止原因 / 结论摘要 */
    private String note;

    /**
     * 事件发生时间。
     *
     * <p>不加 {@code fill = INSERT}：回填必须能写进历史时间，自动填充会把回填的时间改成「现在」，
     * 于是 50 张旧单全挤在同一秒、流水顺序全错。所有写入点都显式 set。
     */
    private LocalDateTime createdAt;
}
