package com.realmgate.gateway.config;

import org.springframework.cloud.gateway.filter.ratelimit.KeyResolver;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import reactor.core.publisher.Mono;

/**
 * Phase 9 — 网关级限流的键解析器。
 *
 * <p>优先用 JWT 中的 {@code tenant_id} 维度限流（多租户公平），无 token 时回退到客户端 IP。
 * 该 bean 被 {@code application.yml} 中 {@code key-resolver: "#{@tenantKeyResolver}"} 引用。</p>
 */
@Configuration
public class GatewayConfig {

  @Bean
  public KeyResolver tenantKeyResolver() {
    return exchange -> {
      String tenantId = exchange.getAttribute("tenantId");
      if (tenantId != null && !tenantId.isBlank()) {
        return Mono.just("tenant:" + tenantId);
      }
      var remote = exchange.getRequest().getRemoteAddress();
      String ip = remote != null ? remote.getAddress().getHostAddress() : "unknown";
      return Mono.just("ip:" + ip);
    };
  }
}
