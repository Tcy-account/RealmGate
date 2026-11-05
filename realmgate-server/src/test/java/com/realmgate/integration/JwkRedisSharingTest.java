package com.realmgate.integration;

import com.nimbusds.jose.jwk.JWK;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.source.JWKSource;
import com.nimbusds.jose.proc.SecurityContext;
import com.realmgate.infrastructure.security.oauth2.RsaKeyManager;
import com.realmgate.infrastructure.security.oauth2.RotatingJwkSource;
import java.lang.reflect.Field;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;
import org.springframework.security.oauth2.server.authorization.config.annotation.web.configuration.OAuth2AuthorizationServerConfiguration;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Phase 10 多实例 — JWK 家族共享（Redis）端到端验证。
 *
 * 模拟"两个 realmgate-server 实例"：各自 new 一个 RsaKeyManager，但都指向同一个 Redis key
 * （realmgate:jwk-set）。验证：
 *   1) 启动共享：两实例从 Redis 读到同一套初始密钥家族（active kid 相同）；
 *   2) 轮换共享：实例 A rotate 后，新 active 写回 Redis，实例 B 经缓存刷新后读到同一新 active；
 *   3) 跨实例验签：A 用当前 active 签发的 token，B 的 jwtDecoder（复用 B 的 RotatingJwkSource）能验过；
 *   4) 宽限期：A rotate 前的旧 token，B 仍能用 history 中的旧 key 验过（不瞬间失效）。
 *
 * 依赖真实 Redis（docker-compose 起的 6379，与 M2/M3/M4 同一套）。@BeforeEach 清共享 key 保证隔离。
 */
@SpringBootTest
@DisplayName("Phase 10 多实例：JWK 家族 Redis 共享")
class JwkRedisSharingTest {

  // 独立命名空间，避免污染开发态默认的 realmgate:jwk-set（应用运行态密钥）
  private static final String JWK_REDIS_KEY = "realmgate:jwk-set:test";

  @Autowired
  StringRedisTemplate redis;

  @TempDir
  Path tempDirA;

  @TempDir
  Path tempDirB;

  @BeforeEach
  void cleanSharedKey() {
    redis.delete(JWK_REDIS_KEY);
  }

  /** 构造一个"实例"：独立的 RsaKeyManager，但共享同一个 Redis key（多实例共享密钥家族）。 */
  private RsaKeyManager newInstance(Path tempDir) throws Exception {
    RsaKeyManager mgr = new RsaKeyManager(redis);
    Field loc = RsaKeyManager.class.getDeclaredField("jwkLocation");
    loc.setAccessible(true);
    loc.set(mgr, tempDir.resolve("jwk.json").toString());
    Field key = RsaKeyManager.class.getDeclaredField("jwkRedisKey");
    key.setAccessible(true);
    key.set(mgr, JWK_REDIS_KEY);
    mgr.init();
    return mgr;
  }

  /** 用指定实例的当前 active key 签发一个 JWT（模拟该实例签发 token）。 */
  private String signWith(RsaKeyManager mgr, String subject) {
    JWKSource<SecurityContext> activeOnly =
        (selector, ctx) -> selector.select(new JWKSet(List.of((JWK) mgr.activeKey())));
    JwtEncoder encoder = new NimbusJwtEncoder(activeOnly);
    JwtClaimsSet claims = JwtClaimsSet.builder()
        .subject(subject)
        .issuedAt(Instant.now())
        .expiresAt(Instant.now().plusSeconds(3600))
        .build();
    return encoder.encode(JwtEncoderParameters.from(claims)).getTokenValue();
  }

  /** 用指定实例的密钥家族（RotatingJwkSource 实时读 Redis）构造验签器，模拟该实例验签。 */
  private JwtDecoder decoderFor(RsaKeyManager mgr) {
    return OAuth2AuthorizationServerConfiguration.jwtDecoder(new RotatingJwkSource(mgr));
  }

  @Test
  @DisplayName("① 两实例启动即从 Redis 共享同一初始密钥家族（active kid 一致）")
  void twoInstancesShareInitialFamily() throws Exception {
    RsaKeyManager a = newInstance(tempDirA);
    RsaKeyManager b = newInstance(tempDirB);

    assertNotNull(a.activeKid());
    assertEquals(a.activeKid(), b.activeKid(), "两实例应从 Redis 读到同一 active kid");
    // Redis 中确有 1 把初始 key
    String stored = redis.opsForValue().get(JWK_REDIS_KEY);
    assertNotNull(stored);
    assertEquals(1, JWKSet.parse(stored).getKeys().size());
  }

  @Test
  @DisplayName("② 实例 A 轮换后，新 active 写回 Redis，实例 B 经缓存刷新读到同一新 active")
  void rotatePropagatesToOtherInstance() throws Exception {
    RsaKeyManager a = newInstance(tempDirA);
    RsaKeyManager b = newInstance(tempDirB);
    String oldKid = a.activeKid();

    String newKid = a.rotate();
    assertNotEquals(oldKid, newKid);

    // Redis 中 active（末位）已是新 kid
    String stored = redis.opsForValue().get(JWK_REDIS_KEY);
    List<JWK> keys = JWKSet.parse(stored).getKeys();
    assertEquals(newKid, keys.get(keys.size() - 1).getKeyID(), "Redis 末位须为新 active");

    // 等 B 的内存视图缓存（CACHE_TTL_MS=2000）过期，B 应刷新读到 Redis 新 active
    Thread.sleep(2500);
    assertEquals(newKid, b.activeKid(), "实例 B 应经 Redis 实时读到 A 轮换后的新 active");
  }

  @Test
  @DisplayName("③ 跨实例验签：A 签的 token 能被 B 验过；④ 宽限期：A 旧 token 仍可被 B 验过")
  void crossInstanceSignAndVerify() throws Exception {
    RsaKeyManager a = newInstance(tempDirA);
    RsaKeyManager b = newInstance(tempDirB);

    // A 用初始 active 签 token，B 验（两实例共享初始家族）
    String tokenOld = signWith(a, "user-old");
    Jwt verifiedOld = decoderFor(b).decode(tokenOld);
    assertEquals("user-old", verifiedOld.getSubject());

    // A 轮换，用新 active 签 token
    String newKid = a.rotate();
    String tokenNew = signWith(a, "user-new");
    assertNotNull(tokenNew);

    // 等 B 缓存刷新后，B 家族含 A 的新 active → 验 A 新 token 成功（跨实例一致）
    Thread.sleep(2500);
    Jwt verifiedNew = decoderFor(b).decode(tokenNew);
    assertEquals("user-new", verifiedNew.getSubject());
    assertEquals(newKid, verifiedNew.getHeaders().get("kid"));

    // 宽限期：A rotate 前的旧 token，B 仍能用 history 中的旧 key 验过（不瞬间失效）
    Jwt verifiedOldAfterRotate = decoderFor(b).decode(tokenOld);
    assertEquals("user-old", verifiedOldAfterRotate.getSubject());
  }
}
