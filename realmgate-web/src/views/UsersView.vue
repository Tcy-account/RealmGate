<script setup lang="ts">
import { ref, onMounted } from 'vue'
import { get, post, put, del } from '@/api/request'
import { ElMessage, ElMessageBox } from 'element-plus'

interface User {
  id: number
  username: string
  email: string
  displayName: string
  status: string
}
interface Role {
  id: number
  roleName: string
  description?: string
}

const users = ref<User[]>([])
const loading = ref(false)
const dialogVisible = ref(false)
const editing = ref<User | null>(null)
const form = ref({ username: '', email: '', displayName: '', status: 'ACTIVE', password: '' })

// 角色绑定
const roleDialog = ref(false)
const roleUser = ref<User | null>(null)
const allRoles = ref<Role[]>([])
const userRoleIds = ref<number[]>([])

async function load() {
  loading.value = true
  try {
    users.value = await get<User[]>('/admin/users')
  } finally {
    loading.value = false
  }
}

function openCreate() {
  editing.value = null
  form.value = { username: '', email: '', displayName: '', status: 'ACTIVE', password: '' }
  dialogVisible.value = true
}
function openEdit(u: User) {
  editing.value = u
  form.value = { username: u.username, email: u.email, displayName: u.displayName, status: u.status, password: '' }
  dialogVisible.value = true
}
async function save() {
  try {
    if (editing.value) await put(`/admin/users/${editing.value.id}`, form.value)
    else await post('/admin/users', form.value)
    ElMessage.success('已保存')
    dialogVisible.value = false
    await load()
  } catch {
    /* 业务错误已由拦截器提示 */
  }
}
async function remove(u: User) {
  try {
    await ElMessageBox.confirm(`确认删除用户「${u.username}」？`, '提示', { type: 'warning' })
  } catch {
    return
  }
  await del(`/admin/users/${u.id}`)
  ElMessage.success('已删除')
  await load()
}

async function openRoles(u: User) {
  roleUser.value = u
  allRoles.value = await get<Role[]>('/admin/roles')
  const bound = await get<Role[]>(`/admin/users/${u.id}/roles`)
  userRoleIds.value = bound.map((r) => r.id)
  roleDialog.value = true
}
async function toggleRole(roleId: number, checked: boolean) {
  const uid = roleUser.value!.id
  try {
    if (checked) await post(`/admin/users/${uid}/roles/${roleId}`)
    else await del(`/admin/users/${uid}/roles/${roleId}`)
    if (checked) userRoleIds.value.push(roleId)
    else userRoleIds.value = userRoleIds.value.filter((id) => id !== roleId)
  } catch {
    /* 业务错误已由拦截器提示，状态回滚由重新打开对话框时刷新 */
  }
}

onMounted(load)
</script>

<template>
  <div>
    <div style="display: flex; justify-content: space-between; align-items: center; margin-bottom: 12px">
      <h2 style="margin: 0">用户管理</h2>
      <el-button type="primary" @click="openCreate">新建用户</el-button>
    </div>
    <el-table :data="users" v-loading="loading" border>
      <el-table-column prop="id" label="ID" width="80" />
      <el-table-column prop="username" label="用户名" />
      <el-table-column prop="email" label="邮箱" />
      <el-table-column prop="displayName" label="显示名" />
      <el-table-column prop="status" label="状态" width="100" />
      <el-table-column label="操作" width="240">
        <template #default="{ row }">
          <el-button size="small" @click="openEdit(row)">编辑</el-button>
          <el-button size="small" @click="openRoles(row)">角色</el-button>
          <el-button size="small" type="danger" @click="remove(row)">删除</el-button>
        </template>
      </el-table-column>
    </el-table>

    <el-dialog v-model="dialogVisible" :title="editing ? '编辑用户' : '新建用户'" width="440px">
      <el-form label-width="80px">
        <el-form-item label="用户名">
          <el-input v-model="form.username" :disabled="!!editing" />
        </el-form-item>
        <el-form-item label="邮箱">
          <el-input v-model="form.email" />
        </el-form-item>
        <el-form-item label="显示名">
          <el-input v-model="form.displayName" />
        </el-form-item>
        <el-form-item label="状态">
          <el-select v-model="form.status">
            <el-option label="ACTIVE" value="ACTIVE" />
            <el-option label="DISABLED" value="DISABLED" />
          </el-select>
        </el-form-item>
        <el-form-item :label="editing ? '重置密码' : '初始密码'">
          <el-input v-model="form.password" type="password" :placeholder="editing ? '留空则不修改' : ''" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="dialogVisible = false">取消</el-button>
        <el-button type="primary" @click="save">保存</el-button>
      </template>
    </el-dialog>

    <el-dialog v-model="roleDialog" :title="`用户角色 - ${roleUser?.username ?? ''}`" width="420px">
      <div v-for="r in allRoles" :key="r.id" style="margin-bottom: 8px">
        <el-checkbox :model-value="userRoleIds.includes(r.id)" @change="(v: any) => toggleRole(r.id, v)">
          {{ r.roleName }}
        </el-checkbox>
      </div>
    </el-dialog>
  </div>
</template>
