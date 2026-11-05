<script setup lang="ts">
import { computed } from 'vue'
import { useAuthStore } from '@/store/auth'

const auth = useAuthStore()

// 解析 JWT payload（access_token）。
// 后端 TenantJwtTokenCustomizer 已把 tenant_id / preferred_username / name /
// permissions / exp 等 claim 写进 token，这里直接在前端解码展示，零后端依赖。
function decodeJwtPayload(token: string): Record<string, unknown> {
  try {
    const payload = token.split('.')[1]
    if (!payload) return {}
    const base64 = payload.replace(/-/g, '+').replace(/_/g, '/')
    const json = decodeURIComponent(
      atob(base64)
        .split('')
        .map((c) => '%' + c.charCodeAt(0).toString(16).padStart(2, '0'))
        .join(''),
    )
    return JSON.parse(json)
  } catch {
    return {}
  }
}

const claims = computed<Record<string, unknown>>(() => decodeJwtPayload(auth.token))

const username = computed(() => (claims.value.preferred_username as string) || auth.username || '—')
const displayName = computed(() => (claims.value.name as string) || '—')
const tenantId = computed(() => (claims.value.tenant_id as string) || auth.tenantId || '—')
const permissions = computed<string[]>(() => {
  const p = claims.value.permissions
  return Array.isArray(p) ? (p as string[]) : []
})
const exp = computed<number | null>(() => {
  const e = claims.value.exp
  return typeof e === 'number' ? e : null
})
const expText = computed(() => (exp.value ? new Date(exp.value * 1000).toLocaleString('zh-CN') : '—'))
const expiringSoon = computed(() => exp.value != null && exp.value * 1000 - Date.now() < 5 * 60 * 1000)

// 标准 claim 也平铺出来，便于排障（iss / aud / scope / sub 等）
const rawClaims = computed(() =>
  Object.entries(claims.value).map(([k, v]) => ({
    key: k,
    value: typeof v === 'object' ? JSON.stringify(v) : String(v),
  })),
)
</script>

<template>
  <div>
    <h2>个人设置</h2>

    <el-descriptions :column="1" border style="max-width: 680px">
      <el-descriptions-item label="用户名">{{ username }}</el-descriptions-item>
      <el-descriptions-item label="姓名">{{ displayName }}</el-descriptions-item>
      <el-descriptions-item label="租户">{{ tenantId }}</el-descriptions-item>
      <el-descriptions-item label="权限">
        <el-tag v-for="p in permissions" :key="p" type="success" size="small" style="margin-right: 6px">{{ p }}</el-tag>
        <span v-if="permissions.length === 0" style="color: #94a3b8">（无）</span>
      </el-descriptions-item>
      <el-descriptions-item label="Token 过期">
        {{ expText }}
        <el-tag v-if="expiringSoon" type="warning" size="small" style="margin-left: 8px">即将过期</el-tag>
      </el-descriptions-item>
    </el-descriptions>

    <h3 style="margin-top: 24px">原始 Token Claims</h3>
    <el-table :data="rawClaims" size="small" style="max-width: 680px">
      <el-table-column prop="key" label="Claim" width="180" />
      <el-table-column prop="value" label="值" />
    </el-table>
  </div>
</template>
