<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { get } from '@/api/request'
import { useAuthStore } from '@/store/auth'

const auth = useAuthStore()
const projectCount = ref<number>(0)
const loading = ref(false)

// Phase 3 联调：带 Bearer token 拉真实业务数据（request.ts 拦截器自动带 token + 处理 401）。
onMounted(async () => {
  loading.value = true
  try {
    const list = await get<unknown[]>('/projects')
    projectCount.value = list.length
  } catch {
    // 错误已由响应拦截器提示
  } finally {
    loading.value = false
  }
})
</script>

<template>
  <div>
    <h2>欢迎，{{ auth.username || '用户' }}</h2>
    <el-row :gutter="16">
      <el-col :span="6">
        <el-card>
          <div style="color: #64748b">当前租户</div>
          <div style="font-size: 20px; font-weight: 700">{{ auth.tenantId || '-' }}</div>
        </el-card>
      </el-col>
      <el-col :span="6">
        <el-card>
          <div style="color: #64748b">项目数量</div>
          <div style="font-size: 28px; font-weight: 700">{{ loading ? '...' : projectCount }}</div>
        </el-card>
      </el-col>
    </el-row>
  </div>
</template>
