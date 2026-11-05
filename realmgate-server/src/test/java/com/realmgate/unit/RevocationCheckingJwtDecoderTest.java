package com.realmgate.unit;

import com.realmgate.infrastructure.security.RevocationCheckingJwtDecoder;
import com.realmgate.infrastructure.security.TokenFamilyService;
import com.realmgate.infrastructure.security.TokenRevocationService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * M3 单元测试：RevocationCheckingJwtDecoder（验签后黑名单 / 家族拦截）。
 * mock delegate（验签）+ revocation + familyService，验证「通过 / jti 黑名单 / 家族失效 / jti 为 null」四态。
 * 这是 M2 TokenRevocationRefreshTest 在集成层端到端证明过的黑名单逻辑，在此隔离断言其精确行为。
 */
@DisplayName("M3 单元测试：RevocationCheckingJwtDecoder（验签后黑名单 / 家族拦截）")
class RevocationCheckingJwtDecoderTest {

    JwtDecoder delegate;
    TokenRevocationService revocation;
    TokenFamilyService familyService;
    RevocationCheckingJwtDecoder decoder;

    @BeforeEach
    void setup() {
        delegate = mock(JwtDecoder.class);
        revocation = mock(TokenRevocationService.class);
        familyService = mock(TokenFamilyService.class);
        decoder = new RevocationCheckingJwtDecoder(delegate, revocation, familyService);
    }

    private Jwt jwt(String jti, String tenant, String family) {
        Jwt j = mock(Jwt.class);
        when(j.getId()).thenReturn(jti);
        when(j.getClaimAsString("tenant_id")).thenReturn(tenant);
        when(j.getClaimAsString("family_id")).thenReturn(family);
        return j;
    }

    @Test
    @DisplayName("未吊销且家族未失效 → 验签通过，原样返回 Jwt")
    void decode_passesThroughWhenNotRevoked() {
        Jwt j = jwt("j1", "alpha", "f1");
        when(delegate.decode("tok")).thenReturn(j);
        when(revocation.isRevoked("alpha", "j1")).thenReturn(false);
        when(familyService.isFamilyRevoked("alpha", "f1")).thenReturn(false);

        assertSame(j, decoder.decode("tok"));
    }

    @Test
    @DisplayName("jti 在黑名单 → 抛 OAuth2AuthenticationException(invalid_token) → 链② 转 401")
    void decode_throwsWhenJtiRevoked() {
        Jwt j = jwt("j1", "alpha", "f1");
        when(delegate.decode("tok")).thenReturn(j);
        when(revocation.isRevoked("alpha", "j1")).thenReturn(true);

        OAuth2AuthenticationException ex = assertThrows(OAuth2AuthenticationException.class,
                () -> decoder.decode("tok"));
        assertEquals("invalid_token", ex.getError().getErrorCode());
    }

    @Test
    @DisplayName("family 整族失效 → 抛 invalid_token")
    void decode_throwsWhenFamilyRevoked() {
        Jwt j = jwt("j1", "alpha", "f1");
        when(delegate.decode("tok")).thenReturn(j);
        when(revocation.isRevoked("alpha", "j1")).thenReturn(false);
        when(familyService.isFamilyRevoked("alpha", "f1")).thenReturn(true);

        OAuth2AuthenticationException ex = assertThrows(OAuth2AuthenticationException.class,
                () -> decoder.decode("tok"));
        assertEquals("invalid_token", ex.getError().getErrorCode());
    }

    @Test
    @DisplayName("jti 为 null → 仍查 isRevoked(tenant,null) 但返回 false，decode 不抛（服务层 null 守卫）")
    void decode_nullJtiPassesThrough() {
        Jwt j = jwt(null, "alpha", "f1");
        when(delegate.decode("tok")).thenReturn(j);
        when(revocation.isRevoked("alpha", null)).thenReturn(false);
        assertSame(j, decoder.decode("tok"));
        // decoder 总是把 (tenant, jti=null) 交给服务层；服务层 null 守卫返回 false → 通过
        verify(revocation).isRevoked("alpha", null);
    }
}
