SET NAMES utf8mb4;

-- ============================================================
-- 2026-11-09 信箱消息的授权链元数据（阁主线程 T261109035247-364）
--
-- 为什么要这三列：
--   #1556 / #1550 的结论是忆阁只能做「每线程发送预算」，做不到字面意义的
--   「授权链深度 N 跳」硬拦。原因两条都在服务端：
--     1. mail_send 没有授权链字段；
--     2. 唤醒发出的信和阁主本人发的信，from_role 都是 gezhu，服务端分不出来。
--   所以由调用方发信时自报「属于哪条链、第几跳、是不是自动回合」，服务端只记录与回显。
--
-- 幂等性：
--   MySQL 5.7（线上 5.7.44）没有 ADD COLUMN IF NOT EXISTS，所以先查 information_schema，
--   列不存在才 ALTER。可重复执行：重跑不报错、不改任何已有数据。
--   deploy.sh 还按 schema_migrations 的 md5 只跑一次，这里是第二道保险。
--
-- 老数据语义（不传这三个参数的调用也一样）：
--   chain_id = NULL（不属于任何授权链）、auto_flag = 0（人工）、chain_depth = 0（链根/无链）。
-- ============================================================

SET @schema := DATABASE();

-- 1) chain_id：授权链根标识
SET @exists := (SELECT COUNT(*) FROM information_schema.COLUMNS
                WHERE TABLE_SCHEMA = @schema
                  AND TABLE_NAME = 'mail_messages'
                  AND COLUMN_NAME = 'chain_id');
SET @ddl := IF(@exists = 0,
    'ALTER TABLE mail_messages ADD COLUMN chain_id VARCHAR(64) NULL COMMENT ''授权链根标识；NULL=不属于任何授权链'' AFTER config_source_id',
    'DO 0');
PREPARE stmt FROM @ddl;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

-- 2) auto_flag：是否自动回合发出（列名不叫 auto —— AUTO 是 AUTO_INCREMENT 的前缀关键字）
SET @exists := (SELECT COUNT(*) FROM information_schema.COLUMNS
                WHERE TABLE_SCHEMA = @schema
                  AND TABLE_NAME = 'mail_messages'
                  AND COLUMN_NAME = 'auto_flag');
SET @ddl := IF(@exists = 0,
    'ALTER TABLE mail_messages ADD COLUMN auto_flag TINYINT(1) NOT NULL DEFAULT 0 COMMENT ''是否自动回合发出：1=自动，0=人工'' AFTER chain_id',
    'DO 0');
PREPARE stmt FROM @ddl;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

-- 3) chain_depth：授权链当前跳数（0 = 链根）
SET @exists := (SELECT COUNT(*) FROM information_schema.COLUMNS
                WHERE TABLE_SCHEMA = @schema
                  AND TABLE_NAME = 'mail_messages'
                  AND COLUMN_NAME = 'chain_depth');
SET @ddl := IF(@exists = 0,
    'ALTER TABLE mail_messages ADD COLUMN chain_depth INT NOT NULL DEFAULT 0 COMMENT ''授权链第几跳，0=链根/无链'' AFTER auto_flag',
    'DO 0');
PREPARE stmt FROM @ddl;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

-- 自证：三列必须都在（缺一列就让部署日志里一眼看见，而不是等运行期 Unknown column）
SELECT COLUMN_NAME, COLUMN_TYPE, IS_NULLABLE, COLUMN_DEFAULT
FROM information_schema.COLUMNS
WHERE TABLE_SCHEMA = @schema
  AND TABLE_NAME = 'mail_messages'
  AND COLUMN_NAME IN ('chain_id', 'auto_flag', 'chain_depth')
ORDER BY ORDINAL_POSITION;
