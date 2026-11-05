package com.xuexijiesuo.opsbff;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
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
    private final com.xuexijiesuo.account.TokenService tokens;

    public OpsController(JdbcClient db, JdbcTemplate jt, com.xuexijiesuo.account.TokenService tokens) {
        this.db = db;
        this.jt = jt;
        this.tokens = tokens;
    }

    public record AdminLoginReq(String username, String password) {}

    // 后台登录（FR-W01：账号密码 → admin scope JWT；PLAIN 引导态首次登录自动升级 bcrypt）
    @PostMapping("/auth/login")
    public Map<String, Object> adminLogin(@RequestBody AdminLoginReq req) {
        var row = db.sql("SELECT id, password_hash, role, status FROM admin_user WHERE username=?")
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
            if (ok) jt.update("UPDATE admin_user SET password_hash=? WHERE id=?",
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
        Long total = db.sql("SELECT COUNT(*) FROM family_account").query(Long.class).single();
        List<Map<String, Object>> list = db.sql("""
                SELECT f.id, f.phone_enc, f.kyc_status, f.status, f.created_at,
                       COALESCE(m.tier,0) AS tier, m.expire_at,
                       (SELECT COUNT(*) FROM student_profile s WHERE s.family_id=f.id) AS students
                FROM family_account f LEFT JOIN member m ON m.family_id=f.id
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
        Long total = db.sql("SELECT COUNT(*) FROM vip_order").query(Long.class).single();
        List<Map<String, Object>> list = db.sql("""
                SELECT order_no, family_id, sku, amount, channel, status, created_at, shipped_at
                FROM vip_order ORDER BY id DESC LIMIT ? OFFSET ?
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
        StringBuilder where = new StringBuilder(" WHERE status IN (1,2)");
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
                FROM alarm a ORDER BY a.id DESC LIMIT 50 OFFSET ?
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
        db.sql("SELECT config_key, config_value FROM sys_config WHERE config_key LIKE 'sku.%' OR config_key LIKE 'trial%' OR config_key LIKE 'ad.%'")
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
        int n = db.sql("UPDATE sys_config SET config_value=? WHERE config_key=?").param(value).param(key).update();
        if (n == 0) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "配置键不存在");
        return ok(Map.of("key", key, "value", value));
    }

    // ---------- W6.1 审核工单（FR-W12：家长题目人工复核，REVIEWER/ADMIN 写） ----------
    @GetMapping("/review/tickets")
    public Map<String, Object> reviewTickets(@RequestParam(defaultValue = "1") Integer status) {
        List<Map<String, Object>> list = db.sql("""
                SELECT id, family_id, subject, grade, difficulty, stem, review_status, review_reason, created_at
                FROM question WHERE review_status=? ORDER BY id DESC LIMIT 100
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
        int n = jt.update("UPDATE question SET review_status=?, review_reason=? WHERE id=? AND review_status=1",
            req.pass() ? 0 : 2, (req.pass() ? "人工复核通过" : "人工复核驳回: " + req.reason()), id);
        if (n == 0) throw bad("工单不存在或已裁决");
        return ok(Map.of("decided", true));
    }

    // ---------- W6.2 客服申诉工单（FR-W15/16，CS/ADMIN 写） ----------
    @GetMapping("/tickets")
    public Map<String, Object> tickets(@RequestParam(defaultValue = "0") Integer status) {
        List<Map<String, Object>> list = db.sql("""
                SELECT id, family_id, profile_id, task_id, type, content, status, decision, created_at
                FROM appeal_ticket WHERE status=? ORDER BY id DESC LIMIT 100
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
        var ticket = db.sql("SELECT task_id FROM appeal_ticket WHERE id=? AND status=0").param(id)
            .query((rs, i) -> rs.getObject("task_id")).optional().orElse(null);
        if (ticket == null) throw bad("工单不存在或已裁决");
        jt.update("UPDATE appeal_ticket SET status=?, decision=? WHERE id=?",
            req.approve() ? 2 : 3, req.decision(), id);
        if (req.approve() && ticket instanceof Number taskIdNum) {
            // 撤销判废：恢复任务有效并按达标处理（积分补偿 TODO）
            jt.update("""
                UPDATE quiz_task SET result=1, score=threshold, abnormal_flag=0
                WHERE id=? AND result=3
                """, taskIdNum.longValue());
            jt.update("INSERT INTO alarm(family_id, type, title, content) VALUES((SELECT family_id FROM appeal_ticket WHERE id=?),'APPEAL_APPROVED','申诉通过','判废已撤销，任务恢复达标')", id);
        }
        return ok(Map.of("decided", true, "approved", req.approve()));
    }

    @GetMapping("/summary")
    public Map<String, Object> summary() {
        Map<String, Object> d = new LinkedHashMap<>();
        d.put("families", count("SELECT COUNT(*) FROM family_account"));
        d.put("students", count("SELECT COUNT(*) FROM student_profile"));
        d.put("questions", count("SELECT COUNT(*) FROM question WHERE status=1"));
        d.put("tasksToday", count("SELECT COUNT(*) FROM quiz_task WHERE DATE(created_at)=CURDATE()"));
        d.put("tasksPassedToday", count("SELECT COUNT(*) FROM quiz_task WHERE DATE(created_at)=CURDATE() AND result=1"));
        d.put("ordersPaid", count("SELECT COUNT(*) FROM vip_order WHERE status=2"));
        d.put("revenueTotal", sum("SELECT COALESCE(SUM(amount),0) FROM vip_order WHERE status=2"));
        d.put("alarmsUnread", count("SELECT COUNT(*) FROM alarm WHERE read_flag=0"));
        d.put("riskEventsToday", count("SELECT COUNT(*) FROM risk_event WHERE DATE(created_at)=CURDATE()"));
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
