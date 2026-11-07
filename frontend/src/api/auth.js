/**
 * 登录态管理（localStorage 持久化 + reactive 全局状态）。
 * key 设计与后端 Sa-Token 口径对齐：Authorization: Bearer <tokenValue>。
 * authState 是全局响应式登录态：saveLogin/clearLogin 会同步更新它，
 * App 导航等组件直接读它即可随登录/登出自动刷新（SPA 内不重载页面）。
 */
import { reactive } from 'vue'

const KEY_TOKEN = 'dh_token'
const KEY_REFRESH = 'dh_refresh_token'
const KEY_USER = 'dh_user'

/** 全局响应式登录态（App.vue 等长期挂载组件从这里读） */
export const authState = reactive({
  user: null,
  loggedIn: false,
  admin: false,
})

function refreshState() {
  const user = currentUser()
  authState.user = user
  authState.loggedIn = !!getToken() && !!user
  authState.admin = user?.role === 'ADMIN'
}

export function saveLogin(data) {
  localStorage.setItem(KEY_TOKEN, data.tokenValue)
  localStorage.setItem(KEY_REFRESH, data.refreshToken || '')
  localStorage.setItem(KEY_USER, JSON.stringify({
    userId: data.userId,
    username: data.username,
    role: data.role,
  }))
  refreshState()
}

export function getToken() {
  return localStorage.getItem(KEY_TOKEN) || ''
}

export function getRefreshToken() {
  return localStorage.getItem(KEY_REFRESH) || ''
}

export function currentUser() {
  try {
    return JSON.parse(localStorage.getItem(KEY_USER)) || null
  } catch {
    return null
  }
}

export function isLoggedIn() {
  return !!getToken() && !!currentUser()
}

export function isAdmin() {
  return currentUser()?.role === 'ADMIN'
}

export function clearLogin() {
  localStorage.removeItem(KEY_TOKEN)
  localStorage.removeItem(KEY_REFRESH)
  localStorage.removeItem(KEY_USER)
  refreshState()
}

refreshState()
