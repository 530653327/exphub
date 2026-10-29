-- 2026-10-29 docs.last_verified_at：结论「最后一次被确认仍然成立」的时间
--
-- 为什么加：现有 updated_at 只回答「这行数据什么时候被改过」，
-- 而「这条经验的结论现在还算数吗」根本没有依据 —— 改个错字、修个 summary 都会刷新 updated_at，
-- 时效判定就变成「谁最近动过」而不是「谁最近确认过」。没有这一列，
-- 搜索回执的「超期 N 条」以及任何过期判定都是空中楼阁。
--
-- 两列的语义必须分开：
--   updated_at       = 最后一次写库（含改错字、改标签、改状态）
--   last_verified_at = 最后一次「确认结论仍然成立」
-- 写入口默认刷新 last_verified_at；只改错字/润色时调用方显式传旧值，表示「本次不算重新验证」。
--
-- 回填口径（重要，别误读）：存量行一律取 updated_at，即「最后一次被修改的时间」——
-- 这是**可辩护的下界**（那天确实有人动过它），不是真的验证记录。
-- 所以上线初期这批值只能当「起点」，不能当「验证证据」；从本迁移之后写入的行才是真验证时间。
--
-- 幂等：列存在则跳过；回填只填空值，重复执行不会把已刷新的时间改回去。

SET @col_exists := (
    SELECT COUNT(*) FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'docs' AND COLUMN_NAME = 'last_verified_at'
);
SET @ddl := IF(@col_exists = 0,
    'ALTER TABLE docs ADD COLUMN last_verified_at DATETIME NULL COMMENT ''最后一次确认结论仍成立的时间；NULL=从未记录'' AFTER updated_at',
    'DO 0');
PREPARE stmt FROM @ddl;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

-- 回填：存量行以 updated_at 为起点（见文件头口径说明）
UPDATE docs SET last_verified_at = updated_at WHERE last_verified_at IS NULL;
