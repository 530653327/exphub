-- ============================================================================
-- 派单表加版本号（2026-11-16 越哥要求）
--
-- 场景：建单时勾了「发版」授权位，就要写清发的是哪个版本；不填则自动 +1。
-- 递增规则：把版本号末尾那段连续数字 +1，前面的原样保留（2.5.6 → 2.5.7，3.4.51 → 3.4.52）。
-- 基准：同一项目里最近一张带版本号的单；项目为空则退到全局最近一张。
--
-- 幂等：information_schema 判存在（MySQL 5.7 无 ADD COLUMN IF NOT EXISTS）。
-- ============================================================================

SET @has_col := (
  SELECT COUNT(*) FROM information_schema.COLUMNS
  WHERE TABLE_SCHEMA = DATABASE()
    AND TABLE_NAME = 'dispatch_header'
    AND COLUMN_NAME = 'version'
);
SET @ddl := IF(@has_col = 0,
  'ALTER TABLE dispatch_header ADD COLUMN version VARCHAR(32) NULL
     COMMENT ''发版版本号；仅 auth_release=1 时有意义'' AFTER priority',
  'SELECT 1');
PREPARE s FROM @ddl; EXECUTE s; DEALLOCATE PREPARE s;

SELECT
  (SELECT COUNT(*) FROM information_schema.COLUMNS
     WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'dispatch_header' AND COLUMN_NAME = 'version') AS has_version_col;
