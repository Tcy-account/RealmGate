<script setup lang="ts">
import { reactive } from 'vue'
import { ElMessage } from 'element-plus'
import { userManager } from '@/oidc/userManager'

const form = reactive({ tenant: 'alpha', username: '', password: '' })

// Phase 3：走标准授权码 + PKCE 流程。
// 真实「用户名 / 密码 / 租户」认证由后端 SAS 登录页(/login.html)完成——
// SPA 作为公开客户端不直接处理密码、也不直接换 token（OIDC 安全规范）。
// 下方表单仅作视觉占位，点击按钮即重定向到后端登录页。
function onLogin() {
  if (!userManager) {
    ElMessage.error('OIDC 未初始化')
    return
  }
  // 缺口4：oidc-client-ts 的 nonce 无默认值且不会透传 settings，
  // 必须调用时显式传入，否则 ID Token 缺少 nonce 校验（抗重放失效）。
  userManager.signinRedirect({ nonce: crypto.randomUUID() })
}
</script>

<template>
  <div style="height: 100vh; display: flex; align-items: center; justify-content: center; background: #0f172a">
    <el-card style="width: 360px">
      <h2 style="text-align: center; margin-top: 0">RealmGate</h2>
      <p style="text-align: center; color: #64748b">多租户项目协作平台</p>
      <el-form :model="form" label-width="64px">
        <el-form-item label="租户">
          <el-input v-model="form.tenant" placeholder="如 alpha" disabled />
        </el-form-item>
        <el-form-item label="用户名">
          <el-input v-model="form.username" disabled />
        </el-form-item>
        <el-form-item label="密码">
          <el-input v-model="form.password" type="password" show-password disabled />
        </el-form-item>
        <el-button type="primary" style="width: 100%" @click="onLogin">使用 RealmGate 登录</el-button>
        <p style="text-align: center; color: #64748b; font-size: 12px; margin-bottom: 0">
          将在 RealmGate 登录页输入租户 / 用户名 / 密码
        </p>
      </el-form>
    </el-card>
  </div>
</template>
