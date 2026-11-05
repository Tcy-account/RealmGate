# OAuth2 机密客户端（Confidential Client） vs 公共客户端（Public Client）

> 
> OAuth2 按照**客户端能否安全保存客户端密钥 (client_secret)** 划分两类，是 OAuth2 最基础分类，直接决定你能用哪种授权流程。

## 核心定义

### 1. 机密客户端 Confidential Client

> 
> 客户端有能力**安全保管 client_secret（客户端密钥）**，密钥不会暴露给终端用户。
> 这类客户端代码运行在**服务端**，用户无法查看源码、抓包拿到密钥。

✅ 典型例子：

- 后端 Web 应用（SpringBoot/NodeJS 服务端渲染网站）
- 后台服务、微服务
- 服务端脚本程序

特点：

- 拥有 `client_id` + `client_secret`
- `client_secret` 只存在你的服务器，**永远下发不到浏览器**
- 可以使用：**授权码模式 Authorization Code Flow**、客户端凭证模式 Client Credentials Flow

### 2. 公共客户端 Public Client

> 
> 客户端**无法安全保存密钥**，代码 / 资源暴露在用户设备上，任何用户都可以查看、反编译、抓包。
> 没有安全的地方存放 `client_secret`，**不能持有 client_secret**。

✅ 典型例子：

- 纯前端 SPA（Vue/React 单页应用）
- 手机原生 App（iOS/Android）
- 桌面客户端

特点：

- 只有 `client_id`，**没有 client_secret**
- 代码 / 资源在用户设备，密钥一旦写进去就会被扒出来
- 不能使用普通授权码模式；**必须使用 PKCE 增强的授权码模式**（Authorization Code Flow with PKCE）

---

# 核心区别表

表格

| 对比项 | 机密客户端 Confidential | 公共客户端 Public |
| --- | --- | --- |
| 是否保存 client_secret | ✅ 可以安全保存 | ❌ 不能保存，没有 secret |
| 代码运行位置 | 后端服务器 | 用户设备（浏览器 / 手机） |
| 密钥是否暴露给用户 | 不会 | 会（源码 / 抓包可看到） |
| 推荐授权流程 | 授权码模式、客户端凭证模式 | **带 PKCE 的授权码模式** |
| 能否使用 PKCE | 可以（可选） | **强制 PKCE** |
| 令牌交换位置 | 后端服务去 token 端点交换 code | 后端中转 / 客户端直接交换（必须 PKCE） |

## 重点解释几个容易混淆的点

### 1. 为什么公共客户端不能用普通授权码模式（不带 PKCE）？

普通授权码模式：拿到 `code` 之后，请求 token 接口需要带上 `client_secret`。
SPA/App 代码在用户设备，如果写死 secret，攻击者直接扒取 secret，冒充客户端。

> 
> 所以公共客户端**禁止使用不带 PKCE 的授权码模式**。PKCE 用来替代 client_secret 的安全能力。

### 2. PKCE 是给谁设计的？

PKCE（Proof Key for Code Exchange）就是专门为**公共客户端**发明的。

- 公共客户端：没有 secret，用 code_verifier /code_challenge 来做证明
- 机密客户端：PKCE 属于可选增强，不是强制

### 3. 客户端凭证模式 Client Credentials Flow

**只能机密客户端使用！**
这个流程是客户端以自己身份，不是用户身份，直接拿 token，请求必须携带 client_id + client_secret。公共客户端不能用。

### 4. 面试一句话区分

- **机密客户端：代码跑在服务器，能藏好密钥，有 client_secret。**
- **公共客户端：代码跑在用户机器，藏不住密钥，无 client_secret，必须 PKCE。**

## 实战选型建议

1. 传统服务端渲染网站（Java Web）：机密客户端，授权码模式，state+nonce
2. React/Vue SPA 前端项目：公共客户端，授权码 + PKCE，state+nonce
3. 移动端 App：公共客户端，授权码 + PKCE
4. 后端定时任务、服务之间调用：机密客户端，客户端凭证模式

## 补充误区

❌ 误区：公共客户端 = 不需要安全防护

> 
> 不是！只是不能用 secret，改用 PKCE，同时依旧要校验 state、nonce、JWT 签名等。

❌ 误区：机密客户端不能用 PKCE

> 
> 规范允许机密客户端也开启 PKCE，作为额外安全加固，只是不强制。

要不要我把 OAuth2 四种流程和这两类客户端做一张对照表，方便面试背诵？

这个 client_secret 是做什么用的

# client_secret 客户端密钥

一句话概括：**client_secret 是授权服务器给机密客户端分配的密码，用来证明：这个请求是【我注册的那个合法客户端】发出来的。**

> 
> client_id：客户端的**用户名**（标识是谁，公开也没关系）
> client_secret：客户端的**密码**（机密，不能泄露）

## 一、什么时候要传 client_secret？

只有**机密客户端**在调用令牌端点 `/token` 兑换 token 的时候使用。

### 1）授权码模式（机密客户端）

拿到 code 之后，后端去换 token：

```
POST /oauth2/token
Content-Type: application/x-www-form-urlencoded

grant_type=authorization_code
&code=xxx授权码
&redirect_uri=https://xxx/callback
&client_id=my-client
&client_secret=abc123xxxx  ← 客户端密钥
```

授权服务器校验：`client_id` 和 `client_secret` 是否匹配，如果匹配，才认为是合法客户端，继续兑换 token。

### 2）客户端凭证模式 Client Credentials

这个流程本身就是客户端以自己身份获取 token，**必须携带 client_id + client_secret**

```
POST /oauth2/token
grant_type=client_credentials
&client_id=my-client
&client_secret=abc123xxxx
```

> 
> ✅ 关键点：client_secret **不是用来验证用户身份**，是验证**客户端应用本身**。
> 用户登录靠 code；应用身份靠 client_id + client_secret。

## 二、为什么公共客户端不能使用 client_secret？

公共客户端（SPA、手机 App）代码跑在用户设备上：

- 如果把 client_secret 写在前端 JS / App 源码里
- 任何人打开浏览器 F12、反编译 APP，直接拿到 secret
- 攻击者拿到 `client_id + client_secret`，就可以冒充你的应用调用令牌接口
👉 所以公共客户端**不分配 client_secret**，改用 PKCE 来完成客户端身份证明。

## 三、client_secret 能解决什么攻击？

防止**客户端冒充攻击**：
假设没有 client_secret：
任何人随便拿一个 `client_id`，截获授权码 `code`，就可以调用 `/token` 端点兑换 token。
加上 client_secret：就算攻击者偷到 code，**没有客户端密钥，无法完成兑换**。

> 
> ⚠️ 注意：client_secret 保护的是【token 兑换环节】，**不能防御 CSRF，也不能防御 ID Token 重放**，那是 state 和 nonce 的职责。

## 四、常见误区

1. ❌ client_secret 放在授权请求 `/authorize` 的 url 参数里

> 
> 不需要！`/authorize` 跳转阶段**不需要 client_secret**，只有 `/token` POST 请求才传。

2. ❌ client_secret 用来校验用户

> 
> 错！区分：
> 
> 
> - client_id + client_secret：**应用身份**
> - code / id_token：**用户身份**

3. ❌ client_secret 可以暴露在前端

> 
> 绝对不行！一旦泄露，攻击者可以冒充你的客户端。泄露后需要在授权平台重置密钥。

4. ❌ PKCE 和 client_secret 是互斥的

> 
> 机密客户端可以同时使用 client_secret + PKCE，双重加固；公共客户端只能 PKCE，无 secret。

## 五、面试极简总结

`client_secret` 是机密客户端的密钥，和 client_id 成对，**在 token 接口验证客户端应用的合法性**，证明当前请求方是注册的那个客户端，防止别人冒充应用去兑换令牌。公共客户端无法安全保存密钥，所以不使用 client_secret，改用 PKCE。