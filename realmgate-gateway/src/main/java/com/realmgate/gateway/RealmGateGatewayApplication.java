package com.realmgate.gateway;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Phase 9 — Gateway 统一入口。
 *
 * <p>独立 Spring Cloud Gateway（WebFlux / Netty）进程，端口 8080，作为企业应用统一入口：
 * <ul>
 *   <li>Routing：{@code /api/**} 与授权端点（{@code /oauth2/**}、{@code /login}、{@code /logout}、{@code /.well-known/**}）透传到后端 realmgate-server(:9000)。</li>
 *   <li>CORS：统一跨域（见 application.yml globalcors）。</li>
 *   <li>Rate Limit：网关级，基于 Redis（{@code RequestRateLimiter}），键取 JWT tenant_id 或客户端 IP。</li>
 *   <li>TraceId：每个请求生成 {@code X-Trace-Id} 透传，便于链路追踪。</li>
 *   <li>JWT 粗校验：网关层只对 {@code /api/**} 校验 Bearer JWT 的签名与过期（拉后端 {@code /oauth2/jwks}），
 *       不查权限（细粒度在后端）。</li>
 * </ul>
 *
 * <p>注意：本工程<b>不</b>引入 {@code spring-boot-starter-web}（servlet 与 WebFlux 冲突），
 * 也不引入 JPA/安全全套——它是纯反向代理 + 轻量校验层。</p>
 */
@SpringBootApplication
public class RealmGateGatewayApplication {

  public static void main(String[] args) {
    SpringApplication.run(RealmGateGatewayApplication.class, args);
  }
}
