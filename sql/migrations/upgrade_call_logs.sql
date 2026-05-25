-- 升级秘钥表，添加权限字段
-- 适用于已有旧版 ai_assistants 表的情况

-- 添加权限字段（如果不存在）
ALTER TABLE ai_assistants ADD COLUMN IF NOT EXISTS can_create TINYINT(1) DEFAULT 0 COMMENT '可创建经验';
ALTER TABLE ai_assistants ADD COLUMN IF NOT EXISTS can_update TINYINT(1) DEFAULT 0 COMMENT '可编辑经验';
ALTER TABLE ai_assistants ADD COLUMN IF NOT EXISTS can_search TINYINT(1) DEFAULT 1 COMMENT '可查询经验';

-- 为现有记录设置默认值（查询权限默认开启）
UPDATE ai_assistants SET can_search = 1 WHERE can_search IS NULL;
UPDATE ai_assistants SET can_create = 0 WHERE can_create IS NULL;
UPDATE ai_assistants SET can_update = 0 WHERE can_update IS NULL;

-- 同时升级 call_logs 表（如需要）
-- DROP TABLE IF EXISTS call_logs;
-- CREATE TABLE call_logs (
--     id              BIGINT PRIMARY KEY AUTO_INCREMENT,
--     api_key         VARCHAR(64) COMMENT 'AppKey',
--     caller_name     VARCHAR(100) COMMENT '调用者名字',
--     keyword         VARCHAR(500) COMMENT '检索关键字',
--     hit_count       INT DEFAULT 0 COMMENT '命中数据条数',
--     created_at      DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
--     INDEX idx_api_key (api_key),
--     INDEX idx_created_at (created_at)
-- ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='调用日志表';
