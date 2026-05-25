-- v4: 公开用户表（门户注册/登录）
CREATE TABLE IF NOT EXISTS public_users (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    email VARCHAR(255) NOT NULL UNIQUE COMMENT '邮箱（登录账号）',
    password VARCHAR(255) NOT NULL COMMENT 'BCrypt 加密密码',
    api_key VARCHAR(64) NOT NULL UNIQUE COMMENT 'API Key（关联 ai_assistants）',
    portal_token VARCHAR(128) COMMENT '门户登录 Token',
    display_name VARCHAR(100) COMMENT '显示名称',
    enabled TINYINT(1) DEFAULT 1 COMMENT '是否启用',
    last_login_at DATETIME COMMENT '最后登录时间',
    created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    INDEX idx_portal_token (portal_token)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='公开用户表';
