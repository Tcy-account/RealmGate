package com.realmgate.admin;

import java.util.List;

/**
 * 管理后台创建/更新 OAuth 客户端的请求体。
 * 注意：RegisteredClient 不可变，service 按此 DTO 构建全新实例；
 * clientSecret 仅对机密客户端有意义，且经 PasswordEncoder 编码后落库，绝不明文。
 */
public record ClientUpsertRequest(
        // OAuth client_id；为空时后端生成 client-<8位随机>
        String clientId,
        // 展示名（必填）
        String clientName,
        // 客户端认证方式，如 ["none","client_secret_basic"]
        List<String> clientAuthenticationMethods,
        // 授权类型，如 ["authorization_code","refresh_token"]
        List<String> authorizationGrantTypes,
        // 回调 URI 列表
        List<String> redirectUris,
        // 退出回调 URI 列表
        List<String> postLogoutRedirectUris,
        // 授权范围列表
        List<String> scopes,
        // 明文客户端密钥（仅机密客户端创建/改密钥时填；公开客户端留空）
        String clientSecret,
        // client_settings.requireProofKey；为空默认 true（推荐 PKCE）
        Boolean requireProofKey) {
}
