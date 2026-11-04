-- ============================================================
-- 「学习解锁」V1 核心建表脚本
-- 依据：App PRD v1.4 §10 数据实体 + 技术架构 §5 数据模型
-- 约定：金额单位=分(整型)；幂等键唯一索引；敏感字段应用层加密
-- ============================================================

CREATE DATABASE IF NOT EXISTS xuexijiesuo DEFAULT CHARSET utf8mb4 COLLATE utf8mb4_unicode_ci;
USE xuexijiesuo;

-- 1. 家庭账号（主体=家长）
CREATE TABLE IF NOT EXISTS family_account (
  id            BIGINT AUTO_INCREMENT PRIMARY KEY,
  phone_enc     VARBINARY(128) NOT NULL COMMENT '手机号(应用层加密)',
  phone_hash    CHAR(64)    NOT NULL COMMENT '手机号SHA256(查询索引用)',
  wx_openid     VARCHAR(64)  NULL,
  wx_unionid    VARCHAR(64)  NULL,
  kyc_status    TINYINT      NOT NULL DEFAULT 0 COMMENT '实名核验 0未 1已',
  cred_version  INT          NOT NULL DEFAULT 1 COMMENT '凭证版本(全局撤销用)',
  status        TINYINT      NOT NULL DEFAULT 1 COMMENT '1正常 2封禁',
  created_at    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  UNIQUE KEY uk_phone_hash (phone_hash),
  UNIQUE KEY uk_unionid (wx_unionid)
) ENGINE=InnoDB COMMENT='家庭账号';

-- 2. 学生档案（家庭账号 1—N）
CREATE TABLE IF NOT EXISTS student_profile (
  id           BIGINT AUTO_INCREMENT PRIMARY KEY,
  family_id    BIGINT      NOT NULL,
  nickname     VARCHAR(32) NOT NULL,
  grade        TINYINT     NOT NULL COMMENT '年级 1-12',
  status       TINYINT     NOT NULL DEFAULT 1,
  created_at   DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at   DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  KEY idx_family (family_id)
) ENGINE=InnoDB COMMENT='学生档案';

-- 3. 设备绑定（学生档案 1—1 管控设备；SVIP 最多3档案/设备，配额见FR-37）
CREATE TABLE IF NOT EXISTS device_bind (
  id                  BIGINT AUTO_INCREMENT PRIMARY KEY,
  family_id           BIGINT      NOT NULL,
  profile_id          BIGINT      NULL COMMENT '学生模式绑定档案',
  device_fingerprint  CHAR(64)    NOT NULL COMMENT '混合设备指纹',
  device_name         VARCHAR(64) NULL,
  device_mode         TINYINT     NOT NULL COMMENT '1学生 2家长',
  os_type             TINYINT     NOT NULL COMMENT '1Android 2iOS',
  bind_status         TINYINT     NOT NULL DEFAULT 1 COMMENT '1绑定 0解绑',
  last_heartbeat_at   DATETIME    NULL COMMENT '在线判定:72h窗口(FR-37)',
  created_at          DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at          DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  UNIQUE KEY uk_fingerprint_mode (device_fingerprint, device_mode),
  KEY idx_profile (profile_id),
  KEY idx_heartbeat (bind_status, last_heartbeat_at)
) ENGINE=InnoDB COMMENT='设备绑定与配额';

-- 4. 题目
CREATE TABLE IF NOT EXISTS question (
  id            BIGINT AUTO_INCREMENT PRIMARY KEY,
  family_id     BIGINT      NULL COMMENT 'NULL=平台内置题库',
  subject       TINYINT     NOT NULL COMMENT '科目 1-10',
  grade         TINYINT     NOT NULL COMMENT '年级 1-12',
  difficulty    TINYINT     NOT NULL COMMENT '难度 L1-L5',
  q_type        TINYINT     NOT NULL COMMENT '题型 1单选 2多选 3判断 4填空',
  stem          TEXT        NOT NULL COMMENT '题干(机审通过)',
  options_json  JSON        NULL COMMENT '选项',
  answer        VARCHAR(512) NOT NULL,
  analysis      TEXT        NULL COMMENT '解析(不下发离线题包)',
  source        TINYINT     NOT NULL COMMENT '1内置 2家长自建',
  status        TINYINT     NOT NULL DEFAULT 0 COMMENT '0草稿 1上架 2下架 3回收站',
  review_status TINYINT     NOT NULL DEFAULT 0 COMMENT '机审 0通过 1待人工 2驳回',
  created_at    DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at    DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  KEY idx_filter (subject, grade, difficulty, status),
  KEY idx_family (family_id, status)
) ENGINE=InnoDB COMMENT='题目';

-- 5. 解锁任务（配置快照+判分，幂等键唯一）
CREATE TABLE IF NOT EXISTS quiz_task (
  id              BIGINT AUTO_INCREMENT PRIMARY KEY,
  profile_id      BIGINT      NOT NULL,
  config_snapshot JSON        NOT NULL COMMENT '任务时配置快照(数量/时长/门槛)',
  question_ids    JSON        NOT NULL,
  score           INT         NULL COMMENT '得分 0-100',
  threshold       INT         NOT NULL COMMENT '门槛分数',
  result          TINYINT     NOT NULL DEFAULT 0 COMMENT '0进行 1达标 2未达标 3作废(作弊)',
  abnormal_flag   TINYINT     NOT NULL DEFAULT 0 COMMENT '异常快答/切屏标记',
  idempotent_key  CHAR(64)    NOT NULL COMMENT 'profileId+taskId+ts',
  started_at      DATETIME    NULL,
  submitted_at    DATETIME    NULL,
  created_at      DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP,
  UNIQUE KEY uk_idempotent (idempotent_key),
  KEY idx_profile_time (profile_id, created_at)
) ENGINE=InnoDB COMMENT='解锁任务';

-- 6. 作答记录
CREATE TABLE IF NOT EXISTS answer_record (
  id           BIGINT AUTO_INCREMENT PRIMARY KEY,
  task_id      BIGINT      NOT NULL,
  question_id  BIGINT      NOT NULL,
  answer       VARCHAR(512) NOT NULL,
  is_correct   TINYINT     NULL,
  cost_ms      INT         NOT NULL DEFAULT 0,
  switch_count INT         NOT NULL DEFAULT 0 COMMENT '切屏次数',
  created_at   DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP,
  KEY idx_task (task_id)
) ENGINE=InnoDB COMMENT='作答记录';

-- 7. 解锁会话（状态机见 PRD 9.2）
CREATE TABLE IF NOT EXISTS unlock_session (
  id           BIGINT AUTO_INCREMENT PRIMARY KEY,
  profile_id   BIGINT      NOT NULL,
  task_id      BIGINT      NULL,
  state        TINYINT     NOT NULL COMMENT '1锁定 2出题 3答题 4判分 5已解锁 6未达标',
  deadline_at  DATETIME    NULL COMMENT '会话到期(服务端时间)',
  fail_open    TINYINT     NOT NULL DEFAULT 0 COMMENT '失联临时开放(E-15)',
  created_at   DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP,
  ended_at     DATETIME    NULL,
  KEY idx_profile_state (profile_id, state)
) ENGINE=InnoDB COMMENT='解锁会话';

-- 8. 会员状态
CREATE TABLE IF NOT EXISTS member (
  id          BIGINT AUTO_INCREMENT PRIMARY KEY,
  family_id   BIGINT   NOT NULL,
  tier        TINYINT  NOT NULL DEFAULT 0 COMMENT '0无 1VIP 2SVIP',
  expire_at   DATETIME NULL COMMENT '9999-12-31 表示永久',
  auto_renew  TINYINT  NOT NULL DEFAULT 0,
  created_at  DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at  DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  UNIQUE KEY uk_family (family_id)
) ENGINE=InnoDB COMMENT='会员状态';

-- 9. 会员订单（资金三件套核心表：幂等键=渠道订单号）
CREATE TABLE IF NOT EXISTS vip_order (
  id               BIGINT AUTO_INCREMENT PRIMARY KEY,
  order_no         VARCHAR(32)  NOT NULL COMMENT '业务单号',
  family_id        BIGINT       NOT NULL,
  sku              TINYINT      NOT NULL COMMENT '1月卡9 2年卡99 3VIP永久199 4SVIP永久299',
  amount           INT          NOT NULL COMMENT '分',
  channel          TINYINT      NOT NULL COMMENT '1微信 2支付宝 3IAP',
  channel_order_no VARCHAR(64)  NULL COMMENT '渠道订单号=发货幂等键(FR-28)',
  status           TINYINT      NOT NULL DEFAULT 0 COMMENT '0待支付 1已支付 2已发货 3已关闭 4退款中 5已退款',
  shipped_at       DATETIME     NULL,
  refund_at        DATETIME     NULL,
  created_at       DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
  UNIQUE KEY uk_order_no (order_no),
  UNIQUE KEY uk_channel_order (channel_order_no),
  KEY idx_family_time (family_id, created_at),
  KEY idx_status (status)
) ENGINE=InnoDB COMMENT='会员订单';

-- 10. 免费时长账户（试用+激励视频叠加）
CREATE TABLE IF NOT EXISTS free_time_account (
  id               BIGINT AUTO_INCREMENT PRIMARY KEY,
  family_id        BIGINT   NOT NULL,
  trial_expire_at  DATETIME NULL COMMENT '3天试用(FR-26)',
  ad_free_expire_at DATETIME NULL COMMENT '视频换天数叠加到期(FR-27)',
  daily_granted    INT      NOT NULL DEFAULT 0 COMMENT '当日已发天数(上限10)',
  daily_date       DATE     NULL,
  updated_at       DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  UNIQUE KEY uk_family (family_id)
) ENGINE=InnoDB COMMENT='免费时长账户';

-- 11. 积分账户与流水（学生维度）
CREATE TABLE IF NOT EXISTS point_account (
  id         BIGINT AUTO_INCREMENT PRIMARY KEY,
  profile_id BIGINT NOT NULL,
  balance    INT    NOT NULL DEFAULT 0,
  updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  UNIQUE KEY uk_profile (profile_id)
) ENGINE=InnoDB COMMENT='积分账户';

CREATE TABLE IF NOT EXISTS point_flow (
  id         BIGINT AUTO_INCREMENT PRIMARY KEY,
  profile_id BIGINT      NOT NULL,
  delta      INT         NOT NULL COMMENT '正获取负消费',
  reason     TINYINT     NOT NULL COMMENT '1答题 2达标奖 3打卡 4兑换 5过期',
  ref_id     VARCHAR(64) NULL,
  created_at DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP,
  KEY idx_profile_time (profile_id, created_at)
) ENGINE=InnoDB COMMENT='积分流水';

-- 12. 勋章与打卡
CREATE TABLE IF NOT EXISTS profile_medal (
  id         BIGINT AUTO_INCREMENT PRIMARY KEY,
  profile_id BIGINT      NOT NULL,
  medal_code VARCHAR(32) NOT NULL,
  granted_at DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP,
  UNIQUE KEY uk_profile_medal (profile_id, medal_code)
) ENGINE=InnoDB COMMENT='勋章';

CREATE TABLE IF NOT EXISTS checkin_record (
  id            BIGINT AUTO_INCREMENT PRIMARY KEY,
  profile_id    BIGINT   NOT NULL,
  checkin_date  DATE     NOT NULL,
  is_supplement TINYINT  NOT NULL DEFAULT 0 COMMENT '补签卡(FR-21)',
  created_at    DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  UNIQUE KEY uk_profile_date (profile_id, checkin_date)
) ENGINE=InnoDB COMMENT='打卡记录';

-- 13. 风控事件与家长告警
CREATE TABLE IF NOT EXISTS risk_event (
  id         BIGINT AUTO_INCREMENT PRIMARY KEY,
  family_id  BIGINT      NOT NULL,
  profile_id BIGINT      NULL,
  type       VARCHAR(8)  NOT NULL COMMENT 'T-01~T-08',
  detail     JSON        NULL,
  created_at DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP,
  KEY idx_family_time (family_id, created_at)
) ENGINE=InnoDB COMMENT='风控事件';

CREATE TABLE IF NOT EXISTS alarm (
  id         BIGINT AUTO_INCREMENT PRIMARY KEY,
  family_id  BIGINT       NOT NULL,
  type       VARCHAR(32)  NOT NULL,
  title      VARCHAR(128) NOT NULL,
  content    VARCHAR(512) NULL,
  read_flag  TINYINT      NOT NULL DEFAULT 0,
  created_at DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
  KEY idx_family_read (family_id, read_flag, created_at)
) ENGINE=InnoDB COMMENT='家长告警';

-- 14. 后台审计日志（append-only，哈希链；业务账号禁 UPDATE/DELETE）
CREATE TABLE IF NOT EXISTS audit_log (
  id           BIGINT AUTO_INCREMENT PRIMARY KEY,
  operator_id  BIGINT      NOT NULL,
  module       VARCHAR(32) NOT NULL,
  action       VARCHAR(64) NOT NULL,
  target_type  VARCHAR(32) NULL,
  target_id    VARCHAR(64) NULL,
  before_json  JSON        NULL,
  after_json   JSON        NULL,
  ip           VARCHAR(45) NULL,
  prev_hash    CHAR(64)    NOT NULL,
  self_hash    CHAR(64)    NOT NULL,
  created_at   DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP,
  KEY idx_operator_time (operator_id, created_at)
) ENGINE=InnoDB COMMENT='后台审计日志(哈希链)';

-- 15. 系统配置（价格/试用/灰度开关，服务端下发，客户端禁硬编码）
CREATE TABLE IF NOT EXISTS sys_config (
  id           BIGINT AUTO_INCREMENT PRIMARY KEY,
  config_key   VARCHAR(64)  NOT NULL,
  config_value VARCHAR(512) NOT NULL,
  updated_at   DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  UNIQUE KEY uk_key (config_key)
) ENGINE=InnoDB COMMENT='系统配置';

-- 初始配置
INSERT IGNORE INTO sys_config (config_key, config_value) VALUES
  ('sku.month.price',   '900'),
  ('sku.year.price',    '9900'),
  ('sku.vip.price',     '19900'),
  ('sku.svip.price',    '29900'),
  ('trial.days',        '3'),
  ('ad.daily.limit',    '10'),
  ('failopen.hours',    '2'),
  ('device.quota.free',  '1,2'),
  ('device.quota.vip',   '1,4'),
  ('device.quota.svip',  '3,1');
