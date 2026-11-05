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
     * @param excludeProjectProgress scopeAll 时是否排除过程流水（2026-11-05：
     *        进度记录是施工日志，不是答案；它们数量远超项目经验（exphub #1 是 34 条进度 vs 8 条经验），
     *        混进主排序会把真正的答案挤下去。显式搜 templateType=project_progress 时不排除）
     *
     *        <p><b>2026-11-06 改成单条件（#1290 C49）：</b>原来是
     *        {@code NOT (project_id IS NOT NULL AND template_type = 'project_progress')}，
     *        只排「项目内」的 —— 全局文档的 {@code project_id IS NULL} 让整个 NOT 恒为真，
     *        所以「全局 + 过程流水」永远排不掉（实测：一条全局 pp 稳居搜索第 1 位）。
     *        现在只看类型，与挂不挂项目无关。
     *
     *        <p><b>为什么必须带 {@code IS NULL}：</b>{@code template_type <> 'x'} 对 NULL 求值为 NULL
     *        而不是 TRUE，直接写 {@code <>} 会把所有 template_type 为空的文档一起排掉。
     *        当前库里没有 NULL 行（2026-11-06 实测 13 个分组无 NULL），但列允许 NULL，
     *        不写这一句就是给将来埋雷。
     */
    @Select("<script>" +
        "SELECT * FROM docs " +
        "WHERE MATCH(title, content, aliases, summary, tags) AGAINST(#{keyword} IN BOOLEAN MODE) " +
        "<choose>" +
        "  <when test='scopeAll'>" +
        "    AND ( (project_id IS NULL AND (visibility = 'PUBLIC' OR api_key = #{apiKey})) " +
        "          <if test='ownerApiKey != null'>OR project_id IN (SELECT id FROM projects WHERE api_key = #{ownerApiKey})</if> ) " +
        "    <if test='excludeProjectProgress'>AND (template_type IS NULL OR template_type NOT IN ('project_progress','todo_list'))</if> " +
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
        @Param("ownerApiKey") String ownerApiKey,
        @Param("excludeProjectProgress") boolean excludeProjectProgress
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
        "    <if test='excludeProjectProgress'>AND (template_type IS NULL OR template_type NOT IN ('project_progress','todo_list'))</if> " +
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
        @Param("ownerApiKey") String ownerApiKey,
        @Param("excludeProjectProgress") boolean excludeProjectProgress
    );
}
