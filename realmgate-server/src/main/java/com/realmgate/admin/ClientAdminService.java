package com.realmgate.admin;

import com.realmgate.common.api.ApiResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.core.ClientAuthenticationMethod;
import org.springframework.security.oauth2.server.authorization.client.JdbcRegisteredClientRepository;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClient;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClientRepository;
import org.springframework.security.oauth2.server.authorization.settings.ClientSettings;
import org.springframework.security.oauth2.server.authorization.settings.TokenSettings;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * OAuth 客户端管理（平台级，跨租户）。RegisteredClient 不可变，所有写操作都按请求构建全新实例。
 * <p>
 * 注：SAS 1.5.1 的 RegisteredClientRepository 接口仅含 save/findById/findByClientId，无 findAll/deleteById。
 * 因此列表用 JdbcTemplate + 官方 RegisteredClientRowMapper 直查 oauth2_registered_client；删除用 JdbcTemplate 直删。
 * <p>
 * 关键约定：
 *  - 公开客户端（NONE 认证）无 client_secret；机密客户端（client_secret_basic/post 等）的明文密钥
 *    必须经 PasswordEncoder 编码后落库，绝不回传。
 *  - 响应统一经 ClientView 遮蔽 secret（只暴露 hasSecret 布尔）。
 *  - 默认 token 设置与 realmgate-web 对齐：AT 30m、RT 7d、reuseRefreshTokens(false)（配合站2 家族轮换）。
 */
@Service
@RequiredArgsConstructor
public class ClientAdminService {

    private final RegisteredClientRepository clientRepository;
    private final JdbcTemplate jdbcTemplate;
    private final PasswordEncoder passwordEncoder;

    // 官方行映射器（无参构造自带 SecurityJackson2Modules，可解析 client_settings/token_settings 的 JSON）
    private static final JdbcRegisteredClientRepository.RegisteredClientRowMapper ROW_MAPPER =
            new JdbcRegisteredClientRepository.RegisteredClientRowMapper();

    public ApiResponse<List<ClientView>> list() {
        List<RegisteredClient> all = jdbcTemplate.query(
                "SELECT * FROM oauth2_registered_client", ROW_MAPPER);
        return ApiResponse.success(all.stream().map(this::toView).toList());
    }

    public ApiResponse<ClientView> getById(String id) {
        RegisteredClient rc = clientRepository.findById(id);
        if (rc == null) {
            return ApiResponse.error(404, "客户端不存在");
        }
        return ApiResponse.success(toView(rc));
    }

    @Transactional
    public ApiResponse<ClientView> create(ClientUpsertRequest req) {
        if (req.clientName() == null || req.clientName().isBlank()) {
            return ApiResponse.error(400, "clientName 必填");
        }
        String clientId = (req.clientId() == null || req.clientId().isBlank())
                ? "client-" + UUID.randomUUID().toString().substring(0, 8)
                : req.clientId();
        if (clientRepository.findByClientId(clientId) != null) {
            return ApiResponse.error(409, "client_id 已存在");
        }
        RegisteredClient rc = buildClient(null, clientId, req);
        clientRepository.save(rc);
        return ApiResponse.success(toView(rc));
    }

    @Transactional
    public ApiResponse<ClientView> update(String id, ClientUpsertRequest req) {
        RegisteredClient existing = clientRepository.findById(id);
        if (existing == null) {
            return ApiResponse.error(404, "客户端不存在");
        }
        // 更新保留原 clientId 与既有 clientSecret（除非本次提供新密钥）
        RegisteredClient rc = buildClient(existing, existing.getClientId(), req);
        clientRepository.save(rc);
        return ApiResponse.success(toView(rc));
    }

    @Transactional
    public ApiResponse<Void> delete(String id) {
        if (clientRepository.findById(id) == null) {
            return ApiResponse.error(404, "客户端不存在");
        }
        jdbcTemplate.update("DELETE FROM oauth2_registered_client WHERE id = ?", id);
        return ApiResponse.success(null);
    }

    // existing==null 表示新建（生成新 id）；否则沿用 existing 的 id 与 clientId，并视情况保留原 secret
    private RegisteredClient buildClient(RegisteredClient existing, String clientId, ClientUpsertRequest req) {
        String id = (existing == null) ? UUID.randomUUID().toString() : existing.getId();

        var b = RegisteredClient.withId(id).clientId(clientId);
        b.clientName(req.clientName());

        // 客户端认证方式：默认公开（none）
        List<ClientAuthenticationMethod> ams = (req.clientAuthenticationMethods() == null
                || req.clientAuthenticationMethods().isEmpty())
                ? List.of(ClientAuthenticationMethod.NONE)
                : req.clientAuthenticationMethods().stream().map(ClientAuthenticationMethod::new).toList();
        ams.forEach(b::clientAuthenticationMethod);

        // 授权类型：默认 authorization_code + refresh_token
        List<AuthorizationGrantType> gts = (req.authorizationGrantTypes() == null
                || req.authorizationGrantTypes().isEmpty())
                ? List.of(new AuthorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE.getValue()),
                        new AuthorizationGrantType("refresh_token"))
                : req.authorizationGrantTypes().stream().map(AuthorizationGrantType::new).toList();
        gts.forEach(b::authorizationGrantType);

        // 更新时若请求省略这些集合（字段为 null），则保留原值，避免部分更新导致必填项变空而 500
        if (req.redirectUris() != null) {
            req.redirectUris().forEach(b::redirectUri);
        } else if (existing != null && existing.getRedirectUris() != null) {
            existing.getRedirectUris().forEach(b::redirectUri);
        }
        if (req.postLogoutRedirectUris() != null) {
            req.postLogoutRedirectUris().forEach(b::postLogoutRedirectUri);
        } else if (existing != null && existing.getPostLogoutRedirectUris() != null) {
            existing.getPostLogoutRedirectUris().forEach(b::postLogoutRedirectUri);
        }
        if (req.scopes() != null) {
            req.scopes().forEach(b::scope);
        } else if (existing != null && existing.getScopes() != null) {
            existing.getScopes().forEach(b::scope);
        }

        // 密钥：仅当含机密类认证方式且本次提供明文时才编码写入；否则沿用既有（若有）
        boolean needsSecret = ams.stream()
                .anyMatch(m -> !ClientAuthenticationMethod.NONE.getValue().equals(m.getValue()));
        String secret = null;
        if (needsSecret && req.clientSecret() != null && !req.clientSecret().isBlank()) {
            secret = passwordEncoder.encode(req.clientSecret());
        } else if (existing != null && existing.getClientSecret() != null) {
            secret = existing.getClientSecret();
        }
        if (secret != null) {
            b.clientSecret(secret);
        }

        boolean requirePk = req.requireProofKey() != null ? req.requireProofKey() : true;
        b.clientSettings(ClientSettings.builder().requireProofKey(requirePk).build());
        b.tokenSettings(TokenSettings.builder()
                .accessTokenTimeToLive(Duration.ofMinutes(30))
                .refreshTokenTimeToLive(Duration.ofDays(7))
                .reuseRefreshTokens(false)
                .build());
        return b.build();
    }

    private ClientView toView(RegisteredClient rc) {
        return new ClientView(
                rc.getId(),
                rc.getClientId(),
                rc.getClientName(),
                rc.getClientAuthenticationMethods().stream().map(ClientAuthenticationMethod::getValue).toList(),
                rc.getAuthorizationGrantTypes().stream().map(AuthorizationGrantType::getValue).toList(),
                rc.getRedirectUris() == null ? List.of() : new ArrayList<>(rc.getRedirectUris()),
                rc.getPostLogoutRedirectUris() == null ? List.of() : new ArrayList<>(rc.getPostLogoutRedirectUris()),
                rc.getScopes() == null ? List.of() : new ArrayList<>(rc.getScopes()),
                rc.getClientSecret() != null,
                rc.getClientSettings() != null && rc.getClientSettings().isRequireProofKey());
    }
}
