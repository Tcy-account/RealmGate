package com.realmgate.infrastructure.security;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.oauth2.core.OAuth2AccessToken;
import org.springframework.security.oauth2.core.OAuth2Token;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.authorization.token.JwtEncodingContext;
import org.springframework.security.oauth2.server.authorization.token.OAuth2TokenCustomizer;
import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.context.request.RequestContextHolder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class TenantJwtTokenCustomizer implements OAuth2TokenCustomizer<JwtEncodingContext> {
    private static final Logger log = LoggerFactory.getLogger(TenantJwtTokenCustomizer.class);

    @Override
    public void customize(JwtEncodingContext context) {
        try {
        // refresh token 由 RealmGateTokenGenerator 自写 claims，不走这里
        if (!isAccessTokenOrIdToken(context)) {
            return;
        }

        String tenant = null;
        // 必须用可变 ArrayList（非 List.of / Stream.toList 的 ImmutableCollections$ListN）：
        // 该 permissions 会随 JWT claims 被 SAS 序列化进 oauth2_authorization.access_token_metadata，
        // SAS 的 AllowlistTypeIdResolver 仅放行 java.util.ArrayList / Collections$Unmodifiable* 等，
        // 不放行 java.util.ImmutableCollections$*（会抛 IllegalArgumentException 导致 refresh 失败）。
        List<String> permissions = new ArrayList<>();

        Authentication principal = context.getPrincipal();
        if (principal instanceof UsernamePasswordAuthenticationToken upat
                && upat.getDetails() instanceof TenantWebAuthenticationDetails details) {
            // ① 授权码首次换取：tenant 在登录 details，permissions 在 authorities
            tenant = details.getTenant();
            permissions = new ArrayList<>(upat.getAuthorities().stream()
                    .map(GrantedAuthority::getAuthority)
                    .toList());
        } else if (context.getAuthorization() != null && context.getAuthorization().getAccessToken() != null) {
            // ② 续刷：从已存进 authorization 的原 AT 复制 tenant/permissions，保证权限不丢
            //    （续刷时 principal 是 OAuth2RefreshTokenAuthenticationToken，无登录 details）
            //    注意：getAccessToken() 返回 Token<OAuth2AccessToken>，getToken() 静态类型是 OAuth2AccessToken，
            //    需先宽转为 OAuth2Token 接口，Jwt 才是其可判定子类型。
            OAuth2Token at = context.getAuthorization().getAccessToken().getToken();
            if (at instanceof Jwt atJwt) {
                tenant = atJwt.getClaimAsString("tenant_id");
                // 读回也包一层 ArrayList，避免 getClaimAsStringList 返回不可变列表再次触发白名单拒绝。
                permissions = new ArrayList<>(atJwt.getClaimAsStringList("permissions"));
            }
        }

        if (tenant == null || tenant.isBlank()) {
            return;
        }

        // ① 把 RBAC 权限 key 写进 JWT 的 permissions claim（access/id token 都带）。
        //    upat.getAuthorities() 来自 UserDetailsServiceImpl.resolveAuthorities()，已是原始权限键
        //    （无 ROLE_ 前缀、无 SCOPE_ 前缀）。资源服务器用自定义 converter 反解。
        context.getClaims()
                .claim("tenant_id", tenant)
                .claim("preferred_username", principal.getName())
                .claim("name", principal.getName())
                .claim("permissions", permissions)
                // ① 站1 Token Revocation：每个 access/id token 一个唯一 jti。
                .claim("jti", UUID.randomUUID().toString());

        // 站2 Token Family：family_id 由 RealmGateTokenGenerator 在 generate 入口缓存进请求属性，
        // 这里读出来写进 AT/ID claim，供 logout 时按族批量失效（见 TokenRevocationController / decoder）。
        String familyId = cachedFamily();
        if (familyId != null) {
            context.getClaims().claim("family_id", familyId);
        }
        // 站③：id_token 加 sid（OIDC session id）。同一登录 session = 同一 token family，
        // 用 family_id 充当 sid，使标准 RP-Initiated Logout / front-channel logout 可关联 session。
        if ("id_token".equals(context.getTokenType().getValue()) && familyId != null) {
            context.getClaims().claim("sid", familyId);
        }
        } catch (RuntimeException ex) {
            log.error("CUST_FAIL tokenType={}", context.getTokenType(), ex);
            throw ex;
        }
    }

    private boolean isAccessTokenOrIdToken(JwtEncodingContext context) {
        return !"refresh_token".equals(context.getTokenType().getValue());
    }

    private static String cachedFamily() {
        RequestAttributes ra = RequestContextHolder.getRequestAttributes();
        return ra == null ? null : (String) ra.getAttribute("rg.familyId", RequestAttributes.SCOPE_REQUEST);
    }
}
