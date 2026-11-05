Discovery（发现机制）是 OIDC 和 OAuth 2.0 体系中解决客户端如何自动找到授权服务器（OP/AS）配置的标准方案。它让你无需在代码里硬编码 /authorize、/token、/jwks.json 等端点 URL，而是通过一个固定格式的元数据文档动态获取。

在你之前问到 JWKS 时，提到过通过 Discovery 文档的 jwks_uri 找到公钥地址，这正是 Discovery 最核心的用途之一。

🎯 它解决什么问题
没有 Discovery 时，集成一个新的身份提供方需要手动查阅文档、复制端点 URL、确认支持的算法和 Scope。一旦提供方变更端点或轮换配置，所有客户端都要改代码重新部署。

Discovery 把这个过程标准化：客户端只需知道一个稳定的 issuer URL，就能推导出配置文档的位置，一次性获取所有交互所需的元数据。 这实现了配置与代码的解耦，也让多租户、动态注册等场景成为可能。

🔗 两个相关的规范
这里有两个容易混淆但关系密切的规范：

OpenID Connect Discovery 1.0：OIDC 层专用的发现规范，定义了 /.well-known/openid-configuration 路径。

RFC 8414 (OAuth 2.0 Authorization Server Metadata)：更通用的 OAuth 2.0 授权服务器元数据规范，定义了 /.well-known/oauth-authorization-server 路径。

OIDC Discovery 可以看作 RFC 8414 的一个配置文件（Profile）。在实践中，绝大多数 OIDC 提供方会同时提供这两个端点，内容高度重叠。如果你只做纯 OAuth 2.0（不涉及 OIDC 身份层），应优先使用 RFC 8414 的端点。

🔍 发现流程与 URL 构造
核心逻辑非常简单：在 issuer 后面拼接固定的 well-known 路径。

如果 issuer 是 https://auth.example.com（无尾部斜杠），文档地址就是：

text
https://auth.example.com/.well-known/openid-configuration
如果 issuer 包含路径，例如 https://auth.example.com/tenant1，则规则是在路径部分之后插入 well-known 段（RFC 8414 的规则）：

text
https://auth.example.com/tenant1/.well-known/openid-configuration
一些较老或非严格的实现可能只支持把 well-known 放在域名根路径下，因此构造时需注意兼容性。客户端通常会先尝试标准的路径插入方式，失败后再回退到根路径。

📄 文档里的关键字段
Discovery 文档是一个 JSON 对象。以下是客户端在集成时最需要关注的字段及其在 OAuth/OIDC 流程中的作用：

字段	是否必需	作用与关联机制
issuer	必需	签发者标识。必须与客户端预期的值完全一致（包括大小写和尾部斜杠），这是发现流程的信任锚点。
authorization_endpoint	必需	授权请求的 URL，用于放置 state、PKCE 的 code_challenge 等参数。
token_endpoint	必需	兑换令牌的 URL，PKCE 的 code_verifier 在此提交。
jwks_uri	必需	JWKS 公钥集合的地址，用于验证 ID Token 或 JWT Access Token 的签名。
userinfo_endpoint	推荐	获取用户声明的端点（OIDC）。
scopes_supported	推荐	提供方支持的 scope 列表，帮助客户端正确构造 scope 参数。
response_types_supported	必需	支持的授权流程类型，如 code、id_token 等。
code_challenge_methods_supported	推荐	明确声明是否支持 PKCE 的 S256 方法。如果缺少 S256，公共客户端应拒绝使用该提供方。
token_endpoint_auth_methods_supported	推荐	机密客户端可用的认证方式，如 client_secret_basic、private_key_jwt。
id_token_signing_alg_values_supported	必需	ID Token 支持的签名算法，如 RS256、ES256。客户端必须根据此列表固定验签算法，而不是盲信 JWT header 里的 alg。
grant_types_supported	可选	支持的授权类型，如 authorization_code、refresh_token。
revocation_endpoint	可选	令牌撤销端点。
end_session_endpoint	可选	登出（结束会话）端点。
一个真实的文档示例结构如下：

json
{
  "issuer": "https://server.example.com",
  "authorization_endpoint": "https://server.example.com/connect/authorize",
  "token_endpoint": "https://server.example.com/connect/token",
  "jwks_uri": "https://server.example.com/jwks.json",
  "scopes_supported": ["openid", "profile", "email"],
  "response_types_supported": ["code"],
  "code_challenge_methods_supported": ["S256"],
  "id_token_signing_alg_values_supported": ["RS256", "ES256"]
}
⚠️ 安全实现要点
Discovery 本身是公开信息，但它是整个信任链的起点。如果 Discovery 文档被篡改，攻击者可以把所有端点指向自己的服务器，或替换 jwks_uri 用自己的公钥。因此，客户端在获取和使用文档时，必须守住以下原则：

强制 HTTPS 且校验 issuer：issuer 和文档中的所有端点 必须使用 HTTPS。获取文档后，首先校验文档中的 issuer 字段与发起发现时使用的 issuer URL 完全一致。如果不一致，直接拒绝后续所有配置，防止攻击者通过重定向或缓存投毒进行欺骗。

SSRF（服务端请求伪造）防护：如果 issuer 来自用户输入或动态配置，在发起发现请求前必须对其进行严格校验，禁止解析到私有 IP、回环地址、链路本地地址或云元数据端点。在生产环境中，绝不应跳过这项校验。

有界缓存与 TTL：Discovery 文档需要缓存，以减少请求并降低被篡改面，但缓存必须有生存时间（TTL）上限。不能永久缓存，否则提供方配置变更后客户端将长期使用过期信息。缓存键应包含完整的 issuer 和所有相关配置输入。

基于能力声明做决策，而非假设：客户端应只使用文档中明确声明支持的能力。例如，只有在 code_challenge_methods_supported 包含 S256 时，才认为该提供方支持 PKCE；只有在 id_token_signing_alg_values_supported 包含某个算法时，才允许用该算法验签。不要因为“它应该是支持的”就跳过校验。

谨慎处理重定向：Discovery 请求可能遇到 HTTP 重定向。如果跟随重定向，每一跳都必须重新执行 SSRF 防护和 HTTPS 校验，并限制重定向次数。已有 CVE 记录显示，不安全的 Discovery 重定向可导致 SSRF 和 JWKS 密钥替换攻击。

💎 总结
Discovery 是 OAuth/OIDC 生态的“配置发现协议”。它用 issuer 作为锚点，通过 well-known URL 获取一份标准 JSON，把 authorization_endpoint、token_endpoint、jwks_uri、scope 支持列表、PKCE 支持情况、签名算法等所有关键元数据一次性暴露给客户端。它的价值在于解耦与标准化；它的安全前提在于信任 issuer、强制 HTTPS、校验文档内容一致性、并做好 SSRF 防护与有界缓存。