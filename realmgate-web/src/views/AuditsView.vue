<script setup lang="ts">
import { ref, onMounted } from 'vue'
import { get } from '@/api/request'

interface AuditLog {
  id: number
  tenantId: string
  actor: string
  actorId: number | null
  action: string
  resourceType: string
  resourceId: string | null
  detail: string | null
  ipAddress: string | null
  createdAt: string
}

const logs = ref<AuditLog[]>([])
const loading = ref(false)
const actionFilter = ref('')
const resourceFilter = ref('')

const actionOptions = ['CREATE', 'UPDATE', 'DELETE']
const resourceOptions = ['TENANT', 'USER', 'ROLE', 'PERMISSION', 'CLIENT']

async function load() {
  loading.value = true
  try {
    const params: Record<string, unknown> = {}
    if (actionFilter.value) params.action = actionFilter.value
    if (resourceFilter.value) params.resourceType = resourceFilter.value
    logs.value = await get<AuditLog[]>('/admin/audits', params)
  } finally {
    loading.value = false
  }
}

onMounted(load)
</script>

<template>
  <div class="audit-view">
    <el-card>
      <template #header>
        <div class="header">
          <span>审计日志</span>
          <div class="filters">
            <el-select
              v-model="actionFilter"
              placeholder="动作"
              clearable
              style="width: 140px"
              @change="load"
            >
              <el-option v-for="a in actionOptions" :key="a" :label="a" :value="a" />
            </el-select>
            <el-select
              v-model="resourceFilter"
              placeholder="资源类型"
              clearable
              style="width: 160px"
              @change="load"
            >
              <el-option v-for="r in resourceOptions" :key="r" :label="r" :value="r" />
            </el-select>
            <el-button type="primary" @click="load">刷新</el-button>
          </div>
        </div>
      </template>

      <el-table :data="logs" v-loading="loading" stripe border height="600">
        <el-table-column prop="createdAt" label="时间" width="200" />
        <el-table-column prop="actor" label="操作人" width="140" />
        <el-table-column prop="action" label="动作" width="100">
          <template #default="{ row }">
            <el-tag
              :type="
                row.action === 'DELETE'
                  ? 'danger'
                  : row.action === 'CREATE'
                    ? 'success'
                    : 'warning'
              "
            >
              {{ row.action }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="resourceType" label="资源类型" width="120" />
        <el-table-column prop="resourceId" label="资源ID" width="120" />
        <el-table-column prop="detail" label="详情" show-overflow-tooltip />
        <el-table-column prop="ipAddress" label="IP" width="140" />
      </el-table>
    </el-card>
  </div>
</template>

<style scoped>
.header {
  display: flex;
  justify-content: space-between;
  align-items: center;
}
.filters {
  display: flex;
  gap: 8px;
}
</style>
