<script setup lang="ts">
import { ref, onMounted } from 'vue'
import { get, post, put, del } from '@/api/request'
import { ElMessage, ElMessageBox } from 'element-plus'

interface Client {
  id: string
  clientId: string
  clientName: string
  clientAuthenticationMethods: string[]
  authorizationGrantTypes: string[]
  redirectUris: string[]
  postLogoutRedirectUris: string[]
  scopes: string[]
  hasSecret: boolean
  requireProofKey: boolean
}

const clients = ref<Client[]>([])
const loading = ref(false)
const dialogVisible = ref(false)
const editing = ref<Client | null>(null)
const form = ref({
  clientId: '',
  clientName: '',
  clientAuthenticationMethods: ['none'],
  authorizationGrantTypes: ['authorization_code', 'refresh_token'],
  redirectUris: '',
  postLogoutRedirectUris: '',
  scopes: 'openid profile',
  clientSecret: '',
  requireProofKey: true,
})

// 机密客户端才需要密钥
const needsSecret = () =>
  form.value.clientAuthenticationMethods.some((m) => m !== 'none')

async function load() {
  loading.value = true
  try {
    clients.value = await get<Client[]>('/admin/clients')
  } finally {
    loading.value = false
  }
}

function openCreate() {
  editing.value = null
  form.value = {
    clientId: '',
    clientName: '',
    clientAuthenticationMethods: ['none'],
    authorizationGrantTypes: ['authorization_code', 'refresh_token'],
    redirectUris: '',
    postLogoutRedirectUris: '',
    scopes: 'openid profile',
    clientSecret: '',
    requireProofKey: true,
  }
  dialogVisible.value = true
}
function openEdit(c: Client) {
  editing.value = c
  form.value = {
    clientId: c.clientId,
    clientName: c.clientName,
    clientAuthenticationMethods: [...c.clientAuthenticationMethods],
    authorizationGrantTypes: [...c.authorizationGrantTypes],
    redirectUris: (c.redirectUris || []).join('\n'),
    postLogoutRedirectUris: (c.postLogoutRedirectUris || []).join('\n'),
    scopes: (c.scopes || []).join(' '),
    clientSecret: '',
    requireProofKey: c.requireProofKey,
  }
  dialogVisible.value = true
}
async function save() {
  try {
    const payload = {
      clientId: form.value.clientId || undefined,
      clientName: form.value.clientName,
      clientAuthenticationMethods: form.value.clientAuthenticationMethods,
      authorizationGrantTypes: form.value.authorizationGrantTypes,
      redirectUris: form.value.redirectUris
        .split('\n').map((s) => s.trim()).filter(Boolean),
      postLogoutRedirectUris: form.value.postLogoutRedirectUris
        .split('\n').map((s) => s.trim()).filter(Boolean),
      scopes: form.value.scopes
        .split(/\s+/).map((s) => s.trim()).filter(Boolean),
      clientSecret: needsSecret() ? form.value.clientSecret || undefined : undefined,
      requireProofKey: form.value.requireProofKey,
    }
    if (editing.value) await put(`/admin/clients/${editing.value.id}`, payload)
    else await post('/admin/clients', payload)
    ElMessage.success('已保存')
    dialogVisible.value = false
    await load()
  } catch {
    /* 业务错误已由拦截器提示 */
  }
}
async function remove(c: Client) {
  try {
    await ElMessageBox.confirm(
      `确认删除客户端「${c.clientName}」(${c.clientId})？`,
      '提示',
      { type: 'warning' },
    )
  } catch {
    return
  }
  await del(`/admin/clients/${c.id}`)
  ElMessage.success('已删除')
  await load()
}

onMounted(load)
</script>

<template>
  <div>
    <div style="display: flex; justify-content: space-between; align-items: center; margin-bottom: 12px">
      <h2 style="margin: 0">OAuth 客户端</h2>
      <el-button type="primary" @click="openCreate">新建客户端</el-button>
    </div>
    <el-table :data="clients" v-loading="loading" border>
      <el-table-column prop="clientId" label="Client ID" width="180" />
      <el-table-column prop="clientName" label="名称" />
      <el-table-column label="认证方式" width="160">
        <template #default="{ row }">{{ (row.clientAuthenticationMethods || []).join(', ') }}</template>
      </el-table-column>
      <el-table-column label="授权类型" width="200">
        <template #default="{ row }">{{ (row.authorizationGrantTypes || []).join(', ') }}</template>
      </el-table-column>
      <el-table-column label="密钥" width="90">
        <template #default="{ row }">
          <el-tag v-if="row.hasSecret" type="warning">机密</el-tag>
          <el-tag v-else type="success">公开</el-tag>
        </template>
      </el-table-column>
      <el-table-column label="PKCE" width="80">
        <template #default="{ row }">{{ row.requireProofKey ? '是' : '否' }}</template>
      </el-table-column>
      <el-table-column label="操作" width="160">
        <template #default="{ row }">
          <el-button size="small" @click="openEdit(row)">编辑</el-button>
          <el-button size="small" type="danger" @click="remove(row)">删除</el-button>
        </template>
      </el-table-column>
    </el-table>

    <el-dialog v-model="dialogVisible" :title="editing ? '编辑客户端' : '新建客户端'" width="540px">
      <el-form label-width="120px">
        <el-form-item label="Client ID">
          <el-input v-model="form.clientId" :disabled="!!editing" placeholder="留空自动生成（创建后不可改）" />
        </el-form-item>
        <el-form-item label="名称">
          <el-input v-model="form.clientName" />
        </el-form-item>
        <el-form-item label="认证方式">
          <el-select v-model="form.clientAuthenticationMethods" multiple>
            <el-option label="none（公开客户端）" value="none" />
            <el-option label="client_secret_basic" value="client_secret_basic" />
            <el-option label="client_secret_post" value="client_secret_post" />
          </el-select>
        </el-form-item>
        <el-form-item label="授权类型">
          <el-select v-model="form.authorizationGrantTypes" multiple>
            <el-option label="authorization_code" value="authorization_code" />
            <el-option label="refresh_token" value="refresh_token" />
            <el-option label="client_credentials" value="client_credentials" />
          </el-select>
        </el-form-item>
        <el-form-item label="回调 URI">
          <el-input v-model="form.redirectUris" type="textarea" :rows="2" placeholder="每行一个" />
        </el-form-item>
        <el-form-item label="退出回调 URI">
          <el-input v-model="form.postLogoutRedirectUris" type="textarea" :rows="2" placeholder="每行一个" />
        </el-form-item>
        <el-form-item label="范围">
          <el-input v-model="form.scopes" placeholder="空格分隔，如 openid profile" />
        </el-form-item>
        <el-form-item v-if="needsSecret()" label="客户端密钥">
          <el-input
            v-model="form.clientSecret"
            type="password"
            show-password
            :placeholder="editing ? '留空则不修改密钥' : '机密客户端必填'"
          />
        </el-form-item>
        <el-form-item label="启用 PKCE">
          <el-switch v-model="form.requireProofKey" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="dialogVisible = false">取消</el-button>
        <el-button type="primary" @click="save">保存</el-button>
      </template>
    </el-dialog>
  </div>
</template>
