<script setup lang="ts">
import { useRouter } from 'vue-router'
import { useAuthStore } from '@/store/auth'
import { userManager } from '@/oidc/userManager'

const router = useRouter()
const auth = useAuthStore()

// 缺口3：RP-Initiated Logout。先清本地状态，再通知 AS 销毁会话并回跳登录页，
// 避免“退出后再点登录直接登入”（AS 会话残留被复用）。
async function onLogout() {
  const t = auth.token
  if (t) {
    try {
      // 站1：清本地状态前先把当前 token 的 jti 写进 Redis 黑名单，
      // 让旧 token 立即失效（走 Vite 代理到后端 /api/tokens/revoke）
      await fetch('/api/tokens/revoke', {
        method: 'POST',
        headers: { Authorization: 'Bearer ' + t },
      })
    } catch {
      // 网络失败也不阻塞注销流程
    }
  }
  auth.logout()
  try {
    await userManager.signoutRedirect()
  } catch {
    router.push('/login')
  }
}
</script>

<template>
  <el-container style="height: 100vh">
    <el-aside width="200px" style="background: #1f2937; color: #fff">
      <div style="padding: 16px; font-weight: 700">RealmGate</div>
      <el-menu background-color="#1f2937" text-color="#cbd5e1" active-text-color="#fff" router>
        <el-menu-item index="/dashboard">Dashboard</el-menu-item>
        <el-menu-item index="/projects">项目</el-menu-item>
        <el-menu-item index="/members">成员</el-menu-item>
        <el-menu-item index="/profile">个人设置</el-menu-item>
        <el-menu-item-group title="管理后台">
          <el-menu-item index="/admin/tenants">租户</el-menu-item>
          <el-menu-item index="/admin/users">用户</el-menu-item>
          <el-menu-item index="/admin/roles">角色</el-menu-item>
          <el-menu-item index="/admin/permissions">权限</el-menu-item>
          <el-menu-item index="/admin/clients">客户端</el-menu-item>
          <el-menu-item index="/admin/audits">审计日志</el-menu-item>
        </el-menu-item-group>
      </el-menu>
    </el-aside>

    <el-container>
      <el-header style="display: flex; align-items: center; justify-content: space-between; border-bottom: 1px solid #e5e7eb">
        <span>当前租户：{{ auth.tenantId || '—' }}</span>
        <div>
          <span style="margin-right: 12px">{{ auth.username || '未登录' }}</span>
          <el-button size="small" @click="onLogout">退出</el-button>
        </div>
      </el-header>

      <el-main>
        <router-view />
      </el-main>
    </el-container>
  </el-container>
</template>
