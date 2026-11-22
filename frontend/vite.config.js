import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue'

// https://vite.dev/config/
export default defineConfig({
  plugins: [vue()],
  server: {
    port: 5173,
    proxy: {
      // 所有 /api 请求代理到本地后端（Spring Boot 8080）
      '/api': {
        target: 'http://localhost:8080',
        changeOrigin: true,
      },
      // 第十一周实时聊天：WebSocket 代理（ws 协议直连 8080 的 /ws/qa 端点）
      '/ws': {
        target: 'ws://localhost:8080',
        ws: true,
      },
    },
  },
})
