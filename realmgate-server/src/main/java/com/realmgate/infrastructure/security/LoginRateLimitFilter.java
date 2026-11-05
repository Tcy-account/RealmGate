package com.realmgate.infrastructure.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.realmgate.common.api.ApiResponse;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.filter.OncePerRequestFilter;

// 认证前拦截 POST /login：已锁定直接 429，省去无谓密码校验（也防在锁定期内继续试密码）
public class LoginRateLimitFilter extends OncePerRequestFilter {

    private final LoginRateLimitService service;
    private final ObjectMapper mapper = new ObjectMapper();

    public LoginRateLimitFilter(LoginRateLimitService service) {
        this.service = service;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest req, HttpServletResponse res, FilterChain chain)
            throws ServletException, IOException {
        // 只拦登录提交；授权码流程、API、静态资源一律放行
        if ("POST".equalsIgnoreCase(req.getMethod()) && "/login".equals(req.getServletPath())) {
            String tenant = req.getParameter("tenant");
            String username = req.getParameter("username");
            if (service.isLocked(tenant, username, clientIp(req))) {
                res.setStatus(HttpStatus.TOO_MANY_REQUESTS.value());   // 429
                res.setContentType(MediaType.APPLICATION_JSON_VALUE);
                res.setHeader("Retry-After", "900");                  // 与 LOCK_SECONDS 对齐
                mapper.writeValue(res.getWriter(),
                    ApiResponse.error(429, "登录尝试过于频繁，请 15 分钟后再试"));
                return;   // 短路，不进认证
            }
        }
        chain.doFilter(req, res);
    }

    // 直连 9000 无反向代理，getRemoteAddr 即真实 IP；兼容 XFF（未来前置网关）
    private String clientIp(HttpServletRequest req) {
        String xff = req.getHeader("X-Forwarded-For");
        if (xff != null && !xff.isBlank()) return xff.split(",")[0].trim();
        return req.getRemoteAddr();
    }
}
