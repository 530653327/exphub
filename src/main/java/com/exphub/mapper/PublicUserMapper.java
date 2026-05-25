package com.exphub.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.exphub.entity.PublicUser;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface PublicUserMapper extends BaseMapper<PublicUser> {
}
