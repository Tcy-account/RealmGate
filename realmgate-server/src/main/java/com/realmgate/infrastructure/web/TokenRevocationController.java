package com.realmgate.infrastructure.web;

import com.realmgate.common.api.ApiResponse;
import com.realmgate.infrastructure.security.TokenFamilyService;
import com.realmgate.infrastructure.security.TokenRevocationService;

import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 站1 Token Revocation：暴露注销端点。
 * 由 SPA 在注销时调用，把"当前已校验"的 token 的 jti 写进 Redis 黑名单。
 * 端点本身在链② 下需 Bearer 鉴权，调用方必须持有效 token —— 即注销它自己。
 *
 * 站2 扩展：access token 现带 family_id，注销时额外调用 revokeFamily，
 * 让同族所有 access/refresh token（含未被单独拉黑的）立即失效。
 */
@RestController
@RequestMapping("/api/tokens")
public class TokenRevocationController {

    private final TokenRevocationService revocation;
    private final TokenFamilyService familyService;

    public TokenRevocationController(TokenRevocationService revocation, TokenFamilyService familyService) {
        this.revocation = revocation;
        this.familyService = familyService;
    }

    @PostMapping("/revoke")
    public ApiResponse<Void> revoke(@AuthenticationPrincipal Jwt jwt) {
        String tenant = jwt.getClaimAsString("tenant_id");
        revocation.revoke(
                tenant,
                jwt.getId(),        // jti（① 已写入）
                jwt.getExpiresAt()); // Instant，用于算 TTL
        // 站2：按 family 整族失效（logout 一次让同族所有 token 作废）
        String familyId = jwt.getClaimAsString("family_id");
        if (familyId != null) {
            familyService.revokeFamily(tenant, familyId);
        }
        return ApiResponse.success(null);
    }
}
