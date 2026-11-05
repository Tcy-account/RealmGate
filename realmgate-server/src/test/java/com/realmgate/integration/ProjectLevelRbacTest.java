package com.realmgate.integration;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.realmgate.identity.role.UserRoleRepository;
import com.realmgate.identity.user.User;
import com.realmgate.identity.user.UserRepository;
import com.realmgate.member.Member;
import com.realmgate.member.MemberRepository;
import com.realmgate.member.MemberRole;
import com.realmgate.project.Project;
import com.realmgate.project.ProjectRepository;
import com.realmgate.task.TaskRepository;
import java.util.List;
import org.springframework.http.MediaType;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.JwtRequestPostProcessor;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * Phase 5 项目级 RBAC 运行时验收 —— 专门触发 ProjectAuthorization 第②步（按 member.role 解析）。
 *
 * RbacPermissionTest 只验了「token 全局 authorities」那条路（TENANT_ADMIN / Viewer），
 * 从未触发过 ProjectAuthorization 第②步：当前用户在某项目的 member.role → ProjectRolePermissions 映射。
 * 本项目级角色接入（PM/MEMBER/VIEWER）后，这一步才是「PM 能管自己项目、且不对其它项目越权」的真正保证。
 *
 * 用 jwt().subject(username) + 不注入 project:delete authority，强制走第②步：
 *   - username 在目标项目是 PM → 命中 project:delete → 放行；
 *   - username 不是目标项目成员 → 第②步返 false → 403；
 *   - PM of A 删 B → 在 B 中不是成员 → 403（跨项目不越权）。
 *
 * 注：本环境 Docker 走 desktop-linux(WSL2) 上下文，Testcontainers 默认 npipe 检测连不上，
 * 故直接打已在 localhost:5432 运行的真实 Postgres，使用独立库 realmgate_test 隔离开发数据。
 */
@SpringBootTest
@AutoConfigureMockMvc
@DisplayName("Phase 5 项目级 RBAC 运行时验收（ProjectAuthorization 第②步）")
class ProjectLevelRbacTest {

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
    UserRepository userRepository;

    @Autowired
    MemberRepository memberRepository;

    @Autowired
    UserRoleRepository userRoleRepository;

    @Autowired
    TaskRepository taskRepository;

    Long projectAId;
    Long projectBId;

    @BeforeEach
    void seed() {
        // 清空顺序严格遵守外键依赖：task → member → project → rbac_user_role → app_user
        // （task 引用 project，必须最先删；否则删 project 时撞 task_project_id_fkey）
        taskRepository.deleteAll();
        userRoleRepository.deleteAll();
        memberRepository.deleteAll();
        projectRepository.deleteAll();
        userRepository.deleteAll();

        User pm = new User();
        pm.setTenantId("alpha");
        pm.setUsername("alpha-pm");
        pm.setEmail("pm@alpha.test");
        pm.setPasswordHash("x");
        User savedPm = userRepository.save(pm);

        User outsider = new User();
        outsider.setTenantId("alpha");
        outsider.setUsername("alpha-outsider");
        outsider.setEmail("out@alpha.test");
        outsider.setPasswordHash("x");
        userRepository.save(outsider);

        Project a = new Project();
        a.setName("Alpha Project A");
        a.setSlug("proj-a");
        a.setTenantId("alpha");
        a.setStatus("ACTIVE");
        projectAId = projectRepository.save(a).getId();

        Project b = new Project();
        b.setName("Alpha Project B");
        b.setSlug("proj-b");
        b.setTenantId("alpha");
        b.setStatus("ACTIVE");
        projectBId = projectRepository.save(b).getId();

        // alpha-pm 是项目 A 的 PM；在项目 B 中没有任何成员记录
        Member m = new Member();
        m.setTenantId("alpha");
        m.setProjectId(projectAId);
        m.setUserId(savedPm.getId());
        m.setRole(MemberRole.PM);
        memberRepository.save(m);
    }

    // PM token：携带 tenant_id=alpha + subject=alpha-pm，但【不注入】project:delete authority
    // → 强制 ProjectAuthorization 走第②步，按 member.role 解析
    static JwtRequestPostProcessor pmToken() {
        return jwt().jwt(j -> j.claim("tenant_id", "alpha").subject("alpha-pm"));
    }

    // 合法用户但不在任何项目里 → 第②步查不到 member → 返 false → 403
    static JwtRequestPostProcessor outsiderToken() {
        return jwt().jwt(j -> j.claim("tenant_id", "alpha").subject("alpha-outsider"));
    }

    @Test
    @DisplayName("PM 删自己作为 PM 的项目 A → 200（第②步命中 project:delete）")
    void pm_canDeleteOwnProject() throws Exception {
        mockMvc.perform(delete("/api/projects/{id}", projectAId).with(pmToken()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0));
    }

    @Test
    @DisplayName("PM of A 删项目 B → 403（跨项目不越权，第②步在 B 中查不到成员）")
    void pm_cannotDeleteOtherProject() throws Exception {
        mockMvc.perform(delete("/api/projects/{id}", projectBId).with(pmToken()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(403));
    }

    @Test
    @DisplayName("非成员（合法用户但不在项目 A）删 A → 403（第②步非成员拦截）")
    void nonMember_cannotDelete() throws Exception {
        mockMvc.perform(delete("/api/projects/{id}", projectAId).with(outsiderToken()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(403));
    }

    // 两个用例验证业务层语义：运行时链② 由 SecurityConfig.rbacJwtAuthenticationConverter 把 JWT 的
    // permissions claim 解析成 authorities（如 task:create）；这里用 .authorities(...) 直接模拟该解析结果，
    // 验证 hasProjectAuthority 第①步判定：持 task:create → 放行；缺 → 转第②步、非成员 → 403。
    // 注：spring-security-test 6.x 的 jwt() 不支持显式指定 converter，无法在单元测试里端到端复现 converter 行为；
    // converter 本身正确性由 SecurityConfig 显式引用该 bean + 编译保证，最终需真实后端登录后手动确认。

    @Test
    @DisplayName("持 task:create 权限（converter 解析结果）→ 非成员也能建任务 200（业务层第①步放行）")
    void tokenPermissionsClaim_createTask_200() throws Exception {
        mockMvc.perform(post("/api/projects/{id}/tasks", projectAId)
                        .with(jwt().jwt(j -> j.claim("tenant_id", "alpha").subject("alpha-outsider"))
                                .authorities(new SimpleGrantedAuthority("task:create")))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"slug\":\"t-conv\",\"title\":\"conv\",\"status\":\"OPEN\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0));
    }

    @Test
    @DisplayName("无 task:create 权限（仅含 project:delete）→ 非成员建任务 403（业务层拦截）")
    void withoutTaskCreate_createTask_403() throws Exception {
        mockMvc.perform(post("/api/projects/{id}/tasks", projectAId)
                        .with(jwt().jwt(j -> j.claim("tenant_id", "alpha").subject("alpha-outsider"))
                                .authorities(new SimpleGrantedAuthority("project:delete")))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"slug\":\"t-scope\",\"title\":\"scope\",\"status\":\"OPEN\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(403));
    }
}
