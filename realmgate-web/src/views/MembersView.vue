<script setup lang="ts">
import { onMounted, ref, computed } from 'vue'
import { get, post, del, patch } from '@/api/request'
import { ElMessage, ElMessageBox } from 'element-plus'

interface Project {
  id: number
  name: string
  tenantId: string
}
interface MemberView {
  id: number
  projectId: number
  userId: number
  role: string
  tenantId: string
  username: string
}

const projects = ref<Project[]>([])
const projectId = ref<number | null>(null)
const members = ref<MemberView[]>([])
const loading = ref(false)

const inviteVisible = ref(false)
const inviteForm = ref<{ userId: number | null; role: string }>({ userId: null, role: 'MEMBER' })
const inviting = ref(false)

const ROLE_OPTIONS = ['PM', 'MEMBER', 'VIEWER']

async function loadProjects() {
  try {
    projects.value = await get<Project[]>('/projects')
    if (projects.value.length && projectId.value == null) {
      projectId.value = projects.value[0].id
      await loadMembers()
    }
  } catch {
    // 错误已由响应拦截器提示
  }
}

async function loadMembers() {
  if (projectId.value == null) return
  loading.value = true
  try {
    members.value = await get<MemberView[]>(`/projects/${projectId.value}/members`)
  } catch {
    // 错误已由响应拦截器提示
  } finally {
    loading.value = false
  }
}

async function onInvite() {
  if (projectId.value == null || inviteForm.value.userId == null) {
    ElMessage.warning('请选择项目并填写用户 ID')
    return
  }
  inviting.value = true
  try {
    await post<MemberView>(`/projects/${projectId.value}/members`, {
      userId: inviteForm.value.userId,
      role: inviteForm.value.role,
    })
    ElMessage.success('已邀请成员')
    inviteVisible.value = false
    inviteForm.value = { userId: null, role: 'MEMBER' }
    await loadMembers()
  } catch {
    // 错误已由响应拦截器提示
  } finally {
    inviting.value = false
  }
}

async function onChangeRole(row: MemberView, role: string) {
  if (projectId.value == null) return
  try {
    await patch<MemberView>(`/projects/${projectId.value}/members/${row.id}`, { role })
    row.role = role // 成功才更新本地，el-select 不回弹
    ElMessage.success('已更新角色')
  } catch {
    // 失败不更新；el-select 因 :model-value 单向绑定会回弹到旧值
  }
}

// el-select change 的 payload 类型较宽，这里收口成 string 再调。
function onRoleChange(row: MemberView, value: unknown) {
  onChangeRole(row, String(value))
}

async function onRemove(row: MemberView) {
  if (projectId.value == null) return
  try {
    await ElMessageBox.confirm(`确认将「${row.username}」从项目移除？`, '移除成员', { type: 'warning' })
  } catch {
    return // 用户取消
  }
  try {
    await del(`/projects/${projectId.value}/members/${row.id}`)
    ElMessage.success('已移除成员')
    await loadMembers()
  } catch {
    // 错误已由响应拦截器提示
  }
}

const canInvite = computed(() => projectId.value != null)

onMounted(loadProjects)
</script>

<template>
  <div>
    <h2>成员</h2>

    <div style="display: flex; gap: 12px; align-items: center; margin-bottom: 16px">
      <span>项目：</span>
      <el-select v-model="projectId" placeholder="选择项目" style="width: 240px" @change="loadMembers">
        <el-option v-for="p in projects" :key="p.id" :label="p.name" :value="p.id" />
      </el-select>
      <el-button type="primary" :disabled="!canInvite" @click="inviteVisible = true">邀请成员</el-button>
    </div>

    <el-table v-loading="loading" :data="members" style="width: 100%">
      <el-table-column prop="id" label="关系 ID" width="100" />
      <el-table-column prop="username" label="用户名" />
      <el-table-column prop="userId" label="用户 ID" width="100" />
      <el-table-column label="角色" width="170">
        <template #default="{ row }">
          <el-select :model-value="row.role" size="small" @change="(v: unknown) => onRoleChange(row, v)">
            <el-option v-for="r in ROLE_OPTIONS" :key="r" :label="r" :value="r" />
          </el-select>
        </template>
      </el-table-column>
      <el-table-column label="操作" width="120">
        <template #default="{ row }">
          <el-button type="danger" link size="small" @click="onRemove(row)">移除</el-button>
        </template>
      </el-table-column>
    </el-table>

    <el-dialog v-model="inviteVisible" title="邀请成员" width="420px">
      <el-form label-width="80px">
        <el-form-item label="用户 ID">
          <el-input v-model.number="inviteForm.userId" type="number" placeholder="目标用户 ID" />
        </el-form-item>
        <el-form-item label="角色">
          <el-select v-model="inviteForm.role" style="width: 100%">
            <el-option v-for="r in ROLE_OPTIONS" :key="r" :label="r" :value="r" />
          </el-select>
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="inviteVisible = false">取消</el-button>
        <el-button type="primary" :loading="inviting" @click="onInvite">确定</el-button>
      </template>
    </el-dialog>
  </div>
</template>
