-- ============================================================
-- A3：元数据变更不产生正文快照（2026-10-25，对应 #631 A3）
-- 背景：update 每调一次就插一条完整 content 副本到历史表，哪怕只改一个标签。
--      实测：463 条快照 / 1.95MB / 平均 4424 字，其中 21 条正文与更早版本完全相同（纯冗余）。
-- 改法：正文没变时快照 content 存 NULL + change_type=META，只记「谁在什么时候改了什么」，
--      不重复存全文；正文变了照旧存全文（change_type=CONTENT）。
-- 幂等：重复执行不会报错；老数据全部标 CONTENT（它们确实都存了全文）。
-- ============================================================

DROP PROCEDURE IF EXISTS sp_exphub_a3_meta_snapshot;
DELIMITER $$
CREATE PROCEDURE sp_exphub_a3_meta_snapshot()
BEGIN
    -- 1) content 允许 NULL：NULL 的语义是「本次没动正文」，不是「数据丢了」
    IF EXISTS (SELECT 1 FROM information_schema.COLUMNS
               WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'doc_versions'
                 AND COLUMN_NAME = 'content' AND IS_NULLABLE = 'NO') THEN
        ALTER TABLE doc_versions MODIFY COLUMN content MEDIUMTEXT NULL
            COMMENT '历史版本正文；NULL = 本次仅元数据变更，正文未变，不重复存全文';
    END IF;

    -- 2) 变更类型：CONTENT=正文变更（有全文）/ META=仅元数据变更（content 为 NULL）
    IF NOT EXISTS (SELECT 1 FROM information_schema.COLUMNS
                   WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'doc_versions'
                     AND COLUMN_NAME = 'change_type') THEN
        ALTER TABLE doc_versions ADD COLUMN change_type VARCHAR(20) DEFAULT 'CONTENT'
            COMMENT 'CONTENT=正文变更（存全文）/ META=仅元数据变更（content 为 NULL）';
    END IF;
END$$
DELIMITER ;

CALL sp_exphub_a3_meta_snapshot();
DROP PROCEDURE sp_exphub_a3_meta_snapshot;

-- 验证
SELECT COLUMN_NAME, COLUMN_TYPE, IS_NULLABLE, COLUMN_DEFAULT
FROM information_schema.COLUMNS
WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'doc_versions'
  AND COLUMN_NAME IN ('content', 'change_type');
