import { defineStore } from 'pinia'
import { ref } from 'vue'

// 认证状态：token / 当前租户 / 用户名。
// 持久化到 localStorage，刷新页面不丢失（真实 Token 由 Phase 2/3 注入）。
export const useAuthStore = defineStore('auth', () => {
  const token = ref<string>(localStorage.getItem('rg_token') || '')
  const tenantId = ref<string>(localStorage.getItem('rg_tenant') || '')
  const username = ref<string>(localStorage.getItem('rg_user') || '')

  function setAuth(t: string, tenant: string, user: string) {
    token.value = t
    tenantId.value = tenant
    username.value = user
    localStorage.setItem('rg_token', t)
    localStorage.setItem('rg_tenant', tenant)
    localStorage.setItem('rg_user', user)
  }

  function logout() {
    token.value = ''
    tenantId.value = ''
    username.value = ''
    localStorage.removeItem('rg_token')
    localStorage.removeItem('rg_tenant')
    localStorage.removeItem('rg_user')
  }

  return { token, tenantId, username, setAuth, logout }
})
