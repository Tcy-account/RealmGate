import axios from 'axios'
import type { AxiosInstance, AxiosResponse, InternalAxiosRequestConfig } from 'axios'
import { ElMessage } from 'element-plus'
import { useAuthStore } from '@/store/auth'
import type { ApiResponse } from '@/types/api'

// 统一请求实例：开发期 baseURL 走 Vite 代理（见 vite.config.ts 的 /api 代理到 :9000）
const request: AxiosInstance = axios.create({
  baseURL: '/api',
  timeout: 15000,
})

// 请求拦截：自动携带 Bearer Token
request.interceptors.request.use((config: InternalAxiosRequestConfig) => {
  const auth = useAuthStore()
  if (auth.token) {
    config.headers.Authorization = `Bearer ${auth.token}`
  }
  return config
})

// 响应拦截：统一处理业务码与 401
request.interceptors.response.use(
  (resp: AxiosResponse<ApiResponse>) => {
    const body = resp.data
    // 业务码非 0 = 业务失败（与 HTTP 状态码解耦，见后端 ResultCode）
    if (body && typeof body.code === 'number' && body.code !== 0) {
      ElMessage.error(body.message || '请求失败')
      return Promise.reject(new Error(body.message || 'business error'))
    }
    return resp
  },
  (error) => {
    const status = error.response?.status
    if (status === 401) {
      const auth = useAuthStore()
      auth.logout()
      if (location.pathname !== '/login') location.href = '/login'
    }
    const msg = error.response?.data?.message || error.message || '网络错误'
    ElMessage.error(msg)
    return Promise.reject(error)
  },
)

// 类型友好的封装：直接返回 data 载荷（T），业务失败已在拦截器 reject
export async function get<T>(url: string, params?: Record<string, unknown>): Promise<T> {
  const resp = await request.get<ApiResponse<T>>(url, { params })
  return resp.data.data as T
}

export async function post<T>(url: string, body?: unknown): Promise<T> {
  const resp = await request.post<ApiResponse<T>>(url, body)
  return resp.data.data as T
}

export async function put<T>(url: string, body?: unknown): Promise<T> {
  const resp = await request.put<ApiResponse<T>>(url, body)
  return resp.data.data as T
}

export async function patch<T>(url: string, body?: unknown): Promise<T> {
  const resp = await request.patch<ApiResponse<T>>(url, body)
  return resp.data.data as T
}

export async function del<T>(url: string): Promise<T> {
  const resp = await request.delete<ApiResponse<T>>(url)
  return resp.data.data as T
}

export default request
