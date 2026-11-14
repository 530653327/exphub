-- ============================================================================
-- W-20261115-17：工具统计排除 mail_start 哨兵轮询（真实读信仍计）
--
-- 背景：十几个信箱哨兵不停调 mail_start(unreadOnly=true)，近 7 天 19.8 万次，
--       占全部工具调用的 83.6%，把使用榜/成功率/趋势全淹了。
--       按工具名一刀切会连带删掉真实读信，所以按 **行** 标记：
--         is_poll=1 → 哨兵轮询，统计排除
--         is_poll=0 → 真实调用，照常计入
--         NULL      → 本迁移之前的历史行，无法区分，**不回填**；
--                     查询端按「未标记为真实 = 不计入统计」处理（见 ToolPollPolicy）
--
-- 幂等：MySQL 5.7 不支持 ADD COLUMN IF NOT EXISTS，用 information_schema 判存在。
-- 部署顺序：deploy.sh 在重启前执行本文件；新代码 select is_poll 时列必须已存在。
-- ============================================================================

-- ---------------------------------------------------------------------------
-- 第一步：备份（只做一次；表 250k 行 / 数据 33MB，整表快照秒级）
-- ---------------------------------------------------------------------------
-- ⚠️ 列必须显式列出，不能写 SELECT *：
--    备份表在加 is_poll **之前**创建，后续重跑时主表多了一列，
--    `INSERT ... SELECT *` 会报 ERROR 1136 Column count doesn't match（2026-11-15 首次部署实测）。
--    显式列在加列前后都存在，重跑安全；备份的语义是「加列前的整表快照」，不含 is_poll 正好。
CREATE TABLE IF NOT EXISTS `call_logs_bak_20261115` LIKE `call_logs`;
INSERT INTO `call_logs_bak_20261115`
    (id, api_key, caller_name, action, tool_name, cost_ms, success,
     keyword, hit_count, doc_id, doc_title, detail, created_at)
SELECT id, api_key, caller_name, action, tool_name, cost_ms, success,
       keyword, hit_count, doc_id, doc_title, detail, created_at
FROM `call_logs`
WHERE NOT EXISTS (SELECT 1 FROM `call_logs_bak_20261115` LIMIT 1);

-- ---------------------------------------------------------------------------
-- 第二步：加列 is_poll（幂等）
-- ---------------------------------------------------------------------------
SET @has_col := (
  SELECT COUNT(*) FROM information_schema.COLUMNS
  WHERE TABLE_SCHEMA = DATABASE()
    AND TABLE_NAME = 'call_logs'
    AND COLUMN_NAME = 'is_poll'
);
SET @ddl_col := IF(@has_col = 0,
  'ALTER TABLE `call_logs` ADD COLUMN `is_poll` TINYINT(1) DEFAULT NULL COMMENT ''是否哨兵轮询：1=轮询（统计排除）/0=真实调用/NULL=迁移前历史行（不回填）'' AFTER `detail`',
  'SELECT ''is_poll 已存在，跳过'' AS msg');
PREPARE stmt_col FROM @ddl_col;
EXECUTE stmt_col;
DEALLOCATE PREPARE stmt_col;

-- ---------------------------------------------------------------------------
-- 第三步：加索引 idx_tool_poll（幂等）
--   (tool_name, is_poll, created_at) 覆盖「按工具 + 轮询标记 + 时间窗」的聚合过滤。
-- ---------------------------------------------------------------------------
SET @has_idx := (
  SELECT COUNT(*) FROM information_schema.STATISTICS
  WHERE TABLE_SCHEMA = DATABASE()
    AND TABLE_NAME = 'call_logs'
    AND INDEX_NAME = 'idx_tool_poll'
);
SET @ddl_idx := IF(@has_idx = 0,
  'ALTER TABLE `call_logs` ADD INDEX `idx_tool_poll` (`tool_name`, `is_poll`, `created_at`)',
  'SELECT ''idx_tool_poll 已存在，跳过'' AS msg');
PREPARE stmt_idx FROM @ddl_idx;
EXECUTE stmt_idx;
DEALLOCATE PREPARE stmt_idx;

-- ---------------------------------------------------------------------------
-- 第四步：验收
-- ---------------------------------------------------------------------------
SELECT COLUMN_NAME, COLUMN_TYPE, IS_NULLABLE, COLUMN_DEFAULT
FROM information_schema.COLUMNS
WHERE TABLE_SCHEMA = DATABASE()
  AND TABLE_NAME = 'call_logs'
  AND COLUMN_NAME IN ('is_poll', 'tool_name', 'cost_ms', 'success');

SELECT INDEX_NAME, SEQ_IN_INDEX, COLUMN_NAME
FROM information_schema.STATISTICS
WHERE TABLE_SCHEMA = DATABASE()
  AND TABLE_NAME = 'call_logs'
  AND INDEX_NAME IN ('idx_tool_created', 'idx_tool_poll')
ORDER BY INDEX_NAME, SEQ_IN_INDEX;

SELECT COUNT(*) AS total_rows,
       SUM(is_poll = 1) AS poll_rows,
       SUM(is_poll = 0) AS real_rows,
       SUM(is_poll IS NULL) AS legacy_null_rows
FROM `call_logs`;
