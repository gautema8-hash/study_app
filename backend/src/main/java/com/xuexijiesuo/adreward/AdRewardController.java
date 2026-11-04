package com.xuexijiesuo.adreward;

import com.xuexijiesuo.account.TokenService;
import java.time.Duration;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

/**
 * 激励视频换免费天数（FR-27，家长 scope）。
 * 发奖铁律：① 服务端回调为准（客户端上报无效）② 回调 ID 幂等 ③ 单日上限 10 天 ④ 仅家长端，学生端无广告组件。
 * W5 开发版：/ad/callback 即模拟平台回调（验签位 TODO(D-07)：穿山甲/优量汇服务端签名校验）。
 */
@RestController
public class AdRewardController {

    private final JdbcClient db;
    private final JdbcTemplate jt;
    private final TokenService tokens;
    private final StringRedisTemplate redis;

    public AdRewardController(JdbcClient db, JdbcTemplate jt, TokenService tokens, StringRedisTemplate redis) {
        this.db = db;
        this.jt = jt;
        this.tokens = tokens;
        this.redis = redis;
    }

    public record AdCallbackReq(String callbackId) {}

    @PostMapping("/api/member/ad/callback")
    public Map<String, Object> callback(@RequestHeader("Authorization") String auth, @RequestBody AdCallbackReq req) {
        long familyId = parentClaims(auth);
        if (req.callbackId() == null || req.callbackId().isBlank()) throw bad("callbackId 必填");

        // ① 回调 ID 幂等（重放不发奖，PRD FR-27 约束3）
        Boolean first = redis.opsForValue().setIfAbsent("ad:" + req.callbackId(), "1", Duration.ofHours(24));
        if (!Boolean.TRUE.equals(first)) return ok(Map.of("granted", false, "reason", "duplicate"));

        // ② 单日上限（防广告欺诈，规则对家长明示）
        LocalDate today = LocalDate.now();
        Map<String, Object> acc = db.sql("SELECT daily_granted, daily_date, ad_free_expire_at FROM free_time_account WHERE family_id=?")
            .param(familyId).query((rs, i) -> {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("granted", rs.getInt("daily_granted"));
                m.put("date", rs.getString("daily_date"));
                m.put("expire", rs.getString("ad_free_expire_at"));
                return m;
            }).optional().orElse(null);
        int granted = 0;
        String expire = null;
        if (acc != null) {
            granted = today.toString().equals(acc.get("date")) ? ((Number) acc.get("granted")).intValue() : 0;
            expire = (String) acc.get("expire");
        }
        if (granted >= 10) {
            redis.delete("ad:" + req.callbackId()); // 上限内不占幂等位，家长明天可继续
            return ok(Map.of("granted", false, "reason", "dailyLimit"));
        }

        // ③ 发奖：到期 = max(当前到期, NOW()) + 1 天
        String now = LocalDate.now().toString();
        String base = expire != null && expire.compareTo(now) > 0 ? expire.split(" ")[0] : now;
        String newExpire = LocalDate.parse(base).plusDays(1).toString();
        jt.update("""
            INSERT INTO free_time_account(family_id, ad_free_expire_at, daily_granted, daily_date)
            VALUES(?,?,1,?)
            ON DUPLICATE KEY UPDATE ad_free_expire_at=?, daily_granted=?, daily_date=?
            """, familyId, newExpire, today.toString(), newExpire, granted + 1, today.toString());
        return ok(Map.of("granted", true, "freeExpire", newExpire, "todayGranted", granted + 1));
    }

    @GetMapping("/api/member/freetime")
    public Map<String, Object> freetime(@RequestHeader("Authorization") String auth) {
        long familyId = parentClaims(auth);
        Map<String, Object> data = new LinkedHashMap<>();
        db.sql("SELECT trial_expire_at, ad_free_expire_at, daily_granted, daily_date FROM free_time_account WHERE family_id=?")
            .param(familyId).query((rs, i) -> {
                data.put("trialExpireAt", rs.getString("trial_expire_at"));
                data.put("adFreeExpireAt", rs.getString("ad_free_expire_at"));
                data.put("todayGranted", rs.getInt("daily_granted"));
                data.put("dailyDate", rs.getString("daily_date"));
                return data;
            }).optional().ifPresent(x -> {});
        if (data.isEmpty()) data.put("trialExpireAt", null);
        return ok(data);
    }

    private long parentClaims(String auth) {
        if (auth == null || !auth.startsWith("Bearer ")) throw bad("缺少 token");
        TokenService.TokenClaims c;
        try { c = tokens.verify(auth.substring(7)); }
        catch (IllegalStateException e) { throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, e.getMessage()); }
        if (!"parent".equals(c.scope())) throw new ResponseStatusException(HttpStatus.FORBIDDEN, "仅家长可操作(学生端禁广告 FR-27)");
        return c.familyId();
    }

    private ResponseStatusException bad(String msg) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, msg);
    }

    private Map<String, Object> ok(Map<String, Object> data) {
        return Map.of("code", 0, "msg", "ok", "data", data);
    }
}
