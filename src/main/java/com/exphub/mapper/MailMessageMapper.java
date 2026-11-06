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
     * 原子认领：以<b>受影响行数</b>判胜负。
     *
     * <p>两条会话并发认领时，「谁先到」在应用层判不出来 —— 时序没有先后。
     * 唯一可靠的是数据库行锁：{@code claimed_by IS NULL} 只有一个事务能改成功。
     */
    @Update("UPDATE mail_messages SET claimed_by = #{role}, claimed_at = NOW() " +
            "WHERE id = #{id} AND claimed_by IS NULL")
    int claim(@Param("id") Long id, @Param("role") String role);
}
