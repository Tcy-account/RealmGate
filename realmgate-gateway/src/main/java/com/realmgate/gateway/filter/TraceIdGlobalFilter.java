package com.realmgate.gateway.filter;

import java.util.UUID;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

/**
 * Phase 9 — TraceId（链路追踪）。
 *
 * <p>每个请求生成 {@code X-Trace-Id}（16 进制 UUID，去横线），放入请求头 + 响应头，
 * 供后端与网关自身日志串联。不阻断、不修改业务。</p>
 */
@Component
public class TraceIdGlobalFilter implements GlobalFilter, Ordered {

  public static final String TRACE_HEADER = "X-Trace-Id";

  @Override
  public int getOrder() {
    return -3;
  }

  @Override
  public Mono<Void> filter(ServerWebExchange exchange, org.springframework.cloud.gateway.filter.GatewayFilterChain chain) {
    String traceId = UUID.randomUUID().toString().replace("-", "");
    exchange.getAttributes().put("traceId", traceId);
    ServerHttpRequest mutated = exchange.getRequest().mutate()
        .header(TRACE_HEADER, traceId)
        .build();
    exchange.getResponse().getHeaders().add(TRACE_HEADER, traceId);
    return chain.filter(exchange.mutate().request(mutated).build());
  }
}
