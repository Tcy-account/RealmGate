package com.realmgate.integration;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.realmgate.member.MemberRepository;
import com.realmgate.project.Project;
import com.realmgate.project.ProjectRepository;
import com.realmgate.task.TaskRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.JwtRequestPostProcessor;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;

/**
 * Phase 5 RBAC 权限消费验收（计划 §52 删除权限闭环）。
 *
 * 验证整条链路：「UserDetailsServiceImpl 装配权限 → TenantJwtTokenCustomizer 写入 permissions
 * → 资源服务器 converter 解出 authorities → ProjectService.delete 按 project:delete 拦截」。
 *
 * 不跑真实授权码流程，用 spring-security-test 的 jwt() 直接注入携带 authorities 的
 * JwtAuthenticationToken，走真实链②（资源服务器 + TenantResolverFilter）+ ProjectController/Service，
 * 验证 DELETE /api/projects/{id} 的 RBAC 拦截行为。
 *
 * 注：本环境 Docker 走 desktop-linux(WSL2) 上下文，Testcontainers 默认 npipe 检测连不上，
 * 故直接打已在 localhost:5432 运行的真实 Postgres，使用独立库 realmgate_test 隔离开发数据。
 */
@SpringBootTest
@AutoConfigureMockMvc
@DisplayName("Phase 5 RBAC 权限消费验收")
class RbacPermissionTest {

    @DynamicPropertySource
    static void useLiveTestPostgres(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> "jdbc:postgresql://localhost:5432/realmgate_test");
        registry.add("spring.datasource.username", () -> "realmgate");
        registry.add("spring.datasource.password", () -> "realmgate");
        registry.add("spring.datasource.driver-class-name", () -> "org.postgresql.Driver");
    }

    @Autowired
    MockMvc mockMvc;

    @Autowired
    ProjectRepository projectRepository;

    @Autowired
    MemberRepository memberRepository;

    @Autowired
    TaskRepository taskRepository;

    Long alphaProjectId;

    @BeforeEach
    void seedProject() {
        // 每次测试重置数据，保证用例间互不干扰。
        // 注意清理顺序：member / task 均外键指向 project，必须先清子表再清 project，
        // 否则 deleteAll(project) 触发 member_project_id_fkey / task_project_id_fkey 冲突。
        memberRepository.deleteAll();
        taskRepository.deleteAll();
        projectRepository.deleteAll();

        Project alpha = new Project();
        alpha.setName("Alpha Project");
        alpha.setSlug("alpha-p");
        alpha.setTenantId("alpha");
        alpha.setStatus("ACTIVE");
        this.alphaProjectId = projectRepository.save(alpha).getId();
    }

    // TENANT_ADMIN：携带 project:delete（以及其它权限），模拟真实 converter 从 permissions claim 解出的 authorities
    static JwtRequestPostProcessor adminToken() {
        return jwt().jwt(j -> j.claim("tenant_id", "alpha")
                        .claim("permissions", List.of("project:read", "project:delete")))
                .authorities(new SimpleGrantedAuthority("project:read"),
                        new SimpleGrantedAuthority("project:delete"));
    }

    // 仅 Viewer：只有 project:read，无 project:delete
    static JwtRequestPostProcessor viewerToken() {
        return jwt().jwt(j -> j.claim("tenant_id", "alpha")
                        .claim("permissions", List.of("project:read")))
                .authorities(new SimpleGrantedAuthority("project:read"));
    }

    @Test
    @DisplayName("TENANT_ADMIN 持 project:delete → DELETE 成功（业务码 0）")
    void admin_canDelete() throws Exception {
        mockMvc.perform(delete("/api/projects/{id}", alphaProjectId).with(adminToken()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0));
    }

    @Test
    @DisplayName("Viewer 无 project:delete → DELETE 被拦（业务码 403）")
    void viewer_cannotDelete() throws Exception {
        mockMvc.perform(delete("/api/projects/{id}", alphaProjectId).with(viewerToken()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(403));
    }

    @Test
    @DisplayName("TENANT_ADMIN 删除后，同租户再用 id 查应 404（数据确实被删）")
    void admin_deleteThenGone() throws Exception {
        mockMvc.perform(delete("/api/projects/{id}", alphaProjectId).with(adminToken()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0));

        mockMvc.perform(delete("/api/projects/{id}", alphaProjectId).with(adminToken()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(404)); // 已删，二次删除返 404
    }
}
