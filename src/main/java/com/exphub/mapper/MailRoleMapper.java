package com.exphub.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.exphub.entity.MailRole;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;

import java.util.List;

/**
 * 信箱角色清单（{@code mail_roles}）。<b>本版只读</b>：写入口（后台页）留给后续，
 * 现在加角色靠迁移脚本或手工 SQL —— 不给 AI 开写工具（工具总数已冻结，见 AGENTS 红线 13）。
 */
@Mapper
public interface MailRoleMapper extends BaseMapper<MailRole> {

    /**
     * 可投递角色：<b>只认 status='ACTIVE'</b>。
     *
     * <p>特意在 SQL 里过滤而不是取全表回 Java 过滤：这条查询在每次发信/拒绝时都会走，
     * 语义写在唯一一处，读代码不用先知道「Java 侧还会再筛一遍」。
     */
    @Select("SELECT role, display_name, status, source, project_id, note, " +
            "created_at, created_by, updated_at, updated_by " +
            "FROM mail_roles WHERE status = 'ACTIVE' ORDER BY role")
    List<MailRole> selectActive();

    /**
     * 停用/待复核的行数，给运维核对用（不进热路径）。
     *
     * <p>用 {@code !=} 而不是 {@code <>}：{@code @Select} 里写 {@code <>} 曾让应用启动即
     * {@code SAXParseException}，而 620 条测试全绿（AGENTS 红线 9，a0ab1d3）。不冒这个险。
     */
    @Select("SELECT COUNT(*) FROM mail_roles WHERE status != 'ACTIVE'")
    long countNotActive();
}
