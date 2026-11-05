import { fileURLToPath, URL } from 'node:url'
import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue'

// https://vitejs.dev/config/
export default defineConfig({
  plugins: [vue()],
  resolve: {
    alias: {
      '@': fileURLToPath(new URL('./src', import.meta.url)),
    },
  },
  server: {
    host: '127.0.0.1',
    port: 5173,
    // Phase 9 统一入口：所有流量（业务 API + OIDC 端点）经 Gateway(8080) 转发到后端(9000)。
    // 开发期前端(5173)与网关同源，避免 CORS；网关层做 JWT 粗校验 / 限流 / TraceId / CORS。
    proxy: {
      // 业务 API 与 OIDC 端点统一代理到网关，由网关路由到后端。
      '/api': {
        target: 'http://localhost:8080',
        changeOrigin: true,
      },
      '/oauth2': {
        target: 'http://localhost:8080',
        changeOrigin: true,
      },
      '/.well-known': {
        target: 'http://localhost:8080',
        changeOrigin: true,
      },
      '/userinfo': {
        target: 'http://localhost:8080',
        changeOrigin: true,
      },
      // 警告：绝对不要代理 '/login'！
      // SPA 自己就有 /login 路由。一旦把它代理出去，浏览器对 5173/login 的任何
      // 整页请求（地址栏直达 / F5 刷新 / 401 后 location.href='/login'）都会被转发到
      // 网关 /login 并 302 到后端登录页，整页脱离 SPA。
    },
  },
})
