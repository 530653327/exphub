-- ============================================================
-- M10：记忆「标记待删」机制（2026-10-25）
-- 背景：AI 助手没有删除能力，错记内容只能回到网页端手工找出来清理。
-- 红线：不可逆操作不给模型。所以 AI 只能「标记」，用户确认后由用户在后台执行删除。
-- 设计：复用 docs.status 增加一个状态值 PENDING_DELETE，
--      搜索/列表默认只查 ACTIVE，天然排除待删项，无需改动任何检索 SQL。
-- 本次只补三列元信息（谁标的、为什么标、什么时候标的），供后台待删队列展示。
-- 幂等：重复执行不会报错。
-- ============================================================

DROP PROCEDURE IF EXISTS sp_exphub_add_pending_delete_cols;
DELIMITER $$
CREATE PROCEDURE sp_exphub_add_pending_delete_cols()
BEGIN
    IF NOT EXISTS (SELECT 1 FROM information_schema.COLUMNS
                   WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'docs'
                     AND COLUMN_NAME = 'delete_reason') THEN
        ALTER TABLE docs ADD COLUMN delete_reason VARCHAR(500) DEFAULT NULL
            COMMENT 'M10 标记待删的原因（AI 填写，用户据此判断是否真删）';
    END IF;

    IF NOT EXISTS (SELECT 1 FROM information_schema.COLUMNS
                   WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'docs'
                     AND COLUMN_NAME = 'delete_marked_by') THEN
        ALTER TABLE docs ADD COLUMN delete_marked_by VARCHAR(100) DEFAULT NULL
            COMMENT 'M10 标记待删的助手名（authorName）';
    END IF;

    IF NOT EXISTS (SELECT 1 FROM information_schema.COLUMNS
                   WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'docs'
                     AND COLUMN_NAME = 'delete_marked_at') THEN
        ALTER TABLE docs ADD COLUMN delete_marked_at DATETIME DEFAULT NULL
            COMMENT 'M10 标记待删的时间';
    END IF;
END$$
DELIMITER ;

CALL sp_exphub_add_pending_delete_cols();
DROP PROCEDURE sp_exphub_add_pending_delete_cols;

-- 便于后台按待删队列快速检索（status 已有索引，这里只补时间维度）
-- 注意：低版本 MySQL 不支持 LOCK=NONE / ALGORITHM=INPLACE，索引变更请在低峰期执行
-- CREATE INDEX idx_docs_delete_marked ON docs (delete_marked_at);

-- 验证
SELECT COLUMN_NAME, COLUMN_TYPE, COLUMN_COMMENT
FROM information_schema.COLUMNS
WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'docs'
  AND COLUMN_NAME IN ('delete_reason', 'delete_marked_by', 'delete_marked_at');
