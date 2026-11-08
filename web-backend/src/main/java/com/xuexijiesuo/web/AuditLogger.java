package com.xuexijiesuo.web;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * 后台操作日志（audit_log 此前仅有表结构、从未写入）。
 * 由 AdminAuthInterceptor 在所有非 GET 请求完成后自动落库，
 * 采用 prev_hash / self_hash 链式哈希，防篡改。
 */
@Service
public class AuditLogger {

    private final JdbcTemplate jt;

    public AuditLogger(JdbcTemplate jt) {
        this.jt = jt;
    }

    public void write(long operatorId, String module, String action, int status, String ip) {
        try {
            String prev = jt.query("SELECT self_hash FROM audit_log ORDER BY id DESC LIMIT 1",
                    rs -> rs.next() ? rs.getString(1) : null);
            if (prev == null) prev = "0".repeat(64);
            String raw = prev + "|" + operatorId + "|" + module + "|" + action + "|" + status
                    + "|" + System.currentTimeMillis();
            String self = sha256(raw);
            jt.update("INSERT INTO audit_log(operator_id, module, action, target_type, target_id, "
                            + "before_json, after_json, ip, prev_hash, self_hash) VALUES(?,?,?,?,?,?,?,?,?,?)",
                    operatorId,
                    module.length() > 32 ? module.substring(0, 32) : module,
                    action.length() > 64 ? action.substring(0, 64) : action,
                    "HTTP", String.valueOf(status), null,
                    "{\"status\":" + status + "}", ip, prev, self);
        } catch (Exception ignored) {
            // 审计失败不应阻断业务主流程
        }
    }

    private static String sha256(String s) {
        try {
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
