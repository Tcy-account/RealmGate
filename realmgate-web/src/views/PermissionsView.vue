<script setup lang="ts">
import { ref, onMounted } from 'vue'
import { get, post, put, del } from '@/api/request'
import { ElMessage, ElMessageBox } from 'element-plus'

interface Permission {
  id: number
  permKey: string
  description?: string
}

const perms = ref<Permission[]>([])
const loading = ref(false)
const dialogVisible = ref(false)
const editing = ref<Permission | null>(null)
const form = ref({ permKey: '', description: '' })

async function load() {
  loading.value = true
  try {
    perms.value = await get<Permission[]>('/admin/permissions')
  } finally {
    loading.value = false
  }
}

function openCreate() {
  editing.value = null
  form.value = { permKey: '', description: '' }
  dialogVisible.value = true
}
function openEdit(p: Permission) {
  editing.value = p
  form.value = { permKey: p.permKey, description: p.description ?? '' }
  dialogVisible.value = true
}
async function save() {
  try {
    if (editing.value) await put(`/admin/permissions/${editing.value.id}`, form.value)
    else await post('/admin/permissions', form.value)
    ElMessage.success('已保存')
    dialogVisible.value = false
    await load()
  } catch {
    /* 业务错误已由拦截器提示 */
  }
}
async function remove(p: Permission) {
  try {
    await ElMessageBox.confirm(`确认删除权限「${p.permKey}」？`, '提示', { type: 'warning' })
  } catch {
    return
  }
  await del(`/admin/permissions/${p.id}`)
  ElMessage.success('已删除')
  await load()
}

onMounted(load)
</script>

<template>
  <div>
    <div style="display: flex; justify-content: space-between; align-items: center; margin-bottom: 12px">
      <h2 style="margin: 0">权限管理</h2>
      <el-button type="primary" @click="openCreate">新建权限</el-button>
    </div>
    <el-table :data="perms" v-loading="loading" border>
      <el-table-column prop="id" label="ID" width="80" />
      <el-table-column prop="permKey" label="权限键" />
      <el-table-column prop="description" label="描述" />
      <el-table-column label="操作" width="160">
        <template #default="{ row }">
          <el-button size="small" @click="openEdit(row)">编辑</el-button>
          <el-button size="small" type="danger" @click="remove(row)">删除</el-button>
        </template>
      </el-table-column>
    </el-table>

    <el-dialog v-model="dialogVisible" :title="editing ? '编辑权限' : '新建权限'" width="420px">
      <el-form label-width="80px">
        <el-form-item label="权限键">
          <el-input v-model="form.permKey" :disabled="!!editing" placeholder="如 user:manage" />
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
  </div>
</template>
