SET NAMES utf8mb4;

-- ============================================================
-- 2026-11-09 收尾：把「夫诸」角色从角色清单里彻底移除（阁主线程 T261109120139-277 / #332）
--
-- 演变（不要只看文件名，文件名保留是为了 schema_migrations 里那条已执行记录不变成孤儿）：
--   1. 06:0x 本文件首版是 UPDATE status='DISABLED'（阁主 #305）——「停用但保留行」。
--   2. 11:5x 线上按阁主 #324 / 线程 T261109115251-098 直接 DELETE 了这两行（含别名）。
--   3. 12:0x 越哥定「从此不许再出现，一个字节不留」：种子（sql/init.sql 与
--      migration_20261109b_mail_roles.sql）已同步删掉这个角色与它的别名，
--      本文件改成**幂等 DELETE**，给存量库兜底收敛。
--
-- 为什么保留文件而不是删掉：
--   线上 schema_migrations 已有本文件名的一条记录。删文件不会删掉那条记录，
--   只会留下一条指向不存在文件的孤儿账（要清它就得改生产库，超出本单授权）。
--   保留文件 + 改成 DELETE，下次部署按 md5 变化重跑一次，匹配 0 行即可。
--
-- 行为说明（别误读成「删了历史」）：
--   - 只删 mail_roles / mail_role_aliases 里的**配置行**；
--   - mail_messages / mail_cursors 里的历史消息与游标**一律不动**（那是事实）；
--   - mail_thread 按 threadId 仍能读回旧线程全文。
--
-- 幂等：DELETE 天然可重跑；新库（种子已无此角色）执行后影响 0 行。
-- ============================================================

DELETE FROM mail_role_aliases
WHERE alias = 'fuzhu'
   OR role = '夫诸';

DELETE FROM mail_roles
WHERE role = '夫诸';

-- 自证 1：目标角色与别名都必须为 0 行（两行都应返回空集）。
SELECT role, display_name, status FROM mail_roles WHERE role = '夫诸';
SELECT alias, role FROM mail_role_aliases WHERE alias = 'fuzhu' OR role = '夫诸';

-- 自证 2：全表计数。期望 mail_roles=11、mail_role_aliases=18（11 条拼音 + 7 条历史错拼）。
SELECT 'mail_roles' AS tbl, COUNT(*) AS cnt FROM mail_roles
UNION ALL
SELECT 'mail_role_aliases', COUNT(*) FROM mail_role_aliases;

-- 自证 3：状态分布。期望只有 ACTIVE=11，其余 0。
SELECT status, COUNT(*) AS cnt
FROM mail_roles
GROUP BY status
ORDER BY status;
