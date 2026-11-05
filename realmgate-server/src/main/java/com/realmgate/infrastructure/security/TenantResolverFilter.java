package com.realmgate.infrastructure.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

public class TenantResolverFilter extends OncePerRequestFilter {

    @Override
    protected void doFilterInternal(HttpServletRequest req, HttpServletResponse res, FilterChain chain)
            throws ServletException, IOException {
        String tenantId = resolveTenantId(req);
        if (tenantId != null && !tenantId.isBlank()) {
            TenantContext.set(tenantId.trim());
        }
        try {
            chain.doFilter(req, res);
        } finally {
            TenantContext.clear(); // 不变：请求结束清空，防线程池串租户
        }
    }

    // 双来源解析：业务请求信 JWT，登录/ping 信请求头
    private String resolveTenantId(HttpServletRequest req) {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth instanceof JwtAuthenticationToken jwtAuth) {
            // 资源服务器已校验过签名 → token 里的 tenant_id 可信，不读请求头
            return jwtAuth.getToken().getClaimAsString("tenant_id");
        }
        // 无 JWT（登录/ping）：登录阶段允许客户端声明租户
        String header = req.getHeader("X-Tenant-Id");
        return (header != null && !header.isBlank()) ? header : null;
    }
}
