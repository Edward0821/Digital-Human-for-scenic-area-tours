import { http } from './http'

/**
 * 系统健康检查：展示前后端联调状态（对应后端 /api/v1/health）
 */
export function getHealth() {
  // http 实例成功时返回 axios response（含 Result 信封），此处解包到业务数据
  return http.get('/health').then((res) => res.data.data)
}
