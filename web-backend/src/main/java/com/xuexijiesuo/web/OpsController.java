package com.xuexijiesuo.web;

import java.sql.PreparedStatement;
import java.sql.Statement;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.bind.annotation.*;

/**
 * 运营汇总（W6，供 admin-web 工作台 W1）。
 * TODO(W安全迭代): 接入后台 RBAC 鉴权后加管理员校验；当前仅内网/开发可用。
 */
@RestController
@RequestMapping("/api/ops")
public class OpsController {

    private final JdbcClient db;
    private final JdbcTemplate jt;
    private final WebTokenService tokens;

    public OpsController(JdbcClient db, JdbcTemplate jt, WebTokenService tokens) {
        this.db = db;
        this.jt = jt;
        this.tokens = tokens;
    }

    public record AdminLoginReq(String username, String password) {}

    // 后台登录（FR-W01：账号密码 → admin scope JWT；PLAIN 引导态首次登录自动升级 bcrypt）
    @PostMapping("/auth/login")
    public Map<String, Object> adminLogin(@RequestBody AdminLoginReq req) {
        var row = db.sql("SELECT id, password_hash, role, status FROM admin_user WHERE is_delete=0 AND username=?")
            .param(req.username() == null ? "" : req.username())
            .query((rs, i) -> Map.of(
                "id", rs.getLong("id"),
                "hash", rs.getString("password_hash"),
                "role", rs.getString("role"),
                "status", rs.getInt("status"))).optional().orElse(null);
        if (row == null || ((Number) row.get("status")).intValue() != 1)
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "账号不存在或已停用");
        String hash = (String) row.get("hash");
        boolean ok;
        if (hash.startsWith("PLAIN:")) {
            ok = hash.substring(6).equals(req.password());
            if (ok) jt.update("UPDATE admin_user SET password_hash=? WHERE id=? AND is_delete=0",
                new BCryptPasswordEncoder().encode(req.password()), row.get("id")); // 升级为 bcrypt
        } else {
            ok = new BCryptPasswordEncoder().matches(req.password() == null ? "" : req.password(), hash);
        }
        if (!ok) throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "密码错误");
        String token = tokens.createAccess(((Number) row.get("id")).longValue(), "admin:" + row.get("role"), null, 1);
        return ok(Map.of("accessToken", token, "role", row.get("role")));
    }

    @GetMapping("/auth/me")
    public Map<String, Object> adminMe(jakarta.servlet.http.HttpServletRequest request) {
        return ok(Map.of("role", request.getAttribute("adminRole")));
    }

    // ---------- W5.1 用户列表（手机号脱敏，FR-W04） ----------
    @GetMapping("/users")
    public Map<String, Object> users(@RequestParam(defaultValue = "1") Integer page,
                                     @RequestParam(defaultValue = "20") Integer size) {
        size = Math.min(size, 100);
        Long total = db.sql("SELECT COUNT(*) FROM family_account WHERE is_delete=0").query(Long.class).single();
        List<Map<String, Object>> list = db.sql("""
                SELECT f.id, f.phone_enc, f.kyc_status, f.status, f.created_at,
                       COALESCE(m.tier,0) AS tier, m.expire_at,
                       (SELECT COUNT(*) FROM student_profile s WHERE s.family_id=f.id AND s.is_delete=0) AS students
                FROM family_account f LEFT JOIN member m ON m.family_id=f.id AND m.is_delete=0
                WHERE f.is_delete=0
                ORDER BY f.id DESC LIMIT ? OFFSET ?
                """).param(size).param((page - 1) * size)
            .query((rs, i) -> {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("familyId", rs.getLong("id"));
                byte[] enc = rs.getBytes("phone_enc");
                String phone = enc == null ? "" : new String(enc, java.nio.charset.StandardCharsets.UTF_8);
                m.put("phone", phone.length() >= 7 ? phone.substring(0, 3) + "****" + phone.substring(phone.length() - 4) : "***");
                m.put("kyc", rs.getInt("kyc_status") == 1);
                m.put("tier", rs.getInt("tier"));
                m.put("expireAt", rs.getString("expire_at"));
                m.put("students", rs.getInt("students"));
                m.put("createdAt", rs.getString("created_at"));
                return m;
            }).list();
        return ok(Map.of("total", total, "list", list));
    }

    // ---------- W5.2 订单列表（全部家庭，资金台账） ----------
    @GetMapping("/orders")
    public Map<String, Object> orders(@RequestParam(defaultValue = "1") Integer page,
                                      @RequestParam(defaultValue = "20") Integer size) {
        size = Math.min(size, 100);
        Long total = db.sql("SELECT COUNT(*) FROM vip_order WHERE is_delete=0").query(Long.class).single();
        List<Map<String, Object>> list = db.sql("""
                SELECT order_no, family_id, sku, amount, channel, status, created_at, shipped_at
                FROM vip_order WHERE is_delete=0 ORDER BY id DESC LIMIT ? OFFSET ?
                """).param(size).param((page - 1) * size)
            .query((rs, i) -> {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("orderNo", rs.getString("order_no"));
                m.put("familyId", rs.getLong("family_id"));
                m.put("sku", rs.getInt("sku"));
                m.put("amount", rs.getInt("amount"));
                m.put("status", rs.getInt("status"));
                m.put("createdAt", rs.getString("created_at"));
                m.put("shippedAt", rs.getString("shipped_at"));
                return m;
            }).list();
        return ok(Map.of("total", total, "list", list));
    }

    // ---------- W5.3 题库全量列表（内置+自建，FR-W11 运营视角） ----------
    @GetMapping("/questions")
    public Map<String, Object> questions(@RequestParam(defaultValue = "1") Integer page,
                                         @RequestParam(defaultValue = "20") Integer size,
                                         @RequestParam(required = false) Integer subject,
                                         @RequestParam(required = false) Integer grade) {
        size = Math.min(size, 100);
        StringBuilder where = new StringBuilder(" WHERE is_delete=0 AND status IN (1,2)");
        List<Object> params = new java.util.ArrayList<>();
        if (subject != null) { where.append(" AND subject=?"); params.add(subject); }
        if (grade != null) { where.append(" AND grade=?"); params.add(grade); }
        Long total = db.sql("SELECT COUNT(*) FROM question" + where).params(params).query(Long.class).single();
        List<Map<String, Object>> list = db.sql("SELECT id, family_id, subject, grade, difficulty, q_type, stem, source, status, created_at FROM question"
                + where + " ORDER BY id DESC LIMIT ? OFFSET ?")
            .params(params).param(size).param((page - 1) * size)
            .query((rs, i) -> {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("id", rs.getLong("id"));
                m.put("familyId", rs.getObject("family_id"));
                m.put("subject", rs.getInt("subject"));
                m.put("grade", rs.getInt("grade"));
                m.put("difficulty", rs.getInt("difficulty"));
                m.put("stem", rs.getString("stem"));
                m.put("source", rs.getInt("source") == 1 ? "内置" : "自建");
                m.put("status", rs.getInt("status"));
                m.put("createdAt", rs.getString("created_at"));
                return m;
            }).list();
        return ok(Map.of("total", total, "list", list));
    }

    // ---------- W5.4 告警列表（全局） ----------
    @GetMapping("/alarms")
    public Map<String, Object> alarms(@RequestParam(defaultValue = "1") Integer page) {
        List<Map<String, Object>> list = db.sql("""
                SELECT a.id, a.family_id, a.type, a.title, a.content, a.read_flag, a.created_at
                FROM alarm a WHERE a.is_delete=0 ORDER BY a.id DESC LIMIT 50 OFFSET ?
                """).param((page - 1) * 50)
            .query((rs, i) -> {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("id", rs.getLong("id"));
                m.put("familyId", rs.getLong("family_id"));
                m.put("type", rs.getString("type"));
                m.put("title", rs.getString("title"));
                m.put("content", rs.getString("content"));
                m.put("read", rs.getInt("read_flag") == 1);
                m.put("createdAt", rs.getString("created_at"));
                return m;
            }).list();
        return ok(Map.of("list", list));
    }

    // ---------- W5.5 价格配置（FR-W18：写 sys_config，双人复核与审计 TODO） ----------
    @GetMapping("/config")
    public Map<String, Object> config() {
        Map<String, Object> cfg = new LinkedHashMap<>();
        db.sql("SELECT config_key, config_value FROM sys_config WHERE (config_key LIKE 'sku.%' OR config_key LIKE 'trial%' OR config_key LIKE 'ad.%') AND is_delete=0")
            .query((rs, i) -> {
                cfg.put(rs.getString("config_key"), rs.getString("config_value"));
                return cfg;
            }).list(); // JdbcClient 惰性执行：必须终结操作才触发查询
        return ok(cfg);
    }

    @PutMapping("/config/{key}")
    public Map<String, Object> updateConfig(@PathVariable String key, @RequestBody Map<String, String> body) {
        if (!key.matches("^(sku\\.(month|year|vip|svip)\\.price|trial\\.days|ad\\.daily\\.limit)$"))
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "非法配置键");
        String value = body.get("value");
        if (value == null || !value.matches("^\\d{1,8}$")) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "值须为数字");
        int n = db.sql("UPDATE sys_config SET config_value=? WHERE config_key=? AND is_delete=0").param(value).param(key).update();
        if (n == 0) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "配置键不存在");
        return ok(Map.of("key", key, "value", value));
    }

    // ---------- W6.1 审核工单（FR-W12：家长题目人工复核，REVIEWER/ADMIN 写） ----------
    @GetMapping("/review/tickets")
    public Map<String, Object> reviewTickets(@RequestParam(defaultValue = "1") Integer status) {
        List<Map<String, Object>> list = db.sql("""
                SELECT id, family_id, subject, grade, difficulty, stem, review_status, review_reason, created_at
                FROM question WHERE is_delete=0 AND review_status=? ORDER BY id DESC LIMIT 100
                """).param(status)
            .query((rs, i) -> {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("id", rs.getLong("id"));
                m.put("familyId", rs.getLong("family_id"));
                m.put("subject", rs.getInt("subject"));
                m.put("grade", rs.getInt("grade"));
                m.put("stem", rs.getString("stem"));
                m.put("reason", rs.getString("review_reason"));
                m.put("createdAt", rs.getString("created_at"));
                return m;
            }).list();
        return ok(Map.of("list", list));
    }

    public record ReviewDecideReq(boolean pass, String reason) {}

    @PostMapping("/review/tickets/{id}/decide")
    public Map<String, Object> reviewDecide(@PathVariable Long id, @RequestBody ReviewDecideReq req) {
        int n = jt.update("UPDATE question SET review_status=?, review_reason=? WHERE id=? AND review_status=1 AND is_delete=0",
            req.pass() ? 0 : 2, (req.pass() ? "人工复核通过" : "人工复核驳回: " + req.reason()), id);
        if (n == 0) throw bad("工单不存在或已裁决");
        return ok(Map.of("decided", true));
    }

    // ---------- W6.1 复核工单 逻辑删除（直接移除灰词题目） ----------
    @DeleteMapping("/review/tickets/{id}")
    public Map<String, Object> deleteReviewTicket(@PathVariable Long id) {
        int n = jt.update("UPDATE question SET is_delete=1 WHERE id=? AND is_delete=0 AND review_status=1", id);
        if (n == 0) throw bad("工单不存在或已裁决");
        return ok(Map.of("deleted", true));
    }

    // ---------- W6.2 客服申诉工单（FR-W15/16，CS/ADMIN 写） ----------
    @GetMapping("/tickets")
    public Map<String, Object> tickets(@RequestParam(defaultValue = "0") Integer status) {
        List<Map<String, Object>> list = db.sql("""
                SELECT id, family_id, profile_id, task_id, type, content, status, decision, created_at
                FROM appeal_ticket WHERE is_delete=0 AND status=? ORDER BY id DESC LIMIT 100
                """).param(status)
            .query((rs, i) -> {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("id", rs.getLong("id"));
                m.put("familyId", rs.getLong("family_id"));
                m.put("profileId", rs.getObject("profile_id"));
                m.put("taskId", rs.getObject("task_id"));
                m.put("type", rs.getString("type"));
                m.put("content", rs.getString("content"));
                m.put("status", rs.getInt("status"));
                m.put("decision", rs.getString("decision"));
                m.put("createdAt", rs.getString("created_at"));
                return m;
            }).list();
        return ok(Map.of("list", list));
    }

    public record AppealDecideReq(boolean approve, String decision) {}

    @PostMapping("/tickets/{id}/decide")
    public Map<String, Object> ticketDecide(@PathVariable Long id, @RequestBody AppealDecideReq req) {
        var ticket = db.sql("SELECT task_id FROM appeal_ticket WHERE is_delete=0 AND id=? AND status=0").param(id)
            .query((rs, i) -> rs.getObject("task_id")).optional().orElse(null);
        if (ticket == null) throw bad("工单不存在或已裁决");
        jt.update("UPDATE appeal_ticket SET status=?, decision=? WHERE id=? AND is_delete=0",
            req.approve() ? 2 : 3, req.decision(), id);
        if (req.approve() && ticket instanceof Number taskIdNum) {
            // 撤销判废：恢复任务有效并按达标处理（积分补偿 TODO）
            jt.update("""
                UPDATE quiz_task SET result=1, score=threshold, abnormal_flag=0
                WHERE id=? AND result=3
                """, taskIdNum.longValue());
            jt.update("INSERT INTO alarm(family_id, type, title, content) VALUES((SELECT family_id FROM appeal_ticket WHERE id=? AND is_delete=0),'APPEAL_APPROVED','申诉通过','判废已撤销，任务恢复达标')", id);
        }
        return ok(Map.of("decided", true, "approved", req.approve()));
    }

    // ---------- W5.1 用户 查看/新增/编辑/逻辑删除 ----------
    @GetMapping("/users/{id}")
    public Map<String, Object> userDetail(@PathVariable Long id) {
        var m = db.sql("SELECT id, phone_enc, kyc_status, status, created_at FROM family_account WHERE id=? AND is_delete=0").param(id)
            .query((rs, i) -> {
                Map<String, Object> r = new LinkedHashMap<>();
                byte[] enc = rs.getBytes("phone_enc");
                String phone = enc == null ? "" : new String(enc, java.nio.charset.StandardCharsets.UTF_8);
                r.put("familyId", rs.getLong("id"));
                r.put("phone", phone);
                r.put("kyc", rs.getInt("kyc_status") == 1);
                r.put("status", rs.getInt("status"));
                r.put("createdAt", rs.getString("created_at"));
                return r;
            }).optional().orElse(null);
        if (m == null) throw bad("家庭不存在");
        m.put("students", db.sql("SELECT id, nickname, grade FROM student_profile WHERE family_id=? AND is_delete=0 ORDER BY id")
            .param(id).query((rs, i) -> {
                Map<String, Object> s = new LinkedHashMap<>();
                s.put("id", rs.getLong("id"));
                s.put("nickname", rs.getString("nickname"));
                s.put("grade", rs.getInt("grade"));
                return s;
            }).list());
        return ok(m);
    }

    public record FamilyCreateReq(String phone, String password, Integer kyc) {}

    @PostMapping("/users")
    public Map<String, Object> createUser(@RequestBody FamilyCreateReq req) {
        if (req.phone() == null || !req.phone().matches("^1\\d{10}$")) throw bad("手机号格式错误");
        if (req.password() == null || req.password().length() < 6) throw bad("初始化密码须≥6位");
        String hash = sha256(req.phone());
        var exist = db.sql("SELECT id FROM family_account WHERE phone_hash=? AND is_delete=0").param(hash).query(Long.class).optional().orElse(null);
        if (exist != null) throw bad("该手机号已注册家庭");
        long id = insertFamily(req.phone(), hash, req.password());
        if (req.kyc() != null) jt.update("UPDATE family_account SET kyc_status=? WHERE id=? AND is_delete=0", req.kyc() == 1 ? 1 : 0, id);
        return ok(Map.of("id", id));
    }

    @PutMapping("/users/{id}")
    public Map<String, Object> updateUser(@PathVariable Long id, @RequestBody Map<String, Object> body) {
        Integer kyc = body.get("kyc") == null ? null : ((Number) body.get("kyc")).intValue();
        Integer status = body.get("status") == null ? null : ((Number) body.get("status")).intValue();
        if (kyc == null && status == null) throw bad("无可更新字段");
        var m = db.sql("UPDATE family_account SET kyc_status=COALESCE(?, kyc_status), status=COALESCE(?, status) WHERE id=? AND is_delete=0")
            .param(kyc).param(status).param(id).update();
        if (m == 0) throw bad("家庭不存在");
        return ok(Map.of("updated", true));
    }

    @DeleteMapping("/users/{id}")
    public Map<String, Object> deleteUser(@PathVariable Long id) {
        int n = jt.update("UPDATE family_account SET is_delete=1 WHERE id=? AND is_delete=0", id);
        if (n == 0) throw bad("家庭不存在");
        return ok(Map.of("deleted", true));
    }

    // ---------- W5.3 题目 查看/新增/编辑/逻辑删除 ----------
    @GetMapping("/questions/{id}")
    public Map<String, Object> questionDetail(@PathVariable Long id) {
        var m = db.sql("SELECT id, family_id, subject, grade, difficulty, q_type, stem, options_json, answer, analysis, source, status, review_status FROM question WHERE id=? AND is_delete=0")
            .param(id).query((rs, i) -> {
                Map<String, Object> r = new LinkedHashMap<>();
                r.put("id", rs.getLong("id"));
                r.put("subject", rs.getInt("subject"));
                r.put("grade", rs.getInt("grade"));
                r.put("difficulty", rs.getInt("difficulty"));
                r.put("qType", rs.getInt("q_type"));
                r.put("stem", rs.getString("stem"));
                r.put("optionsJson", rs.getString("options_json"));
                r.put("answer", rs.getString("answer"));
                r.put("analysis", rs.getString("analysis"));
                r.put("source", rs.getInt("source"));
                r.put("status", rs.getInt("status"));
                r.put("reviewStatus", rs.getInt("review_status"));
                return r;
            }).optional().orElse(null);
        if (m == null) throw bad("题目不存在");
        return ok(m);
    }

    @PostMapping("/questions")
    public Map<String, Object> createQuestion(@RequestBody Map<String, Object> body) {
        Long id = persistQuestion(null, body, true);
        return ok(Map.of("id", id));
    }

    @PutMapping("/questions/{id}")
    public Map<String, Object> updateQuestion(@PathVariable Long id, @RequestBody Map<String, Object> body) {
        var exist = db.sql("SELECT id FROM question WHERE id=? AND is_delete=0").param(id).query(Long.class).optional().orElse(null);
        if (exist == null) throw bad("题目不存在");
        persistQuestion(id, body, false);
        return ok(Map.of("id", id));
    }

    @DeleteMapping("/questions/{id}")
    public Map<String, Object> deleteQuestion(@PathVariable Long id) {
        int n = jt.update("UPDATE question SET is_delete=1 WHERE id=? AND is_delete=0", id);
        if (n == 0) throw bad("题目不存在");
        return ok(Map.of("deleted", true));
    }

    private Long persistQuestion(Long id, Map<String, Object> body, boolean create) {
        Integer subject = num(body, "subject");
        Integer grade = num(body, "grade");
        Integer difficulty = num(body, "difficulty");
        Integer qType = num(body, "qType");
        String stem = str(body, "stem");
        String rawOptions = str(body, "optionsJson");
        String answer = str(body, "answer");
        String analysis = str(body, "analysis");
        if (subject == null || grade == null || difficulty == null || qType == null
                || stem == null || stem.isBlank() || answer == null || answer.isBlank())
            throw bad("科目/年级/难度/题型/题干/答案必填");
        final String optionsJson = rawOptions == null ? "[]" : rawOptions;
        KeyHolder kh = new GeneratedKeyHolder();
        if (create) {
            jt.update(con -> {
                PreparedStatement ps = con.prepareStatement(
                    "INSERT INTO question(family_id, subject, grade, difficulty, q_type, stem, options_json, answer, analysis, source, status, review_status) VALUES(NULL,?,?,?,?,?,?,?,?,1,1,0)",
                    Statement.RETURN_GENERATED_KEYS);
                ps.setInt(1, subject); ps.setInt(2, grade); ps.setInt(3, difficulty); ps.setInt(4, qType);
                ps.setString(5, stem); ps.setString(6, optionsJson); ps.setString(7, answer); ps.setString(8, analysis);
                return ps;
            }, kh);
            return kh.getKey().longValue();
        } else {
            jt.update(con -> {
                PreparedStatement ps = con.prepareStatement(
                    "UPDATE question SET subject=?, grade=?, difficulty=?, q_type=?, stem=?, options_json=?, answer=?, analysis=? WHERE id=? AND is_delete=0",
                    Statement.RETURN_GENERATED_KEYS);
                ps.setInt(1, subject); ps.setInt(2, grade); ps.setInt(3, difficulty); ps.setInt(4, qType);
                ps.setString(5, stem); ps.setString(6, optionsJson); ps.setString(7, answer); ps.setString(8, analysis); ps.setLong(9, id);
                return ps;
            }, kh);
            return id;
        }
    }

    // ---------- W5.2 订单 查看/后台退款 ----------
    @GetMapping("/orders/{orderNo}")
    public Map<String, Object> orderDetail(@PathVariable String orderNo) {
        var m = db.sql("SELECT order_no, family_id, sku, amount, channel, status, created_at, shipped_at, refund_at FROM vip_order WHERE order_no=? AND is_delete=0")
            .param(orderNo).query((rs, i) -> {
                Map<String, Object> r = new LinkedHashMap<>();
                r.put("orderNo", rs.getString("order_no"));
                r.put("familyId", rs.getLong("family_id"));
                r.put("sku", rs.getInt("sku"));
                r.put("amount", rs.getInt("amount"));
                r.put("channel", rs.getInt("channel"));
                r.put("status", rs.getInt("status"));
                r.put("createdAt", rs.getString("created_at"));
                r.put("shippedAt", rs.getString("shipped_at"));
                r.put("refundAt", rs.getString("refund_at"));
                return r;
            }).optional().orElse(null);
        if (m == null) throw bad("订单不存在");
        return ok(m);
    }

    @PostMapping("/orders/{orderNo}/refund")
    public Map<String, Object> refundOrder(@PathVariable String orderNo) {
        int n = jt.update("UPDATE vip_order SET status=5, refund_at=NOW() WHERE order_no=? AND status=2 AND is_delete=0", orderNo);
        if (n == 0) throw bad("仅已发货订单可后台退款");
        return ok(Map.of("refunded", true));
    }

    // ---------- W6.2 客服工单 查看/逻辑删除 ----------
    @GetMapping("/tickets/{id}")
    public Map<String, Object> ticketDetail(@PathVariable Long id) {
        var m = db.sql("SELECT id, family_id, profile_id, task_id, type, content, status, decision, created_at FROM appeal_ticket WHERE id=? AND is_delete=0")
            .param(id).query((rs, i) -> {
                Map<String, Object> r = new LinkedHashMap<>();
                r.put("id", rs.getLong("id"));
                r.put("familyId", rs.getLong("family_id"));
                r.put("profileId", rs.getObject("profile_id"));
                r.put("taskId", rs.getObject("task_id"));
                r.put("type", rs.getString("type"));
                r.put("content", rs.getString("content"));
                r.put("status", rs.getInt("status"));
                r.put("decision", rs.getString("decision"));
                r.put("createdAt", rs.getString("created_at"));
                return r;
            }).optional().orElse(null);
        if (m == null) throw bad("工单不存在");
        return ok(m);
    }

    @DeleteMapping("/tickets/{id}")
    public Map<String, Object> deleteTicket(@PathVariable Long id) {
        int n = jt.update("UPDATE appeal_ticket SET is_delete=1 WHERE id=? AND is_delete=0", id);
        if (n == 0) throw bad("工单不存在");
        return ok(Map.of("deleted", true));
    }

    // ---------- W10 权限管理（admin_user 增删改查 + 重置密码） ----------
    @GetMapping("/iam/admins")
    public Map<String, Object> admins() {
        List<Map<String, Object>> list = db.sql("SELECT id, username, role, status, created_at FROM admin_user WHERE is_delete=0 ORDER BY id")
            .query((rs, i) -> {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("id", rs.getLong("id"));
                m.put("username", rs.getString("username"));
                m.put("role", rs.getString("role"));
                m.put("status", rs.getInt("status"));
                m.put("createdAt", rs.getString("created_at"));
                return m;
            }).list();
        return ok(Map.of("list", list));
    }

    public record AdminSaveReq(String username, String password, String role, Integer status) {}

    @PostMapping("/iam/admins")
    public Map<String, Object> createAdmin(@RequestBody AdminSaveReq req) {
        if (req.username() == null || req.username().isBlank() || req.password() == null || req.password().length() < 6)
            throw bad("用户名/密码(≥6位)必填");
        if (!java.util.Set.of("ADMIN", "REVIEWER", "CS").contains(req.role())) throw bad("角色须为 ADMIN/REVIEWER/CS");
        Integer dup = db.sql("SELECT COUNT(*) FROM admin_user WHERE username=? AND is_delete=0").param(req.username()).query(Integer.class).single();
        if (dup > 0) throw bad("用户名已存在");
        // 曾被逻辑删除的同名账号 → 复用原行并复活，避免 uk_username 唯一键冲突
        Long existAdmin = db.sql("SELECT id FROM admin_user WHERE username=?")
            .param(req.username()).query(Long.class).optional().orElse(null);
        if (existAdmin != null) {
            jt.update("UPDATE admin_user SET password_hash=?, role=?, status=1, is_delete=0 WHERE id=?",
                new BCryptPasswordEncoder().encode(req.password()), req.role(), existAdmin);
            return ok(Map.of("id", existAdmin));
        }
        KeyHolder kh = new GeneratedKeyHolder();
        jt.update(con -> {
            PreparedStatement ps = con.prepareStatement(
                "INSERT INTO admin_user(username, password_hash, role, status) VALUES(?,?,?,1)", Statement.RETURN_GENERATED_KEYS);
            ps.setString(1, req.username());
            ps.setString(2, new BCryptPasswordEncoder().encode(req.password()));
            ps.setString(3, req.role());
            return ps;
        }, kh);
        return ok(Map.of("id", kh.getKey().longValue()));
    }

    @PutMapping("/iam/admins/{id}")
    public Map<String, Object> updateAdmin(@PathVariable Long id, @RequestBody AdminSaveReq req) {
        if (req.role() != null && !java.util.Set.of("ADMIN", "REVIEWER", "CS").contains(req.role())) throw bad("角色须为 ADMIN/REVIEWER/CS");
        var exist = db.sql("SELECT id FROM admin_user WHERE id=? AND is_delete=0").param(id).query(Long.class).optional().orElse(null);
        if (exist == null) throw bad("管理员不存在");
        if (req.role() != null)
            jt.update("UPDATE admin_user SET role=? WHERE id=? AND is_delete=0", req.role(), id);
        if (req.status() != null)
            jt.update("UPDATE admin_user SET status=? WHERE id=? AND is_delete=0", req.status(), id);
        if (req.password() != null && req.password().length() >= 6)
            jt.update("UPDATE admin_user SET password_hash=? WHERE id=? AND is_delete=0", new BCryptPasswordEncoder().encode(req.password()), id);
        return ok(Map.of("updated", true));
    }

    @PutMapping("/iam/admins/{id}/reset")
    public Map<String, Object> resetAdmin(@PathVariable Long id, @RequestBody Map<String, String> body) {
        String pw = body.get("password");
        if (pw == null || pw.length() < 6) throw bad("新密码须≥6位");
        int n = jt.update("UPDATE admin_user SET password_hash=? WHERE id=? AND is_delete=0", new BCryptPasswordEncoder().encode(pw), id);
        if (n == 0) throw bad("管理员不存在");
        return ok(Map.of("reset", true));
    }

    @DeleteMapping("/iam/admins/{id}")
    public Map<String, Object> deleteAdmin(@PathVariable Long id) {
        int n = jt.update("UPDATE admin_user SET is_delete=1 WHERE id=? AND is_delete=0", id);
        if (n == 0) throw bad("管理员不存在");
        return ok(Map.of("deleted", true));
    }

    private long insertFamily(String phone, String hash, String password) {
        byte[] phoneEnc = phone.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        String pwdHash = "PLAIN:" + password;
        // 该手机号所属家庭曾被逻辑删除 → 复用原行并复活，避免 uk_phone_hash 唯一键冲突
        Long exist = db.sql("SELECT id FROM family_account WHERE phone_hash=?").param(hash)
            .query(Long.class).optional().orElse(null);
        if (exist != null) {
            jt.update("UPDATE family_account SET phone_enc=?, parent_pwd_hash=?, is_delete=0 WHERE id=?", phoneEnc, pwdHash, exist);
            return exist;
        }
        KeyHolder kh = new GeneratedKeyHolder();
        jt.update(con -> {
            PreparedStatement ps = con.prepareStatement(
                "INSERT INTO family_account(phone_enc, phone_hash, parent_pwd_hash) VALUES(?,?,?)", Statement.RETURN_GENERATED_KEYS);
            ps.setBytes(1, phoneEnc);
            ps.setString(2, hash);
            ps.setString(3, pwdHash);
            return ps;
        }, kh);
        return kh.getKey().longValue();
    }

    private static Integer num(Map<String, Object> b, String k) {
        Object v = b.get(k);
        return v instanceof Number ? ((Number) v).intValue() : null;
    }

    private static String str(Map<String, Object> b, String k) {
        Object v = b.get(k);
        return v instanceof String s ? s : null;
    }

    private static String sha256(String s) {
        try {
            return java.util.HexFormat.of().formatHex(
                java.security.MessageDigest.getInstance("SHA-256").digest(s.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        } catch (Exception e) { throw new IllegalStateException(e); }
    }

    @GetMapping("/summary")
    public Map<String, Object> summary() {
        Map<String, Object> d = new LinkedHashMap<>();
        d.put("families", count("SELECT COUNT(*) FROM family_account WHERE is_delete=0"));
        d.put("students", count("SELECT COUNT(*) FROM student_profile WHERE is_delete=0"));
        d.put("questions", count("SELECT COUNT(*) FROM question WHERE is_delete=0 AND status=1"));
        d.put("tasksToday", count("SELECT COUNT(*) FROM quiz_task WHERE is_delete=0 AND DATE(created_at)=CURDATE()"));
        d.put("tasksPassedToday", count("SELECT COUNT(*) FROM quiz_task WHERE is_delete=0 AND DATE(created_at)=CURDATE() AND result=1"));
        d.put("ordersPaid", count("SELECT COUNT(*) FROM vip_order WHERE is_delete=0 AND status=2"));
        d.put("revenueTotal", sum("SELECT COALESCE(SUM(amount),0) FROM vip_order WHERE is_delete=0 AND status=2"));
        d.put("alarmsUnread", count("SELECT COUNT(*) FROM alarm WHERE is_delete=0 AND read_flag=0"));
        d.put("riskEventsToday", count("SELECT COUNT(*) FROM risk_event WHERE is_delete=0 AND DATE(created_at)=CURDATE()"));
        return ok(d);
    }

    private long count(String sql) {
        return db.sql(sql).query(Long.class).single();
    }

    private long sum(String sql) {
        return db.sql(sql).query(Long.class).single();
    }

    private ResponseStatusException bad(String msg) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, msg);
    }

    private Map<String, Object> ok(Map<String, Object> data) {
        return Map.of("code", 0, "msg", "ok", "data", data);
    }
}
