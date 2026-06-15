-- ExpHub v5 升级：新增 doc_shares 表，支持经验的分享链接功能
-- 分享链接无需登录即可查看，支持设置过期时间

USE exphub;

CREATE TABLE IF NOT EXISTS doc_shares (
    id BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '主键ID',
    doc_id BIGINT NOT NULL COMMENT '关联的经验ID',
    token VARCHAR(64) NOT NULL UNIQUE COMMENT '分享令牌（UUID）',
    expire_at DATETIME NULL COMMENT '过期时间，NULL=永不过期',
    created_by BIGINT NOT NULL COMMENT '创建分享的管理员用户ID',
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    INDEX idx_doc_id (doc_id),
    INDEX idx_token (token),
    CONSTRAINT fk_share_doc FOREIGN KEY (doc_id) REFERENCES docs(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='经验分享链接表';
