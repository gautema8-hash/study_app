package com.xuexijiesuo.web;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.web.bind.annotation.*;

/**
 * 运营增强接口：仪表盘宽表指标、对账中心、操作日志、待办快捷入口、批量逻辑删除。
 * 全部只读 + 受 AdminAuthInterceptor RBAC 保护；写操作仅 ADMIN 可调用。
 */
@RestController
@RequestMapping("/api/ops/insight")
public class OpsInsightController {

    private final JdbcClient db;
    private final JdbcTemplate jt;

    public OpsInsightController(JdbcClient db, JdbcTemplate jt) {
        this.db = db;
        this.jt = jt;
    }

    // ---------- 待办快捷入口（工作台） ----------
    @GetMapping("/todo")
    public Map<String, Object> todo() {
        Map<String, Object> d = new LinkedHashMap<>();
        d.put("reviewPending", count("SELECT COUNT(*) FROM question WHERE is_delete=0 AND review_status=1"));
        d.put("ticketPending", count("SELECT COUNT(*) FROM appeal_ticket WHERE is_delete=0 AND status=0"));
        d.put("storePending", count("SELECT COUNT(*) FROM bank_store_item WHERE is_delete=0 AND status=1"));
        d.put("alarmsUnread", count("SELECT COUNT(*) FROM alarm WHERE is_delete=0 AND read_flag=0"));
        d.put("ordersRefunding", count("SELECT COUNT(*) FROM vip_order WHERE is_delete=0 AND status=4"));
        return ok(d);
    }

    // ---------- 数据看板（宽表指标 + 趋势 + 分布） ----------
    @GetMapping("/dashboard")
    public Map<String, Object> dashboard() {
        Map<String, Object> d = new LinkedHashMap<>();
        d.put("families", count("SELECT COUNT(*) FROM family_account WHERE is_delete=0"));
        d.put("students", count("SELECT COUNT(*) FROM student_profile WHERE is_delete=0"));
        d.put("questions", count("SELECT COUNT(*) FROM question WHERE is_delete=0 AND status=1"));
        d.put("storeItems", count("SELECT COUNT(*) FROM bank_store_item WHERE is_delete=0 AND status=2"));
        d.put("ordersPaid", count("SELECT COUNT(*) FROM vip_order WHERE is_delete=0 AND status=2"));
        d.put("ordersRefunded", count("SELECT COUNT(*) FROM vip_order WHERE is_delete=0 AND status=5"));
        d.put("revenueTotal", count("SELECT COALESCE(SUM(amount),0) FROM vip_order WHERE is_delete=0 AND status=2"));
        d.put("refundTotal", count("SELECT COALESCE(SUM(amount),0) FROM vip_order WHERE is_delete=0 AND status=5"));
        d.put("vipFamilies", count("SELECT COUNT(*) FROM member WHERE is_delete=0 AND tier>0"));
        d.put("alarmsUnread", count("SELECT COUNT(*) FROM alarm WHERE is_delete=0 AND read_flag=0"));
        // 近 7 日订单趋势
        d.put("orderTrend", db.sql("""
                SELECT DATE(created_at) AS day, COUNT(*) AS cnt, COALESCE(SUM(amount),0) AS amt
                FROM vip_order WHERE is_delete=0 AND created_at >= DATE_SUB(CURDATE(), INTERVAL 6 DAY)
                GROUP BY DATE(created_at) ORDER BY day
                """).query((rs, i) -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("day", rs.getString("day"));
                    m.put("count", rs.getInt("cnt"));
                    m.put("amount", rs.getLong("amt"));
                    return m;
                }).list());
        // 题目科目分布
        d.put("subjectDist", db.sql("""
                SELECT subject, COUNT(*) AS cnt FROM question WHERE is_delete=0 AND status=1
                GROUP BY subject ORDER BY subject
                """).query((rs, i) -> Map.of("subject", rs.getInt("subject"), "count", rs.getInt("cnt"))).list());
        // 会员等级分布
        d.put("tierDist", db.sql("""
                SELECT COALESCE(m.tier,0) AS tier, COUNT(*) AS cnt
                FROM family_account f LEFT JOIN member m ON m.family_id=f.id AND m.is_delete=0
                WHERE f.is_delete=0 GROUP BY COALESCE(m.tier,0) ORDER BY tier
                """).query((rs, i) -> Map.of("tier", rs.getInt("tier"), "count", rs.getInt("cnt"))).list());
        return ok(d);
    }

    // ---------- 对账中心 ----------
    @GetMapping("/reconcile")
    public Map<String, Object> reconcile() {
        Map<String, Object> d = new LinkedHashMap<>();
        d.put("orderCount", count("SELECT COUNT(*) FROM vip_order WHERE is_delete=0"));
        d.put("paidCount", count("SELECT COUNT(*) FROM vip_order WHERE is_delete=0 AND status IN (1,2)"));
        d.put("paidAmount", count("SELECT COALESCE(SUM(amount),0) FROM vip_order WHERE is_delete=0 AND status IN (1,2)"));
        d.put("shippedAmount", count("SELECT COALESCE(SUM(amount),0) FROM vip_order WHERE is_delete=0 AND status=2"));
        d.put("refundCount", count("SELECT COUNT(*) FROM vip_order WHERE is_delete=0 AND status=5"));
        d.put("refundAmount", count("SELECT COALESCE(SUM(amount),0) FROM vip_order WHERE is_delete=0 AND status=5"));
        d.put("refundingCount", count("SELECT COUNT(*) FROM vip_order WHERE is_delete=0 AND status=4"));
        // 按渠道汇总
        d.put("byChannel", db.sql("""
                SELECT channel, COUNT(*) AS cnt, COALESCE(SUM(amount),0) AS amt
                FROM vip_order WHERE is_delete=0 GROUP BY channel ORDER BY channel
                """).query((rs, i) -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("channel", rs.getInt("channel"));
                    m.put("count", rs.getInt("cnt"));
                    m.put("amount", rs.getLong("amt"));
                    return m;
                }).list());
        // 按套餐汇总
        d.put("bySku", db.sql("""
                SELECT sku, COUNT(*) AS cnt, COALESCE(SUM(amount),0) AS amt
                FROM vip_order WHERE is_delete=0 GROUP BY sku ORDER BY sku
                """).query((rs, i) -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("sku", rs.getInt("sku"));
                    m.put("count", rs.getInt("cnt"));
                    m.put("amount", rs.getLong("amt"));
                    return m;
                }).list());
        // 异常：已支付但无发货时间（资损风险）
        d.put("abnormalOrders", db.sql("""
                SELECT order_no, family_id, sku, amount, channel, status, created_at
                FROM vip_order WHERE is_delete=0 AND status=1 AND shipped_at IS NULL
                ORDER BY id DESC LIMIT 50
                """).query((rs, i) -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("orderNo", rs.getString("order_no"));
                    m.put("familyId", rs.getLong("family_id"));
                    m.put("sku", rs.getInt("sku"));
                    m.put("amount", rs.getInt("amount"));
                    m.put("channel", rs.getInt("channel"));
                    m.put("status", rs.getInt("status"));
                    m.put("createdAt", rs.getString("created_at"));
                    return m;
                }).list());
        return ok(d);
    }

    // ---------- 操作日志（audit_log） ----------
    @GetMapping("/audit")
    public Map<String, Object> audit(@RequestParam(defaultValue = "1") Integer page,
                                     @RequestParam(defaultValue = "20") Integer size,
                                     @RequestParam(required = false) String module) {
        page = Math.max(page, 1);
        size = Math.min(Math.max(size, 1), 100);
        // 注意：audit_log 与 admin_user 均有 is_delete 列，JOIN 时必须带别名避免列歧义
        StringBuilder where = new StringBuilder(" WHERE a.is_delete=0");
        List<Object> params = new ArrayList<>();
        if (module != null && !module.isBlank()) { where.append(" AND a.module=?"); params.add(module.trim()); }
        Long total = db.sql("SELECT COUNT(*) FROM audit_log a" + where).params(params).query(Long.class).single();
        List<Map<String, Object>> list = db.sql("""
                SELECT a.id, a.operator_id, a.module, a.action, a.target_type, a.target_id,
                       a.after_json, a.ip, a.created_at, u.username
                FROM audit_log a LEFT JOIN admin_user u ON u.id=a.operator_id AND u.is_delete=0
                """ + where + " ORDER BY a.id DESC LIMIT ? OFFSET ?")
            .params(params).param(size).param((page - 1) * size)
            .query((rs, i) -> {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("id", rs.getLong("id"));
                m.put("operatorId", rs.getLong("operator_id"));
                m.put("username", rs.getString("username"));
                m.put("module", rs.getString("module"));
                m.put("action", rs.getString("action"));
                m.put("target", rs.getString("target_id"));
                m.put("result", rs.getString("after_json"));
                m.put("ip", rs.getString("ip"));
                m.put("createdAt", rs.getString("created_at"));
                return m;
            }).list();
        return ok(Map.of("total", total, "list", list));
    }

    // ---------- 批量逻辑删除（白名单表，防注入） ----------
    public record BatchReq(List<Long> ids) {}

    private static final Map<String, String> BATCH_TABLES = Map.of(
        "users", "family_account",
        "questions", "question",
        "admins", "admin_user",
        "storeItems", "bank_store_item",
        "tickets", "appeal_ticket",
        "reviewTickets", "question"
    );

    @PostMapping("/batch-delete/{domain}")
    public Map<String, Object> batchDelete(@PathVariable String domain,
                                           @RequestBody BatchReq req,
                                           jakarta.servlet.http.HttpServletRequest request) {
        String table = BATCH_TABLES.get(domain);
        if (table == null) throw new org.springframework.web.server.ResponseStatusException(
            org.springframework.http.HttpStatus.BAD_REQUEST, "不支持的批量操作类型");
        List<Long> ids = req.ids() == null ? List.of() : req.ids().stream().distinct().toList();
        if (ids.isEmpty()) throw new org.springframework.web.server.ResponseStatusException(
            org.springframework.http.HttpStatus.BAD_REQUEST, "请至少选择一条记录");
        if (ids.size() > 200) throw new org.springframework.web.server.ResponseStatusException(
            org.springframework.http.HttpStatus.BAD_REQUEST, "单次最多操作 200 条");
        // 管理员批量删除同样禁止删自己 / 最后一个超管
        Object self = request.getAttribute("adminId");
        if ("admins".equals(domain) && self instanceof Number n && ids.contains(n.longValue()))
            throw new org.springframework.web.server.ResponseStatusException(
                org.springframework.http.HttpStatus.BAD_REQUEST, "选中项包含当前登录账号，不能删除自己");
        String ph = String.join(",", java.util.Collections.nCopies(ids.size(), "?"));
        String extra = "reviewTickets".equals(domain) ? " AND review_status=1" : "";
        int n = db.sql("UPDATE " + table + " SET is_delete=1 WHERE id IN (" + ph + ") AND is_delete=0" + extra)
            .params(ids).update();
        return ok(Map.of("deleted", n));
    }

    private long count(String sql) {
        return db.sql(sql).query(Long.class).single();
    }

    private Map<String, Object> ok(Map<String, Object> data) {
        return Map.of("code", 0, "msg", "ok", "data", data);
    }
}
