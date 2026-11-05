-- V1.3 后台管理员（RBAC：ADMIN/OPS/REVIEWER/CS/FINANCE/VIEWER，FR-W01~02）
USE xuexijiesuo;
CREATE TABLE IF NOT EXISTS admin_user (
  id            BIGINT AUTO_INCREMENT PRIMARY KEY,
  username      VARCHAR(32)  NOT NULL,
  password_hash VARCHAR(72)  NOT NULL COMMENT 'bcrypt；PLAIN:前缀为开发引导态，首次登录自动升级',
  role          VARCHAR(16)  NOT NULL,
  status        TINYINT      NOT NULL DEFAULT 1,
  created_at    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
  UNIQUE KEY uk_username (username)
) ENGINE=InnoDB COMMENT='后台管理员';

-- 初始管理员 admin/admin123（开发引导态，首次登录自动升级为 bcrypt；上线前必须改密）
INSERT IGNORE INTO admin_user(username, password_hash, role) VALUES('admin', 'PLAIN:admin123', 'ADMIN');
