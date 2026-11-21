-- ============================================================================
-- 派单发版证据列 dispatch_event.evidence（2026-11-21 派单 W-20261121-000017）
--
-- 背景：W-20261121-000014 把 release_flag 的 RELEASED 写入收紧成「只能后台按钮」，
--       实际卡死——哨兵已发版、阁主握着 commit/线上版本回读却在验收回合标不了。
-- 本单把红线从「AI 一律不许记录已发版」调整为「AI 不许没证据地宣布发版完成」：
--       MCP dispatch_act(mark_release) 允许写 RELEASED，但 evidence 必填、空证据明拒。
--
-- 本脚本只负责加列（证据原文单独成列，不与 note 拼句子，前端按字段核对）：
--   evidence TEXT NULL —— 发版证据原文（commit sha / 线上版本回读 / health UP）。
-- 权限校验在 Service 层（markReleaseFlagAi），不在本脚本里。
--
-- MySQL 5.7.44 不支持 LOCK=NONE / ALGORITHM=INPLACE；DDL 由 deploy.sh 在低峰自动执行。
-- ============================================================================

ALTER TABLE dispatch_event
  ADD COLUMN evidence TEXT NULL
    COMMENT '发版证据原文：AI 标 RELEASED 时必填（commit/线上版本/health），供后台核对';

-- 回读自证：evidence 列建上（值为 1 才算成功）
SELECT SUM(COLUMN_NAME = 'evidence') AS evidence_column_exists
FROM information_schema.COLUMNS
WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'dispatch_event';
