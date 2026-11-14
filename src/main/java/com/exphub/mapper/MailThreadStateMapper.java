package com.exphub.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.exphub.entity.MailThreadState;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

/**
 * 线程封存状态（2026-11-14 阁主 W-20261114-12）。
 *
 * <p>只提供两个查询入口：按状态取全部被封存线程号（唤醒源过滤用，一次取全量）、
 * 按单号取（「封存状态需落库、能按单号查出」）。
 */
@Mapper
public interface MailThreadStateMapper extends BaseMapper<MailThreadState> {

    /**
     * 当前处于封存态的线程号。
     *
     * <p>一次取全量而不是按 threadId IN (...) 拼参数：信箱线程数量级是「几十到几百」，
     * 而唤醒扫描每次都要判所有候选线程 —— 拼一个几百项 IN 反而更慢，还会撞 MySQL 的 packet 上限。
     */
    @Select("SELECT thread_id, order_no, state, seal_from_id, sealed_at, sealed_by, seal_reason FROM mail_thread_states WHERE state = 'SEALED'")
    List<MailThreadState> selectSealed();

    /** 按单号取该单全部线程的封存状态（含已解锁行，行即审计）。 */
    @Select("SELECT * FROM mail_thread_states WHERE order_no = #{orderNo} ORDER BY sealed_at DESC")
    List<MailThreadState> selectByOrder(@Param("orderNo") String orderNo);

    /** 最近被封存的 N 根线程（后台页面用）。 */
    @Select("SELECT * FROM mail_thread_states ORDER BY sealed_at DESC, thread_id LIMIT #{limit}")
    List<MailThreadState> selectRecent(@Param("limit") int limit);
}
