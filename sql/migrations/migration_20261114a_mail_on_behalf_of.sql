SET NAMES utf8mb4;

-- ============================================================
-- 2026-11-14 信箱代发（阁主 W-20261114-09：派单信发件人支持「越哥」）
--
-- 为什么要这一列，而不是把 from_role 写成「越哥」：
--   1. 回信会死。sendDetailed 的 inReplyTo 分支把**父消息发件人**当作回信收件人，
--      且刻意不校验 ACTIVE 名单（防止老线程卡死）。父消息 from_role=越哥 时，
--      收件哨兵的回信会写成 to_role=越哥 —— 越哥没有信箱，回执静默丢失。
--   2. 审计会断。分不清「越哥亲手发的」和「阁主代发的」。
--   3. 历史会漂。老信的 from_role 已是阁主，新语义只能落在新列。
--   所以：from_role 永远是真实发件人（路由 + 审计），on_behalf_of 只承担展示与追责。
--
-- 语义：NULL = 不是代发（老数据与不传 onBehalfOf 的调用都是 NULL，行为零变化）。
--
-- 幂等性：MySQL 5.7（线上 5.7.44）没有 ADD COLUMN IF NOT EXISTS，先查 information_schema，
--   列不存在才 ALTER。可重复执行：重跑不报错、不改任何已有数据。
--   deploy.sh 还按 schema_migrations 的 md5 只跑一次，这里是第二道保险。
--   ⚠️ 本迁移**没有在线上执行过**（本单授权位：发版=否），随下一次部署运行。
-- ============================================================

SET @schema := DATABASE();

SET @exists := (SELECT COUNT(*) FROM information_schema.COLUMNS
                WHERE TABLE_SCHEMA = @schema
                  AND TABLE_NAME = 'mail_messages'
                  AND COLUMN_NAME = 'on_behalf_of');
SET @ddl := IF(@exists = 0,
    'ALTER TABLE mail_messages ADD COLUMN on_behalf_of VARCHAR(32) NULL COMMENT ''代发对象（如 越哥）；NULL=不是代发，真实发件人仍在 from_role'' AFTER from_role',
    'DO 0');
PREPARE stmt FROM @ddl;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

-- 自证：列必须在（缺列会让部署日志一眼看见，而不是等运行期 Unknown column）
SELECT COLUMN_NAME, COLUMN_TYPE, IS_NULLABLE, COLUMN_DEFAULT
FROM information_schema.COLUMNS
WHERE TABLE_SCHEMA = @schema
  AND TABLE_NAME = 'mail_messages'
  AND COLUMN_NAME = 'on_behalf_of';
