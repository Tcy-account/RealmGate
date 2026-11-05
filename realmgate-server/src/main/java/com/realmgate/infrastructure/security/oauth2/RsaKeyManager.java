package com.realmgate.infrastructure.security.oauth2;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.jwk.JWK;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.KeyUse;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.util.Base64URL;
import com.nimbusds.jose.util.JSONObjectUtils;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.interfaces.RSAPrivateCrtKey;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/**
 * Phase 10 多实例 — JWK 家族共享存储（Redis）。
 *
 * 持有"当前激活 key + 历史 key 家族"。Phase 7 起密钥落**本地文件**；本阶段把主存储
 * **搬上 Redis（key = realmgate:jwk-set）**，使多个 realmgate-server 实例共享同一套密钥家族：
 *   - 任一实例 rotate 后，其他实例经 Redis 实时读到新 active，跨实例签发的 token 互相可验签；
 *   - 资源服务器（链② jwtDecoder，复用 RotatingJwkSource）实时从本类取家族，宽限期内旧 key 仍可验。
 *
 * 存储策略：
 *   - 主：Redis（多实例共享）。每次 activeKey()/allKeys() 在 CACHE_TTL_MS 阈值内复用内存视图，
 *     超时则从 Redis 重新拉取，平衡 Redis 压力与多实例一致性（rotate 写回后其他实例最多延迟一个阈值）。
 *   - 兜底：Redis 不可用（get/set 抛异常或 redis==null）时回退本地文件，保证单实例/故障态可启动。
 *
 * 并发安全：
 *   - 初始化：Redis 空时用 SETNX 抢占，仅一个实例负责首次生成，其余加载已写内容，避免多实例各生成一套分裂。
 *   - rotate：加 Redis 分布式锁（SETNX + TTL），持锁内强制从 Redis 读最新家族再旋转写回，
 *     防止多实例同时 rotate 互相覆盖（覆盖会导致对方新 key 丢失、跨实例验签失败）。
 *
 * 注意：私钥以明文 base64url 存 Redis（同文件安全级别，学习/本地用）；生产应改用密钥管理服务或 Redis ACL+TLS。
 */
@Component
public class RsaKeyManager {

  private static final int HISTORY_RETAIN = 2;
  private static final String REDIS_KEY_DEFAULT = "realmgate:jwk-set";
  private static final String REDIS_LOCK_KEY = "realmgate:jwk-set:lock";
  private static final Duration LOCK_TTL = Duration.ofSeconds(10);
  // 内存视图刷新阈值：正常路径每超过该时长才从 Redis 拉取一次。
  private static final long CACHE_TTL_MS = 2000;

  @Value("${realmgate.jwk.location:${user.home}/.realmgate/jwk.json}")
  private String jwkLocation;

  @Value("${realmgate.jwk.redis-key:" + REDIS_KEY_DEFAULT + "}")
  private String jwkRedisKey;

  private final StringRedisTemplate redis;
  @org.springframework.beans.factory.annotation.Autowired(required = false)
  private com.realmgate.infrastructure.observability.SecurityMetrics metrics;

  // 内存视图（带 lastSync 时间戳，超过 CACHE_TTL_MS 重新从 Redis 拉）
  private final Map<String, RSAKey> keys = new LinkedHashMap<>();
  private String activeKid;
  private volatile long lastSyncNanos = 0;

  // 允许 redis 为 null（纯文件兜底模式，便于单测 / Redis 不可用时）。Spring 注入真实 redis bean。
  @org.springframework.beans.factory.annotation.Autowired
  public RsaKeyManager(StringRedisTemplate redis) {
    this.redis = redis;
  }

  /** 测试用无参构造：redis=null、metrics=null，走纯文件兜底，不依赖 Spring/Redis。 */
  public RsaKeyManager() {
    this.redis = null;
  }

  @jakarta.annotation.PostConstruct
  public void init() {
    loadFromStore();
  }

  // ---------------- 存储抽象：优先 Redis，失败回退本地文件 ----------------

  private void loadFromStore() {
    String json = redisGet();
    if (json != null) {
      loadFromJson(json, true);
      return;
    }
    // Redis 空：尝试初始化（SETNX 抢占，多实例只有一个真正成为初始化者）
    if (redisInitialize()) {
      return;
    }
    // 别人已初始化：再读一次；仍空则回退文件
    String after = redisGet();
    if (after != null) {
      loadFromJson(after, true);
      return;
    }
    loadFromFile();
  }

  private String redisGet() {
    if (redis == null) return null;
    try {
      return redis.opsForValue().get(jwkRedisKey);
    } catch (Exception e) {
      return null; // Redis 故障：回退文件
    }
  }

  private boolean redisInitialize() {
    if (redis == null) return false;
    try {
      String json = serialize(generateInitialSet());
      // SETNX：首个成功写者成为初始化者，其余回退到"再读一次"
      Boolean ok = redis.opsForValue().setIfAbsent(jwkRedisKey, json);
      if (ok != null && ok) {
        loadFromJson(json, true);
        return true;
      }
    } catch (Exception e) {
      // 忽略，走文件回退
    }
    return false;
  }

  private void redisPersist(String json) {
    if (redis == null) {
      try {
        persistToFile(json);
      } catch (IOException ignored) {
      }
      return;
    }
    try {
      redis.opsForValue().set(jwkRedisKey, json);
    } catch (Exception e) {
      // Redis 写失败：仍落本地文件兜底（至少本实例可继续签发）
      try {
        persistToFile(json);
      } catch (IOException ignored) {
      }
    }
  }

  // ---------------- 文件兜底（Redis 不可用） ----------------

  private void loadFromFile() {
    Path path = Path.of(jwkLocation);
    try {
      if (Files.exists(path)) {
        loadFromJson(Files.readString(path).trim(), true);
      } else {
        String json = serialize(generateInitialSet());
        persistToFile(json);
        loadFromJson(json, true);
      }
    } catch (Exception e) {
      throw new IllegalStateException("加载/迁移 RSA 密钥失败: " + jwkLocation, e);
    }
  }

  private void persistToFile(String json) throws IOException {
    Path path = Path.of(jwkLocation);
    Files.createDirectories(path.getParent());
    Files.writeString(path, json);
  }

  // ---------------- 内存视图与刷新 ----------------

  private void loadFromJson(String json) {
    loadFromJson(json, false);
  }

  /**
   * 解析 JWK Set JSON 载入内存视图。末位为 active（与 serialize 约定一致）。
   * persistBack=true 时，若触发"active 仅公钥→重新生成带私钥 active"的兜底，则写回存储修正。
   */
  private void loadFromJson(String json, boolean persistBack) {
    List<RSAKey> all = parseSet(json);
    if (all.isEmpty()) {
      all = generateInitialSet();
    }
    RSAKey active = all.get(all.size() - 1);
    // 兜底：若 active 仅公钥（私钥丢失），重新生成带私钥 active，旧公钥留 history
    if (!active.isPrivate()) {
      List<RSAKey> withNew = new ArrayList<>(all.subList(0, all.size() - 1));
      withNew.add(generateNormalizedKey());
      all = withNew;
      if (persistBack) {
        redisPersist(serialize(all));
      }
    }
    keys.clear();
    for (RSAKey k : all) {
      keys.put(k.getKeyID(), k);
    }
    activeKid = all.get(all.size() - 1).getKeyID();
    lastSyncNanos = System.nanoTime();
  }

  private List<RSAKey> parseSet(String json) {
    try {
      return JWKSet.parse(json).getKeys().stream()
          .map(k -> (RSAKey) k)
          .map(this::normalize)
          .toList();
    } catch (Exception e) {
      try {
        // 旧格式：单个 JWK 对象
        return List.of(normalize(RSAKey.parse(json)));
      } catch (Exception ignored) {
        return List.of();
      }
    }
  }

  /** 正常路径：超过阈值才从 Redis 重新拉取（Redis 空则不动内存，保留当前视图）。 */
  private void refreshIfStale() {
    long now = System.nanoTime();
    if (TimeUnit.NANOSECONDS.toMillis(now - lastSyncNanos) < CACHE_TTL_MS) {
      return;
    }
    String json = redisGet();
    if (json != null) {
      loadFromJson(json, false);
    }
    // Redis 空：内存视图保持（已由 init/SETNX 保证有内容）
  }

  /** 强制刷新（rotate 持锁时调用，确保基于 Redis 最新家族旋转）。 */
  private void forceRefresh() {
    String json = redisGet();
    if (json != null) {
      loadFromJson(json, false);
    }
  }

  private List<RSAKey> generateInitialSet() {
    return List.of(generateNormalizedKey());
  }

  // ---------------- 对外 API ----------------

  /** 当前激活 key（JwtEncoder 用它签发，自动带 kid）。正常路径带 2s 内存缓存。 */
  public RSAKey activeKey() {
    refreshIfStale();
    return keys.get(activeKid);
  }

  /** active 在前、history 其后，供 jwks 端点与验签使用。 */
  public List<RSAKey> allKeys() {
    refreshIfStale();
    return new ArrayList<>(keys.values());
  }

  public String activeKid() {
    refreshIfStale();
    return activeKid;
  }

  /**
   * 轮换：生成新 key 作为 active，旧 active 降级为 history（保留最近 HISTORY_RETAIN 个），写回 Redis。
   * 实例内 synchronized + 实例间 Redis 分布式锁，双重保证多实例并发 rotate 不互相覆盖。
   */
  public synchronized String rotate() {
    String lockVal = UUID.randomUUID().toString();
    if (!acquireLock(lockVal)) {
      // 抢不到锁（别的实例在轮换）：强制同步最新视图，返回当前 active（不重复旋转，避免覆盖）
      forceRefresh();
      return activeKid;
    }
    try {
      forceRefresh(); // 持锁后基于 Redis 最新家族旋转
      RSAKey old = keys.get(activeKid);
      // 旧 active 推到队首，再顺次保留已有 history，直到达到 HISTORY_RETAIN 上限（active 始终末位）
      Map<String, RSAKey> rotated = new LinkedHashMap<>();
      rotated.put(old.getKeyID(), old);
      int kept = 1;
      for (RSAKey h : keys.values()) {
        if (h.getKeyID().equals(activeKid)) {
          continue;
        }
        rotated.put(h.getKeyID(), h);
        if (++kept >= HISTORY_RETAIN) {
          break;
        }
      }
      RSAKey fresh = generateNormalizedKey();
      rotated.put(fresh.getKeyID(), fresh);
      activeKid = fresh.getKeyID();

      String json = serialize(new ArrayList<>(rotated.values()));
      redisPersist(json);
      keys.clear();
      keys.putAll(rotated);
      lastSyncNanos = System.nanoTime();
      // 站 M7：仅"本实例真正完成旋转"（抢到锁并生成新 key）才计数；抢锁失败返回当前 active 不计数。
      if (metrics != null) {
        metrics.jwkRotated();
      }
      return fresh.getKeyID();
    } finally {
      releaseLock(lockVal);
    }
  }

  // ---------------- 分布式锁 ----------------

  private boolean acquireLock(String val) {
    if (redis == null) {
      return true; // 无 Redis：单实例，直接进（synchronized 已保证实例内互斥）
    }
    try {
      Boolean ok = redis.opsForValue().setIfAbsent(REDIS_LOCK_KEY, val, LOCK_TTL);
      return ok != null && ok;
    } catch (Exception e) {
      return true; // 锁不可用则放行（降级为无锁旋转，单实例安全）
    }
  }

  private void releaseLock(String val) {
    if (redis == null) {
      return;
    }
    try {
      // 仅删自己持有的锁（GET 比对后 DELETE，非原子但锁带 TTL 兜底防死锁）
      if (val.equals(redis.opsForValue().get(REDIS_LOCK_KEY))) {
        redis.delete(REDIS_LOCK_KEY);
      }
    } catch (Exception ignored) {
    }
  }

  // ---------------- 序列化（手动落 RSA 私钥，Nimbus 9.x 默认不含） ----------------

  /** Family 标准化：补齐 use=sig、alg=RS256；无 kid 旧 key 补随机 kid。 */
  private RSAKey normalize(RSAKey key) {
    RSAKey.Builder b = new RSAKey.Builder(key)
        .keyUse(KeyUse.SIGNATURE)
        .algorithm(JWSAlgorithm.RS256);
    if (!StringUtils.hasText(key.getKeyID())) {
      b.keyID(UUID.randomUUID().toString());
    }
    return b.build();
  }

  private RSAKey generateNormalizedKey() {
    try {
      KeyPairGenerator gen = KeyPairGenerator.getInstance("RSA");
      gen.initialize(2048);
      KeyPair kp = gen.generateKeyPair();
      return new RSAKey.Builder((RSAPublicKey) kp.getPublic())
          .privateKey((RSAPrivateKey) kp.getPrivate())
          .keyID(UUID.randomUUID().toString())
          .keyUse(KeyUse.SIGNATURE)
          .algorithm(JWSAlgorithm.RS256)
          .build();
    } catch (Exception e) {
      throw new IllegalStateException("生成 RSA 密钥失败", e);
    }
  }

  /**
   * 把 key 家族（调用方保证 active 在末位）序列化为 JWK Set JSON，私钥参数 base64url 化一并落盘（坑#2 真修）。
   * Nimbus 9.x 的 JWK/JWKSet 内置序列化（toJSONObject/toString）仅含公钥，必须手动补 d/p/q/dp/dq/qi。
   */
  private String serialize(List<RSAKey> ordered) {
    List<Map<String, Object>> keyMaps = new ArrayList<>();
    for (JWK jwk : ordered) {
      Map<String, Object> m = new LinkedHashMap<>(jwk.toJSONObject());
      if (jwk instanceof RSAKey rsa) {
        try {
          RSAPrivateKey priv = rsa.toRSAPrivateKey(); // Nimbus 取私钥用 toRSAPrivateKey（非 getPrivateKey）
          if (priv != null) {
            m.put("d", Base64URL.encode(priv.getPrivateExponent().toByteArray()).toString());
            if (priv instanceof RSAPrivateCrtKey crt) {
              m.put("p", Base64URL.encode(crt.getPrimeP().toByteArray()).toString());
              m.put("q", Base64URL.encode(crt.getPrimeQ().toByteArray()).toString());
              m.put("dp", Base64URL.encode(crt.getPrimeExponentP().toByteArray()).toString());
              m.put("dq", Base64URL.encode(crt.getPrimeExponentQ().toByteArray()).toString());
              m.put("qi", Base64URL.encode(crt.getCrtCoefficient().toByteArray()).toString());
            }
          }
        } catch (com.nimbusds.jose.JOSEException ignored) {
          // 取不到私钥则仅落公钥（重启后 regenerate），不影响验签
        }
      }
      keyMaps.add(m);
    }
    Map<String, Object> setMap = new LinkedHashMap<>();
    setMap.put("keys", keyMaps);
    return JSONObjectUtils.toJSONString(setMap);
  }
}
