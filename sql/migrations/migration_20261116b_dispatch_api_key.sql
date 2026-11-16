-- ============================================================================
-- 派单表补 Key 隔离（2026-11-16）
--
-- 背景：第一版建表漏了 api_key —— **任何持有有效 Key 的调用方都能看到全库派单**，
--       还能按任意角色名筛选、甚至认领指派给别人的单。这是真实的数据越权，必须补。
--       与 docs / projects 的隔离惯例对齐：同一 Key 下的助手共享数据，不同 Key 互相看不见。
--
-- 幂等：MySQL 5.7 不支持 ADD COLUMN IF NOT EXISTS，用 information_schema 判存在
--       （沿用 migration_20261115a 的写法）。重跑安全。
--
-- 空串语义：历史行与后台直接建的单一律 ''。MCP 查询会把 '' 也当成一个独立租户，
--           所以后台建的单对 AI 不可见 —— 这是有意的：人下的单由人在后台管。
-- ============================================================================

-- ---------------------------------------------------------------------------
-- 第一步：加列 api_key（幂等）
-- ---------------------------------------------------------------------------
SET @has_col := (
  SELECT COUNT(*) FROM information_schema.COLUMNS
  WHERE TABLE_SCHEMA = DATABASE()
    AND TABLE_NAME = 'dispatch_header'
    AND COLUMN_NAME = 'api_key'
);
SET @ddl := IF(@has_col = 0,
  'ALTER TABLE dispatch_header ADD COLUMN api_key VARCHAR(64) NOT NULL DEFAULT ''''
     COMMENT ''归属 API Key，用于数据隔离（同一 Key 下的助手共享）'' AFTER id',
  'SELECT 1');
PREPARE s FROM @ddl; EXECUTE s; DEALLOCATE PREPARE s;

-- ---------------------------------------------------------------------------
-- 第二步：隔离查询要用的两个索引（幂等）
-- ---------------------------------------------------------------------------
SET @has_idx1 := (
  SELECT COUNT(*) FROM information_schema.STATISTICS
  WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'dispatch_header' AND INDEX_NAME = 'idx_dispatch_owner'
);
SET @ddl2 := IF(@has_idx1 = 0,
  'ALTER TABLE dispatch_header ADD KEY idx_dispatch_owner (api_key, id)',
  'SELECT 1');
PREPARE s2 FROM @ddl2; EXECUTE s2; DEALLOCATE PREPARE s2;

SET @has_idx2 := (
  SELECT COUNT(*) FROM information_schema.STATISTICS
  WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'dispatch_header' AND INDEX_NAME = 'idx_dispatch_owner_status'
);
SET @ddl3 := IF(@has_idx2 = 0,
  'ALTER TABLE dispatch_header ADD KEY idx_dispatch_owner_status (api_key, status)',
  'SELECT 1');
PREPARE s3 FROM @ddl3; EXECUTE s3; DEALLOCATE PREPARE s3;

-- ---------------------------------------------------------------------------
-- 回执：三列/索引都在位
-- ---------------------------------------------------------------------------
SELECT
  (SELECT COUNT(*) FROM information_schema.COLUMNS
     WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'dispatch_header' AND COLUMN_NAME = 'api_key') AS has_api_key_col,
  (SELECT COUNT(*) FROM information_schema.STATISTICS
     WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'dispatch_header' AND INDEX_NAME = 'idx_dispatch_owner') AS has_owner_idx,
  (SELECT COUNT(*) FROM information_schema.STATISTICS
     WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'dispatch_header' AND INDEX_NAME = 'idx_dispatch_owner_status') AS has_owner_status_idx;
