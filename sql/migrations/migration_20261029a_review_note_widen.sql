-- 2026-10-29 放宽 review_candidates.note 到 2000 字符
--
-- 为什么：reopen → resolve 往返一次要在 note 里同时装「新说明 + 完整原判定说明」。
-- 500 字符装不下（实测 #17 的撤回记录就有 434 字符），旧说明的结尾会被切掉 ——
-- 而「保留原判定」正是 reopen_candidate 存在的理由，切掉尾巴等于撤回只做到一半。
--
-- 幂等：重复执行无副作用（列已经是 varchar(2000) 时 MODIFY 不改变任何东西）。
-- 只放宽、不缩短，不会截断任何既有数据。

ALTER TABLE review_candidates MODIFY COLUMN note VARCHAR(2000) NULL COMMENT '判断说明（含撤回历史，2026-10-29 由 500 放宽）';
