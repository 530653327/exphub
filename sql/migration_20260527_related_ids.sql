-- ============================================================
-- 迁移脚本：添加 related_ids 字段 + AI 反馈机制
-- 执行时间：2026-05-27
-- ============================================================

-- docs 表新增关联经验字段
ALTER TABLE docs ADD COLUMN IF NOT EXISTS related_ids VARCHAR(500) DEFAULT NULL
  COMMENT '关联经验ID，逗号分隔（互相引用的替代经验）' AFTER status;

-- call_logs 表 action 字段新增 HELPFUL 和 NOT_HELPFUL 类型
-- ALTER TABLE call_logs MODIFY COLUMN action VARCHAR(20) DEFAULT 'SEARCH'
--   COMMENT '操作类型：SEARCH / CREATE / UPDATE / DELETE / HELPFUL / NOT_HELPFUL';
-- 注意：VARCHAR(20) 已足够，无需修改
