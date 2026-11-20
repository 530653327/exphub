-- ============================================================================
-- 派单操作流水事件表 dispatch_event（2026-11-20 派单 W-20261120-000025）
--
-- 为什么单独一张表：dispatch_header 只存当前状态，dispatch_item 存每轮唤醒记录，
-- 谁在什么时候把状态从 A 推到 B 没有独立记录 —— 驳回一次、来回两轮，前面的痕迹被覆盖，
-- 事后拼不出来。本表一动作一行，供忆阁右栏显示成控制台日志（角色名/单号/状态分字段上色）。
--
-- 三条硬约束（写死，不改）：
--   1. 只追加：运行期只有 insert，业务代码里不许有 update / delete。取消整单不删事件，只加 CANCEL。
--   2. 结构化字段：角色名(actor)/单号(order_no)/状态(from_status,to_status)各占一列，
--      不要拼成一句话给前端 —— 前端要按字段上色。
--   3. 幂等只用于历史回填：uk_dispatch_event_idem(order_no,event_type,created_at)，
--      重复跑回填不新增行。运行期动作不做幂等去重（真发生两次就留两条）。
--
-- 事件类型(11)：CREATE / REVIEW_PASS / REVIEW_REJECT / CLAIM / NEED_CONFIRM / REPLY /
--               SUBMIT_ACCEPT / ACCEPT_PASS / ACCEPT_REJECT / CANCEL / TERMINATE
-- actor_type：HUMAN / AI
-- 幂等：information_schema 判表存在（MySQL 5.7.44 无 CREATE TABLE IF NOT EXISTS 的列级校验补充）。
-- ============================================================================

CREATE TABLE IF NOT EXISTS dispatch_event (
  id           BIGINT        NOT NULL AUTO_INCREMENT,
  order_no     VARCHAR(32)   NOT NULL COMMENT '单号 W-YYYYMMDD-NNNNNN，前端按此精确筛+上色',
  event_type   VARCHAR(24)   NOT NULL COMMENT 'CREATE/REVIEW_PASS/REVIEW_REJECT/CLAIM/NEED_CONFIRM/REPLY/SUBMIT_ACCEPT/ACCEPT_PASS/ACCEPT_REJECT/CANCEL/TERMINATE',
  from_status  VARCHAR(16)   NULL     COMMENT '转移前状态；建单为 NULL',
  to_status    VARCHAR(16)   NOT NULL COMMENT '转移后状态，前端按此上色',
  actor        VARCHAR(32)   NOT NULL COMMENT '操作者：越哥/阁主/穷奇/白泽/应龙…（角色正名）',
  actor_type   VARCHAR(8)    NOT NULL COMMENT 'HUMAN / AI，前端区分人和助手',
  note         TEXT          NULL     COMMENT '可选：驳回理由/终止原因/结论摘要',
  created_at   DATETIME      NOT NULL COMMENT '事件发生时间（显式写入，不用 now 自动生成，回填要写历史时刻）',
  PRIMARY KEY (id),
  KEY idx_dispatch_event_order_time (order_no, created_at, id),
  UNIQUE KEY uk_dispatch_event_idem (order_no, event_type, created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='派单操作流水：一动作一行，只追加不修改';

SELECT COUNT(*) AS dispatch_event_exists
FROM information_schema.TABLES
WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'dispatch_event';
