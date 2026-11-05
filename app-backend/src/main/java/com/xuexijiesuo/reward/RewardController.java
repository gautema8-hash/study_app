package com.xuexijiesuo.reward;

import com.xuexijiesuo.account.TokenService;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

/**
 * 学生端激励中心（W6，FR-22）：积分余额/流水、勋章墙、打卡日历。
 * 学生仅可见本人数据（权限矩阵 11.1）；无抽奖/排行（未成年人红线 11.2-5）。
 */
@RestController
@RequestMapping("/api/reward")
public class RewardController {

    private final JdbcClient db;
    private final TokenService tokens;
    private final RewardService rewards;

    public RewardController(JdbcClient db, TokenService tokens, RewardService rewards) {
        this.db = db;
        this.tokens = tokens;
        this.rewards = rewards;
    }

    /** 激励中心聚合页 */
    @GetMapping("/me")
    public Map<String, Object> me(@RequestHeader("Authorization") String auth) {
        long profileId = studentClaims(auth).profileId() == null ? throwNoProfile() : studentClaims(auth).profileId();
        Map<String, Object> data = new LinkedHashMap<>();

        Integer balance = db.sql("SELECT balance FROM point_account WHERE profile_id=? AND is_delete=0").param(profileId)
            .query(Integer.class).optional().orElse(0);
        data.put("balance", balance);

        data.put("streak", rewards.calcStreak(profileId));

        List<Map<String, Object>> medals = db.sql("""
                SELECT medal_code, granted_at FROM profile_medal WHERE profile_id=? AND is_delete=0 ORDER BY granted_at DESC
                """).param(profileId)
            .query((rs, i) -> Map.<String, Object>of(
                "code", rs.getString("medal_code"),
                "grantedAt", rs.getString("granted_at"))).list();
        data.put("medals", medals);

        List<Map<String, Object>> flows = db.sql("""
                SELECT delta, reason, ref_id, created_at FROM point_flow WHERE profile_id=? AND is_delete=0
                ORDER BY id DESC LIMIT 20
                """).param(profileId)
            .query((rs, i) -> Map.<String, Object>of(
                "delta", rs.getInt("delta"),
                "reason", rs.getInt("reason"),
                "ref", rs.getString("ref_id"),
                "createdAt", rs.getString("created_at"))).list();
        data.put("flows", flows);

        List<String> checkins = db.sql("""
                SELECT DATE_FORMAT(checkin_date, '%Y-%m-%d') FROM checkin_record
                WHERE profile_id=? AND is_delete=0 AND checkin_date >= CURDATE() - INTERVAL 60 DAY ORDER BY checkin_date
                """).param(profileId).query(String.class).list();
        data.put("checkins", checkins);
        return ok(data);
    }

    // ---------- helpers ----------
    private TokenService.TokenClaims studentClaims(String auth) {
        if (auth == null || !auth.startsWith("Bearer ")) throw bad("缺少 token");
        TokenService.TokenClaims c;
        try { c = tokens.verify(auth.substring(7)); }
        catch (IllegalStateException e) { throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, e.getMessage()); }
        if (!"student".equals(c.scope())) throw new ResponseStatusException(HttpStatus.FORBIDDEN, "仅学生可查看激励中心");
        return c;
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
