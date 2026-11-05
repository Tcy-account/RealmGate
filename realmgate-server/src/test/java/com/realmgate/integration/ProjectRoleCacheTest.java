package com.realmgate.integration;

import com.realmgate.identity.user.User;
import com.realmgate.identity.user.UserRepository;
import com.realmgate.infrastructure.security.ProjectRoleCache;
import com.realmgate.member.Member;
import com.realmgate.member.MemberRepository;
import com.realmgate.member.MemberRole;
import com.realmgate.project.Project;
import com.realmgate.project.ProjectRepository;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Phase 6 站3 Permission Cache 运行时验收。
 *
 * 不依赖 MockMvc / 登录，直接测 ProjectRoleCache 这一缓存层本身：
 *   - 首次 resolveRole：查库 → 写 Redis；
 *   - 直接改库角色、不调 evict：再次 resolveRole 仍返回旧值 → 证明命中缓存、免查库（站3 核心收益）；
 *   - evictByUserId 后：再次 resolveRole 返回新值 → 证明失效生效（MemberService 写操作据此刷新）。
 *
 * 注：打已在 localhost:5432 运行的真实 Postgres（realmgate_test），Redis 亦为真实实例。
 */
@SpringBootTest
@DisplayName("Phase 6 站3 项目级角色缓存：命中免查库 + 失效刷新")
class ProjectRoleCacheTest {

    @DynamicPropertySource
    static void useLiveTestPostgres(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> "jdbc:postgresql://localhost:5432/realmgate_test");
        registry.add("spring.datasource.username", () -> "realmgate");
        registry.add("spring.datasource.password", () -> "realmgate");
        registry.add("spring.datasource.driver-class-name", () -> "org.postgresql.Driver");
    }

    @Autowired
    ProjectRoleCache cache;
    @Autowired
    UserRepository userRepository;
    @Autowired
    ProjectRepository projectRepository;
    @Autowired
    MemberRepository memberRepository;
    @Autowired
    StringRedisTemplate redis;

    private static final String TENANT = "cachett";
    private Long userId;
    private Long projectId;

    @BeforeEach
    void setUp() {
        // 清缓存，避免跨测试脏读
        Set<String> keys = redis.keys("tenant:rg:cache:projectRole:*");
        if (keys != null && !keys.isEmpty()) redis.delete(keys);
        // 清库（外键用 Long 列，按 member → project → user 顺序）
        memberRepository.deleteAll();
        projectRepository.deleteAll();
        userRepository.deleteAll();

        User u = new User();
        u.setTenantId(TENANT);
        u.setUsername("u1");
        u.setEmail("u1@cachett.test");
        u.setPasswordHash("x");
        u = userRepository.save(u);
        userId = u.getId();

        Project p = new Project();
        p.setTenantId(TENANT);
        p.setName("p1");
        p.setSlug("cachett-p1-" + System.nanoTime());   // 唯一 slug，避免 UNIQUE 冲突
        p.setStatus("ACTIVE");
        p = projectRepository.save(p);
        projectId = p.getId();

        Member m = new Member();
        m.setTenantId(TENANT);
        m.setProjectId(projectId);
        m.setUserId(userId);
        m.setRole(MemberRole.PM);
        memberRepository.save(m);
    }

    @Test
    @DisplayName("resolveRole 首次查库写缓存；改库不 evict 仍返回旧值(命中)；evict 后返回新值")
    void cacheHitAndEvict() {
        // 1) 首次：查库 → PM
        String r1 = cache.resolveRole(TENANT, projectId, "u1");
        assertEquals("PM", r1);
        // Redis 应有缓存 key（前缀 tenant:rg:cache:projectRole:）
        assertTrue(redis.keys("tenant:rg:cache:projectRole:*").size() >= 1, "缓存 key 应已写入 Redis");

        // 2) 直接改库角色为 MEMBER，不调 evict
        Member m = memberRepository.findByTenantIdAndProjectIdAndUserId(TENANT, projectId, userId).orElseThrow();
        m.setRole(MemberRole.MEMBER);
        memberRepository.save(m);

        // 3) 再次 resolveRole：命中缓存 → 仍 PM（证明没查新库值，站3 省库收益成立）
        String r2 = cache.resolveRole(TENANT, projectId, "u1");
        assertEquals("PM", r2, "改库未 evict 时应命中旧缓存");

        // 4) evict
        cache.evictByUserId(TENANT, projectId, userId);

        // 5) evict 后：重查库 → MEMBER（证明失效生效，MemberService 写后据此刷新）
        String r3 = cache.resolveRole(TENANT, projectId, "u1");
        assertEquals("MEMBER", r3, "evict 后应从库重查到新角色");
    }
}
