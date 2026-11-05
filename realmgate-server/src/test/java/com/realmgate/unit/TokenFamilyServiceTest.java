package com.realmgate.unit;

import com.realmgate.infrastructure.security.TokenFamilyService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.SetOperations;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * M3 单元测试：TokenFamilyService（站2 家族生命周期 / 重放 / 整族失效）。
 * 手动 mock Redis 的 ValueOps / SetOps，用 ArgumentCaptor 把 createFamily 写入的 JSON 回读给
 * validateRefresh 解析，验证 OK / REPLAY / FAMILY_REVOKED 三态与 null 安全。
 */
@DisplayName("M3 单元测试：TokenFamilyService（家族生命周期 / 重放 / 整族失效）")
class TokenFamilyServiceTest {

    StringRedisTemplate redis;
    ValueOperations<String, String> valueOps;
    SetOperations<String, String> setOps;
    TokenFamilyService service;

    @BeforeEach
    void setup() {
        redis = mock(StringRedisTemplate.class);
        valueOps = mock(ValueOperations.class);
        setOps = mock(SetOperations.class);
        when(redis.opsForValue()).thenReturn(valueOps);
        when(redis.opsForSet()).thenReturn(setOps);
        service = new TokenFamilyService(redis);
    }

    @Test
    @DisplayName("createFamily 后用当前 refreshJti 校验 → OK")
    void createFamily_thenValidateRefreshOK() {
        service.createFamily("alpha", "fam1", "u1", "realmgate-web", "rt-jti-1");

        var valCap = org.mockito.ArgumentCaptor.forClass(String.class);
        verify(valueOps).set(eq("tenant:alpha:token:family:fam1"), valCap.capture(), any());
        when(valueOps.get("tenant:alpha:token:family:fam1")).thenReturn(valCap.getAllValues().get(0));

        assertEquals(TokenFamilyService.ValidateResult.OK,
                service.validateRefresh("alpha", "fam1", "rt-jti-1"));
    }

    @Test
    @DisplayName("refresh jti 与当前有效不符（旧 RT 重放） → REPLAY")
    void validateRefresh_replayWhenStale() {
        service.createFamily("alpha", "fam1", "u1", "realmgate-web", "rt-jti-1");

        var valCap = org.mockito.ArgumentCaptor.forClass(String.class);
        verify(valueOps).set(eq("tenant:alpha:token:family:fam1"), valCap.capture(), any());
        when(valueOps.get("tenant:alpha:token:family:fam1")).thenReturn(valCap.getAllValues().get(0));

        assertEquals(TokenFamilyService.ValidateResult.REPLAY,
                service.validateRefresh("alpha", "fam1", "stale-rt-jti"));
    }

    @Test
    @DisplayName("revokeFamily 后 isFamilyRevoked=true 且 validateRefresh→FAMILY_REVOKED")
    void revokeFamily_thenRevoked() {
        service.createFamily("alpha", "fam1", "u1", "realmgate-web", "rt-jti-1");

        var valCap = org.mockito.ArgumentCaptor.forClass(String.class);
        verify(valueOps).set(eq("tenant:alpha:token:family:fam1"), valCap.capture(), any());
        when(valueOps.get("tenant:alpha:token:family:fam1")).thenReturn(valCap.getAllValues().get(0));
        when(setOps.isMember("tenant:alpha:token:family_revoked", "fam1")).thenReturn(true);

        service.revokeFamily("alpha", "fam1");

        assertTrue(service.isFamilyRevoked("alpha", "fam1"));
        assertEquals(TokenFamilyService.ValidateResult.FAMILY_REVOKED,
                service.validateRefresh("alpha", "fam1", "rt-jti-1"));
    }

    @Test
    @DisplayName("null tenant / familyId 安全：createFamily 无操作；isFamilyRevoked=false；validateRefresh=FAMILY_REVOKED")
    void nullSafe() {
        service.createFamily(null, "fam", "u", "c", "rt");
        verify(valueOps, never()).set(any(), any(), any());

        assertFalse(service.isFamilyRevoked("alpha", null));
        assertEquals(TokenFamilyService.ValidateResult.FAMILY_REVOKED,
                service.validateRefresh("alpha", null, "rt"));
    }
}
