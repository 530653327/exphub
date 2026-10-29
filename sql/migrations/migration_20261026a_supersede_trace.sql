-- ============================================================
-- mark_superseded：补「谁标的」留痕（2026-10-26，阁主实测发现）
--
-- 背景：同一批调用里 clear_superseded 回执有「操作人」，
--      mark_superseded 一个字都没有 —— 两边不对称。
--
-- 查源码定位（不是忆阁，忆阁对这两个工具都是直接透传）：
--      DocService.markSuperseded 只 set superseded_by 一列，
--      传进来的 by 只进了 log.info，既没落库也没进回执。
--      → 用户问「这条谁标成被取代的」，只能翻日志，而日志会轮转。
--
-- 关键不一致（这才是要修的根因）：
--      set_flags        → flag_marked_by / flag_marked_at   ✅ 有留痕
--      mark_for_deletion → delete_marked_by / delete_marked_at ✅ 有留痕
--      mark_superseded  → 只有 superseded_by               ❌ 没留痕
--      三种都是「元信息操作、不改正文、不产生版本快照」，留痕形态应当一致。
--
-- 设计：加两列，与 flag/delete 完全对称。
--      仍然不递增 version、不写版本快照（整理动作不该产生版本垃圾）。
-- 幂等：重复执行不会报错。
-- ============================================================

DROP PROCEDURE IF EXISTS sp_exphub_add_supersede_trace;
DELIMITER $$
CREATE PROCEDURE sp_exphub_add_supersede_trace()
BEGIN
    IF NOT EXISTS (SELECT 1 FROM information_schema.COLUMNS
                   WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'docs'
                     AND COLUMN_NAME = 'superseded_marked_by') THEN
        ALTER TABLE docs ADD COLUMN superseded_marked_by VARCHAR(100) DEFAULT NULL
            COMMENT '标记取代关系的操作人（与 flag_marked_by / delete_marked_by 对称）';
    END IF;

    IF NOT EXISTS (SELECT 1 FROM information_schema.COLUMNS
                   WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'docs'
                     AND COLUMN_NAME = 'superseded_marked_at') THEN
        ALTER TABLE docs ADD COLUMN superseded_marked_at DATETIME DEFAULT NULL
            COMMENT '标记取代关系的时间';
    END IF;
END$$
DELIMITER ;

CALL sp_exphub_add_supersede_trace();
DROP PROCEDURE sp_exphub_add_supersede_trace;

-- 验证
SELECT COLUMN_NAME, COLUMN_TYPE, COLUMN_COMMENT
FROM information_schema.COLUMNS
WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'docs'
  AND COLUMN_NAME IN ('superseded_by', 'superseded_marked_by', 'superseded_marked_at');

-- 回填历史：已有取代关系但没留痕的，标记人为「未记录」
-- （不做猜测；诚实地写「未记录」比编一个名字更符合审计要求）
--
-- ⚠️ `updated_at = updated_at` 不是多余：docs.updated_at 带 ON UPDATE CURRENT_TIMESTAMP，
-- 这句 UPDATE 只 SET 别的列，MySQL 仍会把 updated_at 刷成执行时刻。
-- 2026-10-29 已经因此把全库修改时间压平过一次，见 migration_20261029c 的说明。
UPDATE docs
   SET superseded_marked_by = '未记录（该列上线前产生）',
       updated_at           = updated_at
 WHERE superseded_by IS NOT NULL
   AND (superseded_marked_by IS NULL OR superseded_marked_by = '');

SELECT COUNT(*) AS backfilled
FROM docs
WHERE superseded_by IS NOT NULL AND superseded_marked_by = '未记录（该列上线前产生）';
