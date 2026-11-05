# OAuth2 state 参数详解

> 
> state 是**授权码模式（authorization code flow）**里非常核心的安全参数，用来**防御 CSRF（跨站请求伪造）攻击**，只在授权请求阶段使用。

## 一、什么时候带上 state？

前端 / 客户端跳转到授权服务器（比如微信、GitHub、Keycloak）的授权地址时，在 URL 参数里带上 `state`：

```
GET https://auth-server.com/oauth2/authorize
?response_type=code
&client_id=xxx
&redirect_uri=https://your-site.com/callback
&scope=openid
&state=【随机字符串】 ← 重点
```

授权服务器**不会解析 state 的业务含义**，只会做一件事：**你传什么，回调的时候原样带回给你的 redirect_uri**。

回调请求（授权服务器跳回你的应用）：

```
GET https://your-site.com/callback
?code=授权码
&state=【刚才你传的随机字符串】
```

## 二、核心规则

1. **state 由客户端（你的应用）生成**，不是授权服务器生成。
2. 必须是**不可预测的随机字符串**（推荐长度≥16 字节，uuid、crypto 随机串）。
3. 生成之后，**你的服务端要保存这个 state，并且和当前用户会话绑定**（放到 session /cookie）。
4. 收到回调的时候，**第一件事校验：回调带回的 state 和当前用户会话里存的 state 是否一致**。
   - 一致：继续拿 code 换 token
   - 不一致 / 不存在：直接拒绝，终止流程

## 三、为什么必须要有 state？（CSRF 攻击场景）

假设**没有 state**：

1. 攻击者诱导用户打开一个恶意页面，触发跳转去授权服务器
2. 用户登录授权服务器，同意授权
3. 授权服务器回调 `https://your-site.com/callback?code=xxx`
4. 你的网站收到回调，直接拿 code 兑换 token，**误以为是当前用户发起的授权**

> 
> 攻击者可以利用用户已经登录授权服务器的状态，偷偷拿到授权码。

有 state 的防护逻辑：

- 正常用户：发起授权时，服务端生成随机 state，存入用户当前 session
- 攻击者构造的回调：攻击者**不知道当前用户 session 里存的 state 值**，回调回来的 state 和服务端保存的对不上 → 请求直接拦截。

> 
> ✅ state 的本质：**把 OAuth 授权请求和用户当前浏览器会话绑定**，保证回调请求是**本次会话主动发起**的，不是第三方伪造的。

## 四、常见误区

1. ❌ state 用来传业务参数

> 
> 不推荐！虽然可以带自定义信息，但 state 主要安全用途。如果要传业务数据，建议加密后放到 state，或者单独用其他机制；不要裸传敏感信息。

2. ❌ state 可以写死固定字符串

> 
> 完全失去 CSRF 防护，等于没用。每次发起授权请求**必须重新生成新随机值**。

3. ❌ 前端把 state 存在 localStorage，服务端不校验

> 
> 前端存储无法抵御 CSRF，**state 校验逻辑必须在后端做**，且必须绑定服务端 session。

4. ❌ 和 nonce 搞混（OIDC）

> 
> - `state`：OAuth2 原生参数，**防御 CSRF**，授权回调 URL 参数带回
> - `nonce`：OIDC 扩展参数，**防御 ID Token 重放攻击**，放在 id_token 里面返回

## 五、最简流程总结

1. 用户点击登录 → 后端生成随机 `state`，存入用户 session
2. 拼接授权 url，带上 state，浏览器跳转授权服务器
3. 用户授权同意 → 授权服务器回调 redirect_uri，带回 code + state
4. 后端收到回调：对比回调 state 和 session 内 state
   - 校验失败：拒绝请求
   - 校验成功：使用 code 去兑换 access_token

## 六、补充小细节

- OAuth2 规范**推荐强制使用 state**，现在大部分身份提供商（Gitee、GitHub、Keycloak）都建议必须携带。
- state 可以是任意字符串，但是 URL 参数，所以注意编码。
- 一次授权请求对应一个 state；授权完成 / 失败后，这个 state 就作废，不能复用。