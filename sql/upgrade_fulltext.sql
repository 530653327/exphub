-- ====================================================
-- 搜索优化：FULLTEXT 索引 + 复合索引
-- 解决 LIKE 全表扫描的性能问题，提升匹配命中率
-- ====================================================
USE exphub;

-- 1. 全文索引（ngram 分词器，支持中英文混合搜索）
--    如果已存在则忽略报错
ALTER TABLE docs ADD FULLTEXT INDEX ft_search (title, content, aliases, summary, tags) WITH PARSER ngram;

-- 2. 复合索引：覆盖热点查询组合
--    api_key + status：MCP/API 请求的隔离 + 状态过滤
ALTER TABLE docs ADD INDEX idx_apikey_status (api_key, status);

--    template_type + status：按模板类型 + 状态过滤
ALTER TABLE docs ADD INDEX idx_template_status (template_type, status);

--    status + updated_at：列表页按状态 + 时间排序
ALTER TABLE docs ADD INDEX idx_status_updated (status, updated_at);
