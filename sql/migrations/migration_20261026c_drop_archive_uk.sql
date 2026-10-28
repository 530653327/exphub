-- ============================================================
-- doc_versions_archive 的 (doc_id, version) 唯一索引：只建不删
--
-- 【2026-10-28 白虎 二次修 —— 上一版修法是错的，比原来更危险】
--
-- 历史：本文件最初是「无条件 DROP uk_doc_version」，起因 2026-10-26 00:54 全量归档
-- 炸在 Duplicate entry '608-2'。因为 deploy.sh 每次部署都全量重跑 migration，
-- 这个 DROP 就成了「每次部署把刚修好的索引再删一次」—— 部署即回归。
--
-- ❌ 上一版（同日晚些时候）改成「有重复就 DROP、没重复就建」，被阁主指出是**反向耦合**，
--    这个判断成立，且比原版更危险：
--        索引缺失 → 并发写入造出重复 → 脚本看到重复 → 走 DROP 分支 → 索引永远回不来
--    也就是「在最需要索引的时候把它删掉」，而且一旦进入这个状态就自锁：
--    产生重复恰恰是索引失效的后果，不是不该有索引的理由。
--
-- ✅ 本版原则：**任何情况下都不 DROP。**
--    · 索引已存在           → 什么都不做
--    · 索引不存在 + 无重复  → 建唯一索引（写入幂等的第二道防线）
--    · 索引不存在 + 有重复  → **只报告，不建也不删**。唯一索引确实建不出来，
--                             但正确动作是「先人工清理重复行，再重建」，
--                             不是「因为脏就把防护拆掉」。
--
-- 【顺带更正一处历史归因】
-- 原文写「#608 的 v2 在**主表**里有 2 条重复」。2026-10-27 实测：
--   SELECT doc_id,version FROM doc_versions GROUP BY doc_id,version HAVING COUNT(*)>1  → 0 组
-- 主表从来没有重复版本行；那两条在**归档表**（id 238/239，archived_at 完全相同）。
-- 把「归档表脏」记成「主表脏」，才会得出「源数据不满足约束」这个错误前提。
--
-- 原则不变：归档只负责原样搬走，**不在归档过程里悄悄抹掉一条**。
-- ============================================================

DROP PROCEDURE IF EXISTS sp_exphub_archive_uk;
DELIMITER $$
CREATE PROCEDURE sp_exphub_archive_uk()
BEGIN
    DECLARE has_uk  INT DEFAULT 0;
    DECLARE dup_cnt INT DEFAULT 0;

    SELECT COUNT(*) INTO has_uk FROM information_schema.STATISTICS
     WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'doc_versions_archive'
       AND INDEX_NAME = 'uk_doc_version';

    IF has_uk = 0 THEN
        SELECT COUNT(*) INTO dup_cnt FROM (
            SELECT doc_id, version FROM doc_versions_archive
            GROUP BY doc_id, version HAVING COUNT(*) > 1
        ) t;

        IF dup_cnt = 0 THEN
            ALTER TABLE doc_versions_archive ADD UNIQUE KEY uk_doc_version (doc_id, version);
        END IF;
        -- dup_cnt > 0 时**不做任何 DDL**：建不出来是事实，但不该因此把已有防护拆掉。
        -- 重复行需人工清理（保留 archived_at 最早的一条），清完本脚本下次会补建。
    END IF;

    -- 回查索引：唯一索引已覆盖同一前缀，两者都没有才补普通索引
    IF NOT EXISTS (SELECT 1 FROM information_schema.STATISTICS
                   WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'doc_versions_archive'
                     AND INDEX_NAME IN ('idx_doc_version', 'uk_doc_version')) THEN
        ALTER TABLE doc_versions_archive ADD INDEX idx_doc_version (doc_id, version);
    END IF;
END$$
DELIMITER ;

CALL sp_exphub_archive_uk();
DROP PROCEDURE sp_exphub_archive_uk;

-- 验证：uk_doc_version 的 NON_UNIQUE 必须为 0（存在时）
SELECT INDEX_NAME, NON_UNIQUE, GROUP_CONCAT(COLUMN_NAME ORDER BY SEQ_IN_INDEX) AS cols
FROM information_schema.STATISTICS
WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'doc_versions_archive'
GROUP BY INDEX_NAME, NON_UNIQUE;

-- 报告归档表重复（只报告，不修）
SELECT doc_id, version, COUNT(*) AS dup_cnt
FROM doc_versions_archive
GROUP BY doc_id, version
HAVING COUNT(*) > 1
ORDER BY dup_cnt DESC, doc_id;

-- 报告主表重复（只报告，不修）
SELECT doc_id, version, COUNT(*) AS dup_cnt
FROM doc_versions
GROUP BY doc_id, version
HAVING COUNT(*) > 1
ORDER BY dup_cnt DESC, doc_id;
