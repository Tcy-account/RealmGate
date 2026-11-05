package com.realmgate.integration;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.realmgate.test.support.RealmGateIntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.server.authorization.OAuth2AuthorizationConsent;
import org.springframework.security.oauth2.server.authorization.OAuth2AuthorizationConsentService;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClientRepository;
import org.springframework.security.oauth2.server.authorization.JdbcOAuth2AuthorizationConsentService;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * M2 安全核心集成测试（一）：完整 OAuth2 + PKCE 授权码流程 + 令牌生命周期五态（AS 层）。
 *
 * 固化 rg_validate.sh 的链路：
 *   State1  授权码换 token(AT1/RT1)
 *   State2  refresh RT1 → AT2/RT2（同 family）
 *   State5  refresh RT2 → AT3/RT3（旋转生效，同 family）
 *   State3  重放 RT1 → 401（family 吊销）
 *
 * 技术说明：
 * - 走真实 AS 端点（/oauth2/authorize → /login → /oauth2/token），用 MockMvc + 跨请求传递同一
 *   MockHttpSession 维持登录态与授权请求（SAS 把授权请求存 session）。
 * - family_id 从 AT 的 JWT payload 读出（base64url 解码，不验签），用于断言旋转后 family 不变。
 * - consent 页：@BeforeEach 预置 OAuth2AuthorizationConsent（scope 须与请求一致），跳过 consent 交互；
 *   若环境无该 bean 或 principal 不匹配导致仍跳 consent，authorizeCode() 有兜底 POST /oauth2/consent。
 * - 注：AT → /api/** 的真实 RS 验签依赖硬编码 JWKS(uri=localhost:9000)，MOCK 测试上下文不在 9000，
 *   故「AT 访问受保护资源 + family 吊销后 AT 失效」的 RS 层断言留给 M4 E2E（脚本已覆盖）；
 *   本项目 RBAC 矩阵由 slice 姿态测试（见 MultiTenantRbacTest）覆盖。
 */
@SpringBootTest
@AutoConfigureMockMvc
@DisplayName("M2 OAuth2 PKCE 授权码流程 + 令牌生命周期五态（AS 层）")
class OAuth2PkceFlowTest extends RealmGateIntegrationTest {

    @Autowired
    MockMvc mockMvc;

    @Autowired
    RegisteredClientRepository registeredClientRepository;

    @Autowired
    JdbcTemplate jdbcTemplate;
    private final PasswordEncoder passwordEncoder = new BCryptPasswordEncoder();

    private static final String TENANT = "alpha";
    private static final String USER = "admin";
    private static final String PASS = "admin123";
    private static final String REDIR = "http://127.0.0.1:5173/callback";
    private static final String CLIENT = "realmgate-web";

    private String verifier;
    private String challenge;

    @BeforeEach
    void setup() throws Exception {
        seedTestUsers();
        // PKCE verifier / challenge
        byte[] bytes = new byte[32];
        new SecureRandom().nextBytes(bytes);
        verifier = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        MessageDigest md = MessageDigest.getInstance("SHA-256");
        challenge = Base64.getUrlEncoder().withoutPadding()
                .encodeToString(md.digest(verifier.getBytes(StandardCharsets.US_ASCII)));

        // 预置 consent，跳过 consent 交互页。
        // 注：SAS 在测试上下文未把 OAuth2AuthorizationConsentService 暴露为可注入 bean，这里直接内联
        // new 一个 Jdbc 实现（与 SAS 默认 ObjectMapper 一致），保证 pre-save 一定生效。
        OAuth2AuthorizationConsentService cs =
                new JdbcOAuth2AuthorizationConsentService(jdbcTemplate, registeredClientRepository);
        cs.save(OAuth2AuthorizationConsent.withId(CLIENT, USER)
                .scope("openid").scope("profile").build());
    }

    /**
     * 幂等 seed 测试登录用户。测试库可能缺 Flyway seed 数据（V6 已 success 但不重跑），
     * 故测试自包含：确保 alpha/admin(admin123) 与 beta/member(member123) 存在，供真实登录流程使用。
     */
    private void seedTestUsers() {
        String adminHash = passwordEncoder.encode("admin123");
        jdbcTemplate.update("INSERT INTO app_user (tenant_id,username,email,password_hash,display_name,status) "
                        + "VALUES (?,?,?,?,?,?) ON CONFLICT (tenant_id,username) DO NOTHING",
                "alpha", "admin", "admin@alpha.dev", adminHash, "Alpha Admin", "ACTIVE");
        String memberHash = passwordEncoder.encode("member123");
        jdbcTemplate.update("INSERT INTO app_user (tenant_id,username,email,password_hash,display_name,status) "
                        + "VALUES (?,?,?,?,?,?) ON CONFLICT (tenant_id,username) DO NOTHING",
                "beta", "member", "member@beta.dev", memberHash, "Beta Member", "ACTIVE");
    }

    @Test
    @DisplayName("PKCE 授权码换 token；refresh 旋转同 family；重放旧 RT=401")
    void pkceAndTokenLifecycle() throws Exception {
        MockHttpSession session = authenticateSession();
        String code = authorizeCode(session);

        // State1：授权码换 token（token 请求须回传与授权请求一致的 redirect_uri，否则 SAS 报 invalid_grant）
        String tok1 = exchange(session, "authorization_code",
                "code", code, "code_verifier", verifier, "redirect_uri", REDIR);
        String at1 = field(tok1, "access_token");
        String rt1 = field(tok1, "refresh_token");
        String fam1 = family(at1);
        assertNotNull(at1, "access_token 非空");
        assertNotNull(rt1, "refresh_token 非空");
        assertNotNull(fam1, "family_id 非空");

        // State2：refresh RT1 → RT2（同 family）
        String tok2 = refresh(rt1);
        String at2 = field(tok2, "access_token");
        String rt2 = field(tok2, "refresh_token");
        assertEquals(fam1, family(at2), "State2 旋转后 family 不变");

        // State5：refresh RT2 → RT3（旋转生效，仍同 family）
        String tok3 = refresh(rt2);
        assertEquals(fam1, family(field(tok3, "access_token")), "State5 两次旋转后 family 仍不变");

        // State3：重放 RT1 → 401（RefreshTokenReplayFilter 检测 family 已吊销）
        mockMvc.perform(post("/oauth2/token")
                        .param("grant_type", "refresh_token")
                        .param("refresh_token", rt1)
                        .param("client_id", CLIENT))
                .andExpect(status().isUnauthorized());
    }

    /**
     * 构造 /oauth2/authorize 的 GET 请求。
     *
     * 关键坑（已踩实）：
     *  - SAS 的 OAuth2AuthorizationEndpointFilter 解析授权请求读的是 getQueryString()（不是 getParameter）。
     *  - 若只用 .param()（进 parameter map，getQueryString 为 null）→ SAS 读不到 response_type → 400。
     *  - 若用字面 query URL 拼 scope=openid%20profile → MockMvc 对 % 二次编码，scope 变字面 "openid%20profile"
     *    单 token → invalid_scope。
     *  故这里双管齐下：.param() 把参数（含真实空格的 scope）写进 parameter map，同时用 RequestPostProcessor
     *  把同一份「真实空格 scope」写进 getQueryString()。无论 SAS 读哪条路径，scope 都是 "openid profile" 被空格拆分。
     */
    private MockHttpServletRequestBuilder authzBuilder(MockHttpSession session) {
        String qs = "response_type=code&client_id=" + CLIENT
                + "&scope=openid profile&redirect_uri=" + REDIR
                + "&code_challenge=" + challenge + "&code_challenge_method=S256&state=xyz";
        MockHttpServletRequestBuilder b = get("/oauth2/authorize")
                .param("response_type", "code")
                .param("client_id", CLIENT)
                .param("scope", "openid profile")
                .param("redirect_uri", REDIR)
                .param("code_challenge", challenge)
                .param("code_challenge_method", "S256")
                .param("state", "xyz")
                .with(req -> {
                    req.setQueryString(qs);
                    return req;
                });
        if (session != null) b.session(session);
        return b;
    }

    /** 未登录访问 /authorize 触发登录，POST /login 完成认证，返回带 SecurityContext 的 session。 */
    private MockHttpSession authenticateSession() throws Exception {
        MvcResult r0 = mockMvc.perform(authzBuilder(null)).andReturn();

        MockHttpSession session = (MockHttpSession) r0.getRequest().getSession();
        mockMvc.perform(post("/login").session(session)
                        .param("tenant", TENANT).param("username", USER).param("password", PASS))
                .andExpect(status().is3xxRedirection());
        return session;
    }

    /** 已认证 session 再次 /authorize → 302 redirect_uri?code=；遇 consent 页则兜底同意。 */
    private String authorizeCode(MockHttpSession session) throws Exception {
        MvcResult r = mockMvc.perform(authzBuilder(session))
                .andExpect(status().is3xxRedirection()).andReturn();
        String loc = r.getResponse().getHeader("Location");

        if (loc != null && loc.contains("/oauth2/consent")) {
            // 兜底：极少数情况下 consent 未预置成功，跟随 consent 页同意。
            MvcResult rc = mockMvc.perform(post("/oauth2/consent").session(session)
                            .param("scope", "openid").param("scope", "profile")
                            .param("client_id", CLIENT).param("state", "xyz")
                            .param("approve", "true"))
                    .andExpect(status().is3xxRedirection()).andReturn();
            loc = rc.getResponse().getHeader("Location");
        }
        assertNotNull(loc, "authorize 应返回重定向 Location");
        int i = loc.indexOf("code=");
        assertTrue(i > 0, "重定向应含 code 参数: " + loc);
        return loc.substring(i + 5).split("&")[0];
    }

    private String exchange(MockHttpSession session, String grant, String... extra) throws Exception {
        var req = post("/oauth2/token").param("grant_type", grant).param("client_id", CLIENT);
        if (session != null) req.session(session);
        for (int i = 0; i < extra.length; i += 2) req.param(extra[i], extra[i + 1]);
        return mockMvc.perform(req).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
    }

    private String refresh(String rt) throws Exception {
        return mockMvc.perform(post("/oauth2/token")
                        .param("grant_type", "refresh_token")
                        .param("refresh_token", rt).param("client_id", CLIENT))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
    }

    private String field(String json, String name) throws Exception {
        JsonNode n = new ObjectMapper().readTree(json);
        assertNotNull(n.get(name), name + " 字段存在");
        return n.get(name).asText();
    }

    /** 从 JWT payload 读 family_id（base64url 解码，不验签）。 */
    private String family(String jwt) throws Exception {
        String p = jwt.split("\\.")[1];
        int pad = (4 - p.length() % 4) % 4;
        if (pad > 0) p = p + "=".repeat(pad);
        byte[] decoded = Base64.getUrlDecoder().decode(p);
        return new ObjectMapper().readTree(decoded).get("family_id").asText();
    }
}
