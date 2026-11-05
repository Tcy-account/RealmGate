package com.realmgate.infrastructure.security;

import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.authentication.AuthenticationFailureHandler;
import org.springframework.security.web.authentication.SimpleUrlAuthenticationFailureHandler;
import com.realmgate.infrastructure.observability.SecurityMetrics;

// 登录失败：累加计数 + 达阈值置锁；其余行为交给默认重定向 /login?error
public class LoginFailureRateLimitHandler implements AuthenticationFailureHandler {

    private final LoginRateLimitService service;
    private final SecurityMetrics metrics;
    private final AuthenticationFailureHandler delegate = new SimpleUrlAuthenticationFailureHandler();

    public LoginFailureRateLimitHandler(LoginRateLimitService service, SecurityMetrics metrics) {
        this.service = service;
        this.metrics = metrics;
    }

    @Override
    public void onAuthenticationFailure(HttpServletRequest request, HttpServletResponse response,
            AuthenticationException exception) throws IOException, ServletException {
        service.onFailure(request.getParameter("tenant"),
                          request.getParameter("username"),
                          clientIp(request));
        // 站 M7：每次登录失败计数（tenant 来自登录表单字段，缺失记 unknown）
        metrics.loginFailure(request.getParameter("tenant"));
        delegate.onAuthenticationFailure(request, response, exception);   // 默认重定向 /login?error
    }

    private String clientIp(HttpServletRequest req) {
        String xff = req.getHeader("X-Forwarded-For");
        if (xff != null && !xff.isBlank()) return xff.split(",")[0].trim();
        return req.getRemoteAddr();
    }
}
