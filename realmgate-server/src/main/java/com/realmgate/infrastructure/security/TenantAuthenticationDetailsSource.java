package com.realmgate.infrastructure.security;

import org.springframework.security.authentication.AuthenticationDetailsSource;
import org.springframework.security.web.authentication.WebAuthenticationDetails;
import jakarta.servlet.http.HttpServletRequest;

public class TenantAuthenticationDetailsSource
    implements AuthenticationDetailsSource<HttpServletRequest, WebAuthenticationDetails> {
  @Override
  public WebAuthenticationDetails buildDetails(HttpServletRequest context) {
    String tenant = context.getParameter("tenant");
    if (tenant != null && !tenant.isBlank()) {
      LoginTenantHolder.set(tenant); // 供 UserDetailsServiceImpl 查询
    } else {
      LoginTenantHolder.clear(); // 清掉线程池复用可能残留的租户，避免串租户
    }
    return new TenantWebAuthenticationDetails(context, tenant);
  }
}
