# OIDC Logout（登出）详细讲解

> 
> OAuth2 本身**没有定义登出规范**！登出是 **OIDC 扩展**，分为两大类：
> 
> 
> 1. **RP-Initiated Logout**：由业务应用（RP，依赖方，也就是你的后端）发起登出（最常用）
> 2. OP-Initiated Logout：授权服务器（OP）发起登出（比如在 Keycloak 后台强制踢用户下线）

> 
> RP = Relying Party 依赖方（业务应用）
> OP = OpenID Provider OIDC 身份提供商（授权服务器，Keycloak/Auth0 等）

## 一、两个容易混淆的登出概念

### 1. 应用本地登出（局部登出，不是 OIDC 登出）

只是**在你的业务应用后端删掉当前用户 session、清除 cookie**。
👉 问题：**授权服务器那边用户还是登录状态**。
你只是退出当前网站，重新访问登录页，授权服务器发现用户还登录，会静默直接回调，自动登录（单点登录 SSO）。

> 
> 所以要实现**单点登出（Single Sign-Out）**，必须调用 OIDC 的`end_session_endpoint`。

### 2. OIDC RP 发起登出（end_session_endpoint，单点登出）

RP 跳转到 OP 的 `end_session_endpoint`（这个地址就是 Discovery 元数据里的`end_session_endpoint`）
作用：**在授权服务器上销毁用户的会话**。
当 OP 会话销毁后，所有依赖这个 OP 单点登录的业务应用，都不再能静默登录，实现一处登出，全部登出。

## 二、RP-Initiated Logout 完整流程（面试重点）

前提：Discovery 元数据里获取到 `end_session_endpoint`

### 1. RP（业务后端）准备跳转参数

跳转到 OP 登出地址，支持下面几个参数：

```
GET end_session_endpoint
?id_token_hint=xxx  // 【最重要】用户当前有效的id_token
&post_logout_redirect_uri=https://your-app/logout-callback
&state=random-state
```

参数说明：

1. **id_token_hint（必传推荐）**
当前用户登录拿到的那个 id_token。
OP 用它知道**是哪个用户要登出**。
> 
> 不是用来验签，只是作为提示，告诉 OP 用户身份。
2. **post_logout_redirect_uri**
可选。OP 完成在授权服务器端登出之后，**跳转回你的应用回调地址**。
⚠️ 这个地址**必须预先在 OP 客户端配置里注册**，和 redirect_uri 一样，否则拒绝跳转。
3. **state（可选，推荐带上）**
跳回 post_logout_redirect_uri 的时候原样带回，**防 CSRF**。和授权阶段 state 作用完全一样。

### 2. 时序步骤

1. 用户点击业务系统的【退出登录】
2. RP（业务后端）清除本地 session、本地登录状态
3. 构造 end_session_endpoint 跳转 URL，带上 `id_token_hint`、post_logout_redirect_uri、state
4. 浏览器跳转至 OP 的登出页面
5. OP 销毁服务器端用户的会话（清除 OP 的登录 Cookie，作废 refresh_token）
6. OP 跳回 `post_logout_redirect_uri`，带回 state
7. RP 收到回调：校验 state，完成应用最后的清理（提示用户登出成功）

> 
> ⚠️ 关键点顺序：**先清本地会话 → 再跳转到 OP 登出端点**

## 三、三种登出模式区分

1. **只清本地 session（无 OIDC 登出）**
应用登出，OP 保持登录。下次访问直接静默登录。**不是单点登出**。
2. **前端跳转 end_session_endpoint（最常用，前端 / 后端都可跳转）**
实现单点登出，销毁 OP 会话。
3. **后台通道登出 Back-Channel Logout（后端通道登出，高级）**

> 
> 前端通道登出依赖浏览器跳转，如果多端登录（手机、多个浏览器标签），其他标签页不会立刻感知到登出。
> Back-Channel：OP 在销毁用户会话之后，**主动发起 HTTP POST 请求调用 RP 后端指定的 backchannel 通知地址**，推送 logout token，让 RP 后端主动销毁该用户会话。
   - 不需要依赖浏览器
   - 适合多设备同时在线，需要实时下线场景
   - logout_token 是 JWT，后端需要验签（同样使用 JWKS 公钥校验）

> 
> Front-Channel Logout：前端通道登出，通过 iframe，多应用同时登出，现在用的少。

## 四、refresh_token 和登出的关系

当在 OP 执行登出操作时：

- OP 会撤销这个用户对应的 refresh_token
- 就算客户端还拿着旧 refresh_token，再调用 `/token` 刷新令牌，直接返回失败

> 
> access_token 一般是短期 JWT，无法主动撤销，只能等过期；refresh_token 可以被 OP 撤销，登出的核心就是作废 refresh_token + 销毁 OP 会话 cookie。

## 五、常见坑（面试高频）

❌ 误区 1：调用 end_session_endpoint 之后，不用清理本地 session

> 
> 错误！一定要先清本地会话。如果只跳 OP 登出，本地 session 还保留，用户依旧处于登录状态。

❌ 误区 2：post_logout_redirect_uri 可以随便写

> 
> 必须在客户端配置预先注册，OP 会校验，否则不会回调。

❌ 误区 3：id_token_hint 是用来做登录校验

> 
> id_token_hint 只是提示 OP 当前是哪个用户，OP 会校验它的有效性，但不是登录鉴权。

❌ 误区 4：OAuth2 原生自带单点登出

> 
> 错！OAuth2 没有登出规范，单点登出是 OIDC 扩展。

❌ 误区 5：登出后 access_token 立刻失效

> 
> 如果 access_token 是 JWT 自包含令牌，**在 exp 到期前，令牌本身验签依旧可以通过**。
> 所以业务系统一般会维护一个短期黑名单，或者 access_token 有效期设置很短（几分钟），降低风险。refresh_token 可以直接被撤销。

## 六、登出相关的 Discovery 元数据字段

在 `/.well-known/openid-configuration` 里面：

- `end_session_endpoint`：前端登出跳转地址（RP-initiated logout）
- `backchannel_logout_supported`：是否支持后端通道登出
- `backchannel_logout_session_supported`：后端登出是否携带会话信息

## 一句话面试背诵版

OIDC 的单点登出通过`end_session_endpoint`实现，属于 OIDC 扩展，OAuth2 没有登出规范；业务应用（RP）先清除本地会话，带上 id_token_hint 跳转 OP 登出端点，OP 销毁服务端会话并撤销 refresh_token，之后可回调回应用的登出回调地址；普通前端登出依赖浏览器跳转；Back-Channel 后端通道登出是 OP 主动调用业务后端接口推送登出事件，适合多设备实时下线。

## 完整串联整套 OIDC 流程（包含登出）

1. 应用配置 issuer，通过 Discovery 拉取全部端点
2. 拼接 authorize url，携带 state、nonce、scope=openid，跳转 OP
3. 用户授权回调，拿到 code
4. 调用 token_endpoint 换取 access_token、id_token、refresh_token
5. 解析 id_token header 的 kid，拉取 JWKS 公钥验签，校验 iss/aud/exp/nonce，登录成功
6. 业务请求 API，带上 access_token，资源服务器校验 scope
7. access_token 过期，使用 refresh_token 换新 token
8. 用户退出：业务应用清除本地 session，跳转 end_session_endpoint，OP 销毁会话，撤销 refresh_token，单点登出完成