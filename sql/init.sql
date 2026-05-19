-- ExpHub 数据库初始化脚本
-- 注意：本脚本使用 IF NOT EXISTS，表名独立，不会影响其他项目的数据

-- 创建数据库（如果不存在）
CREATE DATABASE IF NOT EXISTS exphub DEFAULT CHARSET utf8mb4;

USE exphub;

-- 1. 管理员用户表
CREATE TABLE IF NOT EXISTS users (
    id           BIGINT PRIMARY KEY AUTO_INCREMENT,
    username     VARCHAR(50) NOT NULL UNIQUE COMMENT '登录用户名',
    password     VARCHAR(100) NOT NULL COMMENT 'BCrypt加密密码',
    display_name VARCHAR(100) COMMENT '显示名称',
    role         VARCHAR(20) DEFAULT 'USER' COMMENT 'ADMIN或USER',
    last_login   DATETIME COMMENT '最后登录时间',
    created_at   DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at   DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    INDEX idx_username (username)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='管理员用户表';

-- 2. 秘钥表（AI助手）
CREATE TABLE IF NOT EXISTS ai_assistants (
    id              BIGINT PRIMARY KEY AUTO_INCREMENT,
    assistant_id    VARCHAR(100) NOT NULL UNIQUE COMMENT '唯一标识，如openclaw-zhuque',
    assistant_name  VARCHAR(100) NOT NULL COMMENT '显示名称，如朱雀',
    description     VARCHAR(500) COMMENT '秘钥描述/用途',
    api_key         VARCHAR(64) NOT NULL UNIQUE COMMENT 'API Key',
    api_key_secret  VARCHAR(64) COMMENT 'API Key签名密钥（HMAC）',
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
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='秘钥表';

-- 3. 文档主表
CREATE TABLE IF NOT EXISTS docs (
    id              BIGINT PRIMARY KEY AUTO_INCREMENT,
    title           VARCHAR(200) NOT NULL COMMENT '文档标题',
    category        VARCHAR(50) COMMENT '分类',
    content         MEDIUMTEXT NOT NULL COMMENT '正文Markdown',
    aliases         VARCHAR(500) COMMENT '别名/同义词，逗号分隔',
    tags            VARCHAR(500) COMMENT '标签，逗号分隔',
    summary         VARCHAR(500) COMMENT '一句话摘要',
    version         INT DEFAULT 1 COMMENT '当前版本号',
    author_id       VARCHAR(100) NOT NULL COMMENT '作者助手ID',
    author_name     VARCHAR(100) NOT NULL COMMENT '作者显示名',
    call_count      INT DEFAULT 0 COMMENT '被调用次数',
    success_count   INT DEFAULT 0 COMMENT '成功调用次数',
    fail_count      INT DEFAULT 0 COMMENT '失败调用次数',
    rating          DECIMAL(3,2) DEFAULT 5.00 COMMENT '平均评分1-5',
    rating_count    INT DEFAULT 0 COMMENT '评分次数',
    status          VARCHAR(20) DEFAULT 'ACTIVE' COMMENT 'ACTIVE/BROKEN/DEPRECATED',
    created_at      DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at      DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    INDEX idx_category (category),
    INDEX idx_author (author_id),
    INDEX idx_call_count (call_count),
    INDEX idx_status (status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='经验文档表';

-- 全文索引（尝试 ngram 分词器，如不支持则用默认）
-- 兼容 MySQL 5.7/8.0
DELIMITER //
DROP PROCEDURE IF EXISTS create_ft_index//
CREATE PROCEDURE create_ft_index()
BEGIN
    -- 检查是否有 ngram 插件
    DECLARE has_ngram INT DEFAULT 0;
    SELECT COUNT(*) INTO has_ngram FROM information_schema.PLUGINS WHERE PLUGIN_NAME = 'ngram';
    
    IF has_ngram > 0 THEN
        SET @sql = 'ALTER TABLE docs ADD FULLTEXT INDEX ft_content (title, content, aliases, summary, tags) WITH PARSER ngram';
    ELSE
        SET @sql = 'ALTER TABLE docs ADD FULLTEXT INDEX ft_content (title, content, aliases, summary, tags)';
    END IF;
    
    -- 忽略报错继续执行（索引可能已存在）
    PREPARE stmt FROM @sql;
    EXECUTE stmt;
    PREPARE stmt FROM 'ALTER TABLE docs ADD FULLTEXT INDEX ft_aliases (aliases) WITH PARSER ngram';
    EXECUTE stmt;
    PREPARE stmt FROM 'ALTER TABLE docs ADD FULLTEXT INDEX ft_tags (tags) WITH PARSER ngram';
    EXECUTE stmt;
    DEALLOCATE PREPARE stmt;
END//
DELIMITER ;
CALL create_ft_index();
DROP PROCEDURE IF EXISTS create_ft_index;

-- 4. 版本历史表
CREATE TABLE IF NOT EXISTS doc_versions (
    id          BIGINT PRIMARY KEY AUTO_INCREMENT,
    doc_id      BIGINT NOT NULL COMMENT '关联文档ID',
    version     INT NOT NULL COMMENT '版本号',
    content     MEDIUMTEXT NOT NULL COMMENT '该版本内容快照',
    aliases     VARCHAR(500),
    summary     VARCHAR(500),
    updated_by  VARCHAR(100) COMMENT '更新者ID',
    updated_name VARCHAR(100) COMMENT '更新者名称',
    created_at  DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    INDEX idx_doc_version (doc_id, version),
    FOREIGN KEY (doc_id) REFERENCES docs(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='文档版本历史表';

-- 5. 调用记录表（系统自动记录搜索日志）
CREATE TABLE IF NOT EXISTS call_logs (
    id              BIGINT PRIMARY KEY AUTO_INCREMENT,
    api_key         VARCHAR(64) COMMENT 'AppKey',
    caller_name     VARCHAR(100) COMMENT '调用者名字',
    keyword         VARCHAR(500) COMMENT '检索关键字',
    hit_count       INT DEFAULT 0 COMMENT '命中数据条数',
    created_at      DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    INDEX idx_api_key (api_key),
    INDEX idx_created_at (created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='调用日志表';

-- 6. 经验模板表
CREATE TABLE IF NOT EXISTS doc_templates (
    id              BIGINT PRIMARY KEY AUTO_INCREMENT,
    name            VARCHAR(100) NOT NULL COMMENT '模板名称',
    platform_field  VARCHAR(50) DEFAULT '操作系统' COMMENT '环境字段名',
    instruction     TEXT NOT NULL COMMENT 'AI填写指南',
    template_content TEXT NOT NULL COMMENT '模板结构',
    is_default      TINYINT(1) DEFAULT 0 COMMENT '是否默认模板',
    created_at      DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at      DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='经验模板表';

-- ========================================
-- 升级脚本（如果表已存在且需要重建）
-- ALTER TABLE call_logs DROP FOREIGN KEY doc_id;
-- DROP TABLE IF EXISTS call_logs;
-- 然后重新执行上面的 CREATE TABLE
-- ========================================

-- ========================================
-- 初始化数据
-- ========================================

-- 插入管理员（密码：changeme，BCrypt加密）
INSERT IGNORE INTO users (username, password, display_name, role) VALUES 
('admin', '$2a$10$N9qo8uLOickgx2ZMRZoMyeIjZRGdjGj/n3.Q7P4.yE9gV6U5m5bXm', '管理员', 'ADMIN');

-- 插入默认AI助手（朱雀）
INSERT IGNORE INTO ai_assistants (assistant_id, assistant_name, description, api_key, api_key_secret, enabled) VALUES 
('openclaw-zhuque', '朱雀', 'OpenClaw主助手，南方神兽，浴火而生 🐦‍🔥', 'exphub-zhuque-api-key-2024', 'zhuque-secret-key-2024', 1);

-- 插入示例经验文档
INSERT IGNORE INTO docs (title, category, content, aliases, tags, summary, author_id, author_name) VALUES 
('SSH免密登录配置', '服务器', '## 目标\n使用ssh2 npm包通过密码登录远程服务器并复制公钥，实现免密登录。\n\n## 别名/可能的关键词\n免密登录, 不用密码登录, passwordless ssh, ssh公钥部署\n\n## 前提条件\n- 本地已安装Node.js\n- 远程服务器已开启SSH\n- 知道远程服务器密码\n\n## 操作步骤\n### 步骤1：安装ssh2包\n```bash\nnpm install ssh2\n```\n\n### 步骤2：编写Node.js脚本\n```javascript\nconst { Client } = require(''ssh2'');\nconst fs = require(''fs'');\n\nconst conn = new Client();\nconn.on(''ready'', () => {\n  conn.exec(''mkdir -p ~/.ssh && chmod 700 ~/.ssh && cat >> ~/.ssh/authorized_keys < ~/.ssh/id_rsa.pub'', (err, stream) => {\n    if (err) throw err;\n    stream.on(''close'', () => { conn.end(); console.log(''公钥已复制''); });\n  });\n}).connect({\n  host: ''远程服务器IP'',\n  port: 22,\n  username: ''root'',\n  password: ''密码''\n});\n```\n\n## 验证方式\n本地执行 `ssh root@服务器IP`，无需输入密码即成功。', 'SSH免密登录, 免密登录, passwordless ssh, ssh公钥', 'ssh,服务器,自动化,免密', '使用ssh2 npm包通过密码登录远程服务器并复制公钥，实现免密登录', 'openclaw-zhuque', '朱雀');

-- 插入默认经验模板
INSERT IGNORE INTO doc_templates (name, platform_field, instruction, template_content, is_default) VALUES
('编程经验模板', '操作系统', '请按以下格式记录经验。环境信息帮助判断经验适用的运行平台；场景和问题描述让调用者快速理解上下文；解决方案和示例是核心内容；注意事项帮助避免常见错误；触发关键字提高匹配准确性。', '## 环境信息\n- 操作系统：Windows / macOS / Linux / 通用\n- 依赖条件：需要安装什么前置软件\n- 版本要求：相关版本号\n\n## 场景描述\n描述在什么情况下需要这个经验，适用的业务场景是什么。\n\n## 问题描述\n具体要解决什么问题？遇到了什么困难或痛点？\n\n## 解决方案\n详细的解决步骤和核心代码配置。\n\n## 示例\n```\n示例代码或配置\n```\n\n## 注意事项\n容易出错的地方和需要注意的坑。\n\n## 触发关键字\n什么关键词会触发这条经验（多个用逗号分隔）', 1);