import { defineConfig } from 'vite'
import react from '@vitejs/plugin-react'

// 后台 dev 代理：/api → 本地 backend 服务（Spring Boot 8080）
export default defineConfig({
  plugins: [react()],
  server: {
    port: 3100,
    proxy: {
      '/api': { target: 'http://localhost:8080', changeOrigin: true },
    },
  },
})
