package com.realmgate.integration;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.realmgate.project.Project;
import com.realmgate.project.ProjectRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Phase 4 多租户隔离验收（计划 §23 跨租户攻击测试）。
 *
 * 不依赖真实授权码流程，而是用 spring-security-test 的 jwt() post-processor 直接注入一个
 * 携带 tenant_id claim 的 JwtAuthenticationToken。该 token 会经由链②（资源服务器）的
 * TenantResolverFilter 从 JWT 中解析出 tenant_id 并写入 TenantContext，从而精准验证
 * "资源服务器层 + Repository 层"的租户隔离是否真的生效。
 *
 * 数据库用 Testcontainers 起一个干净的 PostgreSQL（不污染本地库），Flyway 自动跑 V1-V6 建 schema。
 */
@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
@Disabled("Testcontainers 本机标准 Docker 上下文连不上；待可用 Docker 上下文再启用（见 M1 测试战略）")
@DisplayName("Phase 4 跨租户隔离验收")
class TenantIsolationTest {

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

    @DynamicPropertySource
    static void configureDatasource(DynamicPropertyRegistry registry) {
        // 用容器实例的 JDBC 连接覆盖 application-dev.yml 里的本地 PG 配置（@DynamicPropertySource 优先级最高）
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("spring.datasource.driver-class-name", () -> "org.postgresql.Driver");
    }

    @Autowired
    MockMvc mockMvc;

    @Autowired
    ProjectRepository projectRepository;

    Long alphaProjectId;
    Long betaProjectId;

    @BeforeEach
    void seedTwoTenants() {
        // 每次测试重置数据，保证用例间互不干扰
        projectRepository.deleteAll();

        Project alpha = new Project();
        alpha.setName("Alpha Project");
        alpha.setSlug("alpha-p");
        alpha.setTenantId("alpha");
        alpha.setStatus("ACTIVE");
        this.alphaProjectId = projectRepository.save(alpha).getId();

        Project beta = new Project();
        beta.setName("Beta Project");
        beta.setSlug("beta-p");
        beta.setTenantId("beta");
        beta.setStatus("ACTIVE");
        this.betaProjectId = projectRepository.save(beta).getId();
    }

    @Test
    @DisplayName("场景一：alpha 租户 token 列项目，只看到自己租户的数据")
    void alphaToken_seesOnlyAlphaProjects() throws Exception {
        mockMvc.perform(get("/api/projects")
                        .with(jwt().jwt(j -> j.claim("tenant_id", "alpha"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.length()").value(1))
                .andExpect(jsonPath("$.data[0].name").value("Alpha Project"))
                .andExpect(jsonPath("$.data[0].tenantId").value("alpha"));
    }

    @Test
    @DisplayName("场景一（对称）：beta 租户 token 列项目，只看到自己租户的数据")
    void betaToken_seesOnlyBetaProjects() throws Exception {
        mockMvc.perform(get("/api/projects")
                        .with(jwt().jwt(j -> j.claim("tenant_id", "beta"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.length()").value(1))
                .andExpect(jsonPath("$.data[0].name").value("Beta Project"))
                .andExpect(jsonPath("$.data[0].tenantId").value("beta"));
    }

    @Test
    @DisplayName("场景二：伪造 X-Tenant-Id 请求头企图越权，被 TenantResolverFilter 忽略（仍只看 alpha）")
    void alphaToken_forgedHeaderIgnored() throws Exception {
        mockMvc.perform(get("/api/projects")
                        .header("X-Tenant-Id", "beta")   // 攻击者伪造请求头
                        .with(jwt().jwt(j -> j.claim("tenant_id", "alpha"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.length()").value(1))
                .andExpect(jsonPath("$.data[0].name").value("Alpha Project")); // 必须是 alpha，不能是 beta
    }

    @Test
    @DisplayName("场景三：alpha 租户用 id 直接读 beta 租户的项目，被 Repository 隔离挡回 404")
    void alphaToken_cannotReadBetaProjectById() throws Exception {
        mockMvc.perform(get("/api/projects/{id}", betaProjectId)
                        .with(jwt().jwt(j -> j.claim("tenant_id", "alpha"))))
                .andExpect(status().isOk())                 // HTTP 仍是 200（业务码承载错误）
                .andExpect(jsonPath("$.code").value(404))   // 业务码 404 = 不存在 / 不属于当前租户
                .andExpect(jsonPath("$.data").isEmpty());
    }
}
