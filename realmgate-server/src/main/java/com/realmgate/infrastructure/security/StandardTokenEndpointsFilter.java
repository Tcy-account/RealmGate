package com.realmgate.infrastructure.security;

import java.io.IOException;
import java.net.URI;
import java.time.Instant;
import java.util.Set;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.springframework.lang.NonNull;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.web.filter.OncePerRequestFilter;

import com.realmgate.infrastructure.security.TokenFamilyService.RefreshBinding;

/**
 * 站③ Revocation / Logout 标准化：把原有自定义 /api/tokens/revoke 升级为两个 OAuth/OIDC 标准端点。
 *
 *  - POST /oauth2/revoke      —— RFC 7009 标准令牌撤销端点
 *      · form 参数：token（必填）、token_type_hint（可选 access_token|refresh_token|id_token）、client_id（可选）
 *      · 无论 token 是否有效/是否存在，统一返回 200（RFC 7009 §2.2，不泄露 token 是否存在）
 *      · refresh_token：不透明 UUID，经 TokenFamilyService.resolveRefresh 反查 family/tenant/jti，
 *        整族失效（revokeFamily）+ 把 RT 的 jti 拉黑
 *      · access_token / id_token：JWT，jwtDecoder 解码拿 jti/family_id/tenant_id，拉黑 jti + 整族失效
 *  - GET|POST /oauth2/logout  —— OIDC RP-Initiated Logout（end_session_endpoint）
 *      · 参数：id_token_hint（推荐）、post_logout_redirect_uri、state
 *      · 解析 id_token_hint 拿 family_id/tenant_id → 整族失效；校验回跳 URI 后在白名单内 → 302 回 RP
 *
 * 注册位置：AS 链①（认证 filter 之前）。匹配这两个路径时直接短路处理并 return，
 * 不走 chain.doFilter，因此不会被 AS 链默认的 anyRequest().authenticated() 拦截要求 Bearer。
 *
 * 解码用原始的 jwtDecoder bean（非 RevocationCheckingJwtDecoder 装饰器），避免"待撤销 AT 已进黑名单"
 * 导致解码抛错；RFC 7009 对不可解析 token 本就要求静默返回 200。
 */
public class StandardTokenEndpointsFilter extends OncePerRequestFilter {

    private final TokenFamilyService familyService;
    private final TokenRevocationService revocation;
    private final JwtDecoder jwtDecoder;

    /** 站③：logout 回跳白名单，防 open redirect。仅放行本地 SPA 域。 */
    private static final Set<String> ALLOWED_LOGOUT_HOSTS = Set.of(
            "localhost:5173", "127.0.0.1:5173");

    public StandardTokenEndpointsFilter(TokenFamilyService familyService,
            TokenRevocationService revocation, JwtDecoder jwtDecoder) {
        this.familyService = familyService;
        this.revocation = revocation;
        this.jwtDecoder = jwtDecoder;
    }

    @Override
    protected void doFilterInternal(@NonNull HttpServletRequest request,
            @NonNull HttpServletResponse response, @NonNull FilterChain chain)
            throws ServletException, IOException {
        String uri = request.getRequestURI();
        String method = request.getMethod();

        if ("/oauth2/revoke".equals(uri) && "POST".equals(method)) {
            handleRevoke(request, response);
            return;
        }
        if ("/oauth2/logout".equals(uri) && ("GET".equals(method) || "POST".equals(method))) {
            handleLogout(request, response);
            return;
        }
        chain.doFilter(request, response);
    }

    // ───────── RFC 7009 revocation ─────────
    private void handleRevoke(HttpServletRequest request, HttpServletResponse response) throws IOException {
        String token = request.getParameter("token");
        String hint = request.getParameter("token_type_hint");
        String clientId = request.getParameter("client_id");

        if (token == null || token.isBlank()) {
            sendOk(response); // RFC 7009：缺 token 也返回 200
            return;
        }

        // ① 先按 refresh_token 尝试（不透明 RT 无 claim，走 Redis 反查绑定）
        if (!"access_token".equals(hint) && !"id_token".equals(hint)) {
            RefreshBinding binding = familyService.resolveRefresh(token);
            if (binding != null) {
                // 公开客户端不强制 client 一致性（RT 不透明、无法被第三方伪造，持 RT 即有权撤销）。
                revocation.revoke(binding.tenantId, binding.jti,
                        Instant.now().plusSeconds(60L * 60 * 24 * 7));
                familyService.revokeFamily(binding.tenantId, binding.familyId);
                sendOk(response);
                return;
            }
        }

        // ② 否则按 access_token / id_token（JWT）解码
        if (!"refresh_token".equals(hint)) {
            try {
                Jwt jwt = jwtDecoder.decode(token);
                String azp = jwt.getClaimAsString("azp");
                if (clientId == null || clientId.equals(azp)) {
                    String tenant = jwt.getClaimAsString("tenant_id");
                    String jti = jwt.getId();
                    Instant exp = jwt.getExpiresAt();
                    if (tenant != null && jti != null) {
                        revocation.revoke(tenant, jti,
                                exp != null ? exp : Instant.now().plusSeconds(60));
                        String familyId = jwt.getClaimAsString("family_id");
                        if (familyId != null) {
                            familyService.revokeFamily(tenant, familyId);
                        }
                    }
                }
            } catch (JwtException | IllegalArgumentException e) {
                // token 不可解析：RFC 7009 静默返回 200，不泄露是否存在
            }
        }
        sendOk(response);
    }

    // ───────── OIDC RP-Initiated Logout ─────────
    private void handleLogout(HttpServletRequest request, HttpServletResponse response) throws IOException {
        String idTokenHint = request.getParameter("id_token_hint");
        String postLogout = request.getParameter("post_logout_redirect_uri");
        String state = request.getParameter("state");

        if (idTokenHint == null || idTokenHint.isBlank()) {
            response.setStatus(HttpServletResponse.SC_OK);
            response.setContentType("text/plain;charset=utf-8");
            response.getWriter().write("logout");
            return;
        }

        String tenant;
        String familyId;
        try {
            Jwt jwt = jwtDecoder.decode(idTokenHint);
            tenant = jwt.getClaimAsString("tenant_id");
            familyId = jwt.getClaimAsString("family_id");
        } catch (JwtException | IllegalArgumentException e) {
            response.setStatus(HttpServletResponse.SC_BAD_REQUEST);
            response.setContentType("text/plain;charset=utf-8");
            response.getWriter().write("invalid id_token_hint");
            return;
        }

        if (tenant != null && familyId != null) {
            familyService.revokeFamily(tenant, familyId); // 整族失效（同 session 所有 token 作废）
        }

        if (postLogout != null && !postLogout.isBlank() && isAllowedLogoutHost(postLogout)) {
            String target = postLogout
                    + (postLogout.contains("?") ? "&" : "?")
                    + "state=" + (state == null ? "" : state);
            response.setStatus(HttpServletResponse.SC_FOUND); // 302
            response.setHeader("Location", target);
            return;
        }
        response.setStatus(HttpServletResponse.SC_OK);
        response.setContentType("text/plain;charset=utf-8");
        response.getWriter().write("logout successful");
    }

    private boolean isAllowedLogoutHost(String uri) {
        try {
            URI u = URI.create(uri);
            String hostPort = u.getHost() + (u.getPort() > 0 ? ":" + u.getPort() : "");
            return ALLOWED_LOGOUT_HOSTS.contains(hostPort);
        } catch (RuntimeException e) {
            return false;
        }
    }

    private void sendOk(HttpServletResponse response) throws IOException {
        response.setStatus(HttpServletResponse.SC_OK);
        response.setContentType("application/json;charset=utf-8");
        response.getWriter().write("{}");
    }
}
