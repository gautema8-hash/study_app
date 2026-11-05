-- ============================================================
-- V1.6 测试账号与初始数据（开发/联调用，生产环境禁止执行）
-- 依赖：V1__init.sql / V1.3__admin.sql / V1.5__bank_store.sql
-- 约定：口令用 PLAIN: 引导态，首次登录自动升级 bcrypt（OpsController.adminLogin）
--       固定 ID 段 9001+，INSERT IGNORE 幂等，可重复执行
-- ============================================================
USE xuexijiesuo;

-- ---------- 1. 后台测试账号（角色覆盖 RBAC 权限矩阵） ----------
-- admin    / admin123    ADMIN     全部写权限（V1.3 已有，此处兜底）
INSERT IGNORE INTO admin_user(username, password_hash, role) VALUES
  ('admin',    'PLAIN:admin123',    'ADMIN'),
  ('reviewer', 'PLAIN:reviewer123', 'REVIEWER'),  -- 题库商店审核裁决（FR-S04）
  ('ops',      'PLAIN:ops123',      'OPS'),       -- 题库录入/编辑/提交，无审核权
  ('cs',       'PLAIN:cs123',       'CS'),        -- 客服工单
  ('finance',  'PLAIN:finance123',  'FINANCE'),   -- 价格配置
  ('viewer',   'PLAIN:viewer123',   'VIEWER');    -- 只读

-- ---------- 2. 平台内置题目（family_id IS NULL, source=1, 上架且机审通过） ----------
INSERT IGNORE INTO question(id, family_id, subject, grade, difficulty, q_type, stem, options_json, answer, analysis, source, status, review_status) VALUES
  (9001, NULL, 2, 3, 1, 1, '3 + 5 = ?', '["6","7","8","9"]', 'C', '加法：3+5=8，选 C。', 1, 1, 0),
  (9002, NULL, 2, 3, 2, 1, '12 - 4 = ?', '["6","7","8","9"]', 'C', '减法：12-4=8，选 C。', 1, 1, 0),
  (9003, NULL, 2, 3, 3, 4, '小明有 15 颗糖，分给同学 6 颗，还剩____颗。', NULL, '9', '15-6=9。', 1, 1, 0),
  (9004, NULL, 2, 4, 2, 1, '25 × 4 = ?', '["80","90","100","110"]', 'C', '25×4=100，选 C。', 1, 1, 0),
  (9005, NULL, 1, 3, 1, 3, '「举头望明月」的作者是李白。', '["正确","错误"]', 'A', '出自李白《静夜思》。', 1, 1, 0),
  (9006, NULL, 1, 3, 2, 4, '「床前明月光」的下一句是「____」。', NULL, '疑是地上霜', '《静夜思》第二句。', 1, 1, 0),
  (9007, NULL, 3, 3, 1, 1, 'apple 的中文意思是？', '["香蕉","苹果","橘子","葡萄"]', 'B', 'apple=苹果，选 B。', 1, 1, 0),
  (9008, NULL, 3, 4, 2, 1, '选择正确的be动词：She ____ a student.', '["am","is","are","be"]', 'B', '主语第三人称单数用 is。', 1, 1, 0),
  (9009, NULL, 4, 8, 3, 1, '光在真空中的传播速度约为？', '["3×10⁶ m/s","3×10⁸ m/s","3×10¹⁰ m/s","3×10⁴ m/s"]', 'B', 'c≈3×10⁸ m/s，选 B。', 1, 1, 0),
  (9010, NULL, 2, 5, 4, 4, '一个长方形周长 24cm，长 8cm，宽是____cm。', NULL, '4', '(24-2×8)÷2=4。', 1, 1, 0);

-- ---------- 3. 题库商店示例题包（覆盖状态机各态，ID 固定 9101+） ----------
INSERT IGNORE INTO bank_store_item(id, code, title, description, subject, grade, question_count, download_count, status, created_by) VALUES
  (9101, 'BS-DEMO-101', '三年级数学·口算起步', '10 道加减乘除口算题，覆盖 L1-L3 难度，适合三年级课后巩固。', 2, 3, 3, 128, 2, 1),
  (9102, 'BS-DEMO-102', '三年级语文·古诗过关', '《静夜思》等古诗默写与判断，配合诗句填空。', 1, 3, 2, 66, 2, 1),
  (9103, 'BS-DEMO-103', '三年级英语·单词闯关', '常见单词中英互译，含 be 动词基础。', 3, 3, 2, 0, 1, 1),
  (9104, 'BS-DEMO-104', '四年级数学·乘法专项', '两位数乘法专项练习（草稿态，仅供编辑演示）。', 2, 4, 1, 0, 0, 1);

INSERT IGNORE INTO bank_store_question(store_item_id, question_id, sort_no) VALUES
  (9101, 9001, 0), (9101, 9002, 1), (9101, 9003, 2),
  (9102, 9005, 0), (9102, 9006, 1),
  (9103, 9007, 0), (9103, 9008, 1),
  (9104, 9004, 0);

-- 标签关联（标签由 V1.5 种子提供；9101 挂「基础巩固」，9102 挂「期末冲刺」）
INSERT IGNORE INTO bank_store_item_tag(store_item_id, tag_id)
  SELECT 9101, id FROM bank_store_tag WHERE name='基础巩固';
INSERT IGNORE INTO bank_store_item_tag(store_item_id, tag_id)
  SELECT 9102, id FROM bank_store_tag WHERE name='期末冲刺';

-- ---------- 4. 演示家庭与子女（家长端添加/分配联调用） ----------
-- 手机号 13800000001（明文存 phone_enc，phone_hash=SHA256，开发态；生产须应用层加密）
INSERT IGNORE INTO family_account(id, phone_enc, phone_hash, kyc_status, status) VALUES
  (9001, '13800000001', 'b1c4769e3ad14f68ea1a96b73bbe5a83d90792f044828c5daf9d908b0738b177', 1, 1);

INSERT IGNORE INTO student_profile(id, family_id, nickname, grade, status) VALUES
  (9001, 9001, '小乐', 3, 1),
  (9002, 9001, '小欣', 5, 1);

-- 演示家庭已添加 9101 并分配给小乐（可选，便于直接验证「我的题库」）：
-- INSERT IGNORE INTO family_bank_store(family_id, store_item_id) VALUES (9001, 9101);
-- 注意：若启用上行，还需同步 family_bank_store_question 映射与题目复制，建议走家长端「添加」接口完成。

-- ---------- 5. 验证 ----------
-- 1) 管理端：admin/admin123 登录 → 题库商店 → 看到 4 个示例题包（2 上架/1 待审核/1 草稿）
-- 2) 审核流：reviewer/reviewer123 登录 → 审核 9103（通过后家长端可见）
-- 3) 家长端：用 13800000001 登录 → 商店可见 9101/9102（及审核通过后的 9103）→ 添加 → 分配给小乐
