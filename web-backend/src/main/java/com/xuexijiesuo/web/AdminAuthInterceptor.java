package com.xuexijiesuo.web;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * 后台 RBAC 拦截器（FR-W02）：
 * 1. /api/ops/** 全部要求 admin scope 的 JWT（/api/ops/auth/login 除外）；
 * 2. 写操作（非 GET）要求对应角色（资金处置双人复核在服务层另行强制）；
 * 3. 越权返回统一中文 JSON（而非 Spring 默认英文错误体）；
 * 4. 所有写操作自动落 audit_log 操作日志（含 hash 链），供后台审计页查询。
 */
@Component
public class AdminAuthInterceptor implements HandlerInterceptor {

    private final WebTokenService tokens;
    private final AuditLogger audit;

    public AdminAuthInterceptor(WebTokenService tokens, AuditLogger audit) {
        this.tokens = tokens;
        this.audit = audit;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {
        String path = request.getRequestURI();
        if (path.equals("/api/ops/auth/login")) return true;

        String auth = request.getHeader("Authorization");
        if (auth == null || !auth.startsWith("Bearer ")) {
            deny(response, 401, "登录已过期，请重新登录");
            return false;
        }
        WebTokenService.TokenClaims c;
        try {
            c = tokens.verify(auth.substring(7));
        } catch (IllegalStateException e) {
            deny(response, 401, "登录已过期，请重新登录");
            return false;
        }
        if (!c.scope().startsWith("admin:")) {
            deny(response, 403, "当前账号无后台访问权限");
            return false;
        }
        String role = c.scope().substring("admin:".length());
        if (!"GET".equals(request.getMethod())) {
            boolean allowed;
            if (path.startsWith("/api/ops/review")) allowed = role.equals("REVIEWER") || role.equals("ADMIN");
            else if (path.startsWith("/api/ops/store")) allowed = role.equals("REVIEWER") || role.equals("ADMIN");
            else if (path.startsWith("/api/ops/tickets")) allowed = role.equals("CS") || role.equals("ADMIN");
            else if (path.startsWith("/api/ops/config")) allowed = role.equals("FINANCE") || role.equals("ADMIN");
            else allowed = role.equals("ADMIN");
            if (!allowed) {
                deny(response, 403, "当前角色无此操作权限，请联系管理员");
                return false;
            }
        }
        request.setAttribute("adminRole", role);
        request.setAttribute("adminId", c.familyId());
        return true;
    }

    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response,
                                Object handler, Exception ex) {
        if ("GET".equals(request.getMethod()) || request.getRequestURI().equals("/api/ops/auth/login")) return;
        Object id = request.getAttribute("adminId");
        if (id == null) return;
        String path = request.getRequestURI();
        String module = moduleOf(path);
        String action = request.getMethod() + " " + path.replace("/api/ops/", "");
        audit.write(((Number) id).longValue(), module, action,
                response.getStatus(), request.getRemoteAddr());
    }

    private static String moduleOf(String path) {
        if (path.contains("/users")) return "USER";
        if (path.contains("/orders") || path.contains("/config")) return "FINANCE";
        if (path.contains("/questions") || path.contains("/bank") || path.contains("/store")) return "QUESTION";
        if (path.contains("/review") || path.contains("/tickets")) return "TICKET";
        if (path.contains("/iam")) return "IAM";
        return "OPS";
    }

    private static void deny(HttpServletResponse response, int status, String msg) throws java.io.IOException {
        response.setStatus(status);
        response.setCharacterEncoding("UTF-8");
        response.setContentType("application/json;charset=UTF-8");
        response.getWriter().write("{\"code\":" + status + ",\"msg\":\"" + msg + "\",\"data\":null}");
    }
}
