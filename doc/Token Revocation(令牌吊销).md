# OIDC / OAuth2 Token Revocation（令牌吊销）

一句话：**Revocation（令牌吊销）是 OAuth2 规范定义的接口，用来主动通知授权服务器（OP），作废某一枚令牌（refresh_token /access_token）。**

> 
> 注意区分：
> 
> 
> - Logout（登出）：面向用户会话，销毁 OP 的登录 session，同时内部吊销令牌。
> - Revocation（吊销）：**专门的接口，单纯用来作废指定令牌**，可以单独调用，不一定是用户登出场景。

> 
> 吊销接口地址同样可以从 Discovery 元数据获取：`revocation_endpoint`

## 一、能吊销哪些 token？

OAuth2 Revocation 规范（RFC7009）支持吊销两类令牌：

1. **refresh_token（最常用，推荐吊销）**
refresh_token 是长期有效的，**可以被撤销**。一旦吊销成功，这个 refresh_token 就再也不能用来换取新 token。
2. **access_token**
也支持调用吊销接口作废 access_token。
> 
> ⚠️ 重点坑：
> 如果 access_token 是 **JWT 自包含令牌**：
> 吊销接口只是在授权服务器的数据库里标记这个 token 作废。**JWT 本身签名和 exp 没有变化！**
> 资源服务器拿到这个 JWT，本地验签依然能通过。
> → 所以 JWT access_token 的吊销，**资源服务器必须额外做校验（黑名单 / 内省 introspect）**，否则吊销无效。
> 
> 
> 如果 access_token 是**不透明引用令牌**：吊销直接在 OP 删除记录，资源服务器调用 introspect 时就能识别已吊销。

## 二、Revocation 接口请求方式

POST 请求，`application/x-www-form-urlencoded`

```
POST revocation_endpoint
token=要吊销的令牌
&token_type_hint=refresh_token
&client_id=xxx
&client_secret=xxx
```

参数说明：

1. `token`：**必填**，你想要吊销的 token 原文（refresh_token 或者 access_token）
2. `token_type_hint`：可选，提示授权服务器这是什么类型 token：`access_token` / `refresh_token`
> 
> 只是提示，就算不传，OP 也会尝试自动识别；带上可以提升性能。
3. `client_id` / `client_secret`：**机密客户端必须携带**，用来证明调用吊销接口的是合法客户端。
> 
> 公共客户端（SPA/App，无 client_secret）：只传 client_id。

### 返回结果

- HTTP 200 OK：**不管这个 token 是否存在、是否已经吊销，都返回 200**（安全设计，防止攻击者探测 token 是否有效）
> 
> 不会返回 “令牌不存在” 或者 “吊销成功” 这类区分信息。
- 错误场景：客户端认证失败（client_secret 错误），返回 401。

## 三、什么时候调用 Revocation？典型场景

1. 用户主动登出（RP 应用除了跳 end_session_endpoint，也可以主动调用吊销接口作废 refresh_token，双保险）
2. 用户修改密码 → 吊销该用户所有 refresh_token，防止旧令牌继续刷新
3. 管理员后台强制下线用户
4. 客户端感知到 token 泄露，主动吊销
5. 账号注销

> 
> end_session_endpoint（OIDC 登出）内部很多授权服务器底层**自动执行 revocation**，但规范上是两个独立能力。
> 你也可以**不登出会话，单独调用 revocation 作废 refresh_token**。

## 四、Revocation VS Introspect（令牌内省，经常一起考）

- Revocation：**RP 通知 OP，标记令牌作废（写操作）**
- Introspect：**资源服务器向 OP 查询令牌当前是否有效（读操作）**

> 
> 如果 access_token 是 JWT，资源服务器本地验签通过之后，想知道有没有被吊销，就要调用 `/introspect`。

## 五、Revocation 和 Logout 的区别（面试高频重点）

表格

|  | Revocation 令牌吊销 | OIDC end_session_endpoint 登出 |
| --- | --- | --- |
| 作用 | 作废单个 token（refresh/access） | 销毁 OP 端用户浏览器 session，单点登出 |
| 触发方 | 客户端后台 API 调用（后台 http 请求） | 浏览器跳转（前端跳转） |
| 操作对象 | 令牌 | 用户会话 + 令牌 |
| 依赖浏览器？ | ❌ 纯后端调用，不需要浏览器 | ✅ 需要浏览器跳转清除 OP 的 Cookie |
| 所属规范 | OAuth2 RFC7009 | OIDC 规范扩展 |
| 典型返回 | 200 OK | 浏览器 302 跳转 |

举例：

- 调用 Revocation：只是废掉 refresh_token，**用户在 OP 的浏览器会话还保持登录**，仍然可以静默登录。
- 调用 end_session_endpoint：销毁 OP 登录 Cookie，用户会话失效，同时吊销令牌。

> 
> 一句话记忆：
> Revocation：废掉令牌；Logout：干掉用户在身份提供商的登录会话。

## 六、关键坑点（面试必背）

❌ 误区 1：吊销 JWT access_token 之后，JWT 本身就失效无法验签

> 
> ❌ 错误！JWT 本身自包含，签名不会变，exp 也不会变。吊销只是 OP 数据库标记作废。资源服务器如果只做本地验签，无法感知吊销。解决办法：
> 方案 A：资源服务器使用 introspect 接口远程校验 token 有效性
> 方案 B：维护 token 黑名单（Redis），资源服务器本地校验黑名单
> 方案 C：缩短 JWT access_token 有效期（5 分钟以内，尽量减少吊销窗口）

❌ 误区 2：revocation 一定需要浏览器

> 
> ❌ 纯后端 HTTP 调用，不需要浏览器。

❌ 误区 3：吊销成功会返回 success 标识

> 
> ❌ RFC 规范规定：无论 token 存在与否，成功返回 200，避免攻击者枚举探测令牌有效性。

❌ 误区 4：end_session_endpoint 等价于 revocation

> 
> ❌ 二者独立。有些 OP 实现登出的时候内部自动 revoke，但规范没有强制要求。

## 七、Discovery 和 Revocation

`/.well-known/openid-configuration` 元数据里面会包含：
`revocation_endpoint`：令牌吊销接口地址。
客户端不需要硬编码地址，通过 Discovery 获取。

## 八、极简面试背诵版

Revocation（RFC7009）是 OAuth2 令牌吊销接口，用来主动作废 access_token 或 refresh_token；地址从 Discovery 拿到，后端 POST 调用；机密客户端需要携带 client_id+client_secret；JWT 类型 access_token 吊销仅在 OP 标记作废，JWT 本身验签依旧通过，资源服务器需要 introspect 或者黑名单来识别吊销；Revocation 是作废令牌，end_session_endpoint 登出是销毁 OP 用户会话，二者相互独立。

## 顺带：Introspect 简单一句话（配套知识点）

`introspect_endpoint`：资源服务器把 token 发给 OP，查询 token 是否有效、scope、用户信息。对于 JWT access_token，如果你需要识别吊销状态，就必须走 introspect。

# 什么时候需要撤销令牌（Revocation）

核心前提：**撤销令牌的本质，是提前让还没过期的令牌失效**。

> 
> 尤其针对 `refresh_token`（长期令牌），因为 access_token 一般很短（几分钟），大多场景直接等过期就行；refresh_token 有效期很长，是撤销的重点对象。

## 一、最常见业务场景

### 1. 用户主动退出登录（Logout）

用户点退出：

- 走 OIDC 前端登出 `end_session_endpoint` 会销毁 OP 会话，很多 OP 内部自动吊销 refresh_token；
- 部分架构会**额外后台调用 revocation 接口**，主动作废 refresh_token，做双重保险。

> 
> 区别：只清应用本地 session，OP 会话还在，refresh_token 还能用，依然可以静默登录；必须吊销 refresh_token，才不能再刷新出新 token。

### 2. 用户修改密码

用户改密码后，**所有已下发的 refresh_token 全部作废**。
防止别人拿到旧的 refresh_token，继续刷新 access_token 访问接口。

> 
> 安全需求：密码变更 = 所有旧登录会话全部失效。

### 3. 管理员后台强制下线用户

后台运营踢用户下线（风控、账号异常）：

- 调用 revocation 吊销该用户所有 refresh_token；
- 如果是 JWT access_token，短时间窗口可以配合黑名单 /introspect 拦截。

### 4. 令牌泄露（安全事件）

检测到 token 泄露（日志、安全告警发现 refresh_token 被窃取）：
立刻调用 revocation，把泄露的 refresh_token 吊销，阻止攻击者拿它刷新新令牌。

### 5. 用户注销账号（账号删除）

账号注销时，吊销该用户名下全部 refresh_token，禁止继续使用。

### 6. 客户端权限变更

管理员在授权服务器修改了这个客户端的权限、scope，或者直接禁用客户端：
需要吊销已发放的 refresh_token，旧令牌不能再继续刷新。

### 7. 单点登出之外的跨端下线

比如用户在手机端下线，希望网页端也不能继续用 refresh_token 刷新 token。

> 
> 可以后端调用 revocation，吊销该用户的 refresh_token。

## 二、access_token 要不要撤销？（重点区分）

access_token 有效期很短（例如 5 分钟），**一般不需要撤销**，直接等待过期即可。
只有下面特殊场景才考虑吊销 access_token：

1. access_token 有效期被设置得很长（不推荐）；
2. 高安全系统，一旦权限变更 / 风险事件，要求令牌立刻失效；

> 
> 注意：JWT access_token 就算调用 revocation，JWT 本身签名校验仍然能通过。资源服务器必须开启 introspect 或者维护黑名单才能拦截。
> 不透明引用型 access_token：吊销后直接失效，introspect 查询会返回无效。

## 三、什么时候**不需要**撤销令牌？

1. access_token 有效期很短，风险窗口很小，直接等过期；
2. 单纯关闭浏览器标签：不需要，浏览器 cookie 清除不等于令牌需要吊销；
3. 应用本地 session 过期：本地 session 和 OP 的 refresh_token 是两套东西，本地过期不代表要吊销令牌。

## 四、面试一句话总结

**主要是长期有效的 refresh_token 需要撤销：用户改密码、账号注销、管理员踢下线、token 泄露、用户退出登录。短有效期 access_token 一般不用撤销，等待过期即可；JWT access_token 吊销仅在 OP 标记，资源服务器要 introspect 或黑名单才能识别失效。**

## 配套考点：为什么重点是 refresh_token？

access_token 是短期门禁卡，很快过期；
refresh_token 是长期续卡凭证，一旦泄露，攻击者可以持续换新 access_token，风险巨大，所以需要提供吊销能力。