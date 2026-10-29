-- 2026-10-29 docs.last_verified_source：验证时间的「来源」，区分回填值与真验证值
--
-- 为什么必须加：last_verified_at 上线时，645 篇存量行是用 updated_at 回填的 ——
-- 那只是「最后一次被改过的时间」这个下界，不是「有人确认过结论仍然成立」。
-- 但 detail 头部和搜索回执把它们无差别显示成「验证时间」，下一个 AI 读到会当证据用。
-- 光靠文档写一句「这是回填值」没用：文档会被略读，字段不会。
--
-- 取值：
--   backfill = 由 updated_at 回填而来，**不是验证证据**
--   auto     = 写入时系统自动记录（本次写入视为一次验证）
--   manual   = 调用方显式声明「这就是验证时间」
--
-- 回填口径：本次全部标 backfill（含 2026-10-29 当天几次实测写入产生的值）。
-- 为什么不细分到 auto：那些值虽然时间是真的，但同样是「写操作顺带刷的」，不是人工确认；
-- 全标 backfill 更保守，会促使人去核。从本迁移之后写入的才会被标成 auto / manual。
--
-- ⚠️ 回填语句必须显式写 `updated_at = updated_at`，否则 ON UPDATE CURRENT_TIMESTAMP
--    会把全库修改时间刷成执行这一刻（2026-10-29 已经踩过一次，见 migration_20261029c）。

SET @col_exists := (
    SELECT COUNT(*) FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'docs' AND COLUMN_NAME = 'last_verified_source'
);
SET @ddl := IF(@col_exists = 0,
    'ALTER TABLE docs ADD COLUMN last_verified_source VARCHAR(16) NULL COMMENT ''验证时间来源：backfill=updated_at 回填(非证据)/auto=写入时自动/manual=人工声明'' AFTER last_verified_at',
    'DO 0');
PREPARE stmt FROM @ddl;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

UPDATE docs
SET last_verified_source = 'backfill',
    updated_at           = updated_at
WHERE last_verified_source IS NULL;
