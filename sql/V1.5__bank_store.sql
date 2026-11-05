-- ============================================================
-- V1.5 题库商店（PRD《题库商店功能需求文档》v1.0 §5 数据模型）
-- 依赖：V1__init.sql（question / family_account / student_profile）
-- 约定：与既有表一致——BIGINT 主键、DATETIME 默认当前时间、utf8mb4
-- ============================================================
USE xuexijiesuo;

-- 1. 题库（题包商品）
CREATE TABLE IF NOT EXISTS bank_store_item (
  id             BIGINT AUTO_INCREMENT PRIMARY KEY,
  code           VARCHAR(64)  NOT NULL COMMENT '业务编码(幂等/对外引用)',
  title          VARCHAR(64)  NOT NULL COMMENT '题库名称',
  description    VARCHAR(200) NULL COMMENT '简介',
  subject        TINYINT      NOT NULL COMMENT '科目 1-10',
  grade          TINYINT      NOT NULL COMMENT '年级 1-12',
  question_count INT          NOT NULL DEFAULT 0 COMMENT '题量(冗余)',
  download_count INT          NOT NULL DEFAULT 0 COMMENT '累计下载量(家长首次添加+1)',
  status         TINYINT      NOT NULL DEFAULT 0 COMMENT '0草稿 1待审核 2已上架 3已下架 4驳回',
  created_by     BIGINT       NOT NULL COMMENT '创建管理员(admin_user.id)',
  reviewed_by    BIGINT       NULL COMMENT '审核人',
  reviewed_at    DATETIME     NULL,
  review_reason  VARCHAR(200) NULL COMMENT '驳回原因',
  published_at   DATETIME     NULL COMMENT '最近上架时间',
  created_at     DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at     DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  UNIQUE KEY uk_code (code),
  KEY idx_status (status, subject, grade),
  KEY idx_title (title)
) ENGINE=InnoDB COMMENT='题库商店-题包';

-- 2. 题包-题目关联
CREATE TABLE IF NOT EXISTS bank_store_question (
  id            BIGINT AUTO_INCREMENT PRIMARY KEY,
  store_item_id BIGINT NOT NULL,
  question_id   BIGINT NOT NULL COMMENT 'question.id(内置题 family_id IS NULL)',
  sort_no       INT    NOT NULL DEFAULT 0,
  UNIQUE KEY uk_item_question (store_item_id, question_id),
  KEY idx_question (question_id)
) ENGINE=InnoDB COMMENT='题包-题目关联';

-- 3. 运营标签
CREATE TABLE IF NOT EXISTS bank_store_tag (
  id         BIGINT AUTO_INCREMENT PRIMARY KEY,
  name       VARCHAR(16) NOT NULL COMMENT '标签名(≤16字)',
  color      VARCHAR(16) NOT NULL DEFAULT 'blue' COMMENT '展示色',
  created_at DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP,
  UNIQUE KEY uk_name (name)
) ENGINE=InnoDB COMMENT='题库商店-标签';

-- 4. 题包-标签关联(单题包≤5,应用层校验)
CREATE TABLE IF NOT EXISTS bank_store_item_tag (
  store_item_id BIGINT NOT NULL,
  tag_id        BIGINT NOT NULL,
  UNIQUE KEY uk_item_tag (store_item_id, tag_id),
  KEY idx_tag (tag_id)
) ENGINE=InnoDB COMMENT='题包-标签关联';

-- 5. 家长添加记录(幂等:一家庭一题库一条)
CREATE TABLE IF NOT EXISTS family_bank_store (
  id            BIGINT AUTO_INCREMENT PRIMARY KEY,
  family_id     BIGINT   NOT NULL,
  store_item_id BIGINT   NOT NULL,
  added_at      DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  UNIQUE KEY uk_family_item (family_id, store_item_id),
  KEY idx_item (store_item_id)
) ENGINE=InnoDB COMMENT='家庭-已添加题库';

-- 6. 添加时题目复制映射(支撑移除回收)
CREATE TABLE IF NOT EXISTS family_bank_store_question (
  id            BIGINT AUTO_INCREMENT PRIMARY KEY,
  family_bank_id BIGINT NOT NULL COMMENT 'family_bank_store.id',
  question_id   BIGINT NOT NULL COMMENT '复制生成的家庭题目 question.id',
  UNIQUE KEY uk_bank_question (family_bank_id, question_id),
  KEY idx_question (question_id)
) ENGINE=InnoDB COMMENT='已添加题库-复制题目映射';

-- 7. 分配关系(家长把已添加题库授权给子女;幂等:一题库一子女一条)
CREATE TABLE IF NOT EXISTS family_bank_assign (
  id            BIGINT AUTO_INCREMENT PRIMARY KEY,
  family_id     BIGINT   NOT NULL,
  store_item_id BIGINT   NOT NULL,
  profile_id    BIGINT   NOT NULL COMMENT 'student_profile.id(须属于family_id)',
  status        TINYINT  NOT NULL DEFAULT 1 COMMENT '1已分配 0已收回',
  assigned_at   DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at    DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  UNIQUE KEY uk_item_profile (store_item_id, profile_id),
  KEY idx_family_profile (family_id, profile_id, status)
) ENGINE=InnoDB COMMENT='题库-子女分配关系';

-- 初始标签(运营可在后台增删)
INSERT IGNORE INTO bank_store_tag (name, color) VALUES
  ('期末冲刺', 'blue'),
  ('思维训练', 'purple'),
  ('基础巩固', 'green'),
  ('培优拔高', 'gold'),
  ('开学衔接', 'cyan');
