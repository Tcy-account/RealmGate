package com.realmgate.unit;

import com.realmgate.infrastructure.security.SecurityConfig;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * M3 单元测试：rbacJwtAuthenticationConverter（permissions → authorities，无 SCOPE_ 前缀）。
 * 直接调 SecurityConfig.rbacJwtAuthenticationConverter()（@Bean 方法，不启 Spring），锁死坑#1 回归：
 * 默认 converter 会从 scope 取权限并加 SCOPE_ 前缀，导致业务 hasAuthority 全失 → 全接口 403。
 */
@DisplayName("M3 单元测试：rbacJwtAuthenticationConverter（permissions→authorities，无 SCOPE_ 前缀）")
class RbacJwtAuthenticationConverterTest {

    private final JwtAuthenticationConverter converter = new SecurityConfig().rbacJwtAuthenticationConverter();

    private Jwt jwtWith(Map<String, Object> claims) {
        return Jwt.withTokenValue("dummy").header("alg", "RS256").claims(c -> c.putAll(claims)).build();
    }

    @Test
    @DisplayName("permissions claim 直接映射为 authority，无 SCOPE_ / ROLE_ 前缀（坑#1 回归锁）")
    void permissionsMappedWithoutPrefix() {
        Jwt jwt = jwtWith(Map.of("permissions", List.of("user:read", "project:delete")));
        JwtAuthenticationToken token = (JwtAuthenticationToken) converter.convert(jwt);
        List<String> names = token.getAuthorities().stream().map(GrantedAuthority::getAuthority).toList();

        assertEquals(2, names.size());
        assertTrue(names.contains("user:read"));
        assertTrue(names.contains("project:delete"));
        // 关键：绝不可出现 SCOPE_ 前缀（否则业务 hasAuthority 全失 → 403）
        assertFalse(names.stream().anyMatch(n -> n.startsWith("SCOPE_")),
                "出现 SCOPE_ 前缀说明用了默认 converter，permissions 被忽略");
    }

    @Test
    @DisplayName("无 permissions claim → 空 authorities（scope 不被当作业务权限）")
    void noPermissionsClaim_yieldsEmptyAuthorities() {
        Jwt jwt = jwtWith(Map.of("scope", List.of("openid", "profile")));
        JwtAuthenticationToken token = (JwtAuthenticationToken) converter.convert(jwt);
        assertTrue(token.getAuthorities().isEmpty(), "scope 不应被当作业务权限");
    }
}
