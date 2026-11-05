import { defineConfig } from 'vite'
import react from '@vitejs/plugin-react'

// 后台 dev 代理：/api → web-backend（Spring Boot 8081）
export default defineConfig({
  plugins: [react()],
  server: {
    port: 3100,
    proxy: {
      '/api': { target: 'http://localhost:8081', changeOrigin: true }, // web-backend
    },
  },
})
