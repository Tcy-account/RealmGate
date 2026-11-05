package com.realmgate.e2e;

import com.realmgate.test.support.RealmGateIntegrationTest;
import com.nimbusds.jose.jwk.JWK;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jose.jwk.source.JWKSource;
import com.nimbusds.jose.proc.SecurityContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.util.List;
import java.util.Set;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * M4 E2E（一）：令牌五态 RS 层断言（真实签名 token 走完整验签 + 黑名单链）。
 *
 * 这是 OAuth2PkceFlowTest 注释里「留给 M4 E2E」的那一块：AS 层已验证令牌生命周期五态，
 * 但「AT 作为 Bearer 访问 /api/** 受保护资源时，资源服务器链② 的验签/黑名单/权限裁决」一直没端到端断言过。
 *
 * 关键：必须用应用 JwtEncoder Bean（与 jwtDecoder 共享 RsaKeyManager 密钥家族）签真 token，
 * 以原始 Authorization: Bearer 头发出 —— 绝不能走基类 tenantToken/adminToken（那用 jwt() 会把整条
 * JwtDecoder 替换成测试 decoder，架空 RevocationCheckingJwtDecoder 黑名单，永远验不出 401）。见坑#14。
 *
 * 五态（对应计划 §51 Token 安全测试）：
 *   ① 合法（签名有效 + 未过期 + 未吊销 + 有权限）→ 200
 *   ② 过期（exp 在过去）→ 验签阶段 401
 *   ③ 错误签名（攻击者用自己的 RSA key 签）→ 验签阶段 401
 *   ④ 已吊销（jti 进 Redis 黑名单）→ RevocationCheckingJwtDecoder 401
 *   ⑤ 权限不足（token 只带 project:read，访问需 user:read 的管理端点）→ 方法级安全 403
 *
 * 注：jwtDecoder = OAuth2AuthorizationServerConfiguration.jwtDecoder(jwkSource)，仅做签名 + 时间戳校验，
 * 不强制 iss/aud，故自签 token（无 iss/aud）完全兼容。tenant 隔离是 service 层职责，见 E2EBusinessJourneyTest。
 */
@SpringBootTest
@AutoConfigureMockMvc
@DisplayName("M4 E2E 令牌五态 RS 层断言（真实签名 token 走完整验签+黑名单链）")
class E2ETokenStateTest extends RealmGateIntegrationTest {

    @Autowired
    MockMvc mockMvc;

    /** 与应用 jwtDecoder 共享同一 RsaKeyManager 密钥家族，签出的 token 能被链② 真实验签。*/
    @Autowired
    JwtEncoder jwtEncoder;

    @Autowired
    StringRedisTemplate redis;

    private static final String TENANT = "alpha";

    @BeforeEach
    void clearBlacklist() {
        // 清掉本租户全部 jti 黑名单键，避免跨测试串味（revoke 键前缀 tenant:alpha:token:revoked:*）
        Set<String> keys = redis.keys("tenant:" + TENANT + ":token:revoked:*");
        if (keys != null && !keys.isEmpty()) {
            redis.delete(keys);
        }
    }

    /** 用应用 JwtEncoder 签一张真实 JWT（RS256 / active key），作为可被链② 验签的 Bearer token。*/
    private String signedToken(List<String> perms, Instant exp) {
        Instant now = Instant.now();
        JwtClaimsSet claims = JwtClaimsSet.builder()
                .subject("admin")
                .claim("tenant_id", TENANT)
                .claim("permissions", perms)
                .claim("jti", "rg-e2e-" + System.nanoTime())
                .issuedAt(now)
                .expiresAt(exp)
                .build();
        return jwtEncoder.encode(JwtEncoderParameters.from(claims)).getTokenValue();
    }

    /** 攻击者用【自己的】RSA key 签 token（不在应用 RsaKeyManager 里）→ 链② 验签必失败。*/
    private String foreignSignedToken(List<String> perms) throws Exception {
        RSAKey foreign = new RSAKeyGenerator(2048).keyID("foreign-attacker").generate();
        JWKSource<SecurityContext> foreignSrc = (selector, ctx) -> selector.select(new JWKSet(foreign));
        NimbusJwtEncoder foreignEnc = new NimbusJwtEncoder(foreignSrc);
        Instant now = Instant.now();
        JwtClaimsSet claims = JwtClaimsSet.builder()
                .subject("attacker")
                .claim("tenant_id", TENANT)
                .claim("permissions", perms)
                .claim("jti", "rg-foreign-" + System.nanoTime())
                .issuedAt(now)
                .expiresAt(now.plusSeconds(3600))
                .build();
        return foreignEnc.encode(JwtEncoderParameters.from(claims)).getTokenValue();
    }

    @Test
    @DisplayName("① 合法 token（有效签名+未过期+有权限）访问 /api/projects → 200")
    void validToken_accessProtectedResource_200() throws Exception {
        mockMvc.perform(get("/api/projects")
                        .header("Authorization", "Bearer " + signedToken(List.of("project:read"), Instant.now().plusSeconds(3600))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0));
    }

    @Test
    @DisplayName("② 过期 token（exp 在过去）→ 资源服务器验签阶段 401")
    void expiredToken_rejected_401() throws Exception {
        // 过期 token：issuedAt 与 expiresAt 都须在过去，且 exp > issuedAt（Spring 约束）
        Instant past = Instant.now().minusSeconds(20);
        JwtClaimsSet claims = JwtClaimsSet.builder()
                .subject("admin").claim("tenant_id", TENANT)
                .claim("permissions", List.of("project:read"))
                .claim("jti", "rg-exp-" + System.nanoTime())
                .issuedAt(past)
                .expiresAt(past.plusSeconds(10)) // 比 issuedAt 晚 10s，但仍在当前时刻之前 → 已过期
                .build();
        String token = jwtEncoder.encode(JwtEncoderParameters.from(claims)).getTokenValue();
        mockMvc.perform(get("/api/projects").header("Authorization", "Bearer " + token))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("③ 错误签名 token（外部 RSA key 签发）→ 资源服务器验签阶段 401")
    void wrongSignatureToken_rejected_401() throws Exception {
        mockMvc.perform(get("/api/projects")
                        .header("Authorization", "Bearer " + foreignSignedToken(List.of("project:read"))))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("④ 已吊销 token（jti 进 Redis 黑名单）→ RevocationCheckingJwtDecoder 401")
    void revokedToken_rejected_401() throws Exception {
        String jti = "rg-rev-e2e-" + System.nanoTime();
        Instant now = Instant.now();
        JwtClaimsSet claims = JwtClaimsSet.builder()
                .subject("admin").claim("tenant_id", TENANT)
                .claim("permissions", List.of("project:read"))
                .claim("jti", jti)
                .issuedAt(now).expiresAt(now.plusSeconds(3600)).build();
        String token = jwtEncoder.encode(JwtEncoderParameters.from(claims)).getTokenValue();

        // 先注销自身（把 jti 写进黑名单）→ 200
        mockMvc.perform(post("/api/tokens/revoke").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0));

        // 再复用同一张（同 jti）→ 黑名单命中 → 401
        mockMvc.perform(get("/api/projects").header("Authorization", "Bearer " + token))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("⑤ 权限不足 token（仅 project:read）访问需 user:read 的管理端点 → 方法级安全 403")
    void missingPermissionToken_adminEndpoint_forbidden_403() throws Exception {
        mockMvc.perform(get("/api/admin/users")
                        .header("Authorization", "Bearer " + signedToken(List.of("project:read"), Instant.now().plusSeconds(3600))))
                .andExpect(status().isForbidden());
    }
}
