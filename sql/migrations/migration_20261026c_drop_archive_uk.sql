-- ============================================================
-- doc_versions_archive 的 (doc_id, version) 唯一索引：按数据实际情况决定去留
--
-- 【2026-10-28 白虎 改】
-- 本脚本原来是「每次部署无条件 DROP uk_doc_version」，起因是 2026-10-26 00:54
-- 全量归档炸在 Duplicate entry '608-2' —— 归档表里已存在重复行，唯一索引建不出来。
--
-- 但那次的处理方式留下了一个隐蔽回归：
--   部署脚本每次都会跑本文件 → 只要有人重新加上唯一索引，下次部署就被悄悄删掉。
-- 2026-10-27 加的「写入幂等第二道防线」正是这个索引：索引不在，
--   代码里的 DuplicateKeyException 兜底永远不触发，并发重复会在无人察觉时回来。
--
-- 所以改成**条件化**，而不是无条件删：
--   · 归档表存在 (doc_id, version) 重复 → 唯一索引建不出来，DROP 掉并把重复报出来
--   · 归档表无重复                     → 保留 / 补建唯一索引（写入幂等的第二道防线）
--   两种情况都幂等，反复执行结果一致。
--
-- 【顺带更正一处历史归因】
-- 原文写「#608 的 v2 在主表里有 2 条重复」。2026-10-27 实测：
--   `SELECT doc_id,version FROM doc_versions GROUP BY doc_id,version HAVING COUNT(*)>1` → 0 组。
-- 主表从来没有重复版本行；那两条重复在**归档表**（id 238/239，archived_at 完全相同）。
-- 把「归档表脏」记成「主表脏」，才会得出「源数据不满足约束」这个错误前提。
--
-- 原则不变：归档只负责原样搬走，**不在归档过程里悄悄抹掉一条**。
-- ============================================================

DROP PROCEDURE IF EXISTS sp_exphub_archive_uk;
DELIMITER $$
CREATE PROCEDURE sp_exphub_archive_uk()
BEGIN
    DECLARE dup_groups INT DEFAULT 0;

    SELECT COUNT(*) INTO dup_groups FROM (
        SELECT doc_id, version FROM doc_versions_archive
        GROUP BY doc_id, version HAVING COUNT(*) > 1
    ) t;

    IF dup_groups > 0 THEN
        -- 有重复：唯一索引建不出来，先撤掉（原行为），并留报告
        IF EXISTS (SELECT 1 FROM information_schema.STATISTICS
                   WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'doc_versions_archive'
                     AND INDEX_NAME = 'uk_doc_version') THEN
            ALTER TABLE doc_versions_archive DROP INDEX uk_doc_version;
        END IF;
    ELSE
        -- 无重复：保留/补建唯一索引，作为写入幂等的第二道防线
        IF NOT EXISTS (SELECT 1 FROM information_schema.STATISTICS
                       WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'doc_versions_archive'
                         AND INDEX_NAME = 'uk_doc_version') THEN
            ALTER TABLE doc_versions_archive ADD UNIQUE KEY uk_doc_version (doc_id, version);
        END IF;
    END IF;

    -- 回查索引始终要有（唯一索引已覆盖同一前缀，缺了才补普通索引）
    IF NOT EXISTS (SELECT 1 FROM information_schema.STATISTICS
                   WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'doc_versions_archive'
                     AND INDEX_NAME IN ('idx_doc_version', 'uk_doc_version')) THEN
        ALTER TABLE doc_versions_archive ADD INDEX idx_doc_version (doc_id, version);
    END IF;
END$$
DELIMITER ;

CALL sp_exphub_archive_uk();
DROP PROCEDURE sp_exphub_archive_uk;

-- 验证：uk_doc_version 的 NON_UNIQUE 应为 0（存在时）
SELECT INDEX_NAME, NON_UNIQUE, GROUP_CONCAT(COLUMN_NAME ORDER BY SEQ_IN_INDEX) AS cols
FROM information_schema.STATISTICS
WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'doc_versions_archive'
GROUP BY INDEX_NAME, NON_UNIQUE;

-- 报告主表重复（只报告，不修）
SELECT doc_id, version, COUNT(*) AS dup_cnt
FROM doc_versions
GROUP BY doc_id, version
HAVING COUNT(*) > 1
ORDER BY dup_cnt DESC, doc_id;
