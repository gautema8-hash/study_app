-- V1.1 家长操作密码（FR-35：6位操作密码，独立于登录凭证，孩子设备不存密码）
USE xuexijiesuo;
ALTER TABLE family_account
  ADD COLUMN parent_pwd_hash VARCHAR(72) NULL COMMENT '家长操作密码bcrypt' AFTER kyc_status;
