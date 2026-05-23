package com.exphub.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.exphub.entity.Doc;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

@Mapper
public interface DocMapper extends BaseMapper<Doc> {

    @Select("SELECT DISTINCT category FROM docs WHERE category IS NOT NULL AND category != '' ORDER BY category")
    List<String> getCategories();

    /**
     * FULLTEXT 搜索（BOOLEAN MODE），按相关性 + 调用次数排序
     */
    @Select("<script>" +
        "SELECT * FROM docs " +
        "WHERE MATCH(title, content, aliases, summary, tags) AGAINST(#{keyword} IN BOOLEAN MODE) " +
        "<if test='apiKey != null'>AND (api_key = #{apiKey} OR api_key IS NULL)</if> " +
        "<if test='templateType != null and templateType != \"\"'>AND template_type = #{templateType}</if> " +
        "<if test='statusList != null and statusList.size() > 0'>AND status IN <foreach item='s' collection='statusList' open='(' separator=',' close=')'>#{s}</foreach></if> " +
        "ORDER BY MATCH(title, content, aliases, summary, tags) AGAINST(#{keyword} IN NATURAL LANGUAGE MODE) DESC, call_count DESC " +
        "LIMIT #{offset}, #{limit}" +
        "</script>")
    List<Doc> searchByFulltext(
        @Param("keyword") String keyword,
        @Param("apiKey") String apiKey,
        @Param("templateType") String templateType,
        @Param("statusList") List<String> statusList,
        @Param("offset") long offset,
        @Param("limit") long limit
    );

    /**
     * FULLTEXT 搜索结果计数
     */
    @Select("<script>" +
        "SELECT COUNT(*) FROM docs " +
        "WHERE MATCH(title, content, aliases, summary, tags) AGAINST(#{keyword} IN BOOLEAN MODE) " +
        "<if test='apiKey != null'>AND (api_key = #{apiKey} OR api_key IS NULL)</if> " +
        "<if test='templateType != null and templateType != \"\"'>AND template_type = #{templateType}</if> " +
        "<if test='statusList != null and statusList.size() > 0'>AND status IN <foreach item='s' collection='statusList' open='(' separator=',' close=')'>#{s}</foreach></if>" +
        "</script>")
    long countByFulltext(
        @Param("keyword") String keyword,
        @Param("apiKey") String apiKey,
        @Param("templateType") String templateType,
        @Param("statusList") List<String> statusList
    );
}