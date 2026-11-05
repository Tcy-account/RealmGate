package com.realmgate.infrastructure.observability;

import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;

/**
 * 站 M7：安全业务指标埋点。把本项目最值得观测的安全语义事件做成 Prometheus 计数器，
 * 与 Micrometer 自动提供的 JVM / HTTP(server requests) / Redis 连接 / Flyway 等指标一起，
 * 经 /actuator/prometheus 暴露给 Prometheus 抓取，再在 Grafana 按 realmgate.* 前缀分组看板。
 *
 * 指标清单（全部 realmgate.* 前缀）：
 *   - realmgate_token_revoked_total{tenant}   主动吊销的 token 数（站1 黑名单登记，注销/踢人信号）
 *   - realmgate_login_failure_total{tenant}    登录失败次数（暴力破解 / 弱口令 / 撞库信号）
 *   - realmgate_jwk_rotations_total            密钥轮换次数（密钥生命周期 / 运维信号）
 *   - realmgate_token_rejected_total{reason}   资源服务器拒绝的 token 数（expired/not_yet_valid/revoked/family_revoked）
 *
 * 设计：用 MeterRegistry.counter(name, tags...) 便捷方法，自动按 tag 维度复用同一 Counter，
 * 免维护 Map 缓存。tenant 基数有限（alpha/beta/...），reason 固定 4 值，不会爆 tag cardinality。
 */
@Component
public class SecurityMetrics {

    private final MeterRegistry registry;

    public SecurityMetrics(MeterRegistry registry) {
        this.registry = registry;
        // 预注册：Micrometer Counter 惰性注册（未 increment 过不写入 registry），若从不触发则不出现在
        // /actuator/prometheus 输出。启动时预注册保证 4 个自定义指标恒可见（值可为 0），Grafana 配面板不依赖先有数据。
        //
        // 关键坑（Prometheus 硬性约束）：同名 metric 的所有 series 必须拥有【相同的标签键集合】。
        // 若此处预注册成无标签（tags=[]），运行时 loginFailure(tenant) 注册成带 tenant 标签，Prometheus 会拒绝带标签
        // 的 series（WARN: "all meters with the same name have the same set of tag keys"），导致带标签计数永远不被导出。
        // 故预注册必须带上运行时实际使用的标签键形状（unknown 占位即可），保证标签键集合一致。
        registry.counter("realmgate.token.revoked", "tenant", "unknown");
        registry.counter("realmgate.login.failure", "tenant", "unknown");
        registry.counter("realmgate.jwk.rotations"); // 无标签指标，无需占位
        registry.counter("realmgate.token.rejected", "reason", "unknown");
    }

    public void tokenRevoked(String tenantId) {
        registry.counter("realmgate.token.revoked", "tenant", safe(tenantId)).increment();
    }

    public void loginFailure(String tenantId) {
        registry.counter("realmgate.login.failure", "tenant", safe(tenantId)).increment();
    }

    public void jwkRotated() {
        registry.counter("realmgate.jwk.rotations").increment();
    }

    public void tokenRejected(String reason) {
        registry.counter("realmgate.token.rejected", "reason", safe(reason)).increment();
    }

    private static String safe(String v) {
        return (v == null || v.isBlank()) ? "unknown" : v;
    }
}
