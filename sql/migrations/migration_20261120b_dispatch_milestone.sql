-- ============================================================================
-- 派单里程碑字段（2026-11-20 派单 W-20261120-000026）
--
-- 越哥定的燃尽图口径：纵轴=未完成单数（不搞工时）；里程碑是 dispatch_header
-- 上一个可选字段，一单挂一个；老单不回填，空值=独立单；不做子任务表、不表达依赖。
--
-- 两条硬约束：
--   1. 不回填老单：本脚本只加列，一条 UPDATE 都不写。
--   2. 列宽 64 字符：建单入口 trim 后按 Unicode 码点校验，超 64 明确拒绝，
--      不静默截断（静默截断会把两个不同里程碑拼成同一个，图上数据串味）。
--
-- MySQL 5.7.44 不支持 LOCK=NONE / ALGORITHM=INPLACE，DDL 由人在低峰执行，
-- 本脚本只随 commit 提交，不在代码里自动执行。
-- ============================================================================

ALTER TABLE dispatch_header
  ADD COLUMN milestone VARCHAR(64) NULL
    COMMENT '里程碑（可选，空=独立单）；一单挂一个，不回填老单',
  ADD KEY idx_dispatch_milestone (milestone, status, id);

-- 回读自证：列与索引是否都建上（值为 1/1 才算成功）
SELECT
  SUM(COLUMN_NAME = 'milestone') AS milestone_column_exists,
  SUM(INDEX_NAME = 'idx_dispatch_milestone') AS milestone_index_exists
FROM information_schema.STATISTICS
WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'dispatch_header';
