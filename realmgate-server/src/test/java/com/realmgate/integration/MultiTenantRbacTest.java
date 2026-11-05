package com.realmgate.integration;

import com.realmgate.test.support.RealmGateIntegrationTest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * M2 安全核心集成测试（二）：管理后台四件套鉴权矩阵 + 多租户隔离（slice 姿态）。
 *
 * 覆盖 rg_validate.sh 之外、此前集成测试未触达的两类面：
 *  1) 管理 API 鉴权矩阵：Tenant/User/Role/Permission/Client/Audit 六个 admin GET 端点均挂
 *     @PreAuthorize("hasAuthority('xxx:read')")。TENANT_ADMIN（持有全套 :read/:manage）→ 200；
 *     仅持 project:read 的 viewer → @PreAuthorize 拒绝 → 真 HTTP 403（非业务码 403）。
 *  2) 多租户隔离：admin 控制器按 TenantContext（来自 JWT tenant_id）过滤，GET /api/admin/users
 *     只返当前租户用户。挂 beta 独有用户，alpha 管理 token 不应看到它 —— 证明跨租户不可见。
 *
 * 用 M1 基类（RealmGateIntegrationTest）的 jwt() 姿态 token 工厂，绕过真实 converter，
 * 直接验证「给定 authorities 后 @PreAuthorize 拦截 + service 层 TenantContext 隔离」是否正确。
 */
@SpringBootTest
@AutoConfigureMockMvc
@DisplayName("M2 管理 API 鉴权矩阵 + 多租户隔离（slice 姿态）")
class MultiTenantRbacTest extends RealmGateIntegrationTest {

    @Autowired
    MockMvc mockMvc;

    @Autowired
    JdbcTemplate jdbcTemplate;
    private final PasswordEncoder passwordEncoder = new BCryptPasswordEncoder();

    private static final String ALPHA = "alpha";
    private static final String BETA = "beta";

    /** 管理后台六个只读端点（对应 tenant/user/role/permission/client/audit 的 :read 权限）。 */
    private static final String[] ADMIN_READ_ENDPOINTS = {
            "/api/admin/tenants",
            "/api/admin/users",
            "/api/admin/roles",
            "/api/admin/permissions",
            "/api/admin/clients",
            "/api/admin/audits"
    };

    /** 隔离用例用的 beta 独有用户名（每轮唯一，避免与既有数据冲突）。 */
    private String betaOnlyUser;

    @BeforeEach
    void seedBetaOnlyUser() {
        betaOnlyUser = "iso-beta-" + System.nanoTime();
        jdbcTemplate.update(
                "INSERT INTO app_user (tenant_id,username,email,password_hash,display_name,status) "
                        + "VALUES (?,?,?,?,?,?) ON CONFLICT (tenant_id,username) DO NOTHING",
                BETA, betaOnlyUser, betaOnlyUser + "@beta.dev", passwordEncoder.encode("x"), "Beta Iso", "ACTIVE");
    }

    @AfterEach
    void cleanupBetaOnlyUser() {
        jdbcTemplate.update("DELETE FROM app_user WHERE username = ?", betaOnlyUser);
    }

    @Test
    @DisplayName("管理 API 矩阵：TENANT_ADMIN（含各 :read）六个端点全部 200")
    void admin_allowed_allAdminReads() throws Exception {
        for (String ep : ADMIN_READ_ENDPOINTS) {
            mockMvc.perform(get(ep).with(adminToken(ALPHA)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.code").value(0));
        }
    }

    @Test
    @DisplayName("管理 API 矩阵：仅 project:read 的 viewer 六个端点全部 403（@PreAuthorize 拦截，真 HTTP 403）")
    void viewer_denied_allAdminReads() throws Exception {
        for (String ep : ADMIN_READ_ENDPOINTS) {
            mockMvc.perform(get(ep).with(viewerToken(ALPHA)))
                    .andExpect(status().isForbidden());
        }
    }

    @Test
    @DisplayName("多租户隔离：alpha 管理 token 看不到 beta 独有用户（TenantContext 过滤生效）")
    void alpha_admin_cannot_see_beta_user() throws Exception {
        mockMvc.perform(get("/api/admin/users").with(adminToken(ALPHA)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data[?(@.username == '" + betaOnlyUser + "')]").isEmpty());
    }
}
