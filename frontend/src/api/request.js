import axios from 'axios'

/**
 * axios 统一封装：
 * - baseURL 走 Vite 代理（/api -> localhost:8080），规避跨域
 * - 响应拦截器按后端 Result{code, message, data} 约定解包
 */
const request = axios.create({
  baseURL: '/api',
  timeout: 10000,
})

request.interceptors.response.use(
  (response) => {
    const res = response.data
    if (res.code === 200) {
      return res.data
    }
    console.error(`[api] ${res.code}: ${res.message}`)
    return Promise.reject(new Error(res.message || '请求失败'))
  },
  (error) => {
    console.error('[api] 网络异常', error.message)
    return Promise.reject(error)
  },
)

export default request
