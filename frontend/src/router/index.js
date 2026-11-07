import { createRouter, createWebHistory } from 'vue-router'
import HomeView from '../views/HomeView.vue'
import AdminView from '../views/AdminView.vue'
import FileView from '../views/FileView.vue'
import LoginView from '../views/LoginView.vue'
import PermView from '../views/PermView.vue'
import { isLoggedIn, isAdmin } from '../api/auth'

const router = createRouter({
  history: createWebHistory(import.meta.env.BASE_URL),
  routes: [
    {
      path: '/',
      name: 'home',
      component: HomeView,
    },
    {
      path: '/admin',
      name: 'admin',
      component: AdminView,
    },
    {
      path: '/files',
      name: 'files',
      component: FileView,
    },
    {
      path: '/login',
      name: 'login',
      component: LoginView,
    },
    {
      path: '/perm',
      name: 'perm',
      component: PermView,
      meta: { requiresAdmin: true }, // 权限管理页仅 ADMIN（第九周：服务端校验为主，前端守卫是体验层）
    },
  ],
})

// 路由守卫：仅做体验层拦截（真正的权限校验在后端 Sa-Token 注解——前端不可信，slide15 口诀）
router.beforeEach((to) => {
  if (to.meta.requiresAdmin && !isAdmin()) {
    return isLoggedIn() ? { path: '/' } : { path: '/login' }
  }
  return true
})

export default router
