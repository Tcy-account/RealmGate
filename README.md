# RealmGate

多租户 SaaS 协作平台学习项目：Vue3 前端 + Spring Cloud Gateway + Spring Boot（OAuth2/OIDC 授权与资源服务器）+ PostgreSQL + Redis。

## 本地运行（按仓库实际依赖）

前置：JDK 21、Maven、Node.js（含 npm）、Docker（用于 PostgreSQL/Redis）。

可选：复制环境变量模板（真实密钥只放本地 `.env`，已 gitignore）：

```bash
cp .env.example .env
```

1. 启动依赖服务（仓库根目录）：

```bash
docker compose up -d
```

如需监控栈，另有 `docker-compose.monitoring.yml`（Grafana 密码见下方环境变量）。

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

## 环境变量（本地一键与加固）

根目录 `.env.example` 列出可覆盖项；`.env` 仅本机使用，勿提交。

| 变量 | 用途 | 本地默认（仅开发） |
|------|------|-------------------|
| `POSTGRES_DB` / `POSTGRES_USER` / `POSTGRES_PASSWORD` | Docker Compose 中的 PostgreSQL | `realmgate` / `realmgate` / `realmgate` |
| `SPRING_DATASOURCE_URL` / `USERNAME` / `PASSWORD` | `application-dev.yml` 数据源 | 与上面一致，密码默认 `realmgate` |
| `SPRING_DATA_REDIS_HOST` / `PORT` / `PASSWORD` | Redis | `localhost` / `6379` / 空 |
| `REALMGATE_JWT_SECRET` | HS256 JWT 密钥（base64，解码后 ≥32 字节） | 仓库内 **占位** 开发值；共享/上线前必须替换 |
| `REALMGATE_JWT_EXPIRATION_MINUTES` | JWT 过期分钟数 | `60` |
| `GF_SECURITY_ADMIN_PASSWORD` | Grafana 管理员密码 | `changeme` |
| `PGADMIN_PASSWORD` | pgAdmin（`--profile tools`） | `admin` |

未设置环境变量时，dev 配置与 Compose 仍使用上表本地默认，本地一键跑通无需额外步骤。**切勿把这些默认值用于公网或生产。**

## 说明

- 根目录 `.env` 仅用于本地 Docker Compose / 可选覆盖，已在 `.gitignore` 忽略；请勿提交真实生产密钥。
- `application-dev.yml` 已改为从环境变量读取 JWT / DB / Redis；生产请用强随机密钥与密钥管理注入。
- 历史提交中可能仍有旧 JWT 明文；公开仓库前请确认是否需轮换密钥（当前工作树已移除原硬编码值）。