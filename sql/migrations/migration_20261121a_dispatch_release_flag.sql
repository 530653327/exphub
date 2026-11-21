-- ============================================================================
-- 派单发版标记 release_flag（2026-11-21 派单 W-20261121-000014）
--
-- 四态（与九态状态机完全独立，status 一列都不改）：
--   NULL / '' = 未标记（默认）   NEED = 需发版   RELEASED = 已发版   NONE = 无需发版
--
-- 两条硬约束：
--   1. 不加进 header.status、不改状态转移矩阵 —— 这是正交的标记位，不是状态。
--   2. AI 侧（MCP）只能写 NEED / NONE；RELEASED 只能后台由越哥点按钮写。
--      权限分离在 Service 层，不在本脚本里 —— 脚本只负责加列 + 一次历史回填。
--
-- 历史回填口径（不瞎猜）：
--   10 张待验收单里只有 W-20261121-000010 能确证「已发版且线上生效」——
--   它的 version=2.5.18，线上 /exphub/health 当时实测就是 2.5.18。
--   其余 9 张：auth_release=0 的单「没勾发版」不等于确认无需发版；
--   auth_release=1 的忆阁单（3.5.12 / 3.5.13）ExpHub 侧查不到忆阁线上版本。
--   一律留 NULL，由越哥在后台按实际情况点，符合「判断不了留 NULL，不要瞎猜」。
--
-- MySQL 5.7.44 不支持 LOCK=NONE / ALGORITHM=INPLACE；DDL 由 deploy.sh 在低峰自动执行。
-- ============================================================================

ALTER TABLE dispatch_header
  ADD COLUMN release_flag VARCHAR(16) NULL
    COMMENT '发版标记（与九态状态机独立）：NULL/空=未标记, NEED=需发版, RELEASED=已发版, NONE=无需发版',
  ADD KEY idx_dispatch_release_flag (release_flag, status, id);

-- 历史回填：带 version 双条件，幂等；只回填有实证的那一张。
UPDATE dispatch_header SET release_flag = 'RELEASED'
WHERE order_no = 'W-20261121-000010'
  AND version = '2.5.18'
  AND (release_flag IS NULL OR release_flag = '');

-- 回读自证：列与索引是否都建上（值为 1/1 才算成功），并列出已回填的单
SELECT
  SUM(COLUMN_NAME = 'release_flag') AS release_flag_column_exists,
  SUM(INDEX_NAME = 'idx_dispatch_release_flag') AS release_flag_index_exists
FROM information_schema.STATISTICS
WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'dispatch_header';

SELECT order_no, release_flag FROM dispatch_header WHERE release_flag IS NOT NULL AND release_flag <> '';
