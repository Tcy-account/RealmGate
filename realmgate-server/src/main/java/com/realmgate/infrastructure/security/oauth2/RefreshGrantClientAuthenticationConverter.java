package com.realmgate.infrastructure.security.oauth2;

import jakarta.servlet.http.HttpServletRequest;

import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.core.ClientAuthenticationMethod;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.server.authorization.authentication.OAuth2ClientAuthenticationToken;
import org.springframework.security.web.authentication.AuthenticationConverter;
import org.springframework.util.StringUtils;

import java.util.HashMap;
import java.util.Map;

/**
 * 站2 修复 refresh 302 根因。
 *
 * <p>SAS 的 {@code PublicClientAuthenticationConverter} 仅当请求满足
 * {@code matchesPkceTokenRequest}（grant_type=authorization_code 且带 code_verifier）时，
 * 才从请求参数提取 client_id 完成公开客户端（ClientAuthenticationMethod.NONE）认证。
 * 公开客户端的 refresh_token 授予不带 code_verifier，故该 converter 返回 null →
 * OAuth2ClientAuthenticationFilter 不做客户端认证 → 请求保持匿名 → 被 /oauth2/token 的
 * AuthorizationFilter 拒绝 → ExceptionTranslationFilter 触发 LoginUrlAuthenticationEntryPoint
 * 重定向到 /login.html（即"授权码能换 token、refresh 却 302"的唯一根因）。
 *
 * <p>本 converter 补上这一缺口：对 grant_type=refresh_token 且带 client_id 的请求，构造
 * OAuth2ClientAuthenticationToken(ClientAuthenticationMethod.NONE)，使公开客户端在 refresh 时
 * 也能完成客户端认证，从而到达 OAuth2TokenEndpointFilter → OAuth2RefreshTokenAuthenticationProvider
 * 正常换发新 RT（由 RealmGateTokenGenerator 做 rotation + Token Family 维护）。
 *
 * <p>该 converter 经由 OAuth2ClientAuthenticationConfigurer.authenticationConverters(Consumer)
 * 追加到默认 4 个转换器之后（默认转换器含 PublicClientAuthenticationConverter，专门处理授权码 PKCE 流程），
 * 互不冲突：授权码流程仍由 PublicClientAuthenticationConverter 处理，refresh 由本 converter 处理。
 */
public class RefreshGrantClientAuthenticationConverter implements AuthenticationConverter {

    private static final String GRANT_TYPE = "grant_type";
    private static final String REFRESH_TOKEN = "refresh_token";
    private static final String CLIENT_ID = "client_id";

    @Override
    public Authentication convert(HttpServletRequest request) {
        if (!"POST".equalsIgnoreCase(request.getMethod())) {
            return null;
        }
        // 只接管 refresh_token 授予；授权码等 PKCE 流程仍交给 PublicClientAuthenticationConverter。
        String grantType = request.getParameter(GRANT_TYPE);
        if (!REFRESH_TOKEN.equals(grantType)) {
            return null;
        }
        String clientId = request.getParameter(CLIENT_ID);
        if (!StringUtils.hasText(clientId)) {
            return null;
        }
        // client_id 必须唯一，避免参数注入（与 PublicClientAuthenticationConverter 行为对齐）。
        String[] clientIds = request.getParameterValues(CLIENT_ID);
        if (clientIds != null && clientIds.length != 1) {
            throw new OAuth2AuthenticationException("invalid_request");
        }
        // 收集其余参数作为 additionalParameters（refresh converter 自行从请求读 refresh_token，
        // 这里保持 token 结构完整即可，与 SAS 默认行为对齐）。
        Map<String, Object> additionalParameters = new HashMap<>();
        request.getParameterMap().forEach((key, values) -> {
            if (!CLIENT_ID.equals(key)) {
                additionalParameters.put(key, values.length == 1 ? values[0] : values);
            }
        });
        return new OAuth2ClientAuthenticationToken(
                clientId, ClientAuthenticationMethod.NONE, null, additionalParameters);
    }
}
