package com.exphub.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.exphub.entity.MailThreadStateLog;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

/**
 * 线程封存/解锁留痕（只追加，2026-11-14 阁主 W-20261114-12）。
 *
 * <p>「解锁动作要留痕（谁、何时、哪根线程）」的读取口就在这里：
 * 后台线程详情页与 mail_thread 的回执都直接读它。
 */
@Mapper
public interface MailThreadStateLogMapper extends BaseMapper<MailThreadStateLog> {

    @Select("SELECT * FROM mail_thread_state_log WHERE thread_id = #{threadId} ORDER BY id DESC LIMIT #{limit}")
    List<MailThreadStateLog> selectByThread(@Param("threadId") String threadId, @Param("limit") int limit);

    @Select("SELECT * FROM mail_thread_state_log WHERE order_no = #{orderNo} ORDER BY id DESC LIMIT #{limit}")
    List<MailThreadStateLog> selectByOrder(@Param("orderNo") String orderNo, @Param("limit") int limit);
}
