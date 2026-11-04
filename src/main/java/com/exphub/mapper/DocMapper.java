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
     * @param keyword  BOOLEAN MODE 查询词（含 + 前缀等操作符）
     * @param sortKeyword  NATURAL LANGUAGE MODE 排序词（纯净关键词，不含操作符）
     * @param scopeAll  true=不分作用域：全局可读文档 + 归属当前 Key 的项目文档，一起按相关度排序
     * @param ownerApiKey scopeAll=true 时用于圈定「我名下的项目」，为 null 则只搜全局
     */
    @Select("<script>" +
        "SELECT * FROM docs " +
        "WHERE MATCH(title, content, aliases, summary, tags) AGAINST(#{keyword} IN BOOLEAN MODE) " +
        "<choose>" +
        "  <when test='scopeAll'>" +
        "    AND ( (project_id IS NULL AND (visibility = 'PUBLIC' OR api_key = #{apiKey})) " +
        "          <if test='ownerApiKey != null'>OR project_id IN (SELECT id FROM projects WHERE api_key = #{ownerApiKey})</if> ) " +
        "  </when>" +
        "  <otherwise>" +
        "    <if test='apiKey != null and projectId == null'>AND (visibility = 'PUBLIC' OR api_key = #{apiKey})</if> " +
        "    <if test='projectId != null'>AND project_id = #{projectId}</if> " +
        "    <if test='projectId == null'>AND project_id IS NULL</if> " +
        "  </otherwise>" +
        "</choose>" +
        "<if test='templateType != null and templateType != \"\"'>AND template_type = #{templateType}</if> " +
        "<if test='statusList != null and statusList.size() > 0'>AND status IN <foreach item='s' collection='statusList' open='(' separator=',' close=')'>#{s}</foreach></if> " +
        "ORDER BY MATCH(title, content, aliases, summary, tags) AGAINST(#{sortKeyword} IN NATURAL LANGUAGE MODE) DESC, call_count DESC " +
        "LIMIT #{offset}, #{limit}" +
        "</script>")
    List<Doc> searchByFulltext(
        @Param("keyword") String keyword,
        @Param("sortKeyword") String sortKeyword,
        @Param("apiKey") String apiKey,
        @Param("projectId") Long projectId,
        @Param("templateType") String templateType,
        @Param("statusList") List<String> statusList,
        @Param("offset") long offset,
        @Param("limit") long limit,
        @Param("scopeAll") boolean scopeAll,
        @Param("ownerApiKey") String ownerApiKey
    );

    /**
     * FULLTEXT 搜索结果计数
     */
    @Select("<script>" +
        "SELECT COUNT(*) FROM docs " +
        "WHERE MATCH(title, content, aliases, summary, tags) AGAINST(#{keyword} IN BOOLEAN MODE) " +
        "<choose>" +
        "  <when test='scopeAll'>" +
        "    AND ( (project_id IS NULL AND (visibility = 'PUBLIC' OR api_key = #{apiKey})) " +
        "          <if test='ownerApiKey != null'>OR project_id IN (SELECT id FROM projects WHERE api_key = #{ownerApiKey})</if> ) " +
        "  </when>" +
        "  <otherwise>" +
        "    <if test='apiKey != null and projectId == null'>AND (visibility = 'PUBLIC' OR api_key = #{apiKey})</if> " +
        "    <if test='projectId != null'>AND project_id = #{projectId}</if> " +
        "    <if test='projectId == null'>AND project_id IS NULL</if> " +
        "  </otherwise>" +
        "</choose>" +
        "<if test='templateType != null and templateType != \"\"'>AND template_type = #{templateType}</if> " +
        "<if test='statusList != null and statusList.size() > 0'>AND status IN <foreach item='s' collection='statusList' open='(' separator=',' close=')'>#{s}</foreach></if> " +
        "</script>")
    long countByFulltext(
        @Param("keyword") String keyword,
        @Param("apiKey") String apiKey,
        @Param("projectId") Long projectId,
        @Param("templateType") String templateType,
        @Param("statusList") List<String> statusList,
        @Param("scopeAll") boolean scopeAll,
        @Param("ownerApiKey") String ownerApiKey
    );
}
