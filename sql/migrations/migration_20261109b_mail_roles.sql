SET NAMES utf8mb4;

-- ============================================================
-- 2026-11-09 信箱角色清单迁库 · **A+B 合并成一次发版**
--
-- 出处：阁主线程 T261109053740-701（#255）「P9 定案」，越哥 2026-11-09 拍板三条红线：
--   ① 忆阁不直连库；② 忆阁不取、不存、不校验代号，也不做拼错纠正；
--   ③ 名字对不对由 ExpHub 说了算。
--
-- 追加一条（阁主线程 T261109054938-679，越哥同日晚定）：
--   **收件人正名改汉字，拼音降为别名。** 12 个正名：烛龙 / 白泽 / 阁主 / 夫诸 / 貔貅 / 睚眦 /
--   麒麟 / 凤凰 / 重明鸟 / 九尾狐 / 帝江 / 穷奇。对应拼音（zhulong / baize / …）连同历史错拼
--   （zhuolong / piyao / yaci …）**全部是别名**，指向汉字正名。
--   - 兼容期入参汉字和拼音都收，入口统一归一到汉字再入库；
--   - 出口（应答、被拒收件人、候选、名单、错误文案、日志、审计）一律汉字；
--   - 表和约束仍是那两张，**不新建第三张**。
--
-- 为什么 A/B 合成一次（原方案 #1597 分两段）：
--   分两段的唯一理由是「B 段等忆阁读取方式定了再上」。现在忆阁**不读名单**了，
--   B 段没有任何跨仓依赖，再拖一次发版只会让「yml ∪ 历史库」的旧语义多活一轮。
--   回滚 = 重新部署上一版 jar，**不动库**（新表建了也不影响旧代码：旧代码不认识它们）。
--
-- 本脚本做七件事：
--   1. 建表 mail_roles / mail_role_aliases；
--   2. 导入种子：12 个**汉字正名**（SEEDED）；
--   3. 导入别名：12 条拼音 + 7 条历史错拼，全部指向汉字正名；
--   4. 防御：旧版 A 段若被手工执行过（拼音当正名），按别名表把这些行 DISABLED（不删行）；
--   5. 历史数据按别名刷成汉字：mail_messages 收发两侧 + 认领人、mail_cursors 游标；
--      刷不动的（别名表里没有的脏值）原样保留，落第 6 步等人工复核，**不硬猜**；
--   6. 历史出现过的未知名 → OBSERVED + PENDING_REVIEW（等人工复核，**不是**合法来源）；
--   7. 自证 SELECT：状态计数、别名悬空、待复核清单、正名是否仍为纯拼音、
--      历史消息是否仍有别名值、to_role 列的字符集/长度。
--
-- ⚠️ 第 6 条是这次最关键的口径变化：旧语义是「yml ∪ 库里出现过的 role」实时并集，
--   它与「停用」互斥 —— 停用一个角色后，只要历史信里还有该 to_role，并集就把它原地复活；
--   #1459（zhuolong 拼错）也是同一机制的另一面。现在只认 status='ACTIVE'，
--   历史脏代号**先落 PENDING_REVIEW 等人判**，判过了再改 ACTIVE。
--
-- 幂等：两表 CREATE IF NOT EXISTS，数据一律 INSERT IGNORE；历史刷新是按别名表 JOIN 的
--   条件更新，第二次执行匹配 0 行；游标「先复制到汉字、再删拼音」可重跑。
--   deploy.sh 仍按 schema_migrations 的 md5 只跑一次，这是第二道保险。
--
-- 依赖顺序：本文件用到 mail_messages / mail_cursors，由 migration_20261106a_mailbox.sql 建
--   （文件名排序在前）。
-- ============================================================

-- ------------------------------------------------------------
-- 表 1：mail_roles —— 可投递角色（DB 为唯一真源）
--
-- 设计要点：
--   1. 主键就是正名（汉字），不另设自增 ID —— 唯一性天然成立，
--      且与 mail_messages.to_role VARCHAR(32) 对齐（VARCHAR 按**字符**计数，
--      「重明鸟」3 字，utf8mb4 下远小于上限）。
--   2. 停用只改 status，**不删行**：名单本身是审计证据。
--   3. 改名 = 新增一行 + 旧行 DISABLED + 别名表写「旧名 → 新名」，**绝不 UPDATE 主键**；
--      历史信的 to_role 靠别名表刷写/读回。
--   4. 不建外键（与 doc_versions_archive 同口径）：宁可留孤儿，也不让删主表带走证据。
-- ------------------------------------------------------------
CREATE TABLE IF NOT EXISTS mail_roles (
    role         VARCHAR(32)  NOT NULL                COMMENT '角色正名（汉字，如 烛龙/重明鸟）；拼音是别名，与 mail_messages.to_role VARCHAR(32) 对齐',
    display_name VARCHAR(64)  DEFAULT NULL            COMMENT '给人看的名字；正名已是汉字后与本行 role 同值，保留列只为兼容旧后台数据',
    status       VARCHAR(16)  NOT NULL DEFAULT 'ACTIVE' COMMENT 'ACTIVE=可投递 / PENDING_REVIEW=历史脏代号等人工复核 / DISABLED=停用（不删行）',
    source       VARCHAR(16)  NOT NULL DEFAULT 'MANUAL' COMMENT 'MANUAL=后台新增 / SEEDED=随产物种子 / OBSERVED=从历史信一次性导入',
    project_id   BIGINT       DEFAULT NULL            COMMENT '负责的项目ID；NULL=不绑定',
    note         VARCHAR(255) DEFAULT NULL            COMMENT '备注：来源、待复核说明等',
    created_at   DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    created_by   VARCHAR(64)  DEFAULT NULL            COMMENT '谁建的（后台账号 / migration 标识）',
    updated_at   DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    updated_by   VARCHAR(64)  DEFAULT NULL            COMMENT '谁改的',
    PRIMARY KEY (role),
    KEY idx_mail_role_status (status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='信箱可投递角色清单（DB 唯一真源；正名汉字，status=ACTIVE 才可投递）';

-- ------------------------------------------------------------
-- 表 2：mail_role_aliases —— 别名 → 正名（只走一跳）
--
-- 为什么必须有：历史事故 #22 / #45 / #72 —— zhuolong（多一个 o）因「库里出现过」被放行入库，
-- 收件箱按 to_role 精确匹配 → 信在、没人收、全程无感。发信归一 + 读侧并查都依赖这张表。
--
-- 方向（2026-11-09 起）：alias = 拼音/错拼，role = **汉字正名**。
-- 别名指向的正名必须存在于 mail_roles（本文件末尾有自证查询，仓库里也有单测守卫）。
-- ------------------------------------------------------------
CREATE TABLE IF NOT EXISTS mail_role_aliases (
    alias      VARCHAR(32)  NOT NULL                COMMENT '别名 / 历史错拼（拼音，小写）',
    role       VARCHAR(32)  NOT NULL                COMMENT '指向的正名（汉字）',
    note       VARCHAR(255) DEFAULT NULL            COMMENT '为什么有这条别名（事故号/日期）',
    created_at DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (alias),
    KEY idx_mail_alias_role (role)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='收件人别名（拼音/错拼）→ 汉字正名（只走一跳）';

-- ------------------------------------------------------------
-- 种子 1：汉字正名 12 个（source='SEEDED'）
--
-- display_name 与 role 同值：正名已经是给人看的汉字，保留 display_name 只为后台/旧代码兼容。
-- 删 yml 之前必须导完，否则切读路径当天就有人收不到信，而且报错看起来像「对方写错了」。
-- ------------------------------------------------------------
INSERT IGNORE INTO mail_roles (role, display_name, status, source, note, created_by) VALUES
 ('烛龙',   '烛龙',   'ACTIVE', 'SEEDED', '原 application.yml known-roles 种子；拼音 zhulong 见别名表', 'migration_20261109b'),
 ('白泽',   '白泽',   'ACTIVE', 'SEEDED', '原 application.yml known-roles 种子；拼音 baize 见别名表', 'migration_20261109b'),
 ('阁主',   '阁主',   'ACTIVE', 'SEEDED', '原 application.yml known-roles 种子；拼音 gezhu 见别名表', 'migration_20261109b'),
 ('夫诸',   '夫诸',   'ACTIVE', 'SEEDED', '原 application.yml known-roles 种子（阁主 #219 发版角色）；拼音 fuzhu 见别名表', 'migration_20261109b'),
 ('貔貅',   '貔貅',   'ACTIVE', 'SEEDED', '原 application.yml known-roles 种子；拼音 pixiu、错拼 piyao 见别名表', 'migration_20261109b'),
 ('睚眦',   '睚眦',   'ACTIVE', 'SEEDED', '原 application.yml known-roles 种子；拼音 yazi、错拼 yaci 见别名表', 'migration_20261109b'),
 ('麒麟',   '麒麟',   'ACTIVE', 'SEEDED', '原 application.yml known-roles 种子；拼音 qilin 见别名表', 'migration_20261109b'),
 ('凤凰',   '凤凰',   'ACTIVE', 'SEEDED', '原 application.yml known-roles 种子；拼音 fenghuang 见别名表', 'migration_20261109b'),
 ('重明鸟', '重明鸟', 'ACTIVE', 'SEEDED', '原 application.yml known-roles 种子；拼音 chongmingniao 见别名表', 'migration_20261109b'),
 ('九尾狐', '九尾狐', 'ACTIVE', 'SEEDED', '原 application.yml known-roles 种子；拼音 jiuweihu 见别名表', 'migration_20261109b'),
 ('帝江',   '帝江',   'ACTIVE', 'SEEDED', '原 application.yml known-roles 种子；拼音 dijiang 见别名表', 'migration_20261109b'),
 ('穷奇',   '穷奇',   'ACTIVE', 'SEEDED', '原 application.yml known-roles 种子（阁主 #222 哨兵阁负责人）；拼音 qiongqi 见别名表', 'migration_20261109b');

-- ------------------------------------------------------------
-- 种子 2：别名（拼音 + 历史错拼 → 汉字正名）
--
-- 12 条拼音是「兼容入参」的必需品：客户端工具说明里写的仍是拼音代号，
-- 服务端只认汉字会当场把发信打死（阁主 #679 第 2 条明写「不能让」）。
-- 7 条历史错拼来自原 application.yml role-aliases，一条都不删。
-- ------------------------------------------------------------
INSERT IGNORE INTO mail_role_aliases (alias, role, note) VALUES
 ('zhulong',       '烛龙',   '拼音别名：兼容客户端仍写 zhulong'),
 ('baize',         '白泽',   '拼音别名：兼容客户端仍写 baize'),
 ('gezhu',         '阁主',   '拼音别名：兼容客户端仍写 gezhu'),
 ('fuzhu',         '夫诸',   '拼音别名：兼容客户端仍写 fuzhu'),
 ('pixiu',         '貔貅',   '拼音别名：兼容客户端仍写 pixiu'),
 ('yazi',          '睚眦',   '拼音别名：兼容客户端仍写 yazi'),
 ('qilin',         '麒麟',   '拼音别名：兼容客户端仍写 qilin'),
 ('fenghuang',     '凤凰',   '拼音别名：兼容客户端仍写 fenghuang'),
 ('chongmingniao', '重明鸟', '拼音别名：兼容客户端仍写 chongmingniao'),
 ('jiuweihu',      '九尾狐', '拼音别名：兼容客户端仍写 jiuweihu'),
 ('dijiang',       '帝江',   '拼音别名：兼容客户端仍写 dijiang'),
 ('qiongqi',       '穷奇',   '拼音别名：兼容客户端仍写 qiongqi'),
 ('zhuolong',      '烛龙',   '历史错拼（#22/#45/#72 事故：多一个 o 曾让信静默投丢）'),
 ('zhulogn',       '烛龙',   '历史错拼（键序颠倒）'),
 ('zhulon',        '烛龙',   '历史错拼（少一个 g）'),
 ('beize',         '白泽',   '历史错拼'),
 ('gezu',          '阁主',   '历史错拼'),
 ('piyao',         '貔貅',   '2026-11-08 阁主 #119：上一版把貔貅写成 piyao，历史行 #110 的 to_role=piyao 靠它读回'),
 ('yaci',          '睚眦',   '2026-11-08 阁主 #119：睚眦的旧拼写');

-- ------------------------------------------------------------
-- 防御：旧版 A 段若被手工执行过，mail_roles 里会有「拼音当正名」的 ACTIVE 行。
-- 新种子只插汉字行（INSERT IGNORE 不会覆盖旧行），不处理的话同一个角色会同时有
-- 「zhulong」和「烛龙」两行正名 —— 出口又变成拼音。
-- 处理方式：**只停用、不删行**（名单是审计证据），别名表已经把它们指向汉字正名。
-- 显式写回 updated_at：不让 ON UPDATE 把审计时间刷成执行时刻。
-- ------------------------------------------------------------
UPDATE mail_roles r
JOIN mail_role_aliases a ON a.alias = r.role
SET r.status = 'DISABLED', r.updated_at = r.updated_at
WHERE r.status = 'ACTIVE';

-- ------------------------------------------------------------
-- 历史数据刷成汉字（阁主 #679 第 3 条）
--
-- 只刷**别名表里能查到**的值；查不到的原样保留，交给下一步落 PENDING_REVIEW 等人工复核。
-- 「刷不动就留待人工复核，别硬猜」—— 猜错等于把信改投给别人。
--
-- mail_messages 没有 ON UPDATE 列；三个字段用 LEFT JOIN 一次刷完，
-- 避免「只刷了 to_role，from_role 还是拼音」这种半套状态。
-- ------------------------------------------------------------
UPDATE mail_messages m
LEFT JOIN mail_role_aliases a_from  ON a_from.alias = m.from_role
LEFT JOIN mail_role_aliases a_to    ON a_to.alias = m.to_role
LEFT JOIN mail_role_aliases a_claim ON a_claim.alias = m.claimed_by
SET m.from_role  = COALESCE(a_from.role, m.from_role),
    m.to_role    = COALESCE(a_to.role, m.to_role),
    m.claimed_by = COALESCE(a_claim.role, m.claimed_by)
WHERE a_from.alias IS NOT NULL OR a_to.alias IS NOT NULL OR a_claim.alias IS NOT NULL;

-- 游标：先把拼音行复制成汉字行（同线程已存在汉字行则取更大的 last_read_id），再删拼音行。
-- 用 INSERT ... ON DUPLICATE KEY UPDATE 而不是直接 UPDATE：PK 是 (role, thread_id)，
-- 直接改主键在同线程已有汉字行时会撞主键，整批迁移失败。
-- updated_at 显式写回自身：不让 ON UPDATE 把「上次读到哪」的时间刷成迁移时刻。
INSERT INTO mail_cursors (role, thread_id, last_read_id, updated_at)
SELECT a.role, c.thread_id, c.last_read_id, c.updated_at
FROM mail_cursors c
JOIN mail_role_aliases a ON a.alias = c.role
-- WHERE 1=1 不是废话：MySQL 的 INSERT ... SELECT ... ON DUPLICATE KEY UPDATE 有歧义，
-- 解析器可能把 ON DUPLICATE 的 ON 当成 JOIN 条件。加一个 WHERE 明确结束 SELECT 部分。
WHERE 1 = 1
ON DUPLICATE KEY UPDATE
    last_read_id = GREATEST(mail_cursors.last_read_id, VALUES(last_read_id)),
    updated_at   = mail_cursors.updated_at;

DELETE c FROM mail_cursors c JOIN mail_role_aliases a ON a.alias = c.role;

-- ------------------------------------------------------------
-- 种子 3：库里「历史出现过的未知收件人」→ OBSERVED + PENDING_REVIEW
-- （待人工复核，**不是合法来源**）
--
-- 此时已知拼音已被上一步刷成汉字，所以这里剩下的都是别名表也认不出的脏值：
-- 拼错（zhuolon 之类没收录的）、一次性测试名、已离职助手的名字等。
--
-- 为什么要导入：不导的话，这些人会突然「收件人不存在」，而当事人根本不知道
--   是名单换了，会以为对方写错。导进来至少能在后台看见「有这么一批待判的值」。
--
-- 为什么是 PENDING_REVIEW 而不是 ACTIVE：「历史上存在过」≠「现在该收信」。
--   只要还有历史行，实时并集就永远清不掉它们。所以先落待复核，人工判过再改 ACTIVE。
--
-- 别名键照旧排除（双保险）：它们在别名表里，发信时会被归一到正名。
-- ------------------------------------------------------------
INSERT IGNORE INTO mail_roles (role, status, source, note, created_by)
SELECT h.r, 'PENDING_REVIEW', 'OBSERVED', '历史信里出现过、尚未人工复核（复核后改 ACTIVE 或 DISABLED）', 'migration_20261109b'
FROM (
    SELECT DISTINCT LOWER(TRIM(from_role)) AS r FROM mail_messages
    UNION
    SELECT DISTINCT LOWER(TRIM(to_role))   AS r FROM mail_messages
) h
WHERE h.r IS NOT NULL AND h.r <> ''
  AND NOT EXISTS (SELECT 1 FROM mail_role_aliases a WHERE a.alias = h.r);

-- 兜底：若 A 段曾被手工执行过（那时 OBSERVED 写的是 ACTIVE），统一压回待复核。
-- 只改 status，不动正文、不删行；updated_at 显式写回自己，不让 ON UPDATE 把它刷成执行时刻。
UPDATE mail_roles
SET status = 'PENDING_REVIEW', updated_at = updated_at
WHERE source = 'OBSERVED' AND status = 'ACTIVE';

-- ------------------------------------------------------------
-- 自证（部署日志里能一眼看见，而不是等上线后才有人报「收不到信」）
-- ------------------------------------------------------------
-- 1) 按状态/来源看条数：
--    期望 SEEDED/ACTIVE = 12；OBSERVED/PENDING_REVIEW = 历史脏值个数（新库为 0）；
--    DISABLED = 旧版 A 段留下的拼音行（0 或 12）。
SELECT status, source, COUNT(*) AS cnt
FROM mail_roles
GROUP BY status, source
ORDER BY status, source;

-- 2) 别名必须都指向存在的正名，且正名必须是 ACTIVE（正常应为 0 行）：
--    指向 DISABLED/PENDING_REVIEW 的别名会让发信归一到「投不出去的名字」。
SELECT a.alias, a.role AS bad_target_role, r.status AS target_status
FROM mail_role_aliases a
LEFT JOIN mail_roles r ON r.role = a.role
WHERE r.role IS NULL OR r.status <> 'ACTIVE';

-- 3) 待人工复核的收件人清单（给后台/运维照着判；新库 0 行）：
SELECT role, source, note
FROM mail_roles
WHERE status = 'PENDING_REVIEW'
ORDER BY role;

-- 4) ACTIVE 正名里是否还有纯拼音（阁主 #679 硬要求：正名一律汉字；期望 0 行）：
SELECT role, display_name, source
FROM mail_roles
WHERE status = 'ACTIVE' AND role REGEXP '^[A-Za-z0-9_-]+$'
ORDER BY role;

-- 5) 历史消息里是否还有能刷却没刷的别名值（期望 0 行）：
SELECT 'from_role' AS side, COUNT(*) AS cnt
FROM mail_messages m JOIN mail_role_aliases a ON a.alias = m.from_role
UNION ALL
SELECT 'to_role', COUNT(*)
FROM mail_messages m JOIN mail_role_aliases a ON a.alias = m.to_role
UNION ALL
SELECT 'claimed_by', COUNT(*)
FROM mail_messages m JOIN mail_role_aliases a ON a.alias = m.claimed_by;

-- 6) 收件人列必须是 utf8mb4 且长度够放「重明鸟」这类汉字正名（VARCHAR 按字符计数）：
SELECT COLUMN_NAME, CHARACTER_SET_NAME, COLLATION_NAME, CHARACTER_MAXIMUM_LENGTH
FROM information_schema.COLUMNS
WHERE TABLE_SCHEMA = DATABASE()
  AND TABLE_NAME = 'mail_messages'
  AND COLUMN_NAME IN ('from_role', 'to_role', 'claimed_by')
ORDER BY COLUMN_NAME;
