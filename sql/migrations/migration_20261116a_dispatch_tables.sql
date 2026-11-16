-- ============================================================
-- 派单系统：header + item 两张表（2026-11-16 越哥定稿，设计见 #3308）
--
-- 背景：旧协作信箱（mail_* 七张表）已于 2026-11-16 整体删除，commit 7b80345。
--      新系统不再做「信箱」，改成「派单 + 回合制」：
--      header 一单一行，item 一次模型唤醒一行。
--
-- 三条硬设计（不要改）：
--   1. 全系统只有两方（阁主 ↔ 某个哨兵）+ 一个人类（越哥）。所以 item 不存接收方，
--      由「创建人是否等于 header.assignee」推算。
--   2. 查询零副作用。dispatch_list 无论调多少次都不写任何账本 —— 旧信箱死在这里。
--   3. 授权位 11 个独立字段，建单后锁死不可改（改权限只能取消重开）。
--
-- 状态码（库里存英文，回执显中文）：
--   header: DRAFT / REVIEWING / READY / RUNNING / CONFIRMING / VERIFYING / DONE / CANCELLED
--   item  : RUNNING / DONE / ERROR
-- 指令码：EXEC / CONFIRM / TERMINATE
-- ============================================================

CREATE TABLE IF NOT EXISTS dispatch_header (
  id                  BIGINT        NOT NULL AUTO_INCREMENT,
  order_no            VARCHAR(32)   NOT NULL COMMENT '单号 W-YYYYMMDD-NNNNNN，序号留 6 位（旧的 3 位限制会截断封错单）',
  origin              VARCHAR(8)    NOT NULL COMMENT 'HUMAN / AI —— 决定 CONFIRMING 时球给谁（人工单给越哥，AI 单给阁主）',
  creator             VARCHAR(32)   NOT NULL COMMENT '创建人：越哥 或 角色正名',
  created_at          DATETIME      NOT NULL,
  status              VARCHAR(16)   NOT NULL COMMENT 'DRAFT/REVIEWING/READY/RUNNING/CONFIRMING/VERIFYING/DONE/CANCELLED',
  title               VARCHAR(200)  NOT NULL,
  description         MEDIUMTEXT    NOT NULL COMMENT '内容描述；审核绑定的是这一份内容',
  assignee            VARCHAR(32)   NOT NULL COMMENT '指派的哨兵（角色正名），建单后不改',
  accepter            VARCHAR(32)   NULL     COMMENT '接单人，认领时回填',
  accepted_at         DATETIME      NULL,
  reviewer            VARCHAR(32)   NULL     COMMENT '审核人（系统里只有越哥一个人）',
  reviewed_at         DATETIME      NULL,
  verifier            VARCHAR(32)   NULL     COMMENT '验收人（同上）',
  verified_at         DATETIME      NULL,
  verify_note         VARCHAR(1000) NULL     COMMENT '验收结论；未通过时必填驳回理由',
  remark              VARCHAR(1000) NULL     COMMENT '备注',
  cancel_reason       VARCHAR(500)  NULL,
  priority            TINYINT       NOT NULL DEFAULT 3 COMMENT '1=高 3=中 5=低',
  project_id          BIGINT        NULL     COMMENT '项目归属，可空',
  updated_at          DATETIME      NOT NULL,
  auth_modify_code    TINYINT(1)    NOT NULL DEFAULT 0 COMMENT '授权：修改代码',
  auth_commit         TINYINT(1)    NOT NULL DEFAULT 0 COMMENT '授权：commit',
  auth_push           TINYINT(1)    NOT NULL DEFAULT 0 COMMENT '授权：push',
  auth_release        TINYINT(1)    NOT NULL DEFAULT 0 COMMENT '授权：发版',
  auth_data_write     TINYINT(1)    NOT NULL DEFAULT 0 COMMENT '授权：数据写入',
  auth_migration      TINYINT(1)    NOT NULL DEFAULT 0 COMMENT '授权：迁移',
  auth_credential     TINYINT(1)    NOT NULL DEFAULT 0 COMMENT '授权：凭证生成',
  auth_prod_config    TINYINT(1)    NOT NULL DEFAULT 0 COMMENT '授权：生产配置',
  auth_security_group TINYINT(1)    NOT NULL DEFAULT 0 COMMENT '授权：安全组',
  auth_firewall       TINYINT(1)    NOT NULL DEFAULT 0 COMMENT '授权：防火墙',
  auth_spend          TINYINT(1)    NOT NULL DEFAULT 0 COMMENT '授权：花钱',
  PRIMARY KEY (id),
  UNIQUE KEY uk_dispatch_order_no (order_no),
  KEY idx_dispatch_status (status, id),
  KEY idx_dispatch_assignee (assignee, status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='派单表头：一单一行';

CREATE TABLE IF NOT EXISTS dispatch_item (
  id             BIGINT        NOT NULL AUTO_INCREMENT,
  header_id      BIGINT        NOT NULL,
  order_no       VARCHAR(32)   NOT NULL,
  item_no        VARCHAR(48)   NOT NULL COMMENT 'order_no + 两位序号，如 W-20261116-000001-03',
  seq            INT           NOT NULL COMMENT '第几轮，从 1 开始',
  creator        VARCHAR(32)   NOT NULL COMMENT '越哥 / 阁主 / 哨兵',
  created_at     DATETIME      NOT NULL COMMENT '开工时间（一开工就建 item，不是干完才建）',
  finished_at    DATETIME      NULL,
  command        VARCHAR(12)   NOT NULL COMMENT 'EXEC / CONFIRM / TERMINATE',
  status         VARCHAR(12)   NOT NULL COMMENT 'RUNNING / DONE / ERROR',
  reply_required TINYINT(1)    NOT NULL DEFAULT 0 COMMENT '1=需要回复 0=无需回复',
  conclusion     VARCHAR(2000) NULL COMMENT '结论：给对方看的那一句结果',
  content        MEDIUMTEXT    NULL COMMENT '详细内容',
  client_id      VARCHAR(64)   NULL COMMENT '哪个工位/机器跑的，排查用',
  PRIMARY KEY (id),
  UNIQUE KEY uk_dispatch_item_no (item_no),
  KEY idx_dispatch_item_header (header_id, seq),
  KEY idx_dispatch_item_order (order_no, status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='派单执行记录：一次模型唤醒一行';
