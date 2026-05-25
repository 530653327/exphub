-- ============================================================
-- ExpHub 数据库初始化脚本（完整版）
-- 开源用户只需执行本文件即可完成全部建表和数据初始化
-- 
-- 使用方式：
--   mysql -u root -p < sql/init.sql
-- ============================================================

CREATE DATABASE IF NOT EXISTS exphub DEFAULT CHARSET utf8mb4 COLLATE utf8mb4_unicode_ci;
USE exphub;

-- ============================================================
-- 1. 管理员用户表
-- ============================================================
CREATE TABLE IF NOT EXISTS users (
    id           BIGINT PRIMARY KEY AUTO_INCREMENT,
    username     VARCHAR(50) NOT NULL UNIQUE COMMENT '登录用户名',
    password     VARCHAR(100) NOT NULL COMMENT 'BCrypt加密密码',
    display_name VARCHAR(100) COMMENT '显示名称',
    role         VARCHAR(20) DEFAULT 'USER' COMMENT 'ADMIN / USER',
    last_login   DATETIME COMMENT '最后登录时间',
    created_at   DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at   DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    INDEX idx_username (username)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='管理员用户表';

-- ============================================================
-- 2. AI 助手 / API Key 表
-- ============================================================
CREATE TABLE IF NOT EXISTS ai_assistants (
    id              BIGINT PRIMARY KEY AUTO_INCREMENT,
    assistant_id    VARCHAR(100) NOT NULL UNIQUE COMMENT '唯一标识，如 openclaw-zhuque',
    assistant_name  VARCHAR(100) NOT NULL COMMENT '显示名称',
    description     VARCHAR(500) COMMENT '描述/用途',
    api_key         VARCHAR(64) NOT NULL UNIQUE COMMENT 'API Key',
    api_key_secret  VARCHAR(64) COMMENT 'API Key 签名密钥（HMAC）',
    enabled         TINYINT(1) DEFAULT 1 COMMENT '是否启用',
    can_create      TINYINT(1) DEFAULT 0 COMMENT '可创建经验',
    can_update      TINYINT(1) DEFAULT 0 COMMENT '可编辑经验',
    can_search      TINYINT(1) DEFAULT 1 COMMENT '可查询经验',
    total_calls     INT DEFAULT 0 COMMENT '总调用次数',
    success_calls   INT DEFAULT 0 COMMENT '成功调用次数',
    fail_calls      INT DEFAULT 0 COMMENT '失败调用次数',
    last_call_at    DATETIME COMMENT '最后调用时间',
    created_at      DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at      DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    INDEX idx_api_key (api_key),
    INDEX idx_assistant_id (assistant_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='AI 助手 / API Key 表';

-- ============================================================
-- 3. 经验文档表
-- ============================================================
CREATE TABLE IF NOT EXISTS docs (
    id              BIGINT PRIMARY KEY AUTO_INCREMENT,
    title           VARCHAR(200) NOT NULL COMMENT '文档标题',
    category        VARCHAR(50) COMMENT '分类（开发/运维/部署/数据库 等）',
    content         MEDIUMTEXT NOT NULL COMMENT 'Markdown 正文',
    aliases         VARCHAR(500) COMMENT '别名/同义词，逗号分隔',
    tags            VARCHAR(500) COMMENT '标签，逗号分隔',
    summary         VARCHAR(500) COMMENT '一句话摘要',
    version         INT DEFAULT 1 COMMENT '当前版本号',
    author_id       VARCHAR(100) NOT NULL COMMENT '作者助手ID',
    author_name     VARCHAR(100) NOT NULL COMMENT '作者显示名',
    api_key         VARCHAR(64) COMMENT '创建时使用的 API Key',
    template_type   VARCHAR(32) COMMENT '使用的模板类型',
    call_count      INT DEFAULT 0 COMMENT '被调用次数',
    success_count   INT DEFAULT 0 COMMENT '成功调用次数',
    fail_count      INT DEFAULT 0 COMMENT '失败调用次数',
    rating          DECIMAL(3,2) DEFAULT 5.00 COMMENT '平均评分 1-5',
    rating_count    INT DEFAULT 0 COMMENT '评分次数',
    status          VARCHAR(20) DEFAULT 'ACTIVE' COMMENT 'ACTIVE / COMPLETED / BROKEN / DEPRECATED',
    created_at      DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at      DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    INDEX idx_category (category),
    INDEX idx_author (author_id),
    INDEX idx_call_count (call_count),
    INDEX idx_status (status),
    INDEX idx_api_key (api_key),
    INDEX idx_apikey_status (api_key, status),
    INDEX idx_template_status (template_type, status),
    INDEX idx_status_updated (status, updated_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='经验文档表';

-- ============================================================
-- 3a. FULLTEXT 全文索引（ngram 中文分词）
--     使用存储过程兼容 MySQL 5.7 和 8.0
-- ============================================================
DELIMITER //
DROP PROCEDURE IF EXISTS exphub_create_ft//
CREATE PROCEDURE exphub_create_ft()
BEGIN
    DECLARE has_ngram INT DEFAULT 0;
    SELECT COUNT(*) INTO has_ngram FROM information_schema.PLUGINS WHERE PLUGIN_NAME = 'ngram' AND PLUGIN_STATUS = 'ACTIVE';

    IF has_ngram > 0 THEN
        SET @sql = 'ALTER TABLE docs ADD FULLTEXT INDEX ft_search (title, content, aliases, summary, tags) WITH PARSER ngram';
    ELSE
        SET @sql = 'ALTER TABLE docs ADD FULLTEXT INDEX ft_search (title, content, aliases, summary, tags)';
    END IF;

    PREPARE stmt FROM @sql;
    EXECUTE stmt;
    DEALLOCATE PREPARE stmt;
END//
DELIMITER ;
CALL exphub_create_ft();
DROP PROCEDURE IF EXISTS exphub_create_ft;

-- ============================================================
-- 4. 文档版本历史表
-- ============================================================
CREATE TABLE IF NOT EXISTS doc_versions (
    id           BIGINT PRIMARY KEY AUTO_INCREMENT,
    doc_id       BIGINT NOT NULL COMMENT '关联文档ID',
    version      INT NOT NULL COMMENT '版本号',
    content      MEDIUMTEXT NOT NULL COMMENT '该版本内容快照',
    aliases      VARCHAR(500),
    summary      VARCHAR(500),
    updated_by   VARCHAR(100) COMMENT '更新者ID',
    updated_name VARCHAR(100) COMMENT '更新者名称',
    created_at   DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    INDEX idx_doc_version (doc_id, version),
    FOREIGN KEY (doc_id) REFERENCES docs(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='文档版本历史表';

-- ============================================================
-- 5. 调用日志表
-- ============================================================
CREATE TABLE IF NOT EXISTS call_logs (
    id          BIGINT PRIMARY KEY AUTO_INCREMENT,
    api_key     VARCHAR(64) COMMENT 'API Key',
    caller_name VARCHAR(100) COMMENT '调用者名字',
    action      VARCHAR(20) DEFAULT 'SEARCH' COMMENT '操作类型：SEARCH / CREATE / UPDATE / DELETE',
    keyword     VARCHAR(500) COMMENT '检索关键字',
    hit_count   INT DEFAULT 0 COMMENT '命中数据条数',
    doc_id      BIGINT COMMENT '关联文档ID',
    doc_title   VARCHAR(200) COMMENT '文档标题',
    detail      VARCHAR(500) COMMENT '详细信息',
    created_at  DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    INDEX idx_api_key (api_key),
    INDEX idx_created_at (created_at),
    INDEX idx_action (action),
    INDEX idx_doc_id (doc_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='调用日志表';

-- ============================================================
-- 6. 经验模板表
-- ============================================================
CREATE TABLE IF NOT EXISTS doc_templates (
    id               BIGINT PRIMARY KEY AUTO_INCREMENT,
    type             VARCHAR(50) COMMENT '类型标识，如 problem_solution',
    name             VARCHAR(100) NOT NULL COMMENT '模板名称',
    description      VARCHAR(500) COMMENT '适用场景描述',
    instruction      TEXT NOT NULL COMMENT 'AI 填写指南',
    template_content TEXT NOT NULL COMMENT '模板结构（Markdown）',
    created_at       DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at       DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='经验模板表';

-- ============================================================
-- 初始化数据
-- ============================================================

-- 管理员账号（首次登录后请立即修改密码）
INSERT IGNORE INTO users (username, password, display_name, role) VALUES
('admin', '$2a$10$N9qo8uLOickgx2ZMRZoMyeIjZRGdjGj/n3.Q7P4.yE9gV6U5m5bXm', '管理员', 'ADMIN');

-- 默认 AI 助手示例（部署后请修改 api_key 和 api_key_secret）
INSERT IGNORE INTO ai_assistants (assistant_id, assistant_name, description, api_key, api_key_secret, enabled, can_create, can_update, can_search) VALUES
('openclaw-zhuque', '朱雀', 'OpenClaw 主助手', 'exphub-zhuque-api-key-2024', 'zhuque-secret-key-2024', 1, 1, 1, 1);

-- 示例经验
INSERT IGNORE INTO docs (title, category, content, aliases, tags, summary, author_id, author_name, template_type, api_key) VALUES
('SSH 免密登录配置', '服务器',
'## 目标\n使用 ssh2 npm 包通过密码登录远程服务器并复制公钥，实现免密登录。\n\n## 前提条件\n- 本地已安装 Node.js\n- 远程服务器已开启 SSH\n- 知道远程服务器密码\n\n## 解决方案\n### 步骤1：安装 ssh2 包\n```bash\nnpm install ssh2\n```\n\n### 步骤2：编写 Node.js 脚本\n```javascript\nconst { Client } = require(''ssh2'');\nconst conn = new Client();\nconn.on(''ready'', () => {\n  conn.exec(''mkdir -p ~/.ssh && chmod 700 ~/.ssh && cat >> ~/.ssh/authorized_keys'', (err, stream) => {\n    if (err) throw err;\n    stream.on(''close'', () => { conn.end(); console.log(''公钥已复制''); });\n  });\n}).connect({ host: ''远程IP'', port: 22, username: ''root'', password: ''密码'' });\n```\n\n## 验证方式\n本地执行 `ssh root@服务器IP`，无需输入密码即成功。\n\n## 注意事项\n- 确保 `~/.ssh` 目录权限为 700\n- 确保 `authorized_keys` 权限为 600',
'SSH免密登录,免密登录,passwordless ssh,ssh公钥',
'ssh,服务器,自动化,免密,Node.js',
'使用 ssh2 npm 包通过密码登录远程服务器并复制公钥，实现免密登录',
'openclaw-zhuque', '朱雀', 'problem_solution', 'exphub-zhuque-api-key-2024');

-- 预置经验模板（7 种）
INSERT IGNORE INTO doc_templates (type, name, description, instruction, template_content) VALUES

-- 模板 1：问题解决方案
('problem_solution', '🛠️ 问题解决方案',
 '记录技术问题的排查过程和解决方案，方便下次遇到同类问题时快速参考。适用于：bug修复、配置问题、报错排查等。',
 '请按以下格式记录问题解决过程，包括：环境信息、问题现象、排查过程、根因分析、解决方案和注意事项。',
 '## 环境信息\n- 操作系统：\n- 相关版本：\n\n## 问题现象\n描述你遇到了什么问题，错误信息是什么？\n\n## 排查过程\n1. 第一步做了什么...\n2. 第二步发现了什么...\n\n## 根因分析\n问题的根本原因是什么？\n\n## 解决方案\n详细的解决步骤：\n\n```\n关键代码或配置\n```\n\n## ⚠️ 注意事项\n1. 容易踩的坑\n2. 关联依赖\n3. 后续影响'),

-- 模板 2：知识文档
('knowledge_doc', '📚 知识文档',
 '记录技术知识点、配置参考、最佳实践等，方便以后查阅。适用于：配置模板、技术总结、工具使用指南等。',
 '请按以下格式记录知识文档，包括：概述、核心知识点、最佳实践和参考资料。',
 '## 概述\n简要说明本文档涵盖的知识领域和适用场景。\n\n## 核心知识点\n### 知识点1\n- 概念说明\n- 关键配置/代码\n\n### 知识点2\n- 概念说明\n- 关键配置/代码\n\n## 最佳实践\n1. 推荐做法\n2. 避免的坑\n\n## 参考资料\n- [链接标题](URL)\n- 相关文档'),

-- 模板 3：待办事项
('todo_list', '✅ 待办事项',
 '记录需要跟踪处理的事项、后续任务、优化计划等。适用于：待处理的问题、计划中的改进、需要跟进的事项。',
 '请按以下格式记录待办事项，包括：背景、待办清单（按优先级排列）和备注。',
 '## 背景\n为什么要记录这些待办事项？\n\n## 待办清单\n- [ ] 【高优】待办标题\n  - 详细描述\n  - 相关链接/经验ID\n\n- [ ] 【中优】待办标题\n  - 详细描述\n\n- [ ] 【低优】待办标题\n  - 详细描述\n\n## 已完成\n- [x] 已完成的事项\n\n## 备注\n补充说明'),

-- 模板 4：Bug 修复记录
('bug_fix', '🐛 Bug 修复记录',
 '记录Bug的排查过程、根因和修复方案，方便后续类似问题快速定位。适用于：代码Bug、逻辑错误、兼容性问题、性能问题等。',
 '请按以下格式记录Bug的完整处理过程，包括复现步骤、排查过程、根因和修复代码。',
 '## 🐛 Bug 描述\n- 现象：\n- 影响范围：\n- 严重程度：P0/P1/P2/P3\n\n## 🔄 复现步骤\n1.\n2.\n3.\n\n## 🔍 排查过程\n1. 第一步：做了什么，发现了什么\n2. 第二步：做了什么，发现了什么\n\n## 🎯 根因分析\n问题的根本原因是什么？\n\n## 🔧 修复方案\n详细的修复步骤和代码变更：\n\n```\n修复前的代码\n```\n\n```\n修复后的代码\n```\n\n## ✅ 验证方法\n如何验证Bug已修复：\n\n## ⚠️ 注意事项\n1. 修复引入的风险\n2. 关联模块影响'),

-- 模板 5：配置指南
('config_guide', '⚙️ 配置指南',
 '记录各类技术配置项、环境配置和参数说明，方便快速查阅和复用。适用于：服务端配置、中间件配置、环境变量、部署参数等。',
 '请按以下格式记录完整的配置信息，包括参数含义、示例值和注意事项。',
 '## ⚙️ 配置概述\n- 配置名称：\n- 适用版本：\n- 配置位置/文件：\n\n## 📋 配置项说明\n| 参数名 | 类型 | 默认值 | 说明 |\n|-------|------|--------|------|\n|  |  |  |  |\n\n## 📝 完整配置示例\n```\n完整配置文件内容\n```\n\n## 💡 配置要点\n1. 关键配置说明\n2. 常见配置组合\n\n## ⚠️ 注意事项\n1. 配置不当的后果\n2. 与其他配置的冲突\n3. 安全相关注意'),

-- 模板 6：操作指南
('how_to', '📖 操作指南',
 '记录各类操作步骤和流程指南，提供详细可复现的操作方法。适用于：部署流程、环境搭建、工具使用、故障处理流程等。',
 '请按以下格式记录完整的操作步骤，确保其他人可以按步骤复现。',
 '## 🎯 目标\n通过本指南实现什么目标？\n\n## 📦 前置条件\n- 环境要求：\n- 依赖工具：\n- 权限要求：\n\n## 📋 操作步骤\n\n### 步骤1：标题\n详细描述和命令：\n```\n命令或操作\n```\n预期结果：\n\n### 步骤2：标题\n详细描述和命令：\n```\n命令或操作\n```\n预期结果：\n\n## ✅ 验证方法\n如何确认操作成功：\n1.\n2.\n\n## ⚠️ 常见问题\n- 问题1：现象 → 解决方法\n- 问题2：现象 → 解决方法\n\n## 📖 参考资料\n- 相关文档链接'),

-- 模板 7：计划排期
('schedule_plan', '📅 计划排期',
 '记录项目开发排期计划，包括阶段划分、时间节点、任务列表和里程碑。适用于：开发计划、迭代规划、上线排期、功能路线图等。',
 '请按以下格式填写开发排期计划，包括各阶段的时间节点、任务拆分和里程碑。',
 '## 📅 排期概述\n- 项目名称：\n- 计划周期：开始时间 ~ 结束时间\n- 当前阶段：\n\n## 🎯 里程碑\n| 时间节点 | 里程碑 | 交付物 | 状态 |\n|---------|--------|--------|------|\n| YYYY-MM-DD | 需求评审 | PRD文档 | ⏳ |\n| YYYY-MM-DD | 开发完成 | 代码+测试 | ⏳ |\n| YYYY-MM-DD | 上线发布 | 发布报告 | ⏳ |\n\n## 📋 阶段划分\n\n### 阶段一：名称（起止日期）\n- [ ] 任务1\n- [ ] 任务2\n\n### 阶段二：名称（起止日期）\n- [ ] 任务1\n- [ ] 任务2\n\n## ⚠️ 风险与阻塞\n- 风险描述 | 影响范围 | 应对措施\n\n## 📝 进展记录\n- [日期] 完成了什么，遇到了什么问题');

-- ============================================================
-- 完成
-- ============================================================
