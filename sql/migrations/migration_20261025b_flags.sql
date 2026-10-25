-- ============================================================
-- set_flags：记忆「标记」机制（2026-10-25，对应 #631 A1 的第三件）
-- 背景：整理记忆时给一条经验打「待复核 / 疑似重复」标记，唯一办法是
--      update_experience(tags=...)，而 update 会无条件存一份正文全文快照。
--      结果是「整理方案还没开工，版本表已经在膨胀」。
-- 设计：标记独立成 flags 列，专用工具只更 flags，不递增 version、不写版本快照。
--      不打进 tags，是因为 tags 参与 ngram 全文索引与搜索权重，
--      塞机器标记会污染检索；且 tags 是人的分类，flags 是机器的状态。
-- 幂等：重复执行不会报错。
-- ============================================================

DROP PROCEDURE IF EXISTS sp_exphub_add_flags_cols;
DELIMITER $$
CREATE PROCEDURE sp_exphub_add_flags_cols()
BEGIN
    IF NOT EXISTS (SELECT 1 FROM information_schema.COLUMNS
                   WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'docs'
                     AND COLUMN_NAME = 'flags') THEN
        ALTER TABLE docs ADD COLUMN flags VARCHAR(200) DEFAULT NULL
            COMMENT '机器可读标记，逗号分隔：needs_review/duplicate/superseded/stale/low_quality';
    END IF;

    IF NOT EXISTS (SELECT 1 FROM information_schema.COLUMNS
                   WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'docs'
                     AND COLUMN_NAME = 'flag_note') THEN
        ALTER TABLE docs ADD COLUMN flag_note VARCHAR(500) DEFAULT NULL
            COMMENT '标记说明（为什么打这个标记，或复核结论）';
    END IF;

    IF NOT EXISTS (SELECT 1 FROM information_schema.COLUMNS
                   WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'docs'
                     AND COLUMN_NAME = 'flag_marked_by') THEN
        ALTER TABLE docs ADD COLUMN flag_marked_by VARCHAR(100) DEFAULT NULL
            COMMENT '打标记的助手名（authorName）';
    END IF;

    IF NOT EXISTS (SELECT 1 FROM information_schema.COLUMNS
                   WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'docs'
                     AND COLUMN_NAME = 'flag_marked_at') THEN
        ALTER TABLE docs ADD COLUMN flag_marked_at DATETIME DEFAULT NULL
            COMMENT '打标记的时间';
    END IF;
END$$
DELIMITER ;

CALL sp_exphub_add_flags_cols();
DROP PROCEDURE sp_exphub_add_flags_cols;

-- 验证
SELECT COLUMN_NAME, COLUMN_TYPE, COLUMN_COMMENT
FROM information_schema.COLUMNS
WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'docs'
  AND COLUMN_NAME IN ('flags', 'flag_note', 'flag_marked_by', 'flag_marked_at');
