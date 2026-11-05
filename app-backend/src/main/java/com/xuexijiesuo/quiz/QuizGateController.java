package com.xuexijiesuo.quiz;

import com.xuexijiesuo.account.TokenService;
import com.xuexijiesuo.bank.ConfigController;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

/**
 * 孩子端启动状态裁决（C1）：开机/启动后由服务端决定直达哪个状态，
 * 避免客户端强制重新答题造成会话重置、重复刷积分、白屏锁死。
 * state: quiz(自动出题) / using(使用中倒计时) / wait(带原因提示)
 * 仅学生 scope 可调用，家长端行为不受影响。
 */
@RestController
@RequestMapping("/api/quiz")
public class QuizGateController {

    private final JdbcClient db;
    private final TokenService tokens;
    private final ConfigController configs;
    private final org.springframework.jdbc.core.JdbcTemplate jt;

    public QuizGateController(JdbcClient db, TokenService tokens, ConfigController configs,
                              org.springframework.jdbc.core.JdbcTemplate jt) {
        this.db = db;
        this.tokens = tokens;
        this.configs = configs;
        this.jt = jt;
    }

    @GetMapping("/gate")
    public Map<String, Object> gate(@RequestHeader("Authorization") String auth) {
        long profileId = studentClaims(auth).profileId() == null ? throwNoProfile() : studentClaims(auth).profileId();
        long familyId = db.sql("SELECT family_id FROM student_profile WHERE id=?").param(profileId)
            .query(Long.class).optional().orElseThrow(() -> bad("学生档案不存在"));
        Map<String, Object> d = new LinkedHashMap<>();

        // 例外1：会员权益到期 → 按 FR-30 恢复自由使用，禁用强管控
        boolean entitled = db.sql("""
                SELECT COUNT(*) FROM member WHERE family_id=? AND expire_at > NOW()
                """).param(familyId).query(Long.class).single() > 0
            || db.sql("""
                SELECT COUNT(*) FROM free_time_account WHERE family_id=?
                  AND (trial_expire_at > NOW() OR ad_free_expire_at >= CURDATE())
                """).param(familyId).query(Long.class).single() > 0;
        if (!entitled) return wait(d, "MEMBER_EXPIRED", "会员/试用已到期，管控已暂停（FR-30）");

        // 例外2：已有未到期的使用中会话 → 直达使用中页（开机不重置会话）
        var session = db.sql("""
                SELECT id, TIMESTAMPDIFF(SECOND, NOW(), deadline_at) AS remain
                FROM unlock_session WHERE profile_id=? AND state=5 AND deadline_at > NOW()
                ORDER BY id DESC LIMIT 1
                """).param(profileId)
            .query((rs, i) -> Map.of("id", rs.getLong("id"), "remain", rs.getLong("remain")))
            .optional().orElse(null);
        if (session != null) {
            d.put("state", "using");
            d.put("remainSeconds", session.get("remain"));
            return ok(d);
        }

        // 闭环：最近一次判分达标但会话尚未创建（答题结果页返回）→ 自动解锁并直达使用中页
        Long passedTask = db.sql("""
                SELECT id FROM quiz_task WHERE profile_id=? AND result=1
                  AND submitted_at > NOW() - INTERVAL 5 MINUTE
                ORDER BY submitted_at DESC LIMIT 1
                """).param(profileId).query(Long.class).optional().orElse(null);
        if (passedTask != null) {
            int sessionMin = ((Number) configs.loadConfig(familyId).get("sessionMinutes")).intValue();
            jt.update("INSERT INTO unlock_session(profile_id, state, deadline_at) VALUES(?,5,DATE_ADD(NOW(), INTERVAL ? MINUTE))",
                profileId, sessionMin);
            d.put("state", "using");
            d.put("remainSeconds", sessionMin * 60);
            return ok(d);
        }

        // 例外3：每日挑战次数上限（FR-08）
        Map<String, Object> cfg = configs.loadConfig(familyId);
        int dailyLimit = ((Number) cfg.get("dailyLimit")).intValue();
        long today = db.sql("SELECT COUNT(*) FROM quiz_task WHERE profile_id=? AND DATE(created_at)=CURDATE()")
            .param(profileId).query(Long.class).single();
        if (today >= dailyLimit) return wait(d, "DAILY_LIMIT", "今日挑战次数已用完，请明天再试");

        // 例外4：题库不足（E-03）→ 等待家长配置，不锁死白屏
        Integer grade = db.sql("SELECT grade FROM student_profile WHERE id=?").param(profileId)
            .query(Integer.class).optional().orElse(3);
        long available = db.sql("""
                SELECT COUNT(*) FROM question
                WHERE status=1 AND review_status=0 AND grade=? AND (family_id=? OR source=1)
                  AND id NOT IN (SELECT ar.question_id FROM answer_record ar
                    JOIN quiz_task t ON ar.task_id=t.id
                    WHERE t.profile_id=? AND t.created_at > NOW() - INTERVAL 7 DAY)
                """).param(grade).param(familyId).param(profileId).query(Long.class).single();
        if (available == 0) return wait(d, "NO_QUESTIONS", "题库不足，请家长补充题目后再试(E-03)");

        d.put("state", "quiz");
        d.put("count", cfg.get("count"));
        d.put("durationMin", cfg.get("durationMin"));
        return ok(d);
    }

    private Map<String, Object> wait(Map<String, Object> d, String reason, String message) {
        d.put("state", "wait");
        d.put("reason", reason);
        d.put("message", message);
        return ok(d);
    }

    private TokenService.TokenClaims studentClaims(String auth) {
        if (auth == null || !auth.startsWith("Bearer ")) throw bad("缺少 token");
        TokenService.TokenClaims c;
        try { c = tokens.verify(auth.substring(7)); }
        catch (IllegalStateException e) { throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, e.getMessage()); }
        if (!"student".equals(c.scope())) throw new ResponseStatusException(HttpStatus.FORBIDDEN, "仅孩子端可调用");
        return c;
    }

    private long throwNoProfile() {
        throw new ResponseStatusException(HttpStatus.FORBIDDEN, "缺少学生档案");
    }

    private ResponseStatusException bad(String msg) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, msg);
    }

    private Map<String, Object> ok(Map<String, Object> data) {
        return Map.of("code", 0, "msg", "ok", "data", data);
    }
}
