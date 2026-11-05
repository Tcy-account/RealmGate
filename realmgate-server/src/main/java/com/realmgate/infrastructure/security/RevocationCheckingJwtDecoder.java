package com.realmgate.infrastructure.security;

import java.time.Instant;

import com.realmgate.infrastructure.observability.SecurityMetrics;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtException;

/**
 * 站1 Token Revocation：装饰器，包住 Spring 自动配置的 jwtDecoder（RS256 / JWKS 验签）。
 * 验签通过后再按 jti 查 Redis 黑名单，命中则拒绝。
 *
 * 站2 扩展：除 jti 黑名单外，额外查 token 的 family_id 是否已整族撤销
 * （logout 时 TokenRevocationController 调 TokenFamilyService.revokeFamily 写入），
 * 命中则拒绝 —— 实现"注销一次，同族所有 access token 立即失效"。
 *
 * 本类不注册为 @Bean：上下文里已有 AuthorizationServerConfig 的 jwtDecoder 这一个 JwtDecoder Bean，
 * 链①（AS 端点）依赖它；若再注册一个 JwtDecoder Bean 会导致链① 自动装配出现多 Bean 歧义。
 * 由 SecurityConfig 链②用 new 包一层，只影响业务 API 链，链① 不变。
 */
public class RevocationCheckingJwtDecoder implements JwtDecoder {

    private final JwtDecoder delegate;
    private final TokenRevocationService revocation;
    private final TokenFamilyService familyService;
    private final SecurityMetrics metrics;

    /** 站 M7：兼容 M3 单测（无 Spring 上下文，metrics=null，不计数）。 */
    public RevocationCheckingJwtDecoder(JwtDecoder delegate, TokenRevocationService revocation,
            TokenFamilyService familyService) {
        this(delegate, revocation, familyService, null);
    }

    public RevocationCheckingJwtDecoder(JwtDecoder delegate, TokenRevocationService revocation,
            TokenFamilyService familyService, SecurityMetrics metrics) {
        this.delegate = delegate;
        this.revocation = revocation;
        this.familyService = familyService;
        this.metrics = metrics;
    }

    @Override
    public Jwt decode(String token) throws JwtException {
        // 1) 先验签：签名由底层 decoder 负责，失败直接抛 → 401
        Jwt jwt = delegate.decode(token);
        // 1.5) 时间戳校验：SAS 默认 jwtDecoder 不强制 exp/nbf（此前过期 token 被 RS 接受，真实安全缺口）。
        //      这里显式校验 exp（过期）与 nbf（未生效），不依赖 JwtTimestampValidator 的默认语义，
        //      过期/未生效 token → 401。与黑名单/家族检查同处，保证「验签 → 时间窗 → 吊销」三层防御连续生效。
        Instant now = Instant.now();
        Instant exp = jwt.getExpiresAt();
        if (exp != null && exp.isBefore(now)) {
            if (metrics != null) metrics.tokenRejected("expired");
            throw new OAuth2AuthenticationException(new OAuth2Error("invalid_token", "token expired", null));
        }
        Instant nbf = jwt.getNotBefore();
        if (nbf != null && nbf.isAfter(now)) {
            if (metrics != null) metrics.tokenRejected("not_yet_valid");
            throw new OAuth2AuthenticationException(new OAuth2Error("invalid_token", "token not yet valid", null));
        }
        // 2) 验签通过后按 jti 查黑名单
        String jti = jwt.getId(); // 对应 ① TenantJwtTokenCustomizer 写入的 jti claim
        String tenant = jwt.getClaimAsString("tenant_id");
        if (revocation.isRevoked(tenant, jti)) {
            if (metrics != null) metrics.tokenRejected("revoked");
            throw new OAuth2AuthenticationException(
                    new OAuth2Error("invalid_token", "token has been revoked", null));
        }
        // 3) 站2：整族失效（logout 后同 family 的所有 access token 立即 401）
        String familyId = jwt.getClaimAsString("family_id");
        if (familyService.isFamilyRevoked(tenant, familyId)) {
            if (metrics != null) metrics.tokenRejected("family_revoked");
            throw new OAuth2AuthenticationException(
                    new OAuth2Error("invalid_token", "token family has been revoked", null));
        }
        return jwt;
    }
}
