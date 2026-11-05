package com.realmgate.e2e;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.realmgate.member.MemberRepository;
import com.realmgate.project.ProjectRepository;
import com.realmgate.task.TaskRepository;
import com.realmgate.test.support.RealmGateIntegrationTest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * M4 E2E（二）：关键业务旅程（计划 §53–§54 三套最重要场景），全部用真实签名 token 串起完整链路。
 *
 * 与 M2 slice 测试（jwt() 注入 authorities，只验单点拦截）不同，这里把「登录态 token → 建项目 → 建任务
 * → 邀成员 → 改角色 → 验证终态 / 权限拒绝 / 跨租户拒绝」作为一个连贯旅程跑完，验证多步状态在真实
 * converter + RevocationCheckingJwtDecoder + TenantContext + ProjectAuthorization 下的端到端一致性。
 *
 * token 用应用 JwtEncoder 真签（tenant_id + permissions claim），走原始 Authorization 头 —— 与 M4 令牌五态一致，
 * 确保 RBAC 裁决（第①步 token authorities / 第②步 member.role）是真实生效的，而非测试夹具伪造。
 *
 * 三套场景：
 *   场景一 正常业务：admin 建项目→建任务→邀成员→改角色(VIEWER)→列表回读验证终态一致。
 *   场景二 权限控制：仅 project:read 的 viewer 删项目 → 业务 403；admin 删 → 200。
 *   场景三 多租户隔离：beta 建项目；alpha token 查/删该 beta 项目 → 404（TenantContext 过滤）。
 */
@SpringBootTest
@AutoConfigureMockMvc
@DisplayName("M4 E2E 关键业务旅程（场景一/二/三）")
class E2EBusinessJourneyTest extends RealmGateIntegrationTest {

    @Autowired
    MockMvc mockMvc;

    @Autowired
    JwtEncoder jwtEncoder;

    @Autowired
    JdbcTemplate jdbcTemplate;

    @Autowired
    ProjectRepository projectRepository;

    @Autowired
    TaskRepository taskRepository;

    @Autowired
    MemberRepository memberRepository;

    /** 旅程内创建的租户级资源，@AfterEach 兜底清理，避免污染共享测试库。*/
    private final List<Long> createdProjects = new ArrayList<>();
    private final List<Long> createdUsers = new ArrayList<>();

    private static final List<String> FULL_PERMS = List.of(
            "project:create", "project:read", "project:update", "project:delete",
            "task:create", "task:read", "task:edit", "task:delete",
            "member:invite", "member:read", "member:manage", "member:remove",
            "user:read", "role:read", "permission:read", "client:read", "audit:read");

    @AfterEach
    void cleanup() {
        // 先清子表（task/member 引用 project_id），再清 project，最后清旅程自建用户，避免外键冲突
        for (Long pid : createdProjects) {
            jdbcTemplate.update("DELETE FROM task WHERE project_id = ?", pid);
            jdbcTemplate.update("DELETE FROM member WHERE project_id = ?", pid);
            jdbcTemplate.update("DELETE FROM project WHERE id = ?", pid);
        }
        for (Long uid : createdUsers) {
            jdbcTemplate.update("DELETE FROM app_user WHERE id = ?", uid);
        }
        createdProjects.clear();
        createdUsers.clear();
    }

    /** 用应用 JwtEncoder 签一张真实 JWT（tenant_id + subject + permissions claim）。*/
    private String signedToken(String tenant, String subject, List<String> perms) {
        Instant now = Instant.now();
        JwtClaimsSet claims = JwtClaimsSet.builder()
                .subject(subject)
                .claim("tenant_id", tenant)
                .claim("permissions", perms)
                .claim("jti", "rg-journey-" + System.nanoTime())
                .issuedAt(now)
                .expiresAt(now.plusSeconds(3600))
                .build();
        return jwtEncoder.encode(JwtEncoderParameters.from(claims)).getTokenValue();
    }

    /** 幂等 seed 一个 alpha 用户，返回其主键 id（用于受邀成为项目成员）。*/
    private long seedAlphaUser(String username) {
        jdbcTemplate.update(
                "INSERT INTO app_user (tenant_id,username,email,password_hash,display_name,status) "
                        + "VALUES (?,?,?,?,?,?) ON CONFLICT (tenant_id,username) DO NOTHING",
                "alpha", username, username + "@alpha.dev", "x", username, "ACTIVE");
        Long id = jdbcTemplate.queryForObject(
                "SELECT id FROM app_user WHERE tenant_id = ? AND username = ?", Long.class, "alpha", username);
        createdUsers.add(id);
        return id;
    }

    private long createProject(String bearer, String slug) throws Exception {
        MvcResult r = mockMvc.perform(post("/api/projects").header("Authorization", bearer)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"E2E Proj\",\"slug\":\"" + slug + "\",\"status\":\"ACTIVE\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andReturn();
        long pid = readId(r);
        createdProjects.add(pid);
        return pid;
    }

    private long readId(MvcResult r) throws Exception {
        JsonNode n = new ObjectMapper().readTree(r.getResponse().getContentAsString());
        return n.get("data").get("id").asLong();
    }

    @Test
    @DisplayName("场景一 正常业务：建项目→建任务→邀成员→改角色(VIEWER)，终态回读一致")
    void scenario1_normalBusiness_fullJourney() throws Exception {
        String adminTok = "Bearer " + signedToken("alpha", "e2e-admin", FULL_PERMS);
        String ts = "e2e-" + System.nanoTime();
        long invitee = seedAlphaUser("e2e-invitee-" + ts);

        // ① 建项目
        long pid = createProject(adminTok, "e2e-p-" + ts);

        // ② 建任务（需 task:create，FULL_PERMS 含）
        MvcResult tr = mockMvc.perform(post("/api/projects/{pid}/tasks", pid).header("Authorization", adminTok)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"slug\":\"e2e-t-" + ts + "\",\"title\":\"T1\",\"status\":\"OPEN\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andReturn();
        long tid = readId(tr);

        // ③ 邀成员（需 member:invite）
        MvcResult mr = mockMvc.perform(post("/api/projects/{pid}/members", pid).header("Authorization", adminTok)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"userId\":" + invitee + ",\"role\":\"MEMBER\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andReturn();
        long mid = readId(mr);

        // ④ 改角色 MEMBER → VIEWER（需 member:manage）
        mockMvc.perform(patch("/api/projects/{pid}/members/{mid}", pid, mid).header("Authorization", adminTok)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"role\":\"VIEWER\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0));

        // ⑤ 终态回读：任务列表含 tid；成员列表含 mid 且角色已变为 VIEWER
        MvcResult tasks = mockMvc.perform(get("/api/projects/{pid}/tasks", pid).header("Authorization", adminTok))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andReturn();
        assertTrue(taskPresent(tasks, tid), "任务列表应包含刚创建的 tid=" + tid);

        MvcResult members = mockMvc.perform(get("/api/projects/{pid}/members", pid).header("Authorization", adminTok))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andReturn();
        assertTrue(memberHasRole(members, mid, "VIEWER"), "成员 mid=" + mid + " 角色应已变为 VIEWER");
    }

    @Test
    @DisplayName("场景二 权限控制：仅 project:read 的 viewer 删项目 → 403；admin 删 → 200")
    void scenario2_permissionControl() throws Exception {
        String adminTok = "Bearer " + signedToken("alpha", "e2e-admin", FULL_PERMS);
        String ts = "e2e-" + System.nanoTime();
        long pid = createProject(adminTok, "e2e-perm-" + ts);

        // viewer：只带 project:read，无 project:delete → 第①步不通过，第②步非成员 → 业务 403
        String viewerTok = "Bearer " + signedToken("alpha", "e2e-viewer", List.of("project:read"));
        mockMvc.perform(delete("/api/projects/{pid}", pid).header("Authorization", viewerTok))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(403));

        // admin：持 project:delete（FULL_PERMS）→ 第①步放行 → 200
        mockMvc.perform(delete("/api/projects/{pid}", pid).header("Authorization", adminTok))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0));
        createdProjects.remove(Long.valueOf(pid)); // 已删，避免 @AfterEach 重复删（FK 已无）
    }

    @Test
    @DisplayName("场景三 多租户隔离：beta 建项目；alpha token 查/删该 beta 项目 → 404（TenantContext 过滤）")
    void scenario3_multiTenantIsolation() throws Exception {
        String alphaTok = "Bearer " + signedToken("alpha", "e2e-alpha-admin", FULL_PERMS);
        String betaTok = "Bearer " + signedToken("beta", "e2e-beta-admin", FULL_PERMS);
        String slug = "beta-proj-" + System.nanoTime();
        long betaPid = createProject(betaTok, slug); // 用 beta token 建，tenant 绑定 beta

        // alpha 查 beta 项目 → 404（findByIdAndTenantId(betaPid, "alpha") 为空）
        mockMvc.perform(get("/api/projects/{id}", betaPid).header("Authorization", alphaTok))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(404));

        // alpha 项目列表不应含有 beta 的 slug（TenantContext=alpha 过滤）
        MvcResult list = mockMvc.perform(get("/api/projects").header("Authorization", alphaTok))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andReturn();
        assertFalse(slugPresent(list, slug), "alpha 项目列表不应包含 beta 项目 slug=" + slug);

        // alpha 删 beta 项目 → 404（越权 id 不在本租户）
        mockMvc.perform(delete("/api/projects/{id}", betaPid).header("Authorization", alphaTok))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(404));

        // 用 beta 自己的 token 清理（持 project:delete → 200）
        mockMvc.perform(delete("/api/projects/{id}", betaPid).header("Authorization", betaTok))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0));
        createdProjects.remove(Long.valueOf(betaPid));
    }

    private boolean taskPresent(MvcResult r, long tid) throws Exception {
        JsonNode arr = new ObjectMapper().readTree(r.getResponse().getContentAsString()).get("data");
        if (arr == null || !arr.isArray()) return false;
        for (JsonNode n : arr) {
            if (n.get("id").asLong() == tid) return true;
        }
        return false;
    }

    private boolean memberHasRole(MvcResult r, long mid, String role) throws Exception {
        JsonNode arr = new ObjectMapper().readTree(r.getResponse().getContentAsString()).get("data");
        if (arr == null || !arr.isArray()) return false;
        for (JsonNode n : arr) {
            if (n.get("id").asLong() == mid && role.equals(n.get("role").asText())) return true;
        }
        return false;
    }

    private boolean slugPresent(MvcResult r, String slug) throws Exception {
        JsonNode arr = new ObjectMapper().readTree(r.getResponse().getContentAsString()).get("data");
        if (arr == null || !arr.isArray()) return false;
        for (JsonNode n : arr) {
            if (slug.equals(n.get("slug").asText())) return true;
        }
        return false;
    }
}
