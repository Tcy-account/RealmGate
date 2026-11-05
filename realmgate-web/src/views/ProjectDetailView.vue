<script setup lang="ts">
import { onMounted, ref, reactive } from 'vue'
import { useRoute } from 'vue-router'
import { get, post, put, del } from '@/api/request'
import { ElMessage, ElMessageBox } from 'element-plus'
import type { Task } from '@/types/api'

const route = useRoute()
const projectId = Number(route.params.id)

// 任务列表（受租户隔离 + 项目级 RBAC 约束：非成员/无权限由后端 403 拦截）
const tasks = ref<Task[]>([])
const loading = ref(false)

async function loadTasks() {
  loading.value = true
  try {
    tasks.value = await get<Task[]>(`/projects/${projectId}/tasks`)
  } catch {
    // 错误已由响应拦截器提示
  } finally {
    loading.value = false
  }
}

// 新建 / 编辑 弹窗
const dialogVisible = ref(false)
const dialogMode = ref<'create' | 'edit'>('create')
const saving = ref(false)
const STATUS_OPTIONS = ['OPEN', 'DOING', 'DONE']

// 表单：编辑时带 id；新建不含 id。description 可选。
const form = reactive<{ id?: number; slug: string; title: string; description: string; status: string }>({
  slug: '',
  title: '',
  description: '',
  status: 'OPEN',
})

function openCreate() {
  dialogMode.value = 'create'
  Object.assign(form, { id: undefined, slug: '', title: '', description: '', status: 'OPEN' })
  dialogVisible.value = true
}

function openEdit(row: Task) {
  dialogMode.value = 'edit'
  Object.assign(form, { id: row.id, slug: row.slug, title: row.title, description: row.description ?? '', status: row.status })
  dialogVisible.value = true
}

async function onSave() {
  if (!form.slug.trim() || !form.title.trim()) {
    ElMessage.warning('slug 与 title 必填')
    return
  }
  saving.value = true
  try {
    if (dialogMode.value === 'create') {
      await post<Task>(`/projects/${projectId}/tasks`, {
        slug: form.slug,
        title: form.title,
        description: form.description || undefined,
        status: form.status,
      })
      ElMessage.success('已创建任务')
    } else {
      // PUT 仅传非 null 字段（后端只覆盖非 null），故不传 description 当为空时保持原值
      const payload: Record<string, unknown> = { slug: form.slug, title: form.title, status: form.status }
      if (form.description) payload.description = form.description
      await put<Task>(`/projects/${projectId}/tasks/${form.id}`, payload)
      ElMessage.success('已更新任务')
    }
    dialogVisible.value = false
    await loadTasks()
  } catch {
    // 错误已由响应拦截器提示（含 403 无权限）
  } finally {
    saving.value = false
  }
}

async function onRemove(row: Task) {
  try {
    await ElMessageBox.confirm(`确认删除任务「${row.title}」(${row.slug})？`, '删除任务', { type: 'warning' })
  } catch {
    return // 用户取消
  }
  try {
    await del(`/projects/${projectId}/tasks/${row.id}`)
    ElMessage.success('已删除任务')
    await loadTasks()
  } catch {
    // 错误已由响应拦截器提示（含 403 无权限）
  }
}

onMounted(loadTasks)
</script>

<template>
  <div>
    <div style="display: flex; align-items: center; justify-content: space-between; margin-bottom: 16px">
      <h2>项目详情 #{{ projectId }}</h2>
      <el-button type="primary" @click="openCreate">新建任务</el-button>
    </div>

    <el-table v-loading="loading" :data="tasks" style="width: 100%">
      <el-table-column prop="id" label="ID" width="80" />
      <el-table-column prop="slug" label="Slug" width="160" />
      <el-table-column prop="title" label="标题" />
      <el-table-column prop="description" label="描述" />
      <el-table-column prop="status" label="状态" width="120">
        <template #default="{ row }">
          <el-tag :type="row.status === 'DONE' ? 'success' : row.status === 'DOING' ? 'warning' : 'info'">
            {{ row.status }}
          </el-tag>
        </template>
      </el-table-column>
      <el-table-column label="操作" width="160">
        <template #default="{ row }">
          <el-button link type="primary" size="small" @click="openEdit(row)">编辑</el-button>
          <el-button link type="danger" size="small" @click="onRemove(row)">删除</el-button>
        </template>
      </el-table-column>
    </el-table>

    <el-dialog
      v-model="dialogVisible"
      :title="dialogMode === 'create' ? '新建任务' : '编辑任务'"
      width="460px"
    >
      <el-form label-width="80px">
        <el-form-item label="Slug">
          <el-input v-model="form.slug" placeholder="项目内唯一标识" />
        </el-form-item>
        <el-form-item label="标题">
          <el-input v-model="form.title" placeholder="任务标题" />
        </el-form-item>
        <el-form-item label="描述">
          <el-input v-model="form.description" type="textarea" :rows="3" placeholder="可选" />
        </el-form-item>
        <el-form-item label="状态">
          <el-select v-model="form.status" style="width: 100%">
            <el-option v-for="s in STATUS_OPTIONS" :key="s" :label="s" :value="s" />
          </el-select>
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="dialogVisible = false">取消</el-button>
        <el-button type="primary" :loading="saving" @click="onSave">确定</el-button>
      </template>
    </el-dialog>
  </div>
</template>
