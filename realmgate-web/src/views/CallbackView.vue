<script setup lang="ts">
import { onMounted } from 'vue'
import { useRouter } from 'vue-router'
import { userManager } from '@/oidc/userManager'
import { useAuthStore } from '@/store/auth'

const router = useRouter()
const auth = useAuthStore()

// 授权服务器带着 ?code=... 回到 /callback 时，oidc-client-ts 取出 code + verifier，
// 换回 access_token / id_token，并把 token 里的 tenant_id / preferred_username 写进前端 store。
onMounted(async () => {
  try {
    const user = await userManager.signinRedirectCallback()
    const profile = user.profile as Record<string, unknown>
    const tenant = (profile.tenant_id as string) || ''
    const username = (profile.preferred_username as string) || user.profile.sub || ''
    auth.setAuth(user.access_token, tenant, username)
    router.replace('/dashboard')
  } catch {
    router.replace('/login')
  }
})
</script>

<template>
  <div style="height: 100vh; display: flex; align-items: center; justify-content: center; color: #64748b">
    登录中…
  </div>
</template>
