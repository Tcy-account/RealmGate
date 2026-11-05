package com.realmgate.infrastructure.web;

import com.realmgate.common.api.ApiResponse;
import com.realmgate.infrastructure.security.oauth2.RsaKeyManager;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Phase 7 — JWK Rotation：手动轮换端点。
 *
 * 鉴权：仅限持有 jwk:rotate 权限（TENANT_ADMIN）的已认证用户调用（链② 已保证 Bearer 认证）。
 * jwk:rotate 由 UserDetailsServiceImpl 在解析到 TENANT_ADMIN 角色时追加进 token 的 permissions claim。
 *
 * 行为：轮换后旧 key 仍在 /oauth2/jwks 与验签集合内保留 HISTORY_RETAIN 个（宽限期），
 * 已签发的旧 access_token 在其 TTL 内仍有效，不会因轮换而瞬间踢掉在线用户。
 */
@RestController
@RequestMapping("/api/admin/jwk")
public class JwkRotationController {

  private final RsaKeyManager keyManager;

  public JwkRotationController(RsaKeyManager keyManager) {
    this.keyManager = keyManager;
  }

  @PostMapping("/rotate")
  public ApiResponse<Map<String, String>> rotate(@AuthenticationPrincipal Jwt jwt) {
    if (jwt == null || !jwt.getClaimAsStringList("permissions").contains("jwk:rotate")) {
      return ApiResponse.error(403, "无 JWK 轮换权限（需 TENANT_ADMIN）");
    }
    String oldKid = keyManager.activeKid();
    String newKid = keyManager.rotate();
    Map<String, String> body = new LinkedHashMap<>();
    body.put("oldKid", oldKid);
    body.put("newKid", newKid);
    return ApiResponse.success(body);
  }
}
