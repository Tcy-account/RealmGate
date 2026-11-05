// 与后端 ApiResponse<T> 对齐（计划第 45 节）
export interface ApiResponse<T = unknown> {
  code: number
  message: string
  data: T
  timestamp: number
}

// 任务实体（与后端 task.Task 对齐，见 task/Task.java）
export interface Task {
  id: number
  tenantId: string
  projectId: number
  slug: string
  title: string
  description?: string
  status: string
}
