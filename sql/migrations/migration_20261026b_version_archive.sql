-- ============================================================
-- 版本快照归档（2026-10-26，越哥 00:50 拍板「开始第一项」）
--
-- 背景：文档自己写着「没有归档能力」，而验证动作本身在制造快照膨胀 ——
--      阁主为验证写了 7 次 patch，#654 从 v25 涨到 v32；
--      烛龙又改多轮，最终 v42。实测 598 条 / 3.87 MB，Top5 文档占 42%。
--      这是「整理记忆的动作正在喂大它要整理的东西」的死循环。
--
-- 设计原则（按红线「只标记、不删除」「归档可回查」）：
--   1. **归档 = 搬到独立表**，不是 DELETE。任何一条都能原样取回。
--   2. archive 表**不建 FK 到 docs** —— 加 CASCADE 的话删文档会把归档一起带走，
--      那「证据链」就名存实亡。宁可留孤儿，也不让存档跟着正文一起消失。
--   3. 冗余存 doc_title：即使原文档被删，归档仍能独立看懂「这是哪篇的哪个版本」。
--   4. 归档只动「超出保留窗口的历史版本」，**绝不碰当前正文**（docs.content 不受影响）。
--
-- 保留窗口：每篇按 version 倒序保留最近 N 版，其余归档。N 由工具参数控制（默认 10）。
-- 幂等：重复执行不会报错。
-- ============================================================

CREATE TABLE IF NOT EXISTS doc_versions_archive (
    id            BIGINT       NOT NULL AUTO_INCREMENT,
    doc_id        BIGINT       NOT NULL                COMMENT '原文档 ID（不建 FK，避免删文档时级联清掉存档）',
    doc_title     VARCHAR(500) DEFAULT NULL            COMMENT '冗余存标题：原文档删除后仍能看懂这是什么',
    version       INT          NOT NULL                COMMENT '原版本号',
    content       MEDIUMTEXT   DEFAULT NULL            COMMENT '历史正文；NULL = 原本就是仅元数据变更',
    aliases       VARCHAR(500) DEFAULT NULL,
    summary       VARCHAR(500) DEFAULT NULL,
    updated_by    VARCHAR(100) DEFAULT NULL,
    updated_name  VARCHAR(100) DEFAULT NULL,
    change_type   VARCHAR(20)  DEFAULT 'CONTENT'       COMMENT 'CONTENT / META（与主表一致）',
    created_at    DATETIME     NOT NULL                COMMENT '该版本原本的创建时间',
    archived_at   DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '归档动作时间',
    archived_by   VARCHAR(100) DEFAULT NULL            COMMENT '谁执行了归档',
    archive_reason VARCHAR(200) DEFAULT NULL           COMMENT '归档原因，便于日后审计',
    PRIMARY KEY (id),
    -- ⚠️ **故意不建 (doc_id, version) 唯一约束**（2026-10-26 00:54 踩过）
    --
    -- 一开始建了 UNIQUE KEY uk_doc_version，结果全量归档时炸在：
    --   Duplicate entry '608-2' for key 'uk_doc_version'
    -- 查证发现**主表 doc_versions 本身就没有这个唯一约束**，而 #608 的 v2 在主表里有 2 条重复。
    --
    -- 教训：归档的职责是「原样搬走」，**不是顺手去重**。
    -- 主表有重复就说明数据本身有问题（某处写入有并发缺陷），那该单独查、单独修，
    -- 不能在归档过程里悄悄抹掉一条 —— 那等于「以整理之名删了数据」。
    KEY idx_doc_version (doc_id, version),
    KEY idx_doc (doc_id),
    KEY idx_archived (archived_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
  COMMENT='版本快照冷存档：doc_versions 超保留窗口的历史版本搬到这里，可原样取回';

-- 验证
SELECT TABLE_NAME, TABLE_COMMENT
FROM information_schema.TABLES
WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'doc_versions_archive';

-- 现状基线（归档前后对比用）
SELECT 'doc_versions' AS tbl, COUNT(*) AS rows_cnt,
       ROUND(SUM(CHAR_LENGTH(IFNULL(content,'')))/1024/1024, 2) AS mb
FROM doc_versions
UNION ALL
SELECT 'doc_versions_archive', COUNT(*),
       ROUND(SUM(CHAR_LENGTH(IFNULL(content,'')))/1024/1024, 2)
FROM doc_versions_archive;
