package com.exphub.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.exphub.entity.MailWakeOffer;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

/**
 * 唤醒去重账本（2026-11-14 阁主 W-20261114-12 第 5 条）。
 *
 * <p><b>两个写操作都是原子的、都靠唯一键判胜负</b>：
 * <ol>
 *   <li>{@link #tryInsert}：{@code INSERT IGNORE} —— 插进去说明是这个键第一次进唤醒源；</li>
 *   <li>{@link #touchIfExpired}：只有「最后一次投递已经超过窗口」才更新成功，
 *       用来放行「提供过、但回合崩了」的合法重试。</li>
 * </ol>
 * 两步都不需要先读后写，也就不存在「两个扫描同时插同一条」的竞态。
 */
@Mapper
public interface MailWakeOfferMapper extends BaseMapper<MailWakeOffer> {

    @Insert("INSERT IGNORE INTO mail_wake_offers "
            + "(dedupe_key, order_no, client_msg_id, thread_id, message_id, first_offered_at, last_offered_at, offer_count) "
            + "VALUES (#{key}, #{orderNo}, #{clientMsgId}, #{threadId}, #{messageId}, NOW(), NOW(), 1)")
    int tryInsert(@Param("key") String key, @Param("orderNo") String orderNo,
                  @Param("clientMsgId") String clientMsgId, @Param("threadId") String threadId,
                  @Param("messageId") Long messageId);

    /**
     * 过了去重窗口才放行一次重试。
     *
     * <p><b>为什么必须有窗口</b>：无窗口的「同一封信永久只唤醒一次」会把
     * 「已提供给唤醒方、但那轮回合崩了 / 被额度截断」的信变成<b>静默死信</b> ——
     * 信还在信箱里，但永远不会再叫醒任何人。窗口化之后：
     * 异常重扫（秒级/分钟级连环）依旧被挡住，崩掉的回合过一会儿能重试。
     */
    @Update("UPDATE mail_wake_offers SET last_offered_at = NOW(), offer_count = offer_count + 1 "
            + "WHERE dedupe_key = #{key} "
            + "AND last_offered_at <= DATE_SUB(NOW(), INTERVAL #{windowSeconds} SECOND)")
    int touchIfExpired(@Param("key") String key, @Param("windowSeconds") long windowSeconds);

    @Select("SELECT * FROM mail_wake_offers WHERE dedupe_key = #{key} LIMIT 1")
    MailWakeOffer selectByKey(@Param("key") String key);
}
