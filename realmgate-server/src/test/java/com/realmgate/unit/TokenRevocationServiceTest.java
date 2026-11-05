package com.realmgate.unit;

import com.realmgate.infrastructure.observability.SecurityMetrics;
import com.realmgate.infrastructure.security.TokenRevocationService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.time.Duration;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * M3 单元测试：TokenRevocationService（站1 黑名单读写 + TTL）。
 * 手动 mock StringRedisTemplate（不引 Spring），验证 key 规范、TTL 计算、null/过期跳过、查询语义。
 */
@DisplayName("M3 单元测试：TokenRevocationService（黑名单读写 + TTL）")
class TokenRevocationServiceTest {

    StringRedisTemplate redis;
    ValueOperations<String, String> ops;
    TokenRevocationService service;

    @BeforeEach
    void setup() {
        redis = mock(StringRedisTemplate.class);
        ops = mock(ValueOperations.class);
        when(redis.opsForValue()).thenReturn(ops);
        service = new TokenRevocationService(redis, mock(SecurityMetrics.class));
    }

    @Test
    @DisplayName("revoke 写入黑名单，key=tenant:{t}:token:revoked:{jti}，TTL≈token 剩余有效期")
    void revoke_writesBlacklistWithTtl() {
        Instant expiresAt = Instant.now().plusSeconds(120);
        service.revoke("alpha", "jti-1", expiresAt);

        var keyCap = org.mockito.ArgumentCaptor.forClass(String.class);
        var ttlCap = org.mockito.ArgumentCaptor.forClass(Duration.class);
        verify(ops).setIfAbsent(keyCap.capture(), eq("1"), ttlCap.capture());
        assertEquals("tenant:alpha:token:revoked:jti-1", keyCap.getValue());
        long secs = ttlCap.getValue().getSeconds();
        assertTrue(secs >= 119 && secs <= 120, "TTL 应≈token 剩余有效期(120s)，实得 " + secs);
    }

    @Test
    @DisplayName("revoke 在 jti / expiresAt 为 null 时直接跳过，不写 Redis")
    void revoke_skipsWhenNull() {
        service.revoke("alpha", null, Instant.now().plusSeconds(60));
        service.revoke("alpha", "jti", null);
        verify(ops, never()).setIfAbsent(any(), any(), any());
    }

    @Test
    @DisplayName("revoke 在 token 已过期（TTL<=0）时跳过")
    void revoke_skipsWhenExpired() {
        service.revoke("alpha", "jti", Instant.now().minusSeconds(10));
        verify(ops, never()).setIfAbsent(any(), any(), any());
    }

    @Test
    @DisplayName("isRevoked：键存在→true；缺失 / tenant 或 jti 为 null→false")
    void isRevoked_various() {
        when(redis.hasKey("tenant:alpha:token:revoked:jti-x")).thenReturn(true);
        when(redis.hasKey("tenant:alpha:token:revoked:absent")).thenReturn(false);

        assertTrue(service.isRevoked("alpha", "jti-x"));
        assertFalse(service.isRevoked("alpha", "absent"));
        assertFalse(service.isRevoked(null, "jti"));
        assertFalse(service.isRevoked("alpha", null));
    }
}
