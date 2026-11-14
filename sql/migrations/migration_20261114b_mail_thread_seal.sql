SET NAMES utf8mb4;

-- ============================================================
-- 2026-11-14 信箱线程封存 + 唤醒去重（阁主 W-20261114-12 / 线程 T261114142452-739）
--
-- 要治的病（W-06 一单产生 10 封回执、只有 2 封带信息）：
--   1. 一单散在多根线程上，单到待验收后旧线程的尾信被重新扫描唤醒，对方照铁律 2 每封必回，
--      于是产生「已确认、本轮空操作」这类零信息回执。
--   2. 同一封信在异常重扫时被二次唤醒。
--
-- 本脚本只做加列 / 建表 / 加索引，**不删不改任何已有数据**：
--   1. mail_messages.order_no —— 发信时从正文首部「单号: W-YYYYMMDD-NN」解析后落库。
--      ⚠️ 只对**新信**生效，历史信不回填（回填属于「改已有数据」，本单明确禁止）。
--      历史线程要封存时由调用方显式给 threadId（见 mail_thread 的 sealThreads 参数）。
--   2. mail_thread_states —— 线程封存状态（按 threadId 一行，state=SEALED/OPEN）。
--   3. mail_thread_state_log —— 封存/解锁留痕（只追加：谁、何时、哪根线程、什么原因）。
--   4. mail_wake_offers —— 唤醒去重账本（单号 + clientMsgId → 首次/最近一次进入唤醒源的时刻）。
--
-- 幂等：列/索引先查 information_schema 再 ALTER；表一律 CREATE IF NOT EXISTS。
--   deploy.sh 还按 schema_migrations 的 md5 只跑一次，这是第二道保险。
-- ============================================================

SET @schema := DATABASE();

-- ------------------------------------------------------------
-- 1) mail_messages.order_no（从正文首部解析的委托单号）
-- ------------------------------------------------------------
SET @exists := (SELECT COUNT(*) FROM information_schema.COLUMNS
                WHERE TABLE_SCHEMA = @schema
                  AND TABLE_NAME = 'mail_messages'
                  AND COLUMN_NAME = 'order_no');
SET @ddl := IF(@exists = 0,
    'ALTER TABLE mail_messages ADD COLUMN order_no VARCHAR(32) NULL COMMENT ''所属委托单号（发信时从正文首部「单号: W-YYYYMMDD-NN」解析）；NULL=不属于任何单或历史信未回填'' AFTER on_behalf_of',
    'DO 0');
PREPARE stmt FROM @ddl;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

-- ------------------------------------------------------------
-- 2) 按单号查线程的索引（封存时「该单全部线程」靠它，不能只封主线程）
-- ------------------------------------------------------------
SET @idx := (SELECT COUNT(*) FROM information_schema.STATISTICS
             WHERE TABLE_SCHEMA = @schema
               AND TABLE_NAME = 'mail_messages'
               AND INDEX_NAME = 'idx_mail_order');
SET @ddl := IF(@idx = 0,
    'ALTER TABLE mail_messages ADD KEY idx_mail_order (order_no, id)',
    'DO 0');
PREPARE stmt FROM @ddl;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

-- ------------------------------------------------------------
-- 3) mail_thread_states —— 线程封存状态
--
-- 语义：
--   state='SEALED' → 该线程不进唤醒源（对方不会再被这封信叫起来），但信照常入库、
--                    在信箱默认列表 / includeRead / mail_thread 里照常可见，并标 🔒。
--   state='OPEN'   → 人工解锁后的状态。**不删行**：行本身就是审计证据，
--                    删了以后「这根线程曾经被封存过、后来被谁解锁」就查不到了。
-- order_no 冗余存一份：老线程（历史信没有 order_no）显式封存时也能按单号查出来。
-- ------------------------------------------------------------
CREATE TABLE IF NOT EXISTS mail_thread_states (
    thread_id    VARCHAR(32)  NOT NULL                COMMENT '线程号（与 mail_messages.thread_id 对齐）',
    order_no     VARCHAR(32)  NULL                    COMMENT '所属委托单号；NULL=未标注（历史线程显式封存时可能没有）',
    state        VARCHAR(16)  NOT NULL DEFAULT 'SEALED' COMMENT 'SEALED=已封存（不进唤醒源）/ OPEN=已解锁',
    seal_from_id BIGINT       NOT NULL DEFAULT 0      COMMENT '封存水位：id 大于它的消息不再唤醒；id 小于等于它的（含触发封存的那封回执）仍唤醒一次',
    sealed_at    DATETIME     NULL                    COMMENT '最近一次封存时刻',
    sealed_by    VARCHAR(64)  NULL                    COMMENT '谁封的（角色名 / auto:receipt-delivered / 后台账号）',
    seal_reason  VARCHAR(255) NULL                    COMMENT '封存原因（自动封存会写明触发信号）',
    unsealed_at  DATETIME     NULL                    COMMENT '最近一次解锁时刻',
    unsealed_by  VARCHAR(64)  NULL                    COMMENT '谁解的',
    unseal_note  VARCHAR(255) NULL                    COMMENT '解锁原因',
    updated_at   DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (thread_id),
    KEY idx_mts_order (order_no, state)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='信箱线程封存状态（SEALED 不进唤醒源；不删行，行即审计）';

-- ------------------------------------------------------------
-- 4) mail_thread_state_log —— 封存/解锁留痕（只追加）
--
-- 为什么单独一张表：状态表只有「当前值」，而「谁在什么时候解过锁」必须能按时间回看
-- （W-06 的教训就是事后分不清哪封信是重扫唤醒、哪封是人主动发的）。
-- ------------------------------------------------------------
CREATE TABLE IF NOT EXISTS mail_thread_state_log (
    id         BIGINT      PRIMARY KEY AUTO_INCREMENT,
    thread_id  VARCHAR(32) NOT NULL                COMMENT '线程号',
    order_no   VARCHAR(32) NULL                    COMMENT '所属委托单号（可为空）',
    action     VARCHAR(16) NOT NULL                COMMENT 'SEAL=封存 / UNSEAL=解锁',
    actor      VARCHAR(64) NOT NULL                COMMENT '操作者（角色名 / auto:receipt-delivered / 后台账号）',
    note       VARCHAR(500) NULL                   COMMENT '说明（为什么封/为什么解）',
    created_at DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    KEY idx_mtsl_thread (thread_id, id),
    KEY idx_mtsl_order (order_no, id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='信箱线程封存/解锁留痕（只追加，不回改）';

-- ------------------------------------------------------------
-- 5) mail_wake_offers —— 唤醒去重账本
--
-- 键 = 单号 + clientMsgId。**为什么两个一起**：clientMsgId 本身全局唯一（uk_mail_client），
-- 加上单号是为了让「同一单里的同一封信」这件事在库里可读、可按单审计。
-- 为什么要有窗口：见 MailThreadSealService 的注释 —— 无窗口会让「提供过一次但回合崩了」的信
-- 永久不再唤醒（静默丢信），窗口化既能挡住异常重扫的连环唤醒，又让崩掉的回合过一会儿能重试。
-- ------------------------------------------------------------
CREATE TABLE IF NOT EXISTS mail_wake_offers (
    dedupe_key      VARCHAR(160) NOT NULL           COMMENT 'order_no|client_msg_id',
    order_no        VARCHAR(32)  NOT NULL           COMMENT '委托单号',
    client_msg_id   VARCHAR(64)  NOT NULL           COMMENT '幂等键',
    thread_id       VARCHAR(32)  NOT NULL           COMMENT '线程号',
    message_id      BIGINT       NOT NULL           COMMENT '消息ID',
    first_offered_at DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '首次进入唤醒源时刻',
    last_offered_at  DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '最近一次进入唤醒源时刻',
    offer_count     INT          NOT NULL DEFAULT 1 COMMENT '累计进入唤醒源次数（>1 表示过了去重窗口后重试过）',
    PRIMARY KEY (dedupe_key),
    KEY idx_mwo_msg (message_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='唤醒去重账本（单号+clientMsgId，窗口内不二次唤醒）';

-- 自证：列、索引、三张表都必须到位（缺一样就让部署日志一眼看见，而不是等运行期报错）
SELECT 'column' AS kind, COLUMN_NAME AS name, COLUMN_TYPE AS detail
FROM information_schema.COLUMNS
WHERE TABLE_SCHEMA = @schema AND TABLE_NAME = 'mail_messages' AND COLUMN_NAME = 'order_no';

SELECT 'index' AS kind, INDEX_NAME AS name, GROUP_CONCAT(COLUMN_NAME ORDER BY SEQ_IN_INDEX) AS detail
FROM information_schema.STATISTICS
WHERE TABLE_SCHEMA = @schema AND TABLE_NAME = 'mail_messages' AND INDEX_NAME = 'idx_mail_order'
GROUP BY INDEX_NAME;

SELECT 'table' AS kind, TABLE_NAME AS name, TABLE_COMMENT AS detail
FROM information_schema.TABLES
WHERE TABLE_SCHEMA = @schema
  AND TABLE_NAME IN ('mail_thread_states', 'mail_thread_state_log', 'mail_wake_offers');
