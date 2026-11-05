package com.xuexijiesuo.web;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * 后台 RBAC 拦截器（FR-W02）：
 * 1. /api/ops/** 全部要求 admin scope 的 JWT（/api/ops/auth/login 除外）；
 * 2. 写操作（非 GET）要求 ADMIN 或 FINANCE 角色（资金处置双人复核在服务层另行强制）；
 * 3. 越权返回 403 并可扩展审计留痕。
 */
@Component
public class AdminAuthInterceptor implements HandlerInterceptor {

    private final WebTokenService tokens;

    public AdminAuthInterceptor(WebTokenService tokens) {
        this.tokens = tokens;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {
        String path = request.getRequestURI();
        if (path.equals("/api/ops/auth/login")) return true;

        String auth = request.getHeader("Authorization");
        if (auth == null || !auth.startsWith("Bearer ")) {
            response.sendError(401, "未登录");
            return false;
        }
        try {
            var c = tokens.verify(auth.substring(7));
            if (!c.scope().startsWith("admin:")) {
                response.sendError(403, "非后台角色");
                return false;
            }
            if (!"GET".equals(request.getMethod())) {
                String role = c.scope().substring("admin:".length());
                // 细粒度写权限：按路径域映射角色（权限矩阵 FR-W 模块×角色）
                boolean allowed;
                if (path.startsWith("/api/ops/review")) allowed = role.equals("REVIEWER") || role.equals("ADMIN");
                else if (path.startsWith("/api/ops/tickets")) allowed = role.equals("CS") || role.equals("ADMIN");
                else if (path.startsWith("/api/ops/config")) allowed = role.equals("FINANCE") || role.equals("ADMIN");
                else allowed = role.equals("ADMIN");
                if (!allowed) {
                    response.sendError(403, "该角色无此写权限");
                    return false;
                }
            }
            request.setAttribute("adminRole", c.scope().substring("admin:".length()));
            return true;
        } catch (IllegalStateException e) {
            response.sendError(401, e.getMessage());
            return false;
        }
    }
}
