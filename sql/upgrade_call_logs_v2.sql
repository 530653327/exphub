-- 升级 call_logs 表，增加操作类型和文档信息字段
-- 用于记录 AI 助手的 CREATE、UPDATE、DELETE 操作

ALTER TABLE call_logs 
ADD COLUMN action VARCHAR(20) DEFAULT 'SEARCH' COMMENT '操作类型：SEARCH, CREATE, UPDATE, DELETE' AFTER caller_name,
ADD COLUMN doc_id BIGINT COMMENT '文档ID（CREATE/UPDATE/DELETE时用）' AFTER hit_count,
ADD COLUMN doc_title VARCHAR(200) COMMENT '文档标题（CREATE/UPDATE/DELETE时用）' AFTER doc_id,
ADD COLUMN detail VARCHAR(500) COMMENT '详细信息' AFTER doc_title;

-- 为新字段添加索引
ALTER TABLE call_logs ADD INDEX idx_action (action);
ALTER TABLE call_logs ADD INDEX idx_doc_id (doc_id);
