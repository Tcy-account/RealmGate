package com.realmgate.test.support;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;

import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.JwtRequestPostProcessor;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * M1 统一集成测试基座（Phase 10）。
 *
 * 基础设施：复用已在 localhost:5432 运行的真实 Postgres（库 realmgate_test）+ 真实 Redis（6379）。
 * 不碰 Testcontainers —— 本机 Docker 走 desktop-linux/WSL2 上下文，npipe 检测连不上，前车之鉴。
 *
 * 两种测试姿态：
 *  (A) Slice（本基类默认，@SpringBootTest + MockMvc + jwt() 注入 authorities）：
 *      验「资源服务器链② + controller/service 的 RBAC 拦截」。注意 jwt() 绕过真实 converter，
 *      authorities 由测试手动给 —— 只验 service 层拦截逻辑，不验「token→converter→authority」整链。
 *  (B) Full-flow：真实启动 + 真实 token（PKCE 换发），验 OAuth2/OIDC 整链；留给 M2，
 *      需另起基类（或走 rg_validate_*.sh E2E），因为 jwt() 不支持显式指定 converter。
 *
 * 目录约定（Phase 10）：
 *   src/test/java/com/realmgate/integration/  —— Slice 集成测试（继承本类）
 *   src/test/java/com/realmgate/unit/        —— 纯逻辑单元测试（M3，不继承本类）
 *   src/test/java/com/realmgate/test/support/—— 测试基座/工具（本类）
 */
@SpringBootTest
@AutoConfigureMockMvc
@DisplayName("RealmGate 集成测试基座")
public abstract class RealmGateIntegrationTest {

    @DynamicPropertySource
    static void useLiveInfra(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> "jdbc:postgresql://localhost:5432/realmgate_test");
        registry.add("spring.datasource.username", () -> "realmgate");
        registry.add("spring.datasource.password", () -> "realmgate");
        registry.add("spring.datasource.driver-class-name", () -> "org.postgresql.Driver");
        registry.add("spring.data.redis.host", () -> "localhost");
        registry.add("spring.data.redis.port", () -> 6379);
    }

    /**
     * Slice 姿态 token 工厂：用 jwt() 注入 tenant_id + permissions，并手动给定 authorities。
     * 警告：这绕过真实 JwtAuthenticationConverter（链②），authorities 由你全权指定。
     * 用途：验「给定这些 authorities 后，controller/service 的 @PreAuthorize / hasAuthority 拦截是否正确」。
     */
    protected static JwtRequestPostProcessor tenantToken(String tenant, String... perms) {
        GrantedAuthority[] auths = Arrays.stream(perms)
                .map(SimpleGrantedAuthority::new)
                .toArray(GrantedAuthority[]::new);
        return jwt().jwt(j -> j.claim("tenant_id", tenant)
                        .claim("permissions", List.of(perms)))
                .authorities(auths);
    }

    /** 便捷：TENANT_ADMIN 视角（权限键与 UserDetailsServiceImpl 挂 TENANT_ADMIN 的对齐，按需增删）。*/
    protected static JwtRequestPostProcessor adminToken(String tenant) {
        return tenantToken(tenant,
                "tenant:read", "tenant:manage",
                "user:read", "user:manage",
                "role:read", "role:manage",
                "permission:read", "permission:manage",
                "client:read", "client:manage",
                "audit:read",
                "project:read", "project:delete");
    }

    /** 便捷：仅 project:read 的普通成员视角。*/
    protected static JwtRequestPostProcessor viewerToken(String tenant) {
        return tenantToken(tenant, "project:read");
    }
}
