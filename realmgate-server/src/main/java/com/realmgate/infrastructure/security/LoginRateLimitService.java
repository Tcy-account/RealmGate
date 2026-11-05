package com.realmgate.infrastructure.security;

import java.time.Duration;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

@Service
public class LoginRateLimitService {

    private final StringRedisTemplate redis;

    // 计划 §37 示例：5 分钟内失败 10 次 → 临时锁定
    private static final int WINDOW_SECONDS = 300;   // 失败计数滑动窗口 5 分钟
    private static final int MAX_FAILS = 10;          // 窗口内最大失败次数
    private static final int LOCK_SECONDS = 900;      // 锁定 15 分钟（计划未定，取合理值）

    public LoginRateLimitService(StringRedisTemplate redis) {
        this.redis = redis;
    }

    // key 规范（计划 §35：含租户前缀）
    private String failUser(String t, String u) { return "tenant:" + t + ":login:fail:user:" + u; }
    private String lockUser(String t, String u) { return "tenant:" + t + ":login:lock:user:" + u; }
    private String failIp(String t, String ip) { return "tenant:" + t + ":login:fail:ip:" + ip; }
    private String lockIp(String t, String ip) { return "tenant:" + t + ":login:lock:ip:" + ip; }

    private String norm(String s) { return (s == null || s.isBlank()) ? "common" : s.trim(); }

    // 预检查：用户名或 IP 任一被锁 → 拦截
    public boolean isLocked(String tenant, String username, String ip) {
        String t = norm(tenant);
        return Boolean.TRUE.equals(redis.hasKey(lockUser(t, norm(username))))
            || Boolean.TRUE.equals(redis.hasKey(lockIp(t, norm(ip))));
    }

    // 失败：双维度累加；任一层达阈值则置锁（IP 维度防撞库，用户维度防定向爆破）
    public void onFailure(String tenant, String username, String ip) {
        String t = norm(tenant);
        if (incr(failUser(t, norm(username))) >= MAX_FAILS) lock(lockUser(t, norm(username)));
        if (incr(failIp(t, norm(ip))) >= MAX_FAILS) lock(lockIp(t, norm(ip)));
    }

    // 成功：清掉该用户/IP 的计数与锁，避免成功登录后累积误伤
    public void onSuccess(String tenant, String username, String ip) {
        String t = norm(tenant);
        redis.delete(failUser(t, norm(username)));
        redis.delete(lockUser(t, norm(username)));
        redis.delete(failIp(t, norm(ip)));
        redis.delete(lockIp(t, norm(ip)));
    }

    // 滑动窗口：每次失败刷新窗口起点（expire 每次重置 TTL）
    private long incr(String key) {
        Long v = redis.opsForValue().increment(key);
        redis.expire(key, Duration.ofSeconds(WINDOW_SECONDS));
        return v == null ? 0 : v;
    }

    private void lock(String key) {
        redis.opsForValue().setIfAbsent(key, "1", Duration.ofSeconds(LOCK_SECONDS));
    }
}
