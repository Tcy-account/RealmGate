package com.realmgate.infrastructure.security;

import com.realmgate.identity.user.User;
import com.realmgate.identity.user.UserRepository;
import com.realmgate.member.Member;
import com.realmgate.member.MemberRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class ProjectRoleCache {

    private final UserRepository userRepository;
    private final MemberRepository memberRepository;
    private final CacheManager cacheManager;

    // 缓存"用户在某项目的角色"。key 含租户前缀（计划 §35）。
    // 值存 role 名 String；非成员 / 用户不存在返回 null 不缓存（避免穿透占空间）。
    @Cacheable(cacheNames = "projectRole",
            key = "'tenant:' + #tenantId + ':perm:proj:' + #projectId + ':user:' + #username",
            unless = "#result == null")
    public String resolveRole(String tenantId, Long projectId, String username) {
        User user = userRepository.findByTenantIdAndUsername(tenantId, username).orElse(null);
        if (user == null) return null;
        return memberRepository.findByTenantIdAndProjectIdAndUserId(tenantId, projectId, user.getId())
                .map(Member::getRole)
                .map(Enum::name)
                .orElse(null);
    }

    // 精确失效：key 必须与 resolveRole 完全一致
    @CacheEvict(cacheNames = "projectRole",
            key = "'tenant:' + #tenantId + ':perm:proj:' + #projectId + ':user:' + #username")
    public void evict(String tenantId, Long projectId, String username) { }

    // 按 userId 失效（MemberService 持有 userId，先解析 username 再 evict）。
    // 注意：不能在这里调 this.evict(...)，同类内调用不走 AOP → @CacheEvict 不生效；
    // 故直接用 CacheManager 操作缓存，确保失效一定执行。
    public void evictByUserId(String tenantId, Long projectId, Long userId) {
        String username = userRepository.findByIdAndTenantId(userId, tenantId)
                .map(User::getUsername).orElse(null);
        if (username == null) return;
        Cache cache = cacheManager.getCache("projectRole");
        if (cache != null) {
            cache.evict("tenant:" + tenantId + ":perm:proj:" + projectId + ":user:" + username);
        }
    }
}
