# RealmGate

多租户 SaaS 协作平台学习项目：Vue3 前端 + Spring Cloud Gateway + Spring Boot（OAuth2/OIDC 授权与资源服务器）+ PostgreSQL + Redis。

## 本地运行（按仓库实际依赖）

前置：JDK 21、Maven、Node.js（含 npm）、Docker（用于 PostgreSQL/Redis）。

1. 启动依赖服务（仓库根目录）：

```bash
docker compose up -d
```

如需监控栈，另有 `docker-compose.monitoring.yml`。

2. 后端（Java 21 / Maven）：

```bash
cd realmgate-server
mvn spring-boot:run
```

```bash
cd realmgate-gateway
mvn spring-boot:run
```

默认大致端口：server `9000`，gateway `8080`（以各模块配置为准）。

3. 前端（Vite）：

```bash
cd realmgate-web
npm install
npm run dev
```

开发服务一般为 `5173`。

## 说明

- 根目录 `.env` 仅用于本地 Docker Compose，已被 `.gitignore` 忽略；请勿提交真实生产密钥。
- `application-dev.yml` 含本地学习用 JWT 配置，生产环境请改用环境变量/密钥管理注入。