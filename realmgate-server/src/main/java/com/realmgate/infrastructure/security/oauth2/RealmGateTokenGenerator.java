package com.realmgate.infrastructure.security.oauth2;

import com.realmgate.infrastructure.security.TenantWebAuthenticationDetails;
import com.realmgate.infrastructure.security.TokenFamilyService;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.core.OAuth2RefreshToken;
import org.springframework.security.oauth2.core.OAuth2Token;
import org.springframework.security.oauth2.server.authorization.OAuth2Authorization;
import org.springframework.security.oauth2.server.authorization.OAuth2TokenType;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.server.authorization.token.JwtEncodingContext;
import org.springframework.security.oauth2.server.authorization.token.JwtGenerator;
import org.springframework.security.oauth2.server.authorization.token.OAuth2TokenContext;
import org.springframework.security.oauth2.server.authorization.token.OAuth2TokenCustomizer;
import org.springframework.security.oauth2.server.authorization.token.OAuth2TokenGenerator;
import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.context.request.RequestContextHolder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 站2 Refresh Token Rotation + Token Family：覆盖 SAS 默认 OAuth2TokenGenerator。
 *
 * 行为：
 *  - ACCESS_TOKEN / OIDC ID_TOKEN：委派内部 JwtGenerator（复用 TenantJwtTokenCustomizer 写 tenant/permissions/jti/family_id）。
 *  - REFRESH_TOKEN：用 SAS 原生不透明 OAuth2RefreshToken（UUID tokenValue）。
 *
 * 为什么不能用 JWT 形式的 RT：OAuth2Authorization 经 SAS 的 Jackson 序列化/反查
 * （JdbcOAuth2AuthorizationService），自定义 JwtRefreshToken 子类无默认构造器、内嵌 Jwt 不可序列化，
 * 续刷时 findByToken 取不到 authorization → refresh 整条失败（已被此坑踩过）。
 * 故 RT 用不透明形式，family/jti/tenant 外置 Redis（TokenFamilyService.bindRefreshToken），
 * 续刷/replay 按 RT 值反查。
 *
 * 每次 generate 入口先统一确定 familyId 并缓存进 RequestContextHolder（请求级），
 * 解决"AT 与 RT 两次独立 generate、顺序不定"导致 family_id 不一致的问题。
 */
public class RealmGateTokenGenerator implements OAuth2TokenGenerator<OAuth2Token> {

    private static final Logger log = LoggerFactory.getLogger(RealmGateTokenGenerator.class);

    private final JwtGenerator jwtGenerator; // 处理 AT / ID
    private final TokenFamilyService familyService;
    private final Duration refreshTtl = Duration.ofDays(7);

    public RealmGateTokenGenerator(JwtEncoder jwtEncoder,
            OAuth2TokenCustomizer<JwtEncodingContext> customizer,
            TokenFamilyService familyService) {
        this.jwtGenerator = new JwtGenerator(jwtEncoder);
        this.jwtGenerator.setJwtCustomizer(customizer);
        this.familyService = familyService;
    }

    @Override
    public OAuth2Token generate(OAuth2TokenContext context) {
        try {
            // 入口统一确定 familyId（续刷从原 RT 反查，首次新生成），缓存供 AT customizer 与 RT 生成复用
            resolveFamilyId(context);
            if (OAuth2TokenType.REFRESH_TOKEN.equals(context.getTokenType())) {
                return generateRefresh(context);
            }
            return jwtGenerator.generate(context);
        } catch (RuntimeException ex) {
            // 令牌端点异常被 OAuth2TokenEndpointFilter 兜底成 server_error 且吞栈，
            // 这里先打全栈再原样抛出，便于定位真实根因。
            log.error("TOK_GEN_FAIL tokenType={} principalClass={}",
                    context.getTokenType(),
                    context.getPrincipal() != null ? context.getPrincipal().getClass().getName() : "null", ex);
            throw ex;
        }
    }

    private OAuth2Token generateRefresh(OAuth2TokenContext context) {
        try {
            return doGenerateRefresh(context);
        } catch (RuntimeException ex) {
            log.error("GEN_REFRESH_FAIL", ex);
            throw ex;
        }
    }

    private OAuth2Token doGenerateRefresh(OAuth2TokenContext context) {
        String familyId = resolveFamilyId(context);
        String tenantId = resolveTenant(context);
        String userId = resolveUserId(context);
        String clientId = context.getRegisteredClient().getClientId();
        String jti = UUID.randomUUID().toString();
        Instant now = Instant.now();
        // 不透明 refresh token：SAS 原生支持的 OAuth2RefreshToken（UUID），可正常序列化/反查。
        String rtValue = UUID.randomUUID().toString();
        OAuth2RefreshToken rt = new OAuth2RefreshToken(rtValue, now, now.plus(refreshTtl));

        if (isFirstGrant(context)) {
            familyService.createFamily(tenantId, familyId, userId, clientId, jti);
        } else {
            familyService.rotate(tenantId, familyId, jti);
        }
        // 把 family/jti/tenant 绑到 RT 值上，供续刷与 replay 反查（替代原本塞进 RT claim 的做法）。
        familyService.bindRefreshToken(tenantId, rtValue, familyId, jti);
        return rt;
    }

    /**
     * 是否"首次授权码换取"：authorization 尚未建立（null），或虽已建立但尚未签发过
     * refresh_token（getRefreshToken()==null，即正在生成本次 RT）。这两种情况都走 createFamily。
     * 续刷时 authorization 已含旧的不透明 RT，故走 rotate。
     */
    private boolean isFirstGrant(OAuth2TokenContext context) {
        OAuth2Authorization auth = context.getAuthorization();
        return auth == null || auth.getRefreshToken() == null;
    }

    /**
     * 续刷时从"已存进 authorization 的原 RT"反查 familyId：原 RT 是不透明 OAuth2RefreshToken，
     * 取其 tokenValue 走 Redis 绑定（TokenFamilyService.resolveRefresh）拿 familyId。
     */
    private String readStoredFamilyId(OAuth2TokenContext context) {
        OAuth2Authorization auth = context.getAuthorization();
        if (auth == null) {
            return null;
        }
        var rtToken = auth.getRefreshToken();
        if (rtToken == null) {
            return null;
        }
        TokenFamilyService.RefreshBinding b = familyService.resolveRefresh(rtToken.getToken().getTokenValue());
        return b != null ? b.familyId : null;
    }

    private String readStoredTenant(OAuth2TokenContext context) {
        OAuth2Authorization auth = context.getAuthorization();
        if (auth == null) {
            return null;
        }
        var rtToken = auth.getRefreshToken();
        if (rtToken == null) {
            return null;
        }
        TokenFamilyService.RefreshBinding b = familyService.resolveRefresh(rtToken.getToken().getTokenValue());
        return b != null ? b.tenantId : null;
    }

    private String resolveFamilyId(OAuth2TokenContext context) {
        String fid = readStoredFamilyId(context);
        if (fid != null) {
            cacheFamily(fid);
            return fid;
        }
        // 首次或回退：缓存已有则复用（同请求 AT 已生成过、family 已定），否则新生成
        String cached = cachedFamily();
        if (cached != null) {
            return cached;
        }
        String n = UUID.randomUUID().toString();
        cacheFamily(n);
        return n;
    }

    private String resolveTenant(OAuth2TokenContext context) {
        // 续刷：从原 RT 的 Redis 绑定读 tenant（principal 是 refresh token auth，无登录 details）。
        String t = readStoredTenant(context);
        if (t != null) {
            return t;
        }
        // 首次：principal 是登录用户，tenant 在 details
        Authentication p = context.getPrincipal();
        if (p instanceof UsernamePasswordAuthenticationToken upat
                && upat.getDetails() instanceof TenantWebAuthenticationDetails d) {
            return d.getTenant();
        }
        return null;
    }

    private String resolveUserId(OAuth2TokenContext context) {
        return context.getPrincipal().getName();
    }

    private static String cachedFamily() {
        RequestAttributes ra = RequestContextHolder.getRequestAttributes();
        return ra == null ? null : (String) ra.getAttribute("rg.familyId", RequestAttributes.SCOPE_REQUEST);
    }

    private static void cacheFamily(String fid) {
        RequestContextHolder.currentRequestAttributes()
                .setAttribute("rg.familyId", fid, RequestAttributes.SCOPE_REQUEST);
    }
}
