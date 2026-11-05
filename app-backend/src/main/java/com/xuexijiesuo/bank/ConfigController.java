package com.xuexijiesuo.bank;

import com.xuexijiesuo.account.TokenService;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

/**
 * 家长 DIY 配置（FR-08）+ 学生配置脱敏（FR-10：学生 token 禁止读取门槛分数等规则值）。
 * 配置为家庭级；默认次日生效原则由客户端缓存策略实现，服务端始终以最新配置判分。
 */
@RestController
public class ConfigController {

    private final JdbcClient db;
    private final TokenService tokens;

    public ConfigController(JdbcClient db, TokenService tokens) {
        this.db = db;
        this.tokens = tokens;
    }

    public record ConfigReq(Integer count, Integer durationMin, Integer threshold,
                            Integer sessionMinutes, Integer dailyLimit) {}

    // 家长查看完整配置
    @GetMapping("/api/bank/config")
    public Map<String, Object> get(@RequestHeader("Authorization") String auth) {
        long familyId = parentCtx(auth);
        return ok(loadConfig(familyId));
    }

    // 家长修改配置（FR-08 范围约束）
    @PutMapping("/api/bank/config")
    public Map<String, Object> update(@RequestHeader("Authorization") String auth, @RequestBody ConfigReq req) {
        long familyId = parentCtx(auth);
        Map<String, Object> cur = loadConfig(familyId);
        int count = clamp(req.count(), (int) cur.get("count"), 5, 30);
        int duration = clamp(req.durationMin(), (int) cur.get("durationMin"), 3, 30);
        int threshold = clamp(req.threshold(), (int) cur.get("threshold"), 60, 100);
        int session = clamp(req.sessionMinutes(), (int) cur.get("sessionMinutes"), 15, 240);
        int daily = clamp(req.dailyLimit(), (int) cur.get("dailyLimit"), 1, 10);
        db.sql("""
                INSERT INTO quiz_config(family_id, count, duration_min, threshold, session_minutes, daily_limit)
                VALUES(?,?,?,?,?,?)
                ON DUPLICATE KEY UPDATE count=VALUES(count), duration_min=VALUES(duration_min),
                  threshold=VALUES(threshold), session_minutes=VALUES(session_minutes), daily_limit=VALUES(daily_limit)
                """)
            .param(familyId).param(count).param(duration).param(threshold).param(session).param(daily).update();
        return ok(loadConfig(familyId));
    }

    // 学生视角：只下发题数与时长，门槛/每日上限/会话时长脱敏（FR-10 反向应试防护）
    @GetMapping("/api/quiz/config/current")
    public Map<String, Object> currentForStudent(@RequestHeader("Authorization") String auth) {
        TokenService.TokenClaims c = claims(auth);
        if (!"student".equals(c.scope())) throw new ResponseStatusException(HttpStatus.FORBIDDEN, "学生视角接口");
        Map<String, Object> cfg = loadConfig(c.familyId());
        Map<String, Object> masked = new LinkedHashMap<>();
        masked.put("count", cfg.get("count"));
        masked.put("durationMin", cfg.get("durationMin"));
        masked.put("threshold", "已配置"); // 脱敏，不返回具体值
        return ok(masked);
    }

    // ---------- helpers ----------
    public Map<String, Object> loadConfig(long familyId) {
        return db.sql("SELECT count, duration_min, threshold, session_minutes, daily_limit FROM quiz_config WHERE family_id=?")
            .param(familyId)
            .query((rs, i) -> {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("count", rs.getInt("count"));
                m.put("durationMin", rs.getInt("duration_min"));
                m.put("threshold", rs.getInt("threshold"));
                m.put("sessionMinutes", rs.getInt("session_minutes"));
                m.put("dailyLimit", rs.getInt("daily_limit"));
                return m;
            }).optional()
            .orElseGet(() -> {
                Map<String, Object> d = new LinkedHashMap<>();
                d.put("count", 10); d.put("durationMin", 10); d.put("threshold", 80);
                d.put("sessionMinutes", 60); d.put("dailyLimit", 3);
                return d;
            });
    }

    private static int clamp(Integer v, int def, int min, int max) {
        if (v == null) return def;
        return Math.max(min, Math.min(max, v));
    }

    private long parentCtx(String auth) {
        TokenService.TokenClaims c = claims(auth);
        if (!"parent".equals(c.scope())) throw new ResponseStatusException(HttpStatus.FORBIDDEN, "仅家长可操作配置");
        return c.familyId();
    }

    private TokenService.TokenClaims claims(String auth) {
        if (auth == null || !auth.startsWith("Bearer ")) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "缺少 token");
        try {
            return tokens.verify(auth.substring(7));
        } catch (IllegalStateException e) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, e.getMessage());
        }
    }

    private Map<String, Object> ok(Map<String, Object> data) {
        return Map.of("code", 0, "msg", "ok", "data", data);
    }
}
