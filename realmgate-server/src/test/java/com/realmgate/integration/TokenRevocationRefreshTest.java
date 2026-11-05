package com.realmgate.integration;

import com.realmgate.test.support.RealmGateIntegrationTest;
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
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.util.List;
import java.util.Set;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * M2 安全核心集成测试（三）：令牌自吊销 + RS 黑名单复用失效（真实签名 token 姿态）。
 *
 * 覆盖 PKCE 五态（OAuth2PkceFlowTest）之外的独立吊销面 —— 站1 Token Revocation 的「注销即拉黑」：
 *   - 用户注销调 POST /api/tokens/revoke，把当前 token 的 jti 写进 Redis 黑名单
 *     （key=tenant:{t}:token:revoked:{jti}，TTL=token 剩余有效期）；
 *   - 资源服务器链路② 的 RevocationCheckingJwtDecoder 在验签后查黑名单，命中 → 401。
 *
 * 关键坑（已踩实并固化为此文件写法）：
 *   spring-security-test 的 jwt() 后处理器会「替换」链② 的整个 JwtDecoder 为测试 decoder，
 *   从而**绕过**应用自定义的 RevocationCheckingJwtDecoder 黑名单校验 —— 用 jwt() 注入的 token
 *   永远不可能触发 401 吊销断言（这正是旧版 selfRevoke_thenReuse_401 始终 200 的根因）。
 *
 * 正解：用应用上下文里的 JwtEncoder Bean（与 jwtDecoder 共享同一个 RsaKeyManager 密钥家族）
 * 签一张「真签名」的 JWT，作为原始 Authorization: Bearer <token> 头发出，让它真正走完
 * 验签 → 黑名单检查 整链。这样 revoked 之后复用才会被 RevocationCheckingJwtDecoder 拦成 401。
 *
 * jwtDecoder 由 OAuth2AuthorizationServerConfiguration.jwtDecoder(jwkSource) 构建，仅做签名 +
 * 时间戳校验（不强制 iss/aud），与本测试自签 token 完全兼容。token 必须带 jti + exp（revoke 落库依赖），
 * 不带 family_id（revoke 时跳过整族失效，避免污染其他测试的 family 键）。
 */
@SpringBootTest
@AutoConfigureMockMvc
@DisplayName("M2 令牌自吊销 + RS 黑名单复用失效（真实签名 token 姿态）")
class TokenRevocationRefreshTest extends RealmGateIntegrationTest {

    @Autowired
    MockMvc mockMvc;

    @Autowired
    StringRedisTemplate redis;

    /** 与应用 jwtDecoder 共享同一 RsaKeyManager 密钥家族，签出的 token 能被链② 真实验签。*/
    @Autowired
    JwtEncoder jwtEncoder;

    private static final String TENANT = "alpha";

    @BeforeEach
    void clearBlacklist() {
        // 清掉本租户全部 jti 黑名单键，避免跨测试串味（revoke 键前缀 tenant:alpha:token:revoked:*）
        Set<String> keys = redis.keys("tenant:" + TENANT + ":token:revoked:*");
        if (keys != null && !keys.isEmpty()) {
            redis.delete(keys);
        }
    }

    /**
     * 用应用 JwtEncoder 签一张真实 JWT（RS256 / active key），作为可被链② 验签的 Bearer token。
     * 必须显式 jti + exp（否则 TokenRevocationService.revoke 直接跳过、黑名单落空）；
     * 不写 family_id（revoke 时跳过整族失效，不污染 family_revoked 集合）。
     */
    private String signedToken(String jti) {
        Instant now = Instant.now();
        JwtClaimsSet claims = JwtClaimsSet.builder()
                .subject("admin")
                .claim("tenant_id", TENANT)
                .claim("permissions", List.of("user:read", "project:read"))
                .claim("jti", jti)
                .issuedAt(now)
                .expiresAt(now.plusSeconds(3600))
                .build();
        return jwtEncoder.encode(JwtEncoderParameters.from(claims)).getTokenValue();
    }

    @Test
    @DisplayName("自吊销前 AT 可用；调 /api/tokens/revoke 后同 AT 复用 → 401（RS 黑名单命中）")
    void selfRevoke_thenReuse_401() throws Exception {
        String jti = "rg-rev-" + System.nanoTime();
        String token = signedToken(jti);
        String bearer = "Bearer " + token;

        // ① 吊销前：有效 Bearer 通过真实 RevocationCheckingJwtDecoder 验签 → 200（注销自身）
        mockMvc.perform(post("/api/tokens/revoke").header("Authorization", bearer))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0));

        // ② 吊销后：同 AT（同 jti）再访问 → RevocationCheckingJwtDecoder 命中黑名单 → 401
        mockMvc.perform(post("/api/tokens/revoke").header("Authorization", bearer))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("对照：吊销 token A 不影响另一张未吊销的 token B（证明 401 是逐 jti 黑名单，非全局失效）")
    void revokeA_doesNotAffectB() throws Exception {
        String jtiA = "rg-ctrl-a-" + System.nanoTime();
        // 先吊销 A
        mockMvc.perform(post("/api/tokens/revoke").header("Authorization", "Bearer " + signedToken(jtiA)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0));

        // 未吊销的 B 仍可正常注销自身 → 200（逐 jti 命中，互不影响）
        String jtiB = "rg-ctrl-b-" + System.nanoTime();
        mockMvc.perform(post("/api/tokens/revoke").header("Authorization", "Bearer " + signedToken(jtiB)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0));
    }
}
