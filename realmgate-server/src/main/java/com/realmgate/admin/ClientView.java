package com.realmgate.admin;

import java.util.List;

/**
 * 客户端对外视图（遮蔽 client_secret）。hasSecret 仅告知是否存在密钥，绝不回传明文/哈希。
 */
public record ClientView(
        String id,
        String clientId,
        String clientName,
        List<String> clientAuthenticationMethods,
        List<String> authorizationGrantTypes,
        List<String> redirectUris,
        List<String> postLogoutRedirectUris,
        List<String> scopes,
        boolean hasSecret,
        boolean requireProofKey) {
}
