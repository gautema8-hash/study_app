package com.xuexijiesuo.opsbff;

import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.web.bind.annotation.*;

/**
 * 运营汇总（W6，供 admin-web 工作台 W1）。
 * TODO(W安全迭代): 接入后台 RBAC 鉴权后加管理员校验；当前仅内网/开发可用。
 */
@RestController
@RequestMapping("/api/ops")
public class OpsController {

    private final JdbcClient db;

    public OpsController(JdbcClient db) {
        this.db = db;
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

    private Map<String, Object> ok(Map<String, Object> data) {
        return Map.of("code", 0, "msg", "ok", "data", data);
    }
}
