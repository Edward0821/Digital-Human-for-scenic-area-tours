/**
 * 全局 axios 实例（全项目统一走它）：
 * - 请求拦截：自动携带 Authorization: Bearer <token>（登录后所有请求免手工传头）
 * - 响应拦截：后端统一口径为 HTTP 200 + 业务码 code——code≠0 转 reject（保持 err.response.data
 *   结构，与 HTTP 错误一致）；code=10004（未登录/过期）自动清登录态并跳登录页
 */
import axios from 'axios'
import { getToken, clearLogin } from './auth'

export const http = axios.create({ baseURL: '/api/v1', timeout: 60000 })

http.interceptors.request.use((config) => {
  const token = getToken()
  if (token) config.headers.Authorization = `Bearer ${token}`
  return config
})

http.interceptors.response.use(
  (res) => {
    const biz = res.data
    if (biz && typeof biz === 'object' && typeof biz.code === 'number' && biz.code !== 0) {
      // 未登录/过期：清态跳登录（避免在登录页死循环）
      if (biz.code === 10004 && !location.pathname.startsWith('/login')) {
        clearLogin()
        location.href = '/login'
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
