import { createRouter, createWebHistory } from 'vue-router'
import { useAuthStore } from '@/store/auth'

// 路由表（计划第 12 节 P0/P1 页面）。
// 业务页统一挂在 DefaultLayout 下，登录页独立。
const router = createRouter({
  history: createWebHistory(),
  routes: [
    {
      path: '/login',
      name: 'login',
      component: () => import('@/views/LoginView.vue'),
    },
    {
      path: '/callback',
      name: 'callback',
      component: () => import('@/views/CallbackView.vue'),
    },
    {
      path: '/',
      component: () => import('@/layouts/DefaultLayout.vue'),
      // 关键：/ 本身没有内容页，必须重定向到 dashboard，
      // 否则已登录时访问 / 会只渲染侧边栏+顶栏、主区域全空（表现为"什么都没有"）。
      redirect: '/dashboard',
      children: [
        { path: 'dashboard', name: 'dashboard', component: () => import('@/views/DashboardView.vue') },
        { path: 'projects', name: 'projects', component: () => import('@/views/ProjectsView.vue') },
        { path: 'projects/:id', name: 'project-detail', component: () => import('@/views/ProjectDetailView.vue') },
        { path: 'members', name: 'members', component: () => import('@/views/MembersView.vue') },
        { path: 'profile', name: 'profile', component: () => import('@/views/ProfileView.vue') },
        { path: 'admin/tenants', name: 'admin-tenants', component: () => import('@/views/TenantsView.vue') },
        { path: 'admin/users', name: 'admin-users', component: () => import('@/views/UsersView.vue') },
        { path: 'admin/roles', name: 'admin-roles', component: () => import('@/views/RolesView.vue') },
        { path: 'admin/permissions', name: 'admin-permissions', component: () => import('@/views/PermissionsView.vue') },
        { path: 'admin/clients', name: 'admin-clients', component: () => import('@/views/ClientsView.vue') },
        { path: 'admin/audits', name: 'admin-audits', component: () => import('@/views/AuditsView.vue') },
      ],
    },
    { path: '/:pathMatch(.*)*', redirect: '/login' },
  ],
})

// 全局前置守卫：未携带有效 token 且访问非公开页 → 重定向到 /login。
const publicPages = ['/login', '/callback']
router.beforeEach((to) => {
  const auth = useAuthStore()
  if (!auth.token && !publicPages.includes(to.path)) {
    return '/login'
  }
})

export default router
