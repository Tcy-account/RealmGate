# Auth2 / OIDC scope 讲解

一句话：**scope 代表「权限范围」，客户端向授权服务器申请，要获取用户哪些信息 / 操作权限；用户在授权页面看到的授权项，就是 scope。**

## 1. 基础概念

发起授权请求 `/authorize` 时带上 `scope` 参数：

```
GET /oauth2/authorize
?response_type=code
&client_id=xxx
&redirect_uri=https://xxx/callback
&scope=openid profile email
&state=xxx
```

- 多个 scope 用**空格分隔**（不是逗号！这个是高频坑点）
- `scope` 是**客户端向授权服务器提出的申请**
- 最终能不能拿到，**由用户同意**：用户可以选择批准全部，或者部分 scope（有些 IDP 支持）
- 授权成功后返回的 `access_token` 里面会带上本次被授予的 scope，后端校验 token 时可以核对

> 
> access_token payload 示例：

```
{
  "sub": "user123",
  "scope": "openid profile email",
  "exp": 17xxxxxx
}
```

## 2. OIDC 内置标准 scope（重点）

OIDC 在 OAuth2 基础上定义了几个标准 scope，**`openid` 是 OIDC 的标志性 scope**：

1. `openid`
必须！只要你想拿到 `id_token` 做用户登录（OIDC），scope 里**必须包含 openid**。
不带 openid → 就是普通 OAuth2，不会返回 id_token。
2. `profile`：获取用户基础信息，姓名、昵称、头像等
3. `email`：获取用户邮箱
4. `address`：获取地址
5. `phone`：手机号

> 
> 只有写了对应的 scope，id_token /userinfo 接口才会返回对应字段。

## 3. 自定义 scope

业务可以自己定义 scope，用来控制**接口权限**。
例子：

- `order:read`：读取订单
- `order:write`：创建 / 修改订单
- `user:delete`：删除用户

> 
> 这类自定义 scope 一般作用于 **access_token**。
> access_token 用来调用后端资源接口；资源服务器校验 access_token 里的 scope，判断是否允许访问当前接口。

## 4. 两个核心用途（面试重点）

1. **对用户：展示授权弹窗**
用户跳转授权页，会看到：应用申请获取【你的昵称、邮箱】，就是解析 scope 展示给用户。用户同意才会颁发令牌。
2. **对资源服务器：接口鉴权**
当客户端拿着 `access_token` 去访问业务 API 时，资源服务器解析 token，检查 token 里的 scope 是否包含接口要求的权限。

> 
> 例：查询订单接口要求 scope `order:read`，如果 token 里没有，直接 403 拒绝访问。

## 5. 关键区分：scope 在 id_token 和 access_token

- `id_token`：主要放身份信息（openid/profile/email），用于**登录认证**（你是谁）
- `access_token`：携带权限 scope，用于**访问资源接口**（你能做什么）

## 6. 常见误区

❌ 误区 1：scope 写多少，就一定拿到多少

> 
> 用户可以拒绝；授权服务器也可以做限制（客户端预先配置最大允许 scope）。最终生效的 scope 以返回 token 里的 scope 为准，**后端必须校验返回的 scope**，不能信任请求时传入的 scope。

❌ 误区 2：多个 scope 用逗号分隔

> 
> OAuth2 规范：**空格分隔**，`scope=profile email` ✅；`scope=profile,email` ❌

❌ 误区 3：`openid` 只是普通 scope

> 
> 不是。没有 openid，就不是 OIDC 流程，不会下发 id_token。想做单点登录、获取用户身份，必须带上 openid。

❌ 误区 4：scope 只控制用户信息

> 
> OIDC 内置 scope 控制用户信息；**自定义 scope 控制业务 API 权限**。

## 7. 极简面试总结

scope 是权限范围，客户端在授权阶段申请：

- OIDC 标准 scope（openid/profile/email）用于获取用户身份信息；
- 自定义 scope 用于业务接口权限控制；
- 多 scope 空格分隔；
- 最终生效权限以 token 携带的 scope 为准，后端要校验。

---

### 快速串一遍整套参数

`client_id`：应用 ID
`client_secret`：机密客户端的应用密码
`state`：防 CSRF
`nonce`：防止 id_token 重放（OIDC）
`scope`：申请的权限范围