# OIDC nonce 参数详解

> 
> `nonce` 不是 OAuth2 原生参数，是 **OIDC（OpenID Connect）** 新增参数，**只用于授权码模式 + 返回 ID Token 的场景**，核心目的：**防御 ID Token 重放攻击**。
> 经常和 OAuth2 的 `state` 一起使用，但二者职责完全不一样。

## 一、什么时候传 nonce？

发起 OIDC 授权请求（`response_type=code`，scope 必须带 `openid`），URL 参数带上 `nonce`：

```
GET https://auth-server/authorize
?response_type=code
&client_id=xxx
&redirect_uri=https://xxx/callback
&scope=openid profile email
&state=随机串   // OAuth2，防CSRF
&nonce=随机串   // OIDC，防IDToken重放
```

授权服务器收到请求后：

1. 保存这个 nonce
2. 后续颁发的 **ID Token（JWT）payload 里面会带上 `nonce` 字段**

> 
> ⚠️ 重点：
> 
> 
> - `state`：放在**回调 URL 查询参数**返回
> - `nonce`：放在 **ID Token 的 JWT 载荷里面**返回，**不会出现在 callback url 参数**

ID Token 解码示例：

```
{
  "iss": "https://auth-server",
  "sub": "用户唯一id",
  "aud": "client_id",
  "exp": 1735689600,
  "nonce": "刚才客户端传的随机字符串"
}
```

## 二、完整流程

1. 客户端（后端）生成**高熵随机 nonce 字符串**
2. 把 nonce 存入**当前用户会话 session**（后端存！不能只放前端）
3. 拼接授权 URL，带上 nonce，跳转授权服务器
4. 用户授权，回调回来拿到 `code`
5. 后端用 code 向令牌端点换取 `access_token` + `id_token`
6. **解析 id_token，取出里面的 nonce**
7. 校验：`id_token.nonce` 和 session 里保存的 nonce 是否一致
   - 不一致：拒绝，丢弃 token
   - 一致：继续使用

> 
> 校验完成后，这个 nonce 立即作废，不可重复使用。

## 三、nonce 解决什么攻击？ID Token 重放攻击

### 攻击场景（没有 nonce）

1. 攻击者提前拿到一份**合法的、还没过期的 ID Token**（比如抓包或者从别的地方窃取）
2. 诱导访问你的回调地址，把这个旧 ID Token 发给你的后端
3. 后端只校验 JWT 签名、过期时间，**无法区分这个 ID Token 是不是本次登录请求产生的**
4. 攻击者直接用这个旧 ID Token 登录冒充用户

### 有 nonce 的防护

每次登录请求都生成独一无二的 nonce，保存在用户会话。
ID Token 里必须携带本次对应的 nonce。攻击者拿到的旧 ID Token 里面的 nonce 和当前 session 里的不匹配，直接校验失败。

> 
> 一句话：**nonce 用来把 ID Token 和【当前这一次登录会话】绑定在一起。**

## 四、nonce VS state（高频面试对比）

表格

| 参数 | 归属 | 作用 | 返回位置 | 防御攻击 |
| --- | --- | --- | --- | --- |
| state | OAuth2 | 保护回调请求本身 | callback URL query 参数 | CSRF（跨站请求伪造） |
| nonce | OIDC 扩展 | 保护 ID Token | ID Token JWT payload | ID Token 重放 |

✅ 最佳实践：**OIDC 授权码模式，state 和 nonce 两个都要加**

- state：保证回调请求是你这次发起的，不是别人伪造的回调
- nonce：保证拿到的 ID Token 是本次会话颁发的，不是旧 token 重放

## 五、常见误区

1. ❌ nonce 放在回调 URL 里返回

> 
> 错！nonce 在 id_token 内部，不会出现在 callback 的 query。

2. ❌ nonce 前端生成存在 localStorage，后端不校验

> 
> 错，校验必须后端做，并且绑定服务端 session。

3. ❌ nonce 可以复用、固定值

> 
> 完全失效，每次授权请求必须全新随机字符串，长度推荐≥16 字节。

4. ❌ OAuth2 获取 access_token 场景也需要 nonce

> 
> 不需要！只有当你请求返回 `id_token`（OIDC）的时候才需要 nonce。单纯 OAuth2 获取 access_token 没有 id_token，不用 nonce。

## 六、额外补充

1. 规范要求：nonce 值不能放敏感信息，只是随机挑战串
2. nonce 不是必选参数，但**安全实践强烈建议开启**，很多身份平台（Keycloak、Auth0）推荐强制使用
3. nonce 属于一次性挑战（challenge），一次登录用完作废