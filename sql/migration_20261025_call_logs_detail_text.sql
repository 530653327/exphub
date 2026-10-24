-- ============================================================================
-- P0 修复：call_logs.detail 超列宽导致 search_experience 整个请求失败
--
-- 背景（2026-10-25 实测）：
--   detail 原为 varchar(500)，而 logSearch 会把命中前 10 条文档标题拼进 detail
--   （单条标题上限 200 字）。库里已有 480 字符的记录——再加一条标题就超。
--   服务器 sql_mode 含 STRICT_TRANS_TABLES，超长**直接报错**而非静默截断：
--     Data too long for column 'detail'
--   而该 insert 位于搜索主流程且未被捕获 → 整个 search_experience 失败。
--   同一条查询重试可能好（命中集变了、拼接变短），所以表现为「偶发」。
--
-- 本次修复是三层，本脚本只是第一层（放宽列宽）：
--   1) 本脚本：detail → TEXT
--   2) CallLogWriter：写入前按各列宽度截断
--   3) CallLogWriter：独立事务 + 吞异常，日志失败不影响业务
--
-- ⚠️ 只做第 1 层不够：下次换个字段超长，同样的坑会从别的列冒出来。
--
-- 执行方式（可重复执行，幂等）：
--   mysql -uroot -p exphub < sql/migration_20261025_call_logs_detail_text.sql
-- ============================================================================

-- ---------------------------------------------------------------------------
-- 第一步：备份（仅首次执行时保留，已有备份则跳过）
-- ---------------------------------------------------------------------------
-- 表小（约 2200 行），整表快照成本可忽略；出问题可直接回灌。
CREATE TABLE IF NOT EXISTS `call_logs_bak_20261025` LIKE `call_logs`;
INSERT INTO `call_logs_bak_20261025`
SELECT * FROM `call_logs`
WHERE NOT EXISTS (SELECT 1 FROM `call_logs_bak_20261025` LIMIT 1);

-- ---------------------------------------------------------------------------
-- 第二步：仅当 detail 还不是 text 时才改（幂等）
--   MySQL 5.7 下 varchar → text 走 COPY 算法（重建表）。
--   2200 行、无并发写入高峰，秒级完成；不指定 ALGORITHM 以兼容 5.7 语法。
-- ---------------------------------------------------------------------------
SET @need := (
  SELECT COUNT(*) FROM information_schema.COLUMNS
  WHERE TABLE_SCHEMA = DATABASE()
    AND TABLE_NAME = 'call_logs'
    AND COLUMN_NAME = 'detail'
    AND DATA_TYPE <> 'text'
);

SET @ddl := IF(@need > 0,
  'ALTER TABLE `call_logs` MODIFY COLUMN `detail` TEXT NULL COMMENT ''详细信息（原 varchar(500)，2026-10-25 因超长打挂搜索改为 TEXT）''',
  'SELECT ''detail 已是 TEXT，跳过'' AS msg');

PREPARE stmt FROM @ddl;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

-- ---------------------------------------------------------------------------
-- 第三步：验收
-- ---------------------------------------------------------------------------
SELECT COLUMN_NAME, COLUMN_TYPE, IS_NULLABLE
FROM information_schema.COLUMNS
WHERE TABLE_SCHEMA = DATABASE()
  AND TABLE_NAME = 'call_logs'
  AND COLUMN_NAME IN ('detail', 'doc_title', 'keyword');

-- 历史上最长的 detail 有多长（改 TEXT 后不应再有 500 这个天花板）
SELECT MAX(CHAR_LENGTH(detail)) AS max_detail_chars,
       COUNT(*) AS total_rows
FROM `call_logs`
WHERE detail IS NOT NULL;
