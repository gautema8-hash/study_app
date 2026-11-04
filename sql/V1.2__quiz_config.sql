-- V1.2 家长 DIY 配置（FR-08：每次题数/时长/门槛/会话有效期/每日挑战上限；科目权重 v2 再加列）
USE xuexijiesuo;
CREATE TABLE IF NOT EXISTS quiz_config (
  id             BIGINT AUTO_INCREMENT PRIMARY KEY,
  family_id      BIGINT   NOT NULL,
  count          INT      NOT NULL DEFAULT 10  COMMENT '每次题数 5-30',
  duration_min   INT      NOT NULL DEFAULT 10  COMMENT '答题时长 3-30 分钟',
  threshold      INT      NOT NULL DEFAULT 80  COMMENT '分数门槛 60-100',
  session_minutes INT     NOT NULL DEFAULT 60  COMMENT '会话有效期 15-240 分钟',
  daily_limit    INT      NOT NULL DEFAULT 3   COMMENT '每日挑战次数 1-10',
  updated_at     DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  UNIQUE KEY uk_family (family_id)
) ENGINE=InnoDB COMMENT='家长DIY配置';
