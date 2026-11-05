package com.realmgate.infrastructure.security;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.time.Duration;
import java.time.Instant;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

/**
 * 站2 Token Family：用 Redis 维护"同一次登录派生出的 access/refresh token 家族"状态。
 *
 * 两条 Redis 结构：
 *  - 单族记录：tenant:{t}:token:family:{familyId}  = JSON(FamilyRecord)，TTL=refresh TTL(7d)
 *  - 整族失效集合：tenant:{t}:token:family_revoked  = SET<familyId>，TTL=7d
 *    用于"注销一次让整族 access token 立即失效"：资源服务器验签时除查 jti 黑名单外，
 *    再查该 token 的 family_id 是否在此集合（见 RevocationCheckingJwtDecoder）。
 */
@Service
public class TokenFamilyService {

    private final StringRedisTemplate redis;
    private final ObjectMapper mapper = new ObjectMapper();
    private final Duration REFRESH_TTL = Duration.ofDays(7);

    public TokenFamilyService(StringRedisTemplate redis) {
        this.redis = redis;
    }

    /** refresh 进入校验结果：OK / 整族已撤销 / 重放（jti 非当前有效 refresh）。 */
    public enum ValidateResult {
        OK, FAMILY_REVOKED, REPLAY
    }

    /** 单族记录（字段 public，Jackson 直接序列化）。 */
    public static class FamilyRecord {
        public String status;            // active | revoked
        public String currentRefreshJti; // 当前有效的 refresh token jti（rotation 后更新，旧 RT 自然失效）
        public String userId;
        public String clientId;
        public Long expiresAt;           // epoch second

        public FamilyRecord() {
        }

        public FamilyRecord(String status, String currentRefreshJti, String userId, String clientId, Long expiresAt) {
            this.status = status;
            this.currentRefreshJti = currentRefreshJti;
            this.userId = userId;
            this.clientId = clientId;
            this.expiresAt = expiresAt;
        }
    }

    /**
     * 不透明 refresh token 的绑定记录：RT 本身无 claim，family/jti/tenant 全部外置到 Redis，
     * 由 RT 的 tokenValue（全局唯一 UUID）反查。replay filter 与 token generator 都依赖它。
     */
    public static class RefreshBinding {
        public String tenantId;
        public String familyId;
        public String jti;

        public RefreshBinding() {
        }

        public RefreshBinding(String tenantId, String familyId, String jti) {
            this.tenantId = tenantId;
            this.familyId = familyId;
            this.jti = jti;
        }
    }

    private String familyKey(String tenantId, String familyId) {
        return "tenant:" + tenantId + ":token:family:" + familyId;
    }

    private String revokedFamiliesKey(String tenantId) {
        return "tenant:" + tenantId + ":token:family_revoked";
    }

    /** 不透明 RT 绑定 key：RT 值全局唯一，故不挂 tenant 前缀（replay filter 也拿不到 tenant）。 */
    private String rtBindingKey(String rtValue) {
        return "realmgate:rt:" + rtValue;
    }

    /** 签发 RT 时把 family/jti/tenant 绑到 RT 值上，供续刷/replay 反查。TTL=7d（与 RT 寿命一致）。 */
    public void bindRefreshToken(String tenantId, String rtValue, String familyId, String jti) {
        if (tenantId == null || rtValue == null || familyId == null) {
            return;
        }
        RefreshBinding b = new RefreshBinding(tenantId, familyId, jti);
        redis.opsForValue().set(rtBindingKey(rtValue), toJson(b), REFRESH_TTL);
    }

    /** 按 RT 值反查绑定；RT 不存在/已过期/非本系统签发 → null。 */
    public RefreshBinding resolveRefresh(String rtValue) {
        if (rtValue == null) {
            return null;
        }
        String v = redis.opsForValue().get(rtBindingKey(rtValue));
        if (v == null) {
            return null;
        }
        try {
            return mapper.readValue(v, RefreshBinding.class);
        } catch (JsonProcessingException e) {
            return null;
        }
    }

    /** 首次授权码换取：建 family，currentRefreshJti = 本次 RT 的 jti。 */
    public void createFamily(String tenantId, String familyId, String userId, String clientId, String refreshJti) {
        if (tenantId == null || familyId == null) {
            return;
        }
        FamilyRecord rec = new FamilyRecord("active", refreshJti, userId, clientId,
                Instant.now().plus(REFRESH_TTL).getEpochSecond());
        redis.opsForValue().set(familyKey(tenantId, familyId), toJson(rec), REFRESH_TTL);
    }

    /** 续刷：更新 currentRefreshJti（旧 RT 自然失效），重置 7d TTL（续期语义）。 */
    public void rotate(String tenantId, String familyId, String newRefreshJti) {
        if (tenantId == null || familyId == null) {
            return;
        }
        FamilyRecord rec = read(tenantId, familyId);
        if (rec == null) {
            rec = new FamilyRecord();
        }
        rec.status = "active";
        rec.currentRefreshJti = newRefreshJti;
        redis.opsForValue().set(familyKey(tenantId, familyId), toJson(rec), REFRESH_TTL);
    }

    /**
     * refresh 进入时校验：
     *  - family 在整族失效集合 / 记录缺失 / 已 revoked → FAMILY_REVOKED
     *  - refresh jti 与当前有效 jti 不符（即已被 rotation 替换的"旧 RT"再来一次）→ REPLAY
     *  - 否则 OK
     */
    public ValidateResult validateRefresh(String tenantId, String familyId, String refreshJti) {
        if (tenantId == null || familyId == null || refreshJti == null) {
            return ValidateResult.FAMILY_REVOKED;
        }
        if (Boolean.TRUE.equals(redis.opsForSet().isMember(revokedFamiliesKey(tenantId), familyId))) {
            return ValidateResult.FAMILY_REVOKED;
        }
        FamilyRecord rec = read(tenantId, familyId);
        if (rec == null || "revoked".equals(rec.status)) {
            return ValidateResult.FAMILY_REVOKED;
        }
        if (!refreshJti.equals(rec.currentRefreshJti)) {
            return ValidateResult.REPLAY;
        }
        return ValidateResult.OK;
    }

    /** 整族失效（logout / revoke）：标记记录 revoked + 把 familyId 加进失效集合。 */
    public void revokeFamily(String tenantId, String familyId) {
        if (tenantId == null || familyId == null) {
            return;
        }
        FamilyRecord rec = read(tenantId, familyId);
        if (rec != null) {
            rec.status = "revoked";
            long ttl = rec.expiresAt != null
                    ? Math.max(rec.expiresAt - Instant.now().getEpochSecond(), 60)
                    : 60;
            redis.opsForValue().set(familyKey(tenantId, familyId), toJson(rec), Duration.ofSeconds(ttl));
        }
        redis.opsForSet().add(revokedFamiliesKey(tenantId), familyId);
        redis.expire(revokedFamiliesKey(tenantId), REFRESH_TTL);
    }

    /** 资源服务器验签用：family 是否已整族撤销。 */
    public boolean isFamilyRevoked(String tenantId, String familyId) {
        if (tenantId == null || familyId == null) {
            return false;
        }
        return Boolean.TRUE.equals(redis.opsForSet().isMember(revokedFamiliesKey(tenantId), familyId));
    }

    private FamilyRecord read(String tenantId, String familyId) {
        String v = redis.opsForValue().get(familyKey(tenantId, familyId));
        if (v == null) {
            return null;
        }
        try {
            return mapper.readValue(v, FamilyRecord.class);
        } catch (JsonProcessingException e) {
            return null;
        }
    }

    private String toJson(Object rec) {
        try {
            return mapper.writeValueAsString(rec);
        } catch (JsonProcessingException e) {
            return "{}";
        }
    }
}
