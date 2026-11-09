/**
 * 全局 axios 实例（全项目统一走它）：
 * - 请求拦截：自动携带 Authorization: Bearer <token>（登录后所有请求免手工传头）
 * - 响应拦截：后端统一口径为 HTTP 200 + 业务码 code——code≠0 转 reject（保持 err.response.data
 *   结构，与 HTTP 错误一致）
 * - 令牌自动续期：code=10004（未登录/过期）时先用 refresh token 调 /auth/refresh 换新令牌
 *   并重放原请求（单飞：并发 401 共享同一次续期）；续期失败才清登录态跳登录页
 */
import axios from 'axios'
import { getToken, getRefreshToken, saveLogin, clearLogin } from './auth'

export const http = axios.create({ baseURL: '/api/v1', timeout: 60000 })

http.interceptors.request.use((config) => {
  if (config._skipAuth) return config // 重放请求已携带新令牌，跳过
  const token = getToken()
  if (token) config.headers.Authorization = `Bearer ${token}`
  return config
})

function redirectToLogin() {
  clearLogin()
  if (!location.pathname.startsWith('/login')) location.href = '/login'
}

/** 单飞锁：并发多个 401 共享同一次 refresh 调用 */
let refreshing = null

/** 用 refresh token 换新令牌对（裸 axios：不走本实例拦截器，避免递归）。
 *  轮换语义：旧 refresh 一次性作废，成功返回新的 tokenValue + refreshToken。 */
async function tryRefresh() {
  const rt = getRefreshToken()
  if (!rt) return null
  try {
    const res = await axios.post('/api/v1/auth/refresh', { refreshToken: rt })
    const data = res.data?.data
    if (res.data?.code === 0 && data?.tokenValue) {
      saveLogin(data)
      return data.tokenValue
    }
    return null
  } catch {
    return null
  }
}

http.interceptors.response.use(
  async (res) => {
    const biz = res.data
    if (biz && typeof biz === 'object' && typeof biz.code === 'number' && biz.code !== 0) {
      if (biz.code === 10004 && res.config && !res.config._retried) {
        // 单飞：并发多个 401 共享同一次 refresh（refreshing 复位后下一个 401 才会再试）
        refreshing = refreshing || tryRefresh().finally(() => { refreshing = null })
        const newToken = await refreshing
        if (newToken) {
          res.config._retried = true // 防重放死循环：重放后再 401 直接走登录页
          res.config._skipAuth = true
          res.config.headers.Authorization = `Bearer ${newToken}`
          return http.request(res.config)
        }
        redirectToLogin()
      }
      const e = new Error(biz.message || '业务处理失败')
      e.response = res
      e.code = `BIZ_${biz.code}`
      return Promise.reject(e)
    }
    return res
  },
  (err) => Promise.reject(err),
)
