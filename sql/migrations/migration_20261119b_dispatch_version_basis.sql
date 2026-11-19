-- ============================================================================
-- 发版版本号取号基准修复（2026-11-19 派单 W-20261119-000008）
--
-- 现象：W-20261119-000006（忆阁 project_id=6，勾了发版）没填版本号，
--       服务端落成 1.0.3；忆阁线上实际是 3.4.x。根因不是 project_id 没落，
--       而是取号基准取的是「同项目 id 最大的一张带版本单」——那批单的号段本身
--       是从 1.0.0 自我繁殖出来的（1.0.1 → 1.0.2 → 1.0.3），并不是项目真实版本。
--
-- 修法（不改历史单）：
--   1) projects.current_version  —— 项目当前线上版本，作为取号的第一基准；
--      发版单验收通过时自动回写（只升不降），后台项目页也可手动维护。
--   2) dispatch_header.version_basis —— 建单时把「基准是什么」原样落库，
--      审核页面看到的是当时依据，不会因为之后项目版本变化而漂。
--
-- 幂等：information_schema 判存在（MySQL 5.7 无 ADD COLUMN IF NOT EXISTS）。
-- ============================================================================

-- 1) projects.current_version / 更新时间 / 来源
SET @has_pv := (
  SELECT COUNT(*) FROM information_schema.COLUMNS
  WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'projects' AND COLUMN_NAME = 'current_version'
);
SET @ddl := IF(@has_pv = 0,
  'ALTER TABLE projects ADD COLUMN current_version VARCHAR(32) NULL
     COMMENT ''项目当前线上版本；派单发版取号的第一基准'' AFTER status',
  'SELECT 1');
PREPARE s FROM @ddl; EXECUTE s; DEALLOCATE PREPARE s;

SET @has_pvt := (
  SELECT COUNT(*) FROM information_schema.COLUMNS
  WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'projects' AND COLUMN_NAME = 'current_version_updated_at'
);
SET @ddl := IF(@has_pvt = 0,
  'ALTER TABLE projects ADD COLUMN current_version_updated_at DATETIME NULL
     COMMENT ''当前版本最后一次确认时间'' AFTER current_version',
  'SELECT 1');
PREPARE s FROM @ddl; EXECUTE s; DEALLOCATE PREPARE s;

SET @has_pvs := (
  SELECT COUNT(*) FROM information_schema.COLUMNS
  WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'projects' AND COLUMN_NAME = 'current_version_source'
);
SET @ddl := IF(@has_pvs = 0,
  'ALTER TABLE projects ADD COLUMN current_version_source VARCHAR(100) NULL
     COMMENT ''当前版本从哪来（后台手填 / 哪张派单验收回写）'' AFTER current_version_updated_at',
  'SELECT 1');
PREPARE s FROM @ddl; EXECUTE s; DEALLOCATE PREPARE s;

-- 2) dispatch_header.version_basis
SET @has_vb := (
  SELECT COUNT(*) FROM information_schema.COLUMNS
  WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'dispatch_header' AND COLUMN_NAME = 'version_basis'
);
SET @ddl := IF(@has_vb = 0,
  'ALTER TABLE dispatch_header ADD COLUMN version_basis VARCHAR(300) NULL
     COMMENT ''发版版本号的推算依据：基准值 → 结果；手填时记「手动指定」'' AFTER version',
  'SELECT 1');
PREPARE s FROM @ddl; EXECUTE s; DEALLOCATE PREPARE s;

SELECT
  (SELECT COUNT(*) FROM information_schema.COLUMNS
     WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'projects' AND COLUMN_NAME = 'current_version') AS has_projects_current_version,
  (SELECT COUNT(*) FROM information_schema.COLUMNS
     WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'dispatch_header' AND COLUMN_NAME = 'version_basis') AS has_dispatch_version_basis;
