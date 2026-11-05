PKCE（Proof Key for Code Exchange，读音“pixy”）是 OAuth 2.0 的一个安全扩展，专门用于缓解公共客户端在授权码流程中面临的授权码拦截攻击。它的核心思路是：在授权请求时提交一个“承诺”（code_challenge），在兑换令牌时出示“原像”（code_verifier），使授权码即使被截获也无法被攻击者单独兑换为访问令牌。

它解决什么问题
在移动应用或单页应用（SPA）中，客户端无法安全地保存 client_secret。授权码从授权端点返回时，可能经过自定义 URI scheme 或浏览器重定向。恶意应用可以注册相同的 URI scheme，从而截获授权码。如果授权服务器不要求额外证明，攻击者拿到授权码后就能直接兑换令牌。

PKCE 的防护逻辑是：授权码本身不再是兑换令牌的唯一凭据。攻击者截获了授权码，但没有与本次授权请求绑定的 code_verifier，仍然无法完成令牌兑换。

核心角色：code_verifier 与 code_challenge
code_verifier 是客户端为每一次授权请求新生成的高熵随机字符串。规范要求长度为 43–128 个字符，字符集限于 URL 安全字符（字母、数字、-、.、_、~）。推荐做法是生成 32 字节的随机序列，再做 base64url 编码，得到 43 字符的 code_verifier，以保证约 256 比特的熵，使攻击者无法猜测。

code_challenge 由 code_verifier 经过变换得到，随授权请求发送给授权服务器。它相当于对 code_verifier 的“单向承诺”：服务端可以验证持有 code_verifier 的人，但从 code_challenge 反推不出 code_verifier。

完整流程
客户端生成 code_verifier：每次授权请求都生成一个全新的随机值，并在本地暂存。

客户端计算 code_challenge，通常使用 S256 方法：BASE64URL(SHA256(ASCII(code_verifier)))。

发送授权请求：在标准的授权请求中附加 code_challenge 和 code_challenge_method=S256。授权服务器将这两个值与随后签发的授权码关联存储（通常加密嵌入授权码中，或保存在服务端）。

用户完成授权，客户端获得授权码。此时攻击者可能在重定向路径上截获该授权码。

客户端兑换令牌：在令牌端点的请求中，除了授权码，还带上原始的 code_verifier。

授权服务器校验：用同样的变换方法从收到的 code_verifier 计算出 code_challenge，与授权阶段关联存储的值比对。一致则签发令牌；不一致则返回 invalid_grant 错误。攻击者由于不知道 code_verifier，截获的授权码在这里会失效。

S256 与 plain 的关键区别
PKCE 规范定义两种变换方法：plain 和 S256。plain 直接令 code_challenge = code_verifier，这意味着授权请求中携带的值本身就是兑换凭据。如果攻击者不仅能看到授权响应，还能读取授权请求（例如通过操作系统泄露的 HTTP 日志），plain 方法就无法提供保护。S256 则只暴露 code_verifier 的哈希值，攻击者无法从哈希反推出原像，因此即使能观察授权请求，也无法获得兑换令牌所需的 code_verifier。

规范明确要求：客户端如果具备能力，就必须使用 S256；plain 仅用于无法支持 S256 的兼容性场景。安全最佳实践进一步指出，S256 是唯一不暴露 code_verifier 的方法，plain 已被视为不应在新部署中使用。