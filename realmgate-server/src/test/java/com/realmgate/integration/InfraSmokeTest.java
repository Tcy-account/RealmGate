package com.realmgate.integration;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.realmgate.test.support.RealmGateIntegrationTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.MockMvc;

/**
 * M1 脚手架冒烟：仅证明基类（真实 PG/Redis + MockMvc + tenantToken）能起上下文并跑通最小请求。
 * 真正的业务断言（RBAC / 多租户 / OAuth 整链）留给 M2，由你自己写。
 */
@DisplayName("M1 脚手架冒烟：上下文 + 路由 + 安全链最小可用")
class InfraSmokeTest extends RealmGateIntegrationTest {

    @Autowired
    MockMvc mockMvc;

    @Test
    @DisplayName("上下文能起来（@SpringBootTest 起容器即过）")
    void contextLoads() {
        // 空实现：上下文加载失败会让本测试直接失败
    }

    @Test
    @DisplayName("GET /oauth2/jwks 无需认证 → 200（证明路由与 Web 层起得来）")
    void jwks_isOpen() throws Exception {
        mockMvc.perform(get("/oauth2/jwks")).andExpect(status().isOk());
    }

    @Test
    @DisplayName("无 token 访问受保护端点 → 401（证明资源服务器安全链生效）")
    void noToken_isUnauthorized() throws Exception {
        mockMvc.perform(get("/api/admin/clients")).andExpect(status().isUnauthorized());
    }
}
