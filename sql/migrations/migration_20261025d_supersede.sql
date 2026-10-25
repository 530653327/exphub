-- ============================================================
-- D1 施工包 1+3：取代链 + 整理候选队列（2026-10-25）
--
-- 背景：#631 D2 已确认「记忆的权威副本仍在 ExpHub」，于是 2.0 的整理动作
--      必须全部能从 MCP 做。整理产出两种结果：
--        ① 谁取代谁（指针，只存一列，反向关系靠查询推导，避免双写漂移）
--        ② 可能存在重复的候选对（需要人判断，判断前不能动数据）
--
-- 设计要点：
--   · superseded_by 只存在「被取代」的那条上（单一口径）。
--     「本条取代了谁」用 WHERE superseded_by = id 反查，不另存一列 ——
--     存两列必然出现「A 说被 B 取代，B 的列表里没有 A」这种漂移。
--   · review_candidates 的 (doc_a, doc_b) 建唯一键：同一对只入队一次。
--     已判定「不同」的对再次被发现时不重新入队，否则每周都会重复打扰。
--   · 只标记、不合并、不删除。合并错了不可逆，比不整理更糟。
-- 幂等：重复执行不会报错。
-- ============================================================

DROP PROCEDURE IF EXISTS sp_exphub_add_supersede;
DELIMITER $$
CREATE PROCEDURE sp_exphub_add_supersede()
BEGIN
    IF NOT EXISTS (SELECT 1 FROM information_schema.COLUMNS
                   WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'docs'
                     AND COLUMN_NAME = 'superseded_by') THEN
        ALTER TABLE docs ADD COLUMN superseded_by BIGINT DEFAULT NULL
            COMMENT '被哪条经验取代（本条的取代者 ID）。NULL=未被取代';
    END IF;

    IF NOT EXISTS (SELECT 1 FROM information_schema.STATISTICS
                   WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'docs'
                     AND INDEX_NAME = 'idx_superseded_by') THEN
        ALTER TABLE docs ADD INDEX idx_superseded_by (superseded_by);
    END IF;
END$$
DELIMITER ;

CALL sp_exphub_add_supersede();
DROP PROCEDURE sp_exphub_add_supersede;

CREATE TABLE IF NOT EXISTS review_candidates (
    id           BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键',
    doc_a        BIGINT       NOT NULL COMMENT '候选对主体（通常较旧）',
    doc_b        BIGINT       NOT NULL COMMENT '候选对对照（通常较新）',
    score        DECIMAL(6,4) DEFAULT NULL COMMENT '相似度得分 0~1，仅用于排序，不是判决',
    reasons      VARCHAR(500) DEFAULT NULL COMMENT '判为相似的理由，如「标签交集:3 标题重叠:0.62」',
    source       VARCHAR(20)  DEFAULT 'MANUAL' COMMENT 'L0_CREATE/L1_DAILY/L2_WEEKLY/MANUAL',
    status       VARCHAR(20)  NOT NULL DEFAULT 'PENDING'
                 COMMENT 'PENDING 待判断 / DUPLICATE 确为重复 / SUPERSEDED 已标取代 / DISTINCT 判为不同 / SKIPPED 忽略',
    note         VARCHAR(500) DEFAULT NULL COMMENT '判断说明（谁判的、依据什么）',
    resolved_by  VARCHAR(100) DEFAULT NULL COMMENT '处理人（助手代号）',
    created_at   DATETIME     DEFAULT CURRENT_TIMESTAMP COMMENT '入队时间',
    resolved_at  DATETIME     DEFAULT NULL COMMENT '处理时间',
    PRIMARY KEY (id),
    UNIQUE KEY uk_pair (doc_a, doc_b),
    KEY idx_status (status),
    KEY idx_doc_a (doc_a),
    KEY idx_doc_b (doc_b)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='记忆整理候选对（D1：只给人判断，不自动改数据）';

-- 验证
SELECT COLUMN_NAME, COLUMN_TYPE, COLUMN_COMMENT
FROM information_schema.COLUMNS
WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'docs' AND COLUMN_NAME = 'superseded_by';

SELECT COUNT(*) AS review_candidates_ready
FROM information_schema.TABLES
WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'review_candidates';
