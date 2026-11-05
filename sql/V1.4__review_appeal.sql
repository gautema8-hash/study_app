-- V1.4 内容审核与申诉工单（FR-W12/W15/16 + App E-06）
USE xuexijiesuo;

-- 敏感词库（D-02 机审 API 未接入前的本地替身：1灰词→人工复核 2黑词→直接驳回）
CREATE TABLE IF NOT EXISTS sensitive_word (
  id   BIGINT AUTO_INCREMENT PRIMARY KEY,
  word VARCHAR(64) NOT NULL,
  level TINYINT   NOT NULL DEFAULT 1 COMMENT '1灰词(人工复核) 2黑词(直接驳回)',
  UNIQUE KEY uk_word (word)
) ENGINE=InnoDB COMMENT='敏感词库';
INSERT IGNORE INTO sensitive_word(word, level) VALUES('加微信',1),('免费领取',1),('http://',1),('代做作业',2);

-- 申诉工单（学生作弊判废申诉等，FR-W15/16）
CREATE TABLE IF NOT EXISTS appeal_ticket (
  id         BIGINT AUTO_INCREMENT PRIMARY KEY,
  family_id  BIGINT       NOT NULL,
  profile_id BIGINT       NULL,
  task_id    BIGINT       NULL,
  type       VARCHAR(16)  NOT NULL DEFAULT 'CHEAT',
  content    VARCHAR(512) NOT NULL,
  status     TINYINT      NOT NULL DEFAULT 0 COMMENT '0待处理 2已解决(撤销判废) 3已驳回',
  decision   VARCHAR(255) NULL,
  created_at DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  KEY idx_status (status, created_at)
) ENGINE=InnoDB COMMENT='申诉工单';

-- 题目裁决原因
ALTER TABLE question ADD COLUMN review_reason VARCHAR(255) NULL COMMENT '机审/人工裁决原因' AFTER review_status;
