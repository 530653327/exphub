-- 2026-10-29 候选判定审计流水表
--
-- 为什么建：原来把「撤回历史」拼进 review_candidates.note，一次往返约 +184 字，
-- varchar(2000) 只够 8 次，第 9 次必然截断 —— 根因是拿标量字段装无限增长的审计流。
-- 现在 note 只存「当前判定理由」（一判定一覆盖，长度天然有界），
-- 每一次动作（duplicate/superseded/distinct/skip/reopen）在这里追加一行，note 用 TEXT 不截断。
--
-- 幂等：IF NOT EXISTS。

CREATE TABLE IF NOT EXISTS candidate_resolution_log (
    id           BIGINT       NOT NULL AUTO_INCREMENT,
    candidate_id BIGINT       NOT NULL COMMENT 'review_candidates.id',
    action       VARCHAR(20)  NOT NULL COMMENT 'duplicate/superseded/distinct/skip/reopen',
    prev_status  VARCHAR(20)  NULL     COMMENT '动作前状态（reopen 时就是被撤回的那个状态）',
    new_status   VARCHAR(20)  NULL     COMMENT '动作后状态',
    operator     VARCHAR(100) NOT NULL COMMENT '操作人署名',
    note         TEXT         NULL     COMMENT '本次说明，不截断',
    created_at   DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    KEY idx_candidate (candidate_id, id),
    KEY idx_created (created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='候选判定审计流水（只追加，不修改）';
