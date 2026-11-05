<script setup lang="ts">
import { ref, onMounted } from 'vue'
import { get, post, put, del } from '@/api/request'
import { ElMessage, ElMessageBox } from 'element-plus'

interface Tenant {
  id: number
  name: string
  slug: string
  status: string
  createdAt?: string
  updatedAt?: string
}

const tenants = ref<Tenant[]>([])
const loading = ref(false)
const dialogVisible = ref(false)
const editing = ref<Tenant | null>(null)
const form = ref({ name: '', slug: '', status: 'ACTIVE' })

async function load() {
  loading.value = true
  try {
    tenants.value = await get<Tenant[]>('/admin/tenants')
  } finally {
    loading.value = false
  }
}

function openCreate() {
  editing.value = null
  form.value = { name: '', slug: '', status: 'ACTIVE' }
  dialogVisible.value = true
}
function openEdit(t: Tenant) {
  editing.value = t
  form.value = { name: t.name, slug: t.slug, status: t.status }
  dialogVisible.value = true
}
async function save() {
  try {
    if (editing.value) await put(`/admin/tenants/${editing.value.id}`, form.value)
    else await post('/admin/tenants', form.value)
    ElMessage.success('已保存')
    dialogVisible.value = false
    await load()
  } catch {
    /* 业务错误已由拦截器提示 */
  }
}
async function remove(t: Tenant) {
  try {
    await ElMessageBox.confirm(`确认删除租户「${t.name}」？`, '提示', { type: 'warning' })
  } catch {
    return
  }
  await del(`/admin/tenants/${t.id}`)
  ElMessage.success('已删除')
  await load()
}

onMounted(load)
</script>

<template>
  <div>
    <div style="display: flex; justify-content: space-between; align-items: center; margin-bottom: 12px">
      <h2 style="margin: 0">租户管理</h2>
      <el-button type="primary" @click="openCreate">新建租户</el-button>
    </div>
    <el-table :data="tenants" v-loading="loading" border>
      <el-table-column prop="id" label="ID" width="80" />
      <el-table-column prop="name" label="名称" />
      <el-table-column prop="slug" label="Slug" />
      <el-table-column prop="status" label="状态" width="100" />
      <el-table-column label="操作" width="160">
        <template #default="{ row }">
          <el-button size="small" @click="openEdit(row)">编辑</el-button>
          <el-button size="small" type="danger" @click="remove(row)">删除</el-button>
        </template>
      </el-table-column>
    </el-table>

    <el-dialog v-model="dialogVisible" :title="editing ? '编辑租户' : '新建租户'" width="420px">
      <el-form label-width="80px">
        <el-form-item label="名称">
          <el-input v-model="form.name" />
        </el-form-item>
        <el-form-item label="Slug">
          <el-input v-model="form.slug" :disabled="!!editing" placeholder="租户唯一标识，创建后不可改" />
        </el-form-item>
        <el-form-item label="状态">
          <el-select v-model="form.status">
            <el-option label="ACTIVE" value="ACTIVE" />
            <el-option label="DISABLED" value="DISABLED" />
          </el-select>
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="dialogVisible = false">取消</el-button>
        <el-button type="primary" @click="save">保存</el-button>
      </template>
    </el-dialog>
  </div>
</template>
