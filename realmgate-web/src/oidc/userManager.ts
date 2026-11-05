import { UserManager, WebStorageStateStore } from 'oidc-client-ts'

// OIDC 客户端配置：对接后端 Spring Authorization Server (realmgate-server)。
// - authority 设为同源 5173：浏览器所有请求打到 5173，由 Vite 代理转发到后端 9000，
//   因此是同源请求，不会触发 CORS（也是开发期标准做法）。
//   discovery 返回的真实 issuer（如 http://localhost:9000）用于 id_token 校验，与 authority 字符串无关。
// - response_type 'code' + PKCE：公开客户端标准授权码流程，默认启用 S256 挑战
// - userStore 用 localStorage：刷新页面后仍能恢复会话
export const userManager = new UserManager({
  authority: 'http://127.0.0.1:5173',
  client_id: 'realmgate-web',
  redirect_uri: 'http://127.0.0.1:5173/callback',
  post_logout_redirect_uri: 'http://127.0.0.1:5173/login',
  response_type: 'code',
  scope: 'openid profile',
  userStore: new WebStorageStateStore({ store: window.localStorage }),
})
