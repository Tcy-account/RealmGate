package com.realmgate.infrastructure.security;

import java.time.Duration;
import java.time.Instant;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import com.realmgate.infrastructure.observability.SecurityMetrics;

/**
 * 站1 Token Revocation：封装 Redis 黑名单的读写。
 * key 规范（计划 §35）：tenant:{t}:token:revoked:{jti}
 * value 无意义（仅作存在性标记），TTL = token 剩余有效期，过期由 Redis 自动清理。
 */
@Service
public class TokenRevocationService {

    private final StringRedisTemplate redis;
    private final SecurityMetrics metrics;

    public TokenRevocationService(StringRedisTemplate redis, SecurityMetrics metrics) {
        this.redis = redis;
        this.metrics = metrics;
    }

    private String key(String tenantId, String jti) {
        return "tenant:" + tenantId + ":token:revoked:" + jti;
    }

    /**
     * 注销：把 jti 写进黑名单。
     * setIfAbsent 防重复写；剩余秒数 ≤ 0 说明 token 本就快过期，跳过登记。
     */
    public void revoke(String tenantId, String jti, Instant expiresAt) {
        if (tenantId == null || jti == null || expiresAt == null) {
            return;
        }
        long ttlSeconds = expiresAt.getEpochSecond() - Instant.now().getEpochSecond();
        if (ttlSeconds <= 0) {
            return;
        }
        // 站 M7：仅"真正写入黑名单"才计数（setIfAbsent 首次写入，重复注销不重复计数）
        Boolean written = redis.opsForValue().setIfAbsent(key(tenantId, jti), "1", Duration.ofSeconds(ttlSeconds));
        if (Boolean.TRUE.equals(written)) {
            metrics.tokenRevoked(tenantId);
        }
    }

    /**
     * 验签后查询：黑名单命中返回 true。
     * Boolean.TRUE.equals 防 hasKey 返回 null 时空指针。
     */
    public boolean isRevoked(String tenantId, String jti) {
        if (tenantId == null || jti == null) {
            return false;
        }
        return Boolean.TRUE.equals(redis.hasKey(key(tenantId, jti)));
    }
}
