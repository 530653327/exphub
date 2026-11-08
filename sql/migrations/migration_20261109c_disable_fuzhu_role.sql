SET NAMES utf8mb4;

-- ============================================================
-- 2026-11-09 停用「夫诸」信箱角色（阁主线程 T261109062718-346 / #305）
--
-- 背景：越哥 2026-11-09 06:00 在面板保存 roles.json 时已把 fuzhu 从哨兵角色表删掉
--   （穷奇 #281 核查记录见 #1615）。哨兵不再轮询夫诸的信箱，而 mail_roles 里它仍是
--   ACTIVE —— 信能寄进去、永远没人读，这正是 P9 要治的静默丢信，只是这次是配置造成的，
--   不是拼错造成的。
--
-- 口径（阁主 #305 三条）：
--   1. **只动「夫诸」这一行。** mail_roles 是「可投递地址清单」，哨兵 roles.json 是
--      「有人在轮询的角色清单」，两个口径不是一回事；把非 ACTIVE 的行整表扫一遍，
--      会误停别人还在用的地址。
--   2. 停用不是删数据：历史线程与正文都还在，mail_thread 按 threadId 照常能读回来。
--   3. 表是运行时读的（MailRoleCatalog，TTL 30 秒），改完最多半分钟生效；
--      不需要发版，也不要为了验证重启 JVM。
--
-- 幂等：WHERE 带 status <> 'DISABLED'，第二次执行匹配 0 行。
--
-- 回滚（就这一句，需要时取消注释后执行；本文件本身不含可执行的反向语句）：
--   UPDATE mail_roles SET status = 'ACTIVE', updated_by = 'rollback:gezhu#305', updated_at = updated_at
--   WHERE role = '夫诸';
--
-- ⚠️ updated_at 显式写回自身：mail_roles.updated_at 带 ON UPDATE CURRENT_TIMESTAMP，
--    不写回会把「上一次变更时间」静默刷成执行时刻（OnUpdateColumnGuardTest 的红线）。
-- ============================================================

UPDATE mail_roles
SET status     = 'DISABLED',
    updated_by = 'migration_20261109c',
    updated_at = updated_at
WHERE role = '夫诸'
  AND status <> 'DISABLED';

-- 自证 1：目标行。期望恰好 1 行、status=DISABLED。
--   返回空集 = 这行不存在（或连错了库），人工核对，不要当成「已经是停用状态」。
SELECT role, display_name, status, source, project_id, updated_by, updated_at
FROM mail_roles
WHERE role = '夫诸';

-- 自证 2：全表状态计数。期望 ACTIVE=11、DISABLED=1，其余 0 —— 用来证明「只动了这一行」。
SELECT status, COUNT(*) AS cnt
FROM mail_roles
GROUP BY status
ORDER BY status;

-- 自证 3：别名 fuzhu 仍指向夫诸（**保留是刻意的**）。
--   拼音入参仍要归一到这里，才好被投递校验（只认 status='ACTIVE'）明确拒掉；
--   删掉别名只会让它变成「不认识的代号」，把「已停用」和「写错了」混成一句话。
--   因此这里返回 1 行（target_status=DISABLED）是预期结果，不是缺陷。
SELECT a.alias, a.role, r.status AS target_status
FROM mail_role_aliases a
LEFT JOIN mail_roles r ON r.role = a.role
WHERE a.role = '夫诸';
