import request from './request'

/**
 * 系统健康检查：展示前后端联调状态（对应后端 /api/health）
 */
export function getHealth() {
  return request.get('/health')
}
