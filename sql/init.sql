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

-- 2. AI助手表
CREATE TABLE IF NOT EXISTS ai_assistants (
    id              BIGINT PRIMARY KEY AUTO_INCREMENT,
    assistant_id    VARCHAR(100) NOT NULL UNIQUE COMMENT '唯一标识，如openclaw-zhuque',
    assistant_name  VARCHAR(100) NOT NULL COMMENT '显示名称，如朱雀',
    description     VARCHAR(500) COMMENT '助手描述/用途',
    api_key         VARCHAR(64) NOT NULL UNIQUE COMMENT 'API Key',
    api_key_secret  VARCHAR(64) COMMENT 'API Key签名密钥（HMAC）',
    enabled         TINYINT(1) DEFAULT 1 COMMENT '是否启用',
    total_calls     INT DEFAULT 0 COMMENT '总调用次数',
    success_calls   INT DEFAULT 0 COMMENT '成功调用次数',
    fail_calls      INT DEFAULT 0 COMMENT '失败调用次数',
    last_call_at    DATETIME COMMENT '最后调用时间',
    created_at      DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at      DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    INDEX idx_api_key (api_key),
    INDEX idx_assistant_id (assistant_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='AI助手表';

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

-- 5. 调用记录表
CREATE TABLE IF NOT EXISTS call_logs (
    id              BIGINT PRIMARY KEY AUTO_INCREMENT,
    doc_id          BIGINT NOT NULL COMMENT '关联文档ID',
    doc_title       VARCHAR(200) NOT NULL COMMENT '文档标题（冗余）',
    assistant_id    VARCHAR(100) NOT NULL COMMENT '调用者助手ID',
    assistant_name  VARCHAR(100) NOT NULL COMMENT '调用者助手名称',
    success         TINYINT(1) NOT NULL COMMENT '1成功0失败',
    error_msg       VARCHAR(500) COMMENT '错误信息',
    execution_time  INT COMMENT '执行耗时毫秒',
    feedback        TEXT COMMENT '问题反馈',
    fixed_solution  TEXT COMMENT '助手自己的修复方案',
    fixed_by        VARCHAR(100) COMMENT '提供修复方案的助手ID',
    fixed_by_name   VARCHAR(100) COMMENT '提供修复方案的助手名称',
    rating          INT COMMENT '1-5星评分',
    ip_address      VARCHAR(45) COMMENT '调用者IP',
    created_at      DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    INDEX idx_doc_id (doc_id),
    INDEX idx_assistant (assistant_id),
    INDEX idx_created_at (created_at),
    INDEX idx_success (success),
    FOREIGN KEY (doc_id) REFERENCES docs(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='调用日志表';

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