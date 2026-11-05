package com.xuexijiesuo.risk;

import com.xuexijiesuo.account.TokenService;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

/**
 * 申诉入口（FR-W15，App E-06）：学生对作弊判废提出申诉 → 后台客服裁决。
 * 仅允许对「判废(result=3)」任务申诉；同一任务重复申诉由后台侧甄别。
 */
@RestController
@RequestMapping("/api/risk")
public class AppealController {

    private final JdbcClient db;
    private final com.xuexijiesuo.account.TokenService tokens;

    public AppealController(JdbcClient db, com.xuexijiesuo.account.TokenService tokens) {
        this.db = db;
        this.tokens = tokens;
    }

    public record AppealReq(Long taskId, String content) {}

    @PostMapping("/appeal")
    public Map<String, Object> appeal(@RequestHeader("Authorization") String auth, @RequestBody AppealReq req) {
        if (auth == null || !auth.startsWith("Bearer ")) throw bad("缺少 token");
        TokenService.TokenClaims c;
        try { c = tokens.verify(auth.substring(7)); }
        catch (IllegalStateException e) { throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, e.getMessage()); }
        if (req.taskId() == null || req.content() == null || req.content().isBlank()) throw bad("taskId 与申诉内容必填");

        var task = db.sql("""
                SELECT t.profile_id, s.family_id, t.result FROM quiz_task t
                JOIN student_profile s ON s.id = t.profile_id AND s.is_delete=0 WHERE t.id=? AND t.is_delete=0
                """).param(req.taskId())
            .query((rs, i) -> Map.of(
                "profileId", rs.getObject("profile_id"),
                "familyId", rs.getLong("family_id"),
                "result", rs.getInt("result"))).optional().orElse(null);
        if (task == null) throw bad("任务不存在");
        if (((Number) task.get("familyId")).longValue() != c.familyId())
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "任务不属于该家庭");
        if (((Number) task.get("result")).intValue() != 3) throw bad("仅被判废的作答可申诉");

        Long profileId = "student".equals(c.scope()) && c.profileId() != null ? c.profileId()
            : (task.get("profileId") instanceof Number n ? n.longValue() : null);
        db.sql("INSERT INTO appeal_ticket(family_id, profile_id, task_id, type, content) VALUES(?,?,?,'CHEAT',?)")
            .param(c.familyId()).param(profileId).param(req.taskId()).param(req.content()).update();
        return ok(Map.of("appealed", true, "tip", "客服将在48小时内裁决(FR-W16)"));
    }

    private ResponseStatusException bad(String msg) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, msg);
    }

    private Map<String, Object> ok(Map<String, Object> data) {
        return Map.of("code", 0, "msg", "ok", "data", data);
    }
}
