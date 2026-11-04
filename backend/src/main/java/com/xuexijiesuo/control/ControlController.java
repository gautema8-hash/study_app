package com.xuexijiesuo.control;

import com.xuexijiesuo.account.TokenService;
import java.sql.PreparedStatement;
import java.sql.Statement;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

/**
 * 解锁会话管控（W4，FR-12/15/17/18 骨架 + E-15 fail-open 服务端侧）。
 * 状态机（PRD 9.2）：1锁定 2出题 3答题 4判分 5已解锁 6未达标；单向流转，禁止回跳。
 * 时间红线：一切到期判定用 DB NOW()（服务端时间，防 T-03 本地拨表）。
 */
@RestController
@RequestMapping("/api/control")
public class ControlController {

    private final JdbcClient db;
    private final JdbcTemplate jt;
    private final TokenService tokens;
    private final BCryptPasswordEncoder encoder = new BCryptPasswordEncoder();

    public ControlController(JdbcClient db, JdbcTemplate jt, TokenService tokens) {
        this.db = db;
        this.jt = jt;
        this.tokens = tokens;
    }

    public record HeartbeatReq(String deviceFingerprint) {}
    public record ParentActionReq(Long profileId, String password) {}

    // ---------- 学生：判分达标后开放会话（解锁） ----------
    @PostMapping("/session/start")
    public Map<String, Object> startSession(@RequestHeader("Authorization") String auth) {
        long profileId = studentClaims(auth).profileId() == null ? throwNoProfile() : studentClaims(auth).profileId();
        long familyId = familyOf(profileId);
        // 幂等：已有未到期会话直接返回
        Map<String, Object> active = activeSession(profileId);
        if (active != null) return ok(active);
        // 前置：最近一次判分必须达标（result=1），防跳过答题直接解锁（FR-12）
        Integer lastResult = db.sql("""
                SELECT t.result FROM quiz_task t
                WHERE t.profile_id=? AND t.result IN (1,2,3)
                ORDER BY t.submitted_at DESC LIMIT 1
                """).param(profileId).query(Integer.class).optional().orElse(null);
        if (lastResult == null || lastResult != 1)
            throw bad("最近一次答题未达标，不能解锁");
        int sessionMin = (int) loadConfig(familyId).get("sessionMinutes");
        long id = insertSession(profileId, 5, "DATE_ADD(NOW(), INTERVAL " + sessionMin + " MINUTE)");
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("sessionId", id);
        data.put("state", 5);
        data.put("sessionMinutes", sessionMin);
        data.put("serverTime", db.sql("SELECT NOW()").query(String.class).single());
        return ok(data);
    }

    // ---------- 学生：当前会话状态（含到期自动锁定，单向流转） ----------
    @GetMapping("/session/current")
    public Map<String, Object> current(@RequestHeader("Authorization") String auth) {
        long profileId = studentClaims(auth).profileId() == null ? throwNoProfile() : studentClaims(auth).profileId();
        Map<String, Object> s = activeSession(profileId);
        Map<String, Object> data = new LinkedHashMap<>();
        if (s == null) {
            data.put("state", 1);
            data.put("locked", true);
            return ok(data);
        }
        Long remain = db.sql("SELECT TIMESTAMPDIFF(SECOND, NOW(), deadline_at) FROM unlock_session WHERE id=?")
            .param(s.get("sessionId")).query(Long.class).single();
        if (remain <= 0) { // 到期 → 锁定（服务端时间裁决，防 T-03）
            jt.update("UPDATE unlock_session SET state=1, ended_at=NOW() WHERE id=?", s.get("sessionId"));
            data.put("state", 1);
            data.put("locked", true);
            data.put("reason", "expired");
            return ok(data);
        }
        data.put("sessionId", s.get("sessionId"));
        data.put("state", 5);
        data.put("locked", false);
        data.put("remainSeconds", remain);
        return ok(data);
    }

    // ---------- 学生：心跳（更新在线窗口 + 下发服务端时间，FR-37 口径） ----------
    @PostMapping("/heartbeat")
    public Map<String, Object> heartbeat(@RequestHeader("Authorization") String auth, @RequestBody HeartbeatReq req) {
        long familyId = studentClaims(auth).familyId();
        if (req.deviceFingerprint() != null) {
            jt.update("""
                UPDATE device_bind SET last_heartbeat_at=NOW()
                WHERE device_fingerprint=? AND family_id=? AND device_mode=1 AND bind_status=1
                """, req.deviceFingerprint(), familyId);
        }
        int failOpenHours = Integer.parseInt(
            db.sql("SELECT config_value FROM sys_config WHERE config_key='failopen.hours'")
                .query(String.class).optional().orElse("2"));
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("serverTime", db.sql("SELECT NOW()").query(String.class).single());
        data.put("failOpenHours", failOpenHours); // 客户端失联超过该时长自行临时开放(E-15)
        return ok(data);
    }

    // ---------- fail-open 查询：设备超过阈值无心跳 = 客户端应临时开放（宁可漏管不可错锁） ----------
    @GetMapping("/failopen")
    public Map<String, Object> failOpen(@RequestParam String deviceFingerprint) {
        var row = db.sql("SELECT TIMESTAMPDIFF(MINUTE, last_heartbeat_at, NOW()) AS mins, bind_status FROM device_bind WHERE device_fingerprint=? AND device_mode=1")
            .param(deviceFingerprint)
            .query((rs, i) -> Map.of("mins", rs.getObject("mins"), "bound", rs.getInt("bind_status")))
            .optional().orElse(null);
        int failOpenHours = Integer.parseInt(
            db.sql("SELECT config_value FROM sys_config WHERE config_key='failopen.hours'")
                .query(String.class).optional().orElse("2"));
        boolean failOpen = row == null || row.get("mins") == null
            || ((Number) row.get("mins")).intValue() >= failOpenHours * 60;
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("failOpen", failOpen);
        if (row != null && row.get("mins") != null) data.put("silentMinutes", ((Number) row.get("mins")).intValue());
        data.put("thresholdHours", failOpenHours);
        return ok(data);
    }

    // ---------- 家长：紧急解锁（FR-15，家长密码二次确认 + 告警留痕） ----------
    @PostMapping("/parent/unlock")
    public Map<String, Object> parentUnlock(@RequestHeader("Authorization") String auth, @RequestBody ParentActionReq req) {
        long familyId = parentClaims(auth);
        verifyParentPwd(familyId, req.password());
        if (req.profileId() == null || !profileInFamily(req.profileId(), familyId)) throw bad("学生档案不存在");
        jt.update("UPDATE unlock_session SET state=1, ended_at=NOW() WHERE profile_id=? AND state=5", req.profileId());
        int sessionMin = (int) loadConfig(familyId).get("sessionMinutes");
        long id = insertSession(req.profileId(), 5, "DATE_ADD(NOW(), INTERVAL " + sessionMin + " MINUTE)");
        jt.update("INSERT INTO alarm(family_id, type, title, content) VALUES(?,?,?,?)",
            familyId, "PARENT_UNLOCK", "家长紧急解锁", "家长已为该学生紧急解锁 " + sessionMin + " 分钟（操作已留痕）");
        return ok(Map.of("sessionId", id, "sessionMinutes", sessionMin));
    }

    // ---------- 家长：远程收回（可逆原则的另一半） ----------
    @PostMapping("/parent/revoke")
    public Map<String, Object> parentRevoke(@RequestHeader("Authorization") String auth, @RequestBody ParentActionReq req) {
        long familyId = parentClaims(auth);
        if (req.profileId() == null || !profileInFamily(req.profileId(), familyId)) throw bad("学生档案不存在");
        int n = jt.update("UPDATE unlock_session SET state=1, ended_at=NOW() WHERE profile_id=? AND state=5", req.profileId());
        jt.update("INSERT INTO alarm(family_id, type, title, content) VALUES(?,?,?,?)",
            familyId, "PARENT_REVOKE", "家长远程收回", "家长已收回该学生的使用会话");
        return ok(Map.of("revoked", n > 0));
    }

    // ---------- helpers ----------
    private Map<String, Object> activeSession(long profileId) {
        return db.sql("""
                SELECT id, TIMESTAMPDIFF(SECOND, NOW(), deadline_at) AS remain
                FROM unlock_session WHERE profile_id=? AND state=5 AND deadline_at > NOW()
                ORDER BY id DESC LIMIT 1
                """).param(profileId)
            .query((rs, i) -> Map.<String, Object>of("sessionId", rs.getLong("id"), "remain", rs.getLong("remain")))
            .optional().orElse(null);
    }

    private long insertSession(long profileId, int state, String deadlineExpr) {
        KeyHolder kh = new GeneratedKeyHolder();
        jt.update(con -> {
            PreparedStatement ps = con.prepareStatement(
                "INSERT INTO unlock_session(profile_id, state, deadline_at) VALUES(?,?, " + deadlineExpr + ")",
                Statement.RETURN_GENERATED_KEYS);
            ps.setLong(1, profileId);
            ps.setInt(2, state);
            return ps;
        }, kh);
        return kh.getKey().longValue();
    }

    private void verifyParentPwd(long familyId, String password) {
        String hash = db.sql("SELECT parent_pwd_hash FROM family_account WHERE id=?").param(familyId)
            .query(String.class).optional().orElse(null);
        if (hash == null || password == null || !encoder.matches(password, hash))
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "家长操作密码错误(T-08)"); // TODO: 错5次锁定+告警
    }

    private boolean profileInFamily(long profileId, long familyId) {
        return db.sql("SELECT COUNT(*) FROM student_profile WHERE id=? AND family_id=?")
            .param(profileId).param(familyId).query(Long.class).single() > 0;
    }

    private long familyOf(long profileId) {
        return db.sql("SELECT family_id FROM student_profile WHERE id=?").param(profileId)
            .query(Long.class).optional().orElseThrow(() -> bad("学生档案不存在"));
    }

    private Map<String, Object> loadConfig(long familyId) {
        return db.sql("SELECT session_minutes FROM quiz_config WHERE family_id=?").param(familyId)
            .query((rs, i) -> {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("sessionMinutes", rs.getInt("session_minutes"));
                return m;
            }).optional().orElseGet(() -> {
                Map<String, Object> d = new LinkedHashMap<>();
                d.put("sessionMinutes", 60);
                return d;
            });
    }

    private TokenService.TokenClaims claims(String auth) {
        if (auth == null || !auth.startsWith("Bearer ")) throw bad("缺少 token");
        try {
            return tokens.verify(auth.substring(7));
        } catch (IllegalStateException e) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, e.getMessage());
        }
    }

    private TokenService.TokenClaims studentClaims(String auth) {
        TokenService.TokenClaims c = claims(auth);
        if (!"student".equals(c.scope())) throw new ResponseStatusException(HttpStatus.FORBIDDEN, "仅学生可操作");
        return c;
    }

    private long parentClaims(String auth) {
        TokenService.TokenClaims c = claims(auth);
        if (!"parent".equals(c.scope())) throw new ResponseStatusException(HttpStatus.FORBIDDEN, "仅家长可操作");
        return c.familyId();
    }

    private long throwNoProfile() {
        throw new ResponseStatusException(HttpStatus.FORBIDDEN, "学生身份缺少档案");
    }

    private ResponseStatusException bad(String msg) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, msg);
    }

    private Map<String, Object> ok(Map<String, Object> data) {
        return Map.of("code", 0, "msg", "ok", "data", data);
    }
}
