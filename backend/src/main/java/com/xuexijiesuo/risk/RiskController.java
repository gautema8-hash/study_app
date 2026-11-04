package com.xuexijiesuo.risk;

import com.xuexijiesuo.account.TokenService;
import tools.jackson.databind.ObjectMapper;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

/**
 * 风控事件与家长告警（W4，FR-13/14）。
 * 客户端上报（T-01卸载/T-02强停/T-03拨表/T-06切屏等）→ 双写 risk_event + alarm；
 * 家长端可查事件与告警、标记已读。留存 ≥90 天（PRD 11.3-5）。
 */
@RestController
public class RiskController {

    private final JdbcClient db;
    private final JdbcTemplate jt;
    private final TokenService tokens;
    private final ObjectMapper om = new ObjectMapper();

    public RiskController(JdbcClient db, JdbcTemplate jt, TokenService tokens) {
        this.db = db;
        this.jt = jt;
        this.tokens = tokens;
    }

    public record ReportReq(String type, String detail, Long profileId) {}

    // 客户端上报风险事件（任何登录态均可上报）
    @PostMapping("/api/risk/report")
    public Map<String, Object> report(@RequestHeader("Authorization") String auth, @RequestBody ReportReq req) {
        TokenService.TokenClaims c = claims(auth);
        if (req.type() == null || !req.type().matches("^T-0[1-8]$")) throw bad("type 须为 T-01~T-08");
        // detail 列为 MySQL JSON 类型：纯文本自动包装为合法 JSON（防 500）
        String detailJson;
        try {
            if (req.detail() != null) om.readTree(req.detail());
            detailJson = req.detail();
        } catch (Exception parseFail) {
            try { detailJson = om.writeValueAsString(req.detail()); }
            catch (Exception e) { detailJson = "null"; }
        }
        jt.update("INSERT INTO risk_event(family_id, profile_id, type, detail) VALUES(?,?,?,?)",
            c.familyId(), req.profileId(), req.type(), detailJson);
        jt.update("INSERT INTO alarm(family_id, type, title, content) VALUES(?,?,?,?)",
            c.familyId(), "RISK_" + req.type(), "风控事件 " + req.type(),
            req.detail() == null ? "客户端上报风险事件" : req.detail());
        return ok(Map.of("recorded", true));
    }

    // 家长查看风险事件（最近100条）
    @GetMapping("/api/risk/events")
    public Map<String, Object> events(@RequestHeader("Authorization") String auth) {
        long familyId = parentClaims(auth);
        List<Map<String, Object>> list = db.sql("""
                SELECT id, profile_id, type, detail, created_at FROM risk_event
                WHERE family_id=? ORDER BY id DESC LIMIT 100
                """).param(familyId)
            .query((rs, i) -> {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("id", rs.getLong("id"));
                m.put("profileId", rs.getObject("profile_id"));
                m.put("type", rs.getString("type"));
                m.put("detail", rs.getString("detail"));
                m.put("createdAt", rs.getString("created_at"));
                return m;
            }).list();
        return ok(Map.of("list", list));
    }

    // 家长告警列表
    @GetMapping("/api/alarm/list")
    public Map<String, Object> alarms(@RequestHeader("Authorization") String auth,
                                      @RequestParam(defaultValue = "1") Integer page) {
        long familyId = parentClaims(auth);
        List<Map<String, Object>> list = db.sql("""
                SELECT id, type, title, content, read_flag, created_at FROM alarm
                WHERE family_id=? ORDER BY id DESC LIMIT 50 OFFSET ?
                """).param(familyId).param((page - 1) * 50)
            .query((rs, i) -> {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("id", rs.getLong("id"));
                m.put("type", rs.getString("type"));
                m.put("title", rs.getString("title"));
                m.put("content", rs.getString("content"));
                m.put("read", rs.getInt("read_flag") == 1);
                m.put("createdAt", rs.getString("created_at"));
                return m;
            }).list();
        return ok(Map.of("list", list));
    }

    // 标记告警已读（单条或全部）
    @PostMapping("/api/alarm/read")
    public Map<String, Object> read(@RequestHeader("Authorization") String auth,
                                    @RequestBody(required = false) Map<String, Object> body) {
        long familyId = parentClaims(auth);
        Object id = body == null ? null : body.get("id");
        int n;
        if (id instanceof Number num) {
            n = jt.update("UPDATE alarm SET read_flag=1 WHERE family_id=? AND id=?", familyId, num.longValue());
        } else {
            n = jt.update("UPDATE alarm SET read_flag=1 WHERE family_id=?", familyId);
        }
        return ok(Map.of("updated", n));
    }

    // ---------- helpers ----------
    private TokenService.TokenClaims claims(String auth) {
        if (auth == null || !auth.startsWith("Bearer ")) throw bad("缺少 token");
        try {
            return tokens.verify(auth.substring(7));
        } catch (IllegalStateException e) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, e.getMessage());
        }
    }

    private long parentClaims(String auth) {
        TokenService.TokenClaims c = claims(auth);
        if (!"parent".equals(c.scope())) throw new ResponseStatusException(HttpStatus.FORBIDDEN, "仅家长可操作");
        return c.familyId();
    }

    private ResponseStatusException bad(String msg) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, msg);
    }

    private Map<String, Object> ok(Map<String, Object> data) {
        return Map.of("code", 0, "msg", "ok", "data", data);
    }
}
