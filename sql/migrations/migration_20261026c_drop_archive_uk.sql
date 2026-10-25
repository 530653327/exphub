-- ============================================================
-- 修：去掉 doc_versions_archive 的 (doc_id, version) 唯一约束
--
-- 2026-10-26 00:54 全量归档时炸在：
--   Duplicate entry '608-2' for key 'uk_doc_version'
--
-- 查证：**主表 doc_versions 本身没有这个唯一约束**，而 #608 的 v2 在主表里有 2 条重复。
-- 我在归档表强加了源数据并不满足的约束，导致整批事务回滚。
--
-- 为什么不是「去重后归档」而是「去掉约束」：
--   归档的职责是原样搬走。主表的重复是数据质量问题（某处写入有并发缺陷），
--   该单独查、单独修，**不能在归档过程里悄悄抹掉一条** —— 那是「以整理之名删数据」。
--
-- 幂等：不存在就直接跳过。
-- ============================================================

DROP PROCEDURE IF EXISTS sp_exphub_drop_archive_uk;
DELIMITER $$
CREATE PROCEDURE sp_exphub_drop_archive_uk()
BEGIN
    IF EXISTS (SELECT 1 FROM information_schema.STATISTICS
               WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'doc_versions_archive'
                 AND INDEX_NAME = 'uk_doc_version') THEN
        ALTER TABLE doc_versions_archive DROP INDEX uk_doc_version;
    END IF;

    -- 换成普通索引（回查按 doc_id+version 查，仍然需要索引，只是不要唯一性）
    IF NOT EXISTS (SELECT 1 FROM information_schema.STATISTICS
                   WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'doc_versions_archive'
                     AND INDEX_NAME = 'idx_doc_version') THEN
        ALTER TABLE doc_versions_archive ADD INDEX idx_doc_version (doc_id, version);
    END IF;
END$$
DELIMITER ;

CALL sp_exphub_drop_archive_uk();
DROP PROCEDURE sp_exphub_drop_archive_uk;

-- 验证：应只剩 PRIMARY / idx_doc_version / idx_doc / idx_archived，且都非唯一
SELECT INDEX_NAME, NON_UNIQUE, GROUP_CONCAT(COLUMN_NAME ORDER BY SEQ_IN_INDEX) AS cols
FROM information_schema.STATISTICS
WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'doc_versions_archive'
GROUP BY INDEX_NAME, NON_UNIQUE;

-- 顺带把「主表存在重复版本」的数据质量问题查出来（只报告，不修）
SELECT doc_id, version, COUNT(*) AS dup_cnt
FROM doc_versions
GROUP BY doc_id, version
HAVING COUNT(*) > 1
ORDER BY dup_cnt DESC, doc_id;
