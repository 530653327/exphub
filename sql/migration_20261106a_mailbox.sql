SET NAMES utf8mb4;

-- ============================================================
-- 2026-11-06 协作信箱（mailbox）
-- 设计定稿见 ExpHub #1324（阁主↔烛龙协作信箱⑥，C114–C121 封口）
--
-- 只追加、不回改：消息发出即不可编辑，纠错用一条新消息引用原信。
-- 不进 docs 全文索引，因此不会污染经验搜索的排名。
--
-- 表 1：mail_messages —— 消息本体（线程靠 thread_id 自聚合，不另建线程表）
-- 表 2：mail_cursors  —— 阅读游标（角色 + 线程 -> 读到哪条）。只有 3 列，
--                       单独建表是因为它必须重启不丢；塞进消息表会让「一条消息
--                       被多个角色读」互相覆盖。
-- ============================================================

CREATE TABLE IF NOT EXISTS mail_messages (
    id               BIGINT PRIMARY KEY AUTO_INCREMENT,
    thread_id        VARCHAR(32)  NOT NULL COMMENT '线程号，服务端生成，不接受模型传入',
    in_reply_to      BIGINT       NULL     COMMENT '父消息ID；有值即回信，线程从父消息推导',
    from_role        VARCHAR(32)  NOT NULL COMMENT '发件角色（服务端按配置来源填，不接受模型传入）',
    to_role          VARCHAR(32)  NOT NULL COMMENT '收件角色',
    msg_type         VARCHAR(16)  NOT NULL DEFAULT 'note' COMMENT 'dispatch/receipt/accept/note/fix',
    subject          VARCHAR(200) NOT NULL COMMENT '主题',
    body             MEDIUMTEXT   NOT NULL COMMENT '正文，单条建议不超过 8000 字',
    client_msg_id    VARCHAR(64)  NULL     COMMENT '幂等键：重试不重复发',
    claimed_by       VARCHAR(32)  NULL     COMMENT '认领人；原子更新抢锁，先到先得',
    claimed_at       DATETIME     NULL,
    processed_at     DATETIME     NULL     COMMENT '处理过的时间；只做展示，不参与去重',
    config_source_id VARCHAR(64)  NULL     COMMENT '配置来源标识：证明出自哪份客户端配置，不证明是谁写的',
    created_at       DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE KEY uk_mail_client (client_msg_id),
    KEY idx_mail_to (to_role, id),
    KEY idx_mail_thread (thread_id, id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='助手间信箱消息（只追加，不回改）';

CREATE TABLE IF NOT EXISTS mail_cursors (
    role         VARCHAR(32) NOT NULL COMMENT '角色代号',
    thread_id    VARCHAR(32) NOT NULL COMMENT '线程号',
    last_read_id BIGINT      NOT NULL DEFAULT 0 COMMENT '已读到的最新消息ID',
    updated_at   DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (role, thread_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='信箱阅读游标：推信只认它，未读只是计数';
