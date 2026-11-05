# RealmGate

## 多租户 SaaS 项目协作平台

### 独立项目实施与学习计划

**项目版本：v1.0**

---

# 1. 项目概述

## 1.1 项目名称

**RealmGate**

---

## 1.2 项目定位

RealmGate 是一个面向多个组织/企业的 **多租户 SaaS 项目协作平台**。

系统允许不同企业在同一个平台中独立使用：

* 用户管理
* 角色管理
* 权限管理
* 项目管理
* 任务管理
* 成员管理
* 身份认证
* OAuth2 / OIDC 登录
* Session 管理
* Token 管理

不同租户之间的数据、用户、角色、权限、项目等资源必须严格隔离。

---

## 1.3 项目核心目标

这个项目不是为了简单做出一个 CRUD 系统，也不是为了单独实现一个 OAuth Server。

项目的真正目标是：

> **通过一个完整可运行的 SaaS 项目，系统学习并实践 Java 后端、多租户、认证、授权、OAuth2/OIDC、Redis、数据库、前后端协作以及工程化开发。**

最终项目应该满足：

```text
可以启动
可以登录
可以注册/创建租户
可以创建项目
可以管理成员
可以分配角色
可以控制权限
可以进行 OAuth/OIDC 登录
可以进行多租户隔离测试
可以通过浏览器完整操作
可以 Docker 部署
可以作为简历项目展示
```

---

# 2. 为什么选择这个业务

项目业务不能太简单。

如果只做：

```text
用户
角色
权限
```

最终容易变成：

> 一个 RBAC Demo。

如果业务过于复杂，又会把大量时间消耗在业务开发上，反而无法深入学习认证和多租户。

因此 RealmGate 选择：

```text
租户
  ↓
用户
  ↓
项目
  ↓
任务
```

作为核心业务。

---

# 3. 核心业务模型

系统包含四个核心层级：

```text
Platform
   │
   └── Tenant
          │
          ├── User
          │
          ├── Role
          │
          ├── Permission
          │
          ├── Project
          │      │
          │      └── Task
          │
          └── Client
```

---

# 4. SaaS 使用场景

假设平台中存在两个公司：

```text
Alpha 公司
Beta 公司
```

Alpha：

```text
Alpha
├── 张三
├── 李四
│
├── 电商项目
│   ├── 登录功能
│   ├── 支付功能
│   └── 订单功能
│
└── 官网项目
```

Beta：

```text
Beta
├── 张三
├── 王五
│
└── CRM 项目
    ├── 客户管理
    └── 销售管理
```

Alpha 和 Beta 可以拥有：

```text
相同用户名
相同角色名
相同 Client ID
甚至相同项目名称
```

但是它们属于完全不同的数据空间。

---

# 5. 用户角色

系统至少包含以下角色。

## 5.1 平台管理员

负责整个 RealmGate 平台：

```text
创建租户
停用租户
查看租户
管理平台级配置
```

平台管理员不应该因为拥有平台权限，就自动拥有所有租户中的业务权限。

---

## 5.2 租户管理员

负责一个租户：

```text
用户管理
成员管理
角色管理
权限管理
Client 管理
项目管理
```

---

## 5.3 项目管理员

负责某些项目：

```text
查看项目
修改项目
管理任务
管理项目成员
```

---

## 5.4 普通成员

```text
查看项目
查看任务
创建任务
修改自己的任务
```

---

## 5.5 Viewer

只拥有查看能力：

```text
查看项目
查看任务
```

不能修改数据。

---

# 6. 核心业务功能

## 6.1 租户管理

平台管理员可以：

```text
创建租户
查看租户
启用租户
停用租户
```

例如：

```text
Tenant
├── id
├── name
├── slug
├── status
├── created_at
└── updated_at
```

---

# 7. 用户管理

租户管理员可以：

```text
创建用户
禁用用户
启用用户
查看用户
修改用户信息
重置密码
```

用户属于某个租户：

```text
User
├── id
├── tenant_id
├── username
├── email
├── password
├── status
├── created_at
└── updated_at
```

---

# 8. 成员管理

一个用户加入租户以后，可以成为租户成员。

管理员可以：

```text
邀请成员
移除成员
修改成员角色
查看成员
```

---

# 9. 项目管理

用户进入系统后，可以看到自己有权限访问的项目。

项目：

```text
Project
├── id
├── tenant_id
├── name
├── description
├── owner_id
├── status
├── created_at
└── updated_at
```

支持：

```text
创建项目
查询项目
修改项目
删除项目
```

---

# 10. 任务管理

项目下面存在任务：

```text
Project
   │
   ├── Task
   ├── Task
   └── Task
```

任务：

```text
Task
├── id
├── tenant_id
├── project_id
├── title
├── description
├── assignee_id
├── priority
├── status
├── created_at
└── updated_at
```

支持：

```text
创建任务
修改任务
删除任务
分配任务
修改状态
修改优先级
```

---

# 11. 前端系统

RealmGate 必须拥有完整 Web 前端。

前端不是简单写几个测试页面，而是作为真正的系统入口。

---

## 11.1 登录页面

```text
┌───────────────────────────────┐
│                               │
│          RealmGate            │
│                               │
│       多租户项目协作平台       │
│                               │
│  租户：[ Alpha           ]    │
│                               │
│  用户名：[______________]     │
│  密码：  [______________]     │
│                               │
│          [ 登录 ]             │
│                               │
│       [ 第三方登录 ]          │
│                               │
└───────────────────────────────┘
```

---

# 12. 前端页面规划

## P0 页面

### 登录

```text
/login
```

### Dashboard

```text
/dashboard
```

### 项目

```text
/projects
/projects/:id
```

### 任务

```text
/projects/:id/tasks
```

### 成员

```text
/members
```

### 用户个人设置

```text
/profile
```

---

## P1 页面

### 租户设置

```text
/settings
```

### 角色管理

```text
/roles
```

### 权限管理

```text
/permissions
```

### OAuth Client 管理

```text
/clients
```

### 审计日志

```text
/audit
```

---

# 13. Dashboard

登录之后首先进入：

```text
/dashboard
```

显示：

```text
当前租户：Alpha

项目数量：12
任务数量：68
我的任务：8
待处理任务：3
```

下面显示：

```text
最近项目
最近任务
最近操作
```

---

# 14. 项目页面

例如：

```text
项目：电商系统

┌─────────────────────────────────────┐
│ 电商系统                             │
│                                     │
│ 描述：企业电商平台                   │
│ 状态：进行中                         │
│                                     │
│ [任务] [成员] [设置]                │
└─────────────────────────────────────┘
```

任务页面可以采用简单 Kanban：

```text
TODO              DOING             DONE

登录接口           JWT认证            数据库设计
注册接口           OAuth登录          用户模块
订单接口
```

这样业务复杂度适中，同时足以验证权限。

---

# 15. 技术栈

## 15.1 后端

```text
Java 21
Spring Boot
Spring Security
Spring Authorization Server
Spring Data JPA
PostgreSQL
Redis
Flyway
JUnit 5
Testcontainers
```

---

## 15.2 前端

```text
Vue 3
TypeScript
Vite
Vue Router
Pinia
Element Plus
Axios
```

---

## 15.3 工程化

```text
Maven
Git
Docker
Docker Compose
GitHub Actions
```

---

# 16. 系统整体架构

第一阶段采用：

> **模块化单体 Modular Monolith**

而不是直接拆成大量微服务。

最终架构：

```text
                       Browser
                          │
                          ▼
                  ┌───────────────┐
                  │    Vue 3      │
                  │  Web Client   │
                  └───────┬───────┘
                          │
                          ▼
                  ┌───────────────┐
                  │    Gateway    │
                  └───────┬───────┘
                          │
                          ▼
             ┌─────────────────────────┐
             │     RealmGate Server    │
             │                         │
             │ Authentication          │
             │ Authorization           │
             │ OAuth / OIDC             │
             │ Tenant                   │
             │ User                     │
             │ Role / Permission        │
             │ Project                  │
             │ Task                     │
             │ Audit                    │
             └──────────┬───────┬──────┘
                        │       │
                        ▼       ▼
                  PostgreSQL   Redis
```

---

# 17. 为什么采用模块化单体

这个项目的目标是学习和展示 Java 后端能力。

如果一开始拆成：

```text
Auth Service
User Service
Project Service
Task Service
Permission Service
Gateway
```

会产生大量：

```text
RPC
服务注册
配置中心
分布式事务
服务部署
```

反而会让项目复杂度失控。

因此第一版：

```text
一个后端服务
+
清晰的领域模块
```

已经足够。

---

# 18. 后端模块设计

```text
com.realmgate
│
├── platform
│   └── tenant
│
├── identity
│   ├── user
│   ├── credential
│   └── session
│
├── authorization
│   ├── role
│   ├── permission
│   └── scope
│
├── client
│
├── protocol
│   ├── oauth
│   ├── oidc
│   └── jwk
│
├── project
│
├── task
│
├── member
│
├── audit
│
└── infrastructure
    ├── persistence
    ├── redis
    ├── security
    ├── exception
    └── web
```

模块之间不能随意访问 Repository。

例如：

```text
Controller
   ↓
Service
   ↓
Repository
```

而不是：

```text
Controller
   ↓
Repository
```

---

# 19. 多租户模型

多租户是 RealmGate 最核心的技术能力之一。

采用：

> **Shared Database + tenant_id**

例如：

```text
users

id
tenant_id
username
password
...
```

查询用户必须：

```sql
SELECT *
FROM users
WHERE tenant_id = ?
AND username = ?
```

而不能：

```sql
SELECT *
FROM users
WHERE username = ?
```

---

# 20. 数据隔离范围

以下数据必须进行租户隔离：

```text
User
Role
Permission
Client
Project
Task
Member
Session
Audit
```

所有租户业务表原则上包含：

```text
id
tenant_id
created_at
updated_at
```

---

# 21. 租户上下文

请求进入系统以后：

```text
HTTP Request
      ↓
TenantResolver
      ↓
TenantContext
      ↓
tenant_id
      ↓
Service
      ↓
Repository
```

例如：

```text
/api/tenants/alpha/projects
```

解析：

```text
tenant = alpha
```

然后：

```text
TenantContext.getTenantId()
```

获取当前租户。

请求结束：

```text
finally {
    TenantContext.clear();
}
```

---

# 22. 多租户安全原则

不能只依赖前端：

```text
tenant_id = alpha
```

也不能单纯相信：

```text
X-Tenant-Id: alpha
```

真正的租户身份应该和认证身份建立关系。

例如 Token：

```json
{
  "iss": "http://localhost:9000/tenants/alpha",
  "sub": "user-001",
  "aud": "realmgate-api",
  "tenant_id": "alpha"
}
```

服务器验证：

```text
请求租户
   ↓
Token issuer
   ↓
Token tenant_id
```

三者必须一致。

---

# 23. 跨租户攻击测试

例如：

```text
Alpha 用户
```

拿着 Alpha Token 请求：

```text
/api/tenants/beta/projects/001
```

必须拒绝。

同样：

```text
Alpha Token
+
Beta tenant_id
```

必须拒绝。

这类测试是整个项目的重要验收标准。

---

# 24. Authentication 认证体系

系统认证分为：

```text
Authentication
```

和：

```text
Authorization
```

两个部分。

Authentication 解决：

> 你是谁？

Authorization 解决：

> 你能做什么？

---

# 25. 用户名密码认证

第一阶段使用：

```text
用户名
+
密码
```

密码数据库中不能保存明文。

使用：

```text
PasswordEncoder
```

进行哈希。

---

# 26. Spring Security

重点学习：

```text
SecurityFilterChain
AuthenticationManager
AuthenticationProvider
UserDetails
UserDetailsService
PasswordEncoder
SecurityContext
```

登录流程：

```text
Username
Password
   ↓
Authentication
   ↓
AuthenticationManager
   ↓
AuthenticationProvider
   ↓
UserDetailsService
   ↓
PasswordEncoder
   ↓
Authentication
   ↓
SecurityContext
```

---

# 27. OAuth2 / OIDC

使用：

> Spring Authorization Server

不自己重新实现 OAuth2/OIDC 协议。

实现：

```text
Authorization Code
PKCE
OIDC
State
Nonce
ID Token
Access Token
Refresh Token
JWKS
Discovery
Logout
```

---

# 28. OAuth 登录流程

浏览器：

```text
Frontend
   ↓
/oauth2/authorize
   ↓
用户登录
   ↓
用户授权
   ↓
Authorization Code
   ↓
Frontend
   ↓
/oauth2/token
   ↓
Access Token
+
ID Token
+
Refresh Token
```

之后：

```text
Access Token
      ↓
Business API
      ↓
JWT 验证
      ↓
Authentication
      ↓
Authorization
```

---

# 29. Client

系统中的 Client 代表：

> 一个需要使用 RealmGate 身份认证能力的应用程序。

例如：

```text
realmgate-web
```

可以注册：

```text
client_id
redirect_uri
grant_type
scope
client authentication method
```

每个租户可以拥有自己的 Client。

例如：

```text
Alpha:
web-client

Beta:
web-client
```

虽然 Client ID 相同，但属于不同租户。

---

# 30. JWT

Access Token 使用 JWT。

学习：

```text
Header
Payload
Signature
```

重点理解：

```text
iss
sub
aud
exp
iat
scope
tenant_id
```

Resource Server 必须验证：

```text
Signature
Issuer
Audience
Expiration
```

---

# 31. 授权模型

RealmGate 使用：

> **RBAC + Scope + Permission**

核心关系：

```text
User
 ↓
Role
 ↓
Permission
```

OAuth：

```text
Client
 ↓
Scope
```

最终业务：

```text
Authentication
       ↓
Role / Scope
       ↓
Permission
       ↓
Resource
```

---

# 32. Permission 设计

例如：

```text
project:read
project:create
project:update
project:delete

task:read
task:create
task:update
task:delete

member:read
member:invite
member:remove

user:read
user:update
```

---

# 33. 权限示例

Tenant Admin：

```text
project:*
task:*
member:*
user:*
role:*
```

Project Manager：

```text
project:read
project:update
task:*
member:read
```

Member：

```text
project:read
task:read
task:create
task:update
```

Viewer：

```text
project:read
task:read
```

---

# 34. 前端权限控制

前端可以根据权限：

```text
project:delete
```

决定是否显示：

```text
[删除项目]
```

但是必须明确：

> 前端权限控制只是用户体验。

真正的权限判断必须发生在后端。

---

# 35. Redis

Redis 用于解决真实业务问题。

第一阶段使用：

```text
Session
OAuth State
PKCE Challenge
Token Revocation
Login Rate Limit
Permission Cache
```

Redis Key 必须包含租户信息。

例如：

```text
tenant:alpha:session:xxx

tenant:alpha:login:rate:zhangsan

tenant:alpha:token:revoked:xxx
```

---

# 36. Token 生命周期

实现：

```text
Access Token
+
Refresh Token
```

Refresh Token 使用 Rotation：

```text
RefreshToken-1
       ↓
Refresh
       ↓
RefreshToken-2
       ↓
Refresh
       ↓
RefreshToken-3
```

如果旧 Token 被重复使用：

```text
Replay
  ↓
检测
  ↓
Token Family Revocation
```

---

# 37. 登录安全

实现：

```text
登录失败次数限制
IP 限流
用户名限流
临时锁定
```

例如：

```text
5 分钟内失败 10 次
        ↓
临时锁定
```

使用 Redis 实现。

---

# 38. Audit 审计

记录安全相关操作：

```text
LOGIN_SUCCESS
LOGIN_FAILED

LOGOUT

TOKEN_REFRESH
TOKEN_REVOKED

ROLE_CHANGED
PERMISSION_CHANGED

PROJECT_CREATED
PROJECT_DELETED

MEMBER_INVITED
MEMBER_REMOVED
```

审计信息：

```text
tenant_id
actor_id
event_type
resource
ip
user_agent
created_at
```

绝对不能记录：

```text
Password
Access Token
Refresh Token
Client Secret
Private Key
OTP
```

---

# 39. Gateway

Gateway 放在核心业务完成之后。

职责：

```text
统一入口
路由
CORS
基础限流
TraceId
粗粒度认证检查
```

但是：

> Gateway 不负责最终业务权限判断。

最终：

```text
Resource Server
   ↓
JWT
   ↓
Role
   ↓
Permission
```

---

# 40. 数据库设计

核心表：

```text
tenant
user
credential
role
permission
user_role
role_permission

oauth_client

project
project_member
task

session
token
audit_event
```

---

# 41. 数据库核心约束

例如：

```text
UNIQUE(tenant_id, username)

UNIQUE(tenant_id, client_id)

UNIQUE(tenant_id, role_name)

UNIQUE(tenant_id, project_name)
```

保证：

```text
Alpha + zhangsan
Beta  + zhangsan
```

可以同时存在。

---

# 42. 数据库学习目标

通过项目深入学习：

```text
B+Tree
索引
联合索引
最左匹配
唯一索引
事务
隔离级别
MVCC
乐观锁
悲观锁
```

不单独为了考试背概念，而是结合实际业务理解。

---

# 43. Java 后端学习目标

这个项目同时承担 Java 后端能力补全任务。

重点：

```text
Java Collections
泛型
Stream
Optional
异常
IO
并发
线程
线程池
CompletableFuture
JVM 基础
```

---

# 44. Spring 学习目标

重点：

```text
IoC
DI
Bean 生命周期
Spring MVC
DispatcherServlet
Filter
Interceptor
AOP
Transaction
Validation
Exception Handler
```

尤其需要理解：

```text
HTTP
 ↓
Tomcat
 ↓
Filter
 ↓
Spring Security FilterChain
 ↓
DispatcherServlet
 ↓
Interceptor
 ↓
Controller
 ↓
Service
 ↓
Repository
```

这是整个项目非常重要的一条学习主线。

---

# 45. API 设计

统一：

```text
/api/...
```

例如：

```text
GET    /api/projects
POST   /api/projects
GET    /api/projects/{id}
PUT    /api/projects/{id}
DELETE /api/projects/{id}
```

返回：

```json
{
  "code": 0,
  "message": "success",
  "data": {}
}
```

OAuth/OIDC 标准端点遵循标准协议格式，不强制套用业务 API 响应结构。

---

# 46. 异常处理

统一处理：

```text
400
401
403
404
409
500
```

例如：

```text
401
未认证

403
已认证但没有权限

404
资源不存在

409
资源冲突
```

---

# 47. 测试体系

项目必须建立：

```text
Unit Test
Integration Test
Security Test
E2E Test
```

---

# 48. 单元测试

测试：

```text
Service
Permission
TenantResolver
Token Service
```

---

# 49. 集成测试

测试：

```text
Controller
Security
Database
Redis
```

使用：

```text
Testcontainers
```

启动真实：

```text
PostgreSQL
Redis
```

而不是全部 Mock。

---

# 50. 多租户安全测试

重点测试：

```text
Alpha User
```

不能：

```text
访问 Beta Project
访问 Beta User
访问 Beta Role
访问 Beta Task
访问 Beta Session
```

---

# 51. Token 安全测试

测试：

```text
错误 Signature
错误 Issuer
错误 Audience
过期 Token
错误 tenant_id
缺少 Scope
```

---

# 52. 权限测试

例如：

```text
Viewer
   ↓
DELETE /projects/1
   ↓
403
```

而：

```text
Project Admin
   ↓
DELETE /projects/1
   ↓
200
```

---

# 53. E2E 测试

使用浏览器进行完整测试。

例如：

```text
打开网站
 ↓
登录 Alpha
 ↓
进入 Dashboard
 ↓
创建 Project
 ↓
创建 Task
 ↓
邀请成员
 ↓
修改成员角色
 ↓
使用成员账号登录
 ↓
验证权限
```

---

# 54. 最重要的 E2E 场景

### 场景一：正常业务

```text
注册租户
 ↓
创建管理员
 ↓
登录
 ↓
创建项目
 ↓
创建任务
```

### 场景二：权限控制

```text
Viewer 登录
 ↓
尝试删除项目
 ↓
403
```

### 场景三：多租户隔离

```text
Alpha 登录
 ↓
访问 Beta 资源
 ↓
拒绝
```

---

# 55. Docker

最终所有核心组件可以通过：

```bash
docker compose up -d
```

启动。

至少包含：

```text
Frontend
Backend
PostgreSQL
Redis
```

后续：

```text
Gateway
Prometheus
Grafana
```

根据阶段加入。

---

# 56. 项目实施阶段

---

## Phase 0：工程环境

目标：

> 项目能够启动。

学习：

```text
Git
Maven
Vue
Vite
Docker
PostgreSQL
Redis
```

完成：

```text
Spring Boot
Vue
PostgreSQL
Redis
Docker Compose
```

---

## Phase 1：完成基础业务

目标：

> 先做出一个真正能用的 SaaS 页面。

完成：

```text
Tenant
User
Project
Task
Member
```

前端：

```text
Login
Dashboard
Project
Task
Member
```

暂时不引入复杂 OAuth。

---

## Phase 2：Spring Security

目标：

> 掌握真正的 Web 安全链路。

实现：

```text
SecurityFilterChain
Authentication
AuthenticationManager
AuthenticationProvider
UserDetailsService
PasswordEncoder
SecurityContext
```

完成：

```text
登录
认证
401
403
```

---

## Phase 3：OAuth2 / OIDC

目标：

> 让系统成为真正的身份认证平台。

完成：

```text
Authorization Code
PKCE
State
Nonce
OIDC
ID Token
Access Token
Refresh Token
JWKS
Discovery
Logout
```

---

## Phase 4：多租户

目标：

> 建立 Realm/Tenant 隔离。

完成：

```text
Tenant
TenantContext
TenantResolver
tenant_id
issuer
```

建立：

```text
Alpha
Beta
```

并完成跨租户测试。

---

## Phase 5：RBAC

目标：

> 建立完整授权模型。

完成：

```text
Role
Permission
Scope
User Role
Role Permission
```

实现：

```text
Tenant Admin
Project Manager
Member
Viewer
```

---

## Phase 6：Redis

目标：

> 将 Redis 应用到真正业务。

完成：

```text
Session
OAuth State
PKCE
Token Revocation
Rate Limit
Permission Cache
```

---

## Phase 7：Token 安全

目标：

> 完善身份系统。

完成：

```text
Refresh Token Rotation
Token Family
Replay Detection
JWK Rotation
Logout
Revocation
```

---

## Phase 8：管理后台

目标：

> 让系统真正具备 SaaS 平台形态。

完成：

```text
Tenant Management
User Management
Role Management
Permission Management
Client Management
Audit
```

---

## Phase 9：Gateway

目标：

> 学习企业应用统一入口。

完成：

```text
Routing
CORS
Rate Limit
TraceId
JWT 粗校验
```

---

## Phase 10：测试与工程化

完成：

```text
Unit Test
Integration Test
Security Test
E2E Test

Docker
CI
Logging
Monitoring
```

---

# 57. P0 / P1 / P2

为了防止项目失控，严格划分优先级。

## P0：必须完成

```text
Java
Spring Boot
Spring Security

Vue3
TypeScript

PostgreSQL
Redis

Tenant
User
Project
Task
Member

OAuth2
OIDC
PKCE
JWT

RBAC
Role
Permission
Scope

TenantContext
tenant_id 隔离

Session
Refresh Token

前端页面

自动化测试
E2E

Docker
```

---

## P1：完成后提升项目质量

```text
Gateway
MFA
第三方 OIDC
登录限流
账户锁定
Audit
JWK Rotation
Prometheus
Grafana
```

---

## P2：扩展学习

```text
RocketMQ
OpenSearch
PostgreSQL RLS
Schema-per-tenant
Database-per-tenant
性能测试
故障注入
水平扩容
```

---

# 58. 非目标

第一版明确不做：

```text
完整复制 Keycloak

SAML
LDAP
SCIM

复杂工作流系统

复杂消息驱动架构

Kubernetes

Service Mesh

多地域部署

复杂分布式事务

复杂策略引擎

过度复杂的业务功能
```

原因：

> RealmGate 的核心价值是多租户 + 身份认证 + 授权 + SaaS 业务，而不是技术栈数量。

---

# 59. 最终系统使用流程

最终用户从浏览器开始：

```text
打开 RealmGate
      ↓
选择/进入租户
      ↓
登录
      ↓
OAuth2 / OIDC
      ↓
获取 Token
      ↓
进入 Dashboard
      ↓
查看项目
      ↓
创建任务
      ↓
管理成员
      ↓
根据权限执行操作
      ↓
记录审计
```

---

# 60. 最终安全链路

```text
HTTP Request
      ↓
Tenant Resolution
      ↓
Spring Security FilterChain
      ↓
JWT / Session
      ↓
Authentication
      ↓
Tenant Validation
      ↓
Role
      ↓
Scope
      ↓
Permission
      ↓
Resource Authorization
      ↓
Service
      ↓
Repository
      ↓
WHERE tenant_id = ?
      ↓
PostgreSQL
```

这条链路是 RealmGate 最核心的技术主线。

---

# 61. 最终验收标准

## 产品层面

用户能够：

```text
打开网页
注册/进入租户
登录
查看 Dashboard
创建项目
创建任务
管理成员
修改角色
退出登录
```

---

## 认证层面

必须支持：

```text
用户名密码
OAuth2 Authorization Code
PKCE
OIDC
JWT
Refresh Token
Logout
```

---

## 授权层面

必须能够证明：

```text
不同角色拥有不同权限
没有权限的用户无法执行操作
```

---

## 多租户层面

必须证明：

```text
Alpha ≠ Beta
```

包括：

```text
User
Role
Permission
Client
Project
Task
Session
Audit
```

全部隔离。

---

## 安全层面

必须证明：

```text
错误 Token → 拒绝

错误 issuer → 拒绝

错误 audience → 拒绝

过期 Token → 拒绝

跨租户 → 拒绝

权限不足 → 403

未认证 → 401

Refresh Token Replay → 撤销
```

---

# 62. 最终项目结构

```text
realmgate/
│
├── backend/
│   ├── src/main/java/com/realmgate
│   │   ├── platform
│   │   ├── tenant
│   │   ├── identity
│   │   ├── authorization
│   │   ├── client
│   │   ├── protocol
│   │   ├── session
│   │   ├── project
│   │   ├── task
│   │   ├── member
│   │   ├── audit
│   │   └── infrastructure
│   │
│   └── src/test
│
├── frontend/
│   ├── src/
│   │   ├── views/
│   │   ├── components/
│   │   ├── router/
│   │   ├── stores/
│   │   └── api/
│
├── gateway/
│
├── docs/
│   ├── architecture.md
│   ├── authentication.md
│   ├── authorization.md
│   ├── multi-tenancy.md
│   └── security.md
│
├── docker-compose.yml
│
└── README.md
```

---

# 63. 简历最终定位

项目名称：

> **RealmGate —— 多租户 SaaS 项目协作平台**

技术栈：

```text
Java 21
Spring Boot
Spring Security
Spring Authorization Server
PostgreSQL
Redis
Vue3
Docker
JUnit
Testcontainers
Playwright
```

项目描述：

> 基于 Spring Boot 构建多租户 SaaS 项目协作平台，集成 OAuth2/OIDC 身份认证、JWT、RBAC 权限控制及 Redis Session/Token 管理，实现租户、用户、角色、权限、项目和任务等核心业务模块。

项目亮点：

> 1. 基于 `tenant_id + TenantContext` 实现共享数据库模式下的多租户数据隔离，并对 User、Role、Client、Project、Task 等核心资源进行租户边界控制。
>
> 2. 基于 Spring Authorization Server 实现 OAuth2 Authorization Code + PKCE、OIDC、JWT、Refresh Token 等认证能力。
>
> 3. 构建 Role、Permission、Scope 多层授权模型，实现租户管理员、项目管理员、普通成员等不同角色的细粒度权限控制。
>
> 4. 使用 Redis 实现 Session、Token Revocation、OAuth 状态、登录限流及权限缓存等功能。
>
> 5. 实现 Token issuer、tenant_id 与请求租户的一致性校验，针对跨租户访问、非法 Token、权限越权等场景建立安全测试。
>
> 6. 使用 PostgreSQL、Flyway、Testcontainers 构建数据持久化和集成测试体系，并通过 Playwright 完成关键业务 E2E 测试。
>
> 7. 使用 Vue3 + TypeScript 构建完整 Web 管理界面，并通过 Docker Compose 实现项目一键启动。

---

# 64. 项目的真正学习目标

完成项目以后，不应该只是：

> “我会 Spring Security。”

而应该达到：

```text
我知道一次 HTTP 请求是怎么进入 Spring 的
        ↓
我知道 Filter 和 SecurityFilterChain 在哪里
        ↓
我知道 Authentication 是怎么产生的
        ↓
我知道 JWT 是怎么验证的
        ↓
我知道 OAuth2 为什么需要 Authorization Server
        ↓
我知道 OIDC 为什么需要 ID Token
        ↓
我知道 Scope / Role / Permission 的区别
        ↓
我知道租户身份应该在哪里确定
        ↓
我知道数据库为什么必须带 tenant_id
        ↓
我知道 Redis 为什么也需要租户隔离
        ↓
我知道跨租户请求为什么必须拒绝
        ↓
我知道前端权限控制为什么不能代替后端授权
        ↓
我知道一个 Java Web 项目从 HTTP 到数据库的完整链路
```

---

# 65. 项目完成后的能力画像

最终 RealmGate 应该让你具备：

### Java

```text
Java 基础
集合
并发
线程池
异常
JVM 基础
```

### Spring

```text
Spring Boot
Spring MVC
IoC / DI
AOP
Transaction
Validation
```

### Security

```text
Spring Security
Authentication
Authorization
JWT
OAuth2
OIDC
PKCE
RBAC
Permission
```

### Backend

```text
REST API
PostgreSQL
Redis
Session
Cache
Transaction
```

### SaaS

```text
Tenant
TenantContext
Data Isolation
Permission Isolation
Cache Isolation
```

### Engineering

```text
Docker
Git
Testing
E2E
Logging
Monitoring
CI
```

---

# 66. 最终项目目标

RealmGate 最终不是：

```text
一个学习 OAuth2 的 Demo
```

也不是：

```text
一个简单的 CRUD 项目
```

而应该是：

```text
                RealmGate
                    │
       ┌────────────┼────────────┐
       │            │            │
   SaaS业务       IAM          多租户
       │            │            │
 Project/Task   OAuth/OIDC    Tenant Isolation
       │            │            │
       └────────────┼────────────┘
                    │
              Java Backend
                    │
          ┌─────────┴─────────┐
          │                   │
      PostgreSQL            Redis
          │                   │
          └─────────┬─────────┘
                    │
                  Vue3
                    │
                 Browser
```

最终形成一个：

> **能够运行、能够演示、能够测试、能够部署、能够解释技术原理，并且可以放进简历的完整 Java 多租户 SaaS 项目。**

项目的核心原则始终保持：

> **业务适中，安全深入，多租户做透，工程完整，而不是盲目堆技术。**
