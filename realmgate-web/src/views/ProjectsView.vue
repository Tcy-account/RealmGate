<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { get } from '@/api/request'

const router = useRouter()

// Phase 3 联调：真实项目列表（受租户隔离约束，只会返回当前 token 租户下的项目）。
const projects = ref<Array<Record<string, unknown>>>([])
const loading = ref(false)

onMounted(async () => {
  loading.value = true
  try {
    projects.value = await get<Array<Record<string, unknown>>>('/projects')
  } catch {
    // 错误已由响应拦截器提示
  } finally {
    loading.value = false
  }
})

function openProject(row: Record<string, unknown>) {
  router.push(`/projects/${row.id}`)
}
</script>

<template>
  <div>
    <h2>项目</h2>
    <el-table v-loading="loading" :data="projects" style="width: 100%">
      <el-table-column prop="id" label="ID" width="80" />
      <el-table-column prop="name" label="名称" />
      <el-table-column prop="tenantId" label="租户" width="120" />
      <el-table-column label="操作" width="120">
        <template #default="{ row }">
          <el-button link type="primary" size="small" @click="openProject(row)">进入项目</el-button>
        </template>
      </el-table-column>
    </el-table>
  </div>
</template>
