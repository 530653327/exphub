package com.exphub.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.exphub.entity.MailRoleAlias;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;

import java.util.List;

/**
 * 收件人别名表（{@code mail_role_aliases}）。只读：写入口留给后台（本版未提供）。
 *
 * <p>别名全表很小（个位数到几十行），一次全取进内存快照 —— 一半的用途是
 * 「按正名反查别名」（{@code WHERE role = ?} 的反向），按别名单查反而要查两次。
 */
@Mapper
public interface MailRoleAliasMapper extends BaseMapper<MailRoleAlias> {

    @Select("SELECT alias, role, note, created_at FROM mail_role_aliases ORDER BY alias")
    List<MailRoleAlias> selectAllAliases();
}
