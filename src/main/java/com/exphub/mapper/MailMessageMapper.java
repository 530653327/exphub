package com.exphub.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.exphub.entity.MailMessage;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;

@Mapper
public interface MailMessageMapper extends BaseMapper<MailMessage> {

    /** 后台筛选下拉用：出现过的角色代号（收发两侧合并去重）。 */
    @Select("SELECT DISTINCT from_role FROM mail_messages " +
            "UNION SELECT DISTINCT to_role FROM mail_messages ORDER BY 1")
    List<String> distinctRoles();

    /** 后台线程数统计。 */
    @Select("SELECT COUNT(DISTINCT thread_id) FROM mail_messages")
    long countThreads();

    /**
     * 按委托单号取该单全部关联线程（2026-11-14 阁主 W-20261114-12）。
     *
     * <p>"同一单支持多根线程（正式派单线、补发线、哨兵补发线等），封存必须覆盖全部，
     * 不能只封主线程" —— 这里就是「全部」的口径：只要该线程里有一封信解析出了这个单号，
     * 它就算这单的线程。order_no 由发信时解析落库（见 MailThreadSealService.orderNoOf）。
     */
    @Select("SELECT DISTINCT thread_id FROM mail_messages WHERE order_no = #{orderNo}")
    List<String> selectThreadIdsByOrderNo(@Param("orderNo") String orderNo);

    /**
     * 某根线程里最大的消息 id（2026-11-14 W-20261114-12 封存水位用）。
     *
     * <p>封存时把它记成 {@code seal_from_id}：id 大于它的不再唤醒，小于等于它的仍唤醒一次 ——
     * 触发封存的那封（通常是 delivered 回执）必须还能叫醒收件人一次，否则验收那一步永远推进不了。
     */
    @Select("SELECT MAX(id) FROM mail_messages WHERE thread_id = #{threadId}")
    Long selectMaxIdByThread(@Param("threadId") String threadId);

    /**
     * 原子认领：以<b>受影响行数</b>判胜负。
     *
     * <p>两条会话并发认领时，「谁先到」在应用层判不出来 —— 时序没有先后。
     * 唯一可靠的是数据库行锁：{@code claimed_by IS NULL} 只有一个事务能改成功。
     */
    @Update("UPDATE mail_messages SET claimed_by = #{role}, claimed_at = NOW() " +
            "WHERE id = #{id} AND claimed_by IS NULL")
    int claim(@Param("id") Long id, @Param("role") String role);
}
