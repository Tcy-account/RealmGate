package com.realmgate.infrastructure.security.oauth2;

import com.nimbusds.jose.KeySourceException;
import com.nimbusds.jose.jwk.JWK;
import com.nimbusds.jose.jwk.JWKSelector;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.source.JWKSource;
import com.nimbusds.jose.proc.SecurityContext;
import java.util.ArrayList;
import java.util.List;

/**
 * 动态 JWKSource：每次 get() 实时从 RsaKeyManager 取当前 key 家族（active + history）。
 *
 * 这样 rotate 之后：
 *  - /oauth2/jwks 端点立即暴露新 key（旧 key 仍在，宽限期可验签）；
 *  - 资源服务器（链② 的 jwtDecoder，复用本 source）能验签新旧 key，旧 access_token 在 TTL 内仍有效。
 * 无需重启 AS 即可完成密钥轮换。
 *
 * 注意：本类不标 @Component，由 AuthorizationServerConfig.jwkSource() 以 @Primary @Bean 形式
 * 创建唯一实例，避免与 jwkSource bean 重复导致 SAS 注入二义性。
 */
public class RotatingJwkSource implements JWKSource<SecurityContext> {

  private final RsaKeyManager keyManager;

  public RotatingJwkSource(RsaKeyManager keyManager) {
    this.keyManager = keyManager;
  }

  @Override
  public List<JWK> get(JWKSelector selector, SecurityContext context) throws KeySourceException {
    // allKeys() 返回 List<RSAKey>，转成 List<JWK> 再包成 JWKSet 喂给 selector（泛型不变性）
    return selector.select(new JWKSet(new ArrayList<JWK>(keyManager.allKeys())));
  }
}
