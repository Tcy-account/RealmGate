# OAuth2 / OIDC 里的三种核心 Token

> 
> 重点：**OAuth2 本身只定义 `access_token`；OIDC 在 OAuth2 之上新增 `id_token`；还有一个 `refresh_token`**。
> 一共三类：

1. `access_token` 访问令牌
2. `id_token` 身份令牌（OIDC 专属，JWT）
3. `refresh_token` 刷新令牌

## 一、access_token（访问令牌）

### 作用

**代表权限，用来访问资源服务器的业务接口。**
客户端拿着它去调用后端 API，资源服务器校验这个 token，判断你有没有权限访问接口。

### 特点

1. 格式**不一定是 JWT**！两种形态：
   - JWT 格式：自包含，里面带`scope`、sub 等 claims，资源服务器本地验签即可。
   - 不透明令牌（随机字符串，引用 token）：只是一个 id，资源服务器要调用授权服务器 `/introspect` 接口远程校验。
2. 短期有效，有效期一般几分钟～1 小时
3. 可以携带多个 scope，资源服务器校验 scope 判断接口权限
4. **不做登录认证！不是用来证明用户是谁的**，只是代表你能访问哪些资源。

> 
> 一句话：**access_token = 门禁卡，凭卡开门访问资源**

## 二、id_token（身份令牌，OIDC 才有）

### 作用

**证明「用户是谁」，用于登录认证，完成单点登录。**

> 
> 只有授权请求带上 `scope=openid` 才会返回 id_token，普通 OAuth2 没有。

### 特点

1. **强制是 JWT**（规范规定）
2. 载荷包含标准 claims：`iss`签发方、`sub`用户唯一 id、`aud`受众、`exp`过期、`nonce`
3. 由授权服务器私钥签名，业务后端需要用 JWKS 公钥验签，并且校验 nonce
4. 主要给**客户端做登录使用**，拿到 id_token 就知道当前登录用户身份
5. 有效期一般和 access_token 接近，短有效期

> 
> 一句话：**id_token = 身份证，证明你这个人是谁**

⚠️ 重要区分面试考点：

> 
> ❌ 不能拿 id_token 去调用业务资源接口！
> id_token 的受众 `aud` 是你的 client_id，**资源服务器一般不认 id_token，只认 access_token**。

## 三、refresh_token（刷新令牌）

### 作用

当 `access_token` 过期后，**不用让用户重新登录，使用 refresh_token 去换取新的 access_token（也可能同时拿到新 id_token）**。

### 特点

1. 格式一般都是**不透明字符串**（不是 JWT）
2. 有效期很长（几天、几周甚至数月）
3. 不会用来调用业务 API，**只发给 token 端点 `/token` 使用**
4. 可以被授权服务器**撤销**（登出、修改密码时作废 refresh_token）
5. 不是所有流程都会返回：授权码模式可以返回；客户端凭证模式、隐式模式**没有 refresh_token**

请求刷新 token 示例：

```
POST /oauth2/token
grant_type=refresh_token
&refresh_token=xxx
&client_id=xxx
&client_secret=xxx （机密客户端）
```

> 
> 一句话：**refresh_token = 续卡凭证，门禁卡过期了，用它换新门禁卡，不用重新登录**

# 对比汇总表

表格

| token | 所属规范 | 用途 | 格式 | 有效期 | 能不能调用业务 API |
| --- | --- | --- | --- | --- | --- |
| access_token | OAuth2 | 访问业务资源，鉴权 | JWT / 不透明串 | 短（分钟级） | ✅ 可以 |
| id_token | OIDC 扩展 | 用户身份认证，登录 | **必须 JWT** | 短 | ❌ 不建议 |
| refresh_token | OAuth2 | 过期后刷新 access_token | 大多不透明串 | 很长 | ❌ 不可以 |

# 完整时序串一遍（授权码模式 OIDC）

1. 用户授权，回调拿到 code
2. 后端用 code 换取三样东西：`access_token` + `id_token` + `refresh_token`
3. 校验 id_token（验签 + nonce+iss+aud+exp）→ 完成登录，拿到用户信息
4. 客户端携带 access_token，调用业务资源 API，资源服务器校验 access_token 和 scope
5. access_token 过期 → 使用 refresh_token 请求新 access_token，无需用户重新登录
6. 用户退出登录：授权服务器撤销 refresh_token，无法再刷新令牌

# 高频面试坑

1. ❌ id_token 可以用来调用资源接口
规范设计上不是用来干这个的，aud 是客户端，资源服务器不会校验 id_token。
2. ❌ access_token 一定是 JWT
错！有引用类型的不透明 token。
3. ❌ refresh_token 永久有效
不是，可以被后台主动撤销。
4. ❌ 只要 OAuth2 就有 id_token
错，必须 scope 带 openid，走 OIDC 才有 id_token。

# 极简背诵版

- id_token（OIDC）：身份证，证明用户是谁，JWT，登录认证用
- access_token（OAuth2）：门禁卡，访问后端接口鉴权，可 JWT 可随机串
- refresh_token（OAuth2）：续卡凭证，access_token 过期换新，长有效期，不能访问 API