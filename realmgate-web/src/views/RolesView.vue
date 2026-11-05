<script setup lang="ts">
import { ref, onMounted } from 'vue'
import { get, post, put, del } from '@/api/request'
import { ElMessage, ElMessageBox } from 'element-plus'

interface Role {
  id: number
  roleName: string
  description?: string
}
interface Permission {
  id: number
  permKey: string
  description?: string
}

const roles = ref<Role[]>([])
const loading = ref(false)
const dialogVisible = ref(false)
const editing = ref<Role | null>(null)
const form = ref({ roleName: '', description: '' })

// 权限绑定
const permDialog = ref(false)
const permRole = ref<Role | null>(null)
const allPerms = ref<Permission[]>([])
const rolePermIds = ref<number[]>([])

async function load() {
  loading.value = true
  try {
    roles.value = await get<Role[]>('/admin/roles')
  } finally {
    loading.value = false
  }
}

function openCreate() {
  editing.value = null
  form.value = { roleName: '', description: '' }
  dialogVisible.value = true
}
function openEdit(r: Role) {
  editing.value = r
  form.value = { roleName: r.roleName, description: r.description ?? '' }
  dialogVisible.value = true
}
async function save() {
  try {
    if (editing.value) await put(`/admin/roles/${editing.value.id}`, form.value)
    else await post('/admin/roles', form.value)
    ElMessage.success('已保存')
    dialogVisible.value = false
    await load()
  } catch {
    /* 业务错误已由拦截器提示 */
  }
}
async function remove(r: Role) {
  try {
    await ElMessageBox.confirm(`确认删除角色「${r.roleName}」？`, '提示', { type: 'warning' })
  } catch {
    return
  }
  await del(`/admin/roles/${r.id}`)
  ElMessage.success('已删除')
  await load()
}

async function openPerms(r: Role) {
  permRole.value = r
  allPerms.value = await get<Permission[]>('/admin/permissions')
  const bound = await get<Permission[]>(`/admin/roles/${r.id}/permissions`)
  rolePermIds.value = bound.map((p) => p.id)
  permDialog.value = true
}
async function savePerms() {
  await put(`/admin/roles/${permRole.value!.id}/permissions`, rolePermIds.value)
  ElMessage.success('权限已更新')
  permDialog.value = false
}

onMounted(load)
</script>

<template>
  <div>
    <div style="display: flex; justify-content: space-between; align-items: center; margin-bottom: 12px">
      <h2 style="margin: 0">角色管理</h2>
      <el-button type="primary" @click="openCreate">新建角色</el-button>
    </div>
    <el-table :data="roles" v-loading="loading" border>
      <el-table-column prop="id" label="ID" width="80" />
      <el-table-column prop="roleName" label="角色名" />
      <el-table-column prop="description" label="描述" />
      <el-table-column label="操作" width="240">
        <template #default="{ row }">
          <el-button size="small" @click="openEdit(row)">编辑</el-button>
          <el-button size="small" @click="openPerms(row)">权限</el-button>
          <el-button size="small" type="danger" @click="remove(row)">删除</el-button>
        </template>
      </el-table-column>
    </el-table>

    <el-dialog v-model="dialogVisible" :title="editing ? '编辑角色' : '新建角色'" width="420px">
      <el-form label-width="80px">
        <el-form-item label="角色名">
          <el-input v-model="form.roleName" :disabled="!!editing" />
        </el-form-item>
        <el-form-item label="描述">
          <el-input v-model="form.description" type="textarea" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="dialogVisible = false">取消</el-button>
        <el-button type="primary" @click="save">保存</el-button>
      </template>
    </el-dialog>

    <el-dialog v-model="permDialog" :title="`角色权限 - ${permRole?.roleName ?? ''}`" width="460px">
      <el-checkbox-group v-model="rolePermIds">
        <div v-for="p in allPerms" :key="p.id" style="margin-bottom: 8px">
          <el-checkbox :value="p.id">
            {{ p.permKey }} <span style="color: #94a3b8">- {{ p.description }}</span>
          </el-checkbox>
        </div>
      </el-checkbox-group>
      <template #footer>
        <el-button @click="permDialog = false">取消</el-button>
        <el-button type="primary" @click="savePerms">保存</el-button>
      </template>
    </el-dialog>
  </div>
</template>
