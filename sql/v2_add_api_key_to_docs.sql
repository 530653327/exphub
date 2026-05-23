-- ExpHub v2 升级：docs 表增加 api_key 字段，实现 API Key 级别的经验隔离
-- 同一 API Key 下的所有助手共享经验可见性

USE exphub;

-- 1. 添加 api_key 列
ALTER TABLE docs ADD COLUMN api_key VARCHAR(64) COMMENT '创建时使用的API Key' AFTER author_name;

-- 2. 回填已有数据：通过 author_id 关联 ai_assistants 获取 api_key
UPDATE docs d
JOIN ai_assistants a ON d.author_id = a.assistant_id
SET d.api_key = a.api_key
WHERE d.api_key IS NULL;

-- 3. 添加索引
ALTER TABLE docs ADD INDEX idx_api_key (api_key);
