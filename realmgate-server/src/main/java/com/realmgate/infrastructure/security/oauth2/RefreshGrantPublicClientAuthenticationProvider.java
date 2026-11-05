package com.realmgate.infrastructure.security.oauth2;

import java.util.Map;

import org.springframework.security.authentication.AuthenticationProvider;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.oauth2.core.ClientAuthenticationMethod;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2ErrorCodes;
import org.springframework.security.oauth2.core.endpoint.OAuth2ParameterNames;
import org.springframework.security.oauth2.server.authorization.authentication.OAuth2ClientAuthenticationToken;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClient;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClientRepository;
import org.springframework.util.Assert;

/**
 * 站2 修复 refresh 400 invalid_grant 的"客户端认证"缺口（SAS 1.5.1 框架层缺陷）。
 *
 * <p>根因：SAS 1.5.1 的 {@code PublicClientAuthenticationProvider} 对所有 NONE 方法（公开客户端）
 * 的 token 端点请求，都会调用 {@code CodeVerifierAuthenticator.authenticateRequired}。而该校验器
 * 仅在 {@code grant_type=authorization_code} 时返回 true（按 code 反查授权、比对 code_verifier）；
 * 其余授予类型一律返回 false，于是 {@code authenticateRequired} 必然抛出
 * {@code invalid_grant(Client authentication failed: code_verifier)}。
 * 即：公开客户端（NONE + requireProofKey）在 SAS 1.5.1 下根本无法完成 refresh_token 授予
 * ——refresh 请求无 code_verifier，于是在进入 OAuth2RefreshTokenAuthenticationProvider 之前就被
 * 客户端认证阶段否决（400）。
 *
 * <p>本 Provider 通过 {@code OAuth2ClientAuthenticationConfigurer.authenticationProviders} 前插到
 * 默认 provider 列表最前（configure 的 init() 中 custom providers 经 addAll(0, ...) 前置），
 * 仅接管 {@code grant_type=refresh_token} 且客户端为 NONE 方法的请求：
 *   - 仅校验 client_id 属于该已注册的公开客户端，即视为客户端认证通过；
 *   - 不做 code_verifier 校验（refresh 本就无需 PKCE 的 code_verifier）。
 * 其余授予类型（如 authorization_code）返回 null，交由默认 PublicClientAuthenticationProvider 处理，
 * 完整保留授权码流程的 PKCE 保护。
 *
 * <p>注意：本类与 {@link RefreshGrantClientAuthenticationConverter} 配合——后者在 refresh 请求时
 * 把公开客户端构造成 NONE 方法的 OAuth2ClientAuthenticationToken（additionalParameters 含
 * grant_type=refresh_token），本类据此识别并认证；二者互不冲突。
 */
public final class RefreshGrantPublicClientAuthenticationProvider implements AuthenticationProvider {

	private static final String ERROR_URI = "https://datatracker.ietf.org/doc/html/rfc6749#section-3.2.1";

	private final RegisteredClientRepository registeredClientRepository;

	public RefreshGrantPublicClientAuthenticationProvider(RegisteredClientRepository registeredClientRepository) {
		Assert.notNull(registeredClientRepository, "registeredClientRepository cannot be null");
		this.registeredClientRepository = registeredClientRepository;
	}

	@Override
	public Authentication authenticate(Authentication authentication) throws AuthenticationException {
		OAuth2ClientAuthenticationToken clientAuthentication = (OAuth2ClientAuthenticationToken) authentication;

		// 只接管公开客户端（NONE 方法）的请求；机密客户端（带 secret）返回 null，交给默认 provider。
		if (!ClientAuthenticationMethod.NONE.equals(clientAuthentication.getClientAuthenticationMethod())) {
			return null;
		}

		// 仅接管 refresh_token 授予；其余（如 authorization_code）返回 null，交给默认
		// PublicClientAuthenticationProvider（保留 PKCE 的 code_verifier 校验）。
		Map<String, Object> additionalParameters = clientAuthentication.getAdditionalParameters();
		String grantType = additionalParameters == null
				? null
				: (String) additionalParameters.get(OAuth2ParameterNames.GRANT_TYPE);
		if (!OAuth2ParameterNames.REFRESH_TOKEN.equals(grantType)) {
			return null;
		}

		// 验证 client_id 属于该已注册的公开客户端（防任意 client_id 冒用）。
		String clientId = clientAuthentication.getPrincipal().toString();
		RegisteredClient registeredClient = this.registeredClientRepository.findByClientId(clientId);
		if (registeredClient == null) {
			throwInvalidClient(OAuth2ParameterNames.CLIENT_ID);
		}
		if (!registeredClient.getClientAuthenticationMethods()
				.contains(ClientAuthenticationMethod.NONE)) {
			throwInvalidClient("authentication_method");
		}

		// refresh 授予无需 code_verifier：公开客户端身份已由 client_id 唯一标识，认证通过。
		return new OAuth2ClientAuthenticationToken(registeredClient,
				ClientAuthenticationMethod.NONE, null);
	}

	@Override
	public boolean supports(Class<?> authentication) {
		return OAuth2ClientAuthenticationToken.class.isAssignableFrom(authentication);
	}

	private static void throwInvalidClient(String parameterName) {
		OAuth2Error error = new OAuth2Error(OAuth2ErrorCodes.INVALID_CLIENT,
				"Client authentication failed: " + parameterName, ERROR_URI);
		throw new OAuth2AuthenticationException(error);
	}
}
