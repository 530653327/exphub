package com.exphub.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.exphub.entity.Doc;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;

import java.util.List;

@Mapper
public interface DocMapper extends BaseMapper<Doc> {

    @Select("SELECT DISTINCT category FROM docs WHERE category IS NOT NULL AND category != '' ORDER BY category")
    List<String> getCategories();
}