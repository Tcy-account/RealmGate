# RealmGate 技术栈与功能总结

> 多租户 SaaS 项目协作平台（v1.0）。定位：通过一个完整可运行的项目，系统学习 Java 后端、多租户、认证授权、OAuth2/OIDC、Redis、数据库、前后端协作与工程化。

## 一、系统架构

```
Browser
   │
   ▼
realmgate-web :5173            Vue3 + TS + Vite + Pinia + Element Plus
   │
   ▼
realmgate-gateway :8080        Spring Cloud Gateway · 路由 / CORS / 限流 / TraceId / JWT 粗校验
   │
   ▼
realmgate-server :9000         AS+RS · Spring Boot 3.5 · Java 21 · SAS 1.5.1 · 多租户 · RBAC · OAuth2/OIDC
   │                         ┌─────────────────┴──────────────────┐
   ▼                         ▼                                   ▼
PostgreSQL 16            Redis 7                           Monitoring
Flyway V1–V13            session / state / rate-limit      Prometheus + Grafana
tenant_id 共享 schema     / revocation / JWK
```

- 统一入口：前端经网关 8080，业务 API `/api/**` 走 JWT 粗校验 + 限流，OAuth 端点 `/oauth2/**`、`/login`、`/logout`、`.well-known/**` 透传不限流。
- 后端 AS（授权服务器）与 RS（资源服务器）同进程（端口 9000）；网关仅做粗粒度校验，最终业务权限判定在 RS。

## 二、技术栈

### 后端（模块化单体 `realmgate-server` :9000）

| 类别 | 选型 |
|---|---|
| 语言 / 框架 | Java 21、Spring Boot 3.5.4 |
| 安全 | Spring Security 3.5 + Spring Authorization Server **1.5.1**（AS+RS 同进程） |
| 持久层 | Spring Data JPA (Hibernate) + Spring Data Redis (Lettuce) |
| 数据库 | PostgreSQL 16（共享库 + `tenant_id`） |
| 缓存 / 状态 | Redis 7（session、OAuth state、PKCE、限流、吊销、JWK） |
| 迁移 | Flyway **V1–V13**（schema 全归迁移，JPA `ddl-auto: validate`） |
| 网关模块 | `realmgate-gateway` :8080 — Spring Cloud Gateway **2025.0.0**（WebFlux / Netty） |
| 文档 / 监控 | springdoc-openapi 2.8.6、Actuator + Micrometer Prometheus |
| 工具 | Lombok、Maven 多模块 |

### 前端（`realmgate-web` :5173）

Vue 3 + TypeScript + Vite + Vue Router + Pinia + Element Plus + Axios。Vite 代理统一指向网关 8080，且不代理 `/login`。

### 基础设施 / 工程化

- Docker Compose：`postgres:16-alpine`、`redis:7-alpine`、pgAdmin4、redis-commander（`--profile tools` 才启）。
- 监控栈：Prometheus + Grafana（`docker-compose.monitoring.yml` + `monitoring/` 配置目录）。
- 测试：JUnit 5、Spring Boot Test / MockMvc、spring-security-test、Testcontainers（依赖在，实际集成测试打真实 PG `realmgate_test`）。
- GitHub Actions：CI 流水线（待做，见第四节）。

## 三、已实现功能（按领域）

### 多租户底座
- Shared Database + `tenant_id`；`TenantResolver` → `TenantContext`（finally 清理）。
- 严格隔离：JWT 携带 `tenant_id` 且 issuer 绑定，**请求租户 / Token issuer / Token tenant_id 三者须一致**；跨租户请求（Alpha 拿 Beta 资源）一律拒绝。
- 业务表联合唯一约束 `UNIQUE(tenant_id, username / client_id / role_name / project_name)`。
- 铁律：Repository 禁裸 `findById` 必带 `tenantId`；写前 `setTenantId(TenantContext.get())`；finally `clear()`。

### 业务功能
- 租户管理（平台管理员建 / 启 / 停 / 查）。
- 用户管理（建 / 禁 / 启 / 查 / 改 / 重置密码）。
- RBAC：角色 / 权限、`user_role` / `role_permission`、项目级角色 PM(10) / MEMBER(5) / VIEWER(3)。
- 项目与任务 CRUD（Kanban、优先级 / 状态）、`project_member` 成员关系。
- 成员管理：邀请 / 移除 / 改角色 / 查。
- 统一响应体 `ApiResponse<T>`（HTTP 恒 200 + `code≠0`）；仅 401 / 403 是真 HTTP；service 删 / 写须 `@Transactional`。

### 认证与授权
- 用户名密码登录（PasswordEncoder 哈希）。
- OAuth2 / OIDC 全链路：Authorization Code **+ PKCE**、State、Nonce、ID Token、JWT Access Token、Refresh Token、JWKS、Discovery、Logout（signout redirect）。
- 公开客户端（NONE + PKCE）：`client_id` 固定 `realmgate-web`，override `OAuth2TokenGenerator` + 前插刷新 provider 跳过 `code_verifier`。
- Client 注册 JDBC 化（V12）：客户端落库，client id 固定（非 UUID）。

### 令牌与密钥安全
- Refresh Token Rotation + 复用检测 → Token Family 吊销。
- RFC 7009 吊销端点 + `RevocationCheckingJwtDecoder`（验签后显式判 exp / nbf，修补 SAS 默认只验签的缺口）。
- RSA 密钥轮转：多实例 JWK 搬 Redis（`refreshIfStale` 2s 内存缓存、`SETNX` 抢占初始化、rotate 加分布式锁、本地文件兜底）。

### 网关（Phase 9）
统一入口；`/api/**` 走 JWT 粗校验 + `RequestRateLimiter`（按 `tenant_id` 或 IP 限流，演示值 burst 5 / replenish 1）；OAuth 端点透传不限流；全局 CORS、TraceId。

### 审计（Phase 8）
AOP `@AfterReturning` 写 `audit_event`：登录成败、登出、令牌刷新 / 吊销、角色 / 权限变更、项目增删、成员邀请 / 移除。明确不记密码 / 令牌 / 私钥；`AuditLog` 不继承 `TenantEntity`。

### 监控（M7）
Micrometer 暴露 `/actuator/prometheus`，Prometheus 抓取 + Grafana 看板；登录失败 / 限流计数器预注册带标签形状，`jwk_rotations` 无标签。

## 四、验证状态与待办

### 测试基线（Phase 10）
**55 测 0 失败 4 跳过**（`TenantIsolationTest` @Disabled）。拆分：
- 集成 M1 12/12、安全集成 M2 18/18、单元 M3 20/20、E2E M4 8/8、多实例 JWK-Redis 3/3、日志监控 M7 2/2。
- P0–P9 全绿，Phase 9 网关 9/9 通过。

### 明确未做 / 待办
- **M6 CI 流水线**（GitHub Actions 固化 M1–M4）：未做，前提是把仓库推到 GitHub。
- **M5 Docker 镜像化**：已砍——开发态用 IDE 直接启动 server / gateway / web，PG + Redis 仍由 compose 起。
- 集成测试名义用 Testcontainers，实际打真实 PG（`realmgate_test`），非容器隔离。

### 部署偏好
开发态 IDE 直接启动 server(:9000) / gateway(:8080) / web(:5173)，不做 backend / web 镜像化；PG + Redis 仍由 compose 起。
