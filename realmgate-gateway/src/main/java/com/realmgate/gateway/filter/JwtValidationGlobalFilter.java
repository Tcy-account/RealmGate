package com.realmgate.gateway.filter;

import java.nio.charset.StandardCharsets;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.http.HttpStatus;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;

/**
 * Phase 9 — JWT 粗校验（网关层）。
 *
 * <p>只对 {@code /api/**} 生效：</p>
 * <ul>
 *   <li>带 Bearer token：拉后端 {@code /oauth2/jwks} 构建的 {@link JwtDecoder} 验签 + 过期；
 *       无效（签名错/过期）→ 401。有效 → 把 {@code tenant_id}/{@code sub}/{@code authorities}
 *       写入 {@code X-*} 请求头透传后端（后端仍独立校验 JWT，这里只是粗粒度 + 可观测）。</li>
 *   <li>无 token：放行，交由后端按 {@code @PreAuthorize} 决定（粗校验不强制所有 /api 必须带 token）。</li>
 *   <li>非 {@code /api/**}（授权端点）：直接透传，不校验。</li>
 * </ul>
 *
 * <p>顺序设为 {@code -2}，早于网关限流（{@code RequestRateLimiter} order=0），保证限流键能拿到 tenant_id。</p>
 */
@Component
public class JwtValidationGlobalFilter implements GlobalFilter, Ordered {

  private final String jwksUri;

  private volatile JwtDecoder decoder;

  public JwtValidationGlobalFilter(@Value("${realmgate.backend.jwks-uri}") String jwksUri) {
    this.jwksUri = jwksUri;
  }

  /** 懒加载 JwtDecoder：首次请求（此时后端应已起）才拉 JWKS，避免网关启动因 9000 未起而失败。 */
  private JwtDecoder decoder() {
    if (decoder == null) {
      synchronized (this) {
        if (decoder == null) {
          decoder = NimbusJwtDecoder.withJwkSetUri(jwksUri).build();
        }
      }
    }
    return decoder;
  }

  @Override
  public int getOrder() {
    return -2;
  }

  @Override
  public Mono<Void> filter(ServerWebExchange exchange, org.springframework.cloud.gateway.filter.GatewayFilterChain chain) {
    String path = exchange.getRequest().getURI().getPath();
    if (!path.startsWith("/api/")) {
      return chain.filter(exchange); // 授权端点等透传
    }

    String auth = exchange.getRequest().getHeaders().getFirst("Authorization");
    if (auth == null || !auth.startsWith("Bearer ")) {
      return chain.filter(exchange); // 无 token 放行，后端决定
    }
    String token = auth.substring(7).trim();

    return Mono.fromCallable(() -> decoder().decode(token))
        .subscribeOn(Schedulers.boundedElastic())
        .flatMap(jwt -> {
          String tenantId = jwt.getClaimAsString("tenant_id");
          List<String> authorities = jwt.getClaimAsStringList("authorities");
          if (authorities == null) {
            authorities = List.of();
          }
          ServerHttpRequest mutated = exchange.getRequest().mutate()
              .header("X-Tenant-Id", tenantId == null ? "" : tenantId)
              .header("X-User-Id", jwt.getSubject() == null ? "" : jwt.getSubject())
              .header("X-Authorities", String.join(",", authorities))
              .build();
          if (tenantId != null) {
            exchange.getAttributes().put("tenantId", tenantId);
          }
          return chain.filter(exchange.mutate().request(mutated).build());
        })
        .onErrorResume(JwtException.class, ex -> unauthorized(exchange));
  }

  private Mono<Void> unauthorized(ServerWebExchange exchange) {
    ServerHttpResponse resp = exchange.getResponse();
    resp.setStatusCode(HttpStatus.UNAUTHORIZED);
    resp.getHeaders().add("Content-Type", "application/json;charset=UTF-8");
    String body = "{\"code\":401,\"message\":\"Invalid or expired token\"}";
    var buffer = resp.bufferFactory().wrap(body.getBytes(StandardCharsets.UTF_8));
    return resp.writeWith(Mono.just(buffer));
  }
}
