import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue'
import { fileURLToPath, URL } from 'node:url'

// https://vite.dev/config/
export default defineConfig({
  plugins: [vue()],
  resolve: {
    alias: {
      '@': fileURLToPath(new URL('./src', import.meta.url))
    }
  },
  server: {
    port: 5173,
    // 允许通过内网穿透（cpolar/樱花等）的任意域名访问，避免 Vite 默认拦截外部 Host
    allowedHosts: true,
    proxy: {
      // 开发环境将 /api 转发到后端
      '/api': {
        target: 'http://localhost:8080',
        changeOrigin: true
      }
    }
  }
})
