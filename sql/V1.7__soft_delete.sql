-- V1.7 逻辑删除支持
-- 为全部业务表新增 is_delete 标记：0=未删除（默认），1=已删除。
-- 约定：所有查询统一附加 `is_delete = 0` 过滤；删除操作改为 `UPDATE ... SET is_delete = 1`，
--       不再执行物理 DELETE（历史已存在的物理删除接口保持兼容，但新代码一律逻辑删除）。
-- 注意：本项目未启用 Flyway，本文件需手动通过 mysql 客户端执行（与各 V1.x 脚本一致）。

ALTER TABLE admin_user                     ADD COLUMN is_delete TINYINT(1) NOT NULL DEFAULT 0 COMMENT '逻辑删除:0正常,1已删';
ALTER TABLE alarm                          ADD COLUMN is_delete TINYINT(1) NOT NULL DEFAULT 0 COMMENT '逻辑删除:0正常,1已删';
ALTER TABLE answer_record                  ADD COLUMN is_delete TINYINT(1) NOT NULL DEFAULT 0 COMMENT '逻辑删除:0正常,1已删';
ALTER TABLE appeal_ticket                  ADD COLUMN is_delete TINYINT(1) NOT NULL DEFAULT 0 COMMENT '逻辑删除:0正常,1已删';
ALTER TABLE audit_log                      ADD COLUMN is_delete TINYINT(1) NOT NULL DEFAULT 0 COMMENT '逻辑删除:0正常,1已删';
ALTER TABLE bank_store_item                ADD COLUMN is_delete TINYINT(1) NOT NULL DEFAULT 0 COMMENT '逻辑删除:0正常,1已删';
ALTER TABLE bank_store_item_tag            ADD COLUMN is_delete TINYINT(1) NOT NULL DEFAULT 0 COMMENT '逻辑删除:0正常,1已删';
ALTER TABLE bank_store_question            ADD COLUMN is_delete TINYINT(1) NOT NULL DEFAULT 0 COMMENT '逻辑删除:0正常,1已删';
ALTER TABLE bank_store_tag                 ADD COLUMN is_delete TINYINT(1) NOT NULL DEFAULT 0 COMMENT '逻辑删除:0正常,1已删';
ALTER TABLE checkin_record                 ADD COLUMN is_delete TINYINT(1) NOT NULL DEFAULT 0 COMMENT '逻辑删除:0正常,1已删';
ALTER TABLE device_bind                    ADD COLUMN is_delete TINYINT(1) NOT NULL DEFAULT 0 COMMENT '逻辑删除:0正常,1已删';
ALTER TABLE family_account                 ADD COLUMN is_delete TINYINT(1) NOT NULL DEFAULT 0 COMMENT '逻辑删除:0正常,1已删';
ALTER TABLE family_bank_assign             ADD COLUMN is_delete TINYINT(1) NOT NULL DEFAULT 0 COMMENT '逻辑删除:0正常,1已删';
ALTER TABLE family_bank_store              ADD COLUMN is_delete TINYINT(1) NOT NULL DEFAULT 0 COMMENT '逻辑删除:0正常,1已删';
ALTER TABLE family_bank_store_question     ADD COLUMN is_delete TINYINT(1) NOT NULL DEFAULT 0 COMMENT '逻辑删除:0正常,1已删';
ALTER TABLE free_time_account              ADD COLUMN is_delete TINYINT(1) NOT NULL DEFAULT 0 COMMENT '逻辑删除:0正常,1已删';
ALTER TABLE member                         ADD COLUMN is_delete TINYINT(1) NOT NULL DEFAULT 0 COMMENT '逻辑删除:0正常,1已删';
ALTER TABLE point_account                  ADD COLUMN is_delete TINYINT(1) NOT NULL DEFAULT 0 COMMENT '逻辑删除:0正常,1已删';
ALTER TABLE point_flow                     ADD COLUMN is_delete TINYINT(1) NOT NULL DEFAULT 0 COMMENT '逻辑删除:0正常,1已删';
ALTER TABLE profile_medal                  ADD COLUMN is_delete TINYINT(1) NOT NULL DEFAULT 0 COMMENT '逻辑删除:0正常,1已删';
ALTER TABLE question                       ADD COLUMN is_delete TINYINT(1) NOT NULL DEFAULT 0 COMMENT '逻辑删除:0正常,1已删';
ALTER TABLE quiz_config                    ADD COLUMN is_delete TINYINT(1) NOT NULL DEFAULT 0 COMMENT '逻辑删除:0正常,1已删';
ALTER TABLE quiz_task                      ADD COLUMN is_delete TINYINT(1) NOT NULL DEFAULT 0 COMMENT '逻辑删除:0正常,1已删';
ALTER TABLE risk_event                     ADD COLUMN is_delete TINYINT(1) NOT NULL DEFAULT 0 COMMENT '逻辑删除:0正常,1已删';
ALTER TABLE sensitive_word                 ADD COLUMN is_delete TINYINT(1) NOT NULL DEFAULT 0 COMMENT '逻辑删除:0正常,1已删';
ALTER TABLE student_profile                ADD COLUMN is_delete TINYINT(1) NOT NULL DEFAULT 0 COMMENT '逻辑删除:0正常,1已删';
ALTER TABLE sys_config                     ADD COLUMN is_delete TINYINT(1) NOT NULL DEFAULT 0 COMMENT '逻辑删除:0正常,1已删';
ALTER TABLE unlock_session                 ADD COLUMN is_delete TINYINT(1) NOT NULL DEFAULT 0 COMMENT '逻辑删除:0正常,1已删';
ALTER TABLE vip_order                      ADD COLUMN is_delete TINYINT(1) NOT NULL DEFAULT 0 COMMENT '逻辑删除:0正常,1已删';
