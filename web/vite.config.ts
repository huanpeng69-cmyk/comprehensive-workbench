import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue'
import { fileURLToPath, URL } from 'node:url'

export default defineConfig({
  plugins: [vue()],
  resolve: {
    alias: {
      '@': fileURLToPath(new URL('./src', import.meta.url))
    }
  },
  server: {
    port:5173,
    host: true,
    proxy: {
      // 同源代理：前端以 /api 调用，由 vite 转发到后端 8080。
      // 同时把 Origin 归一为后端 CORS 白名单内的 http://localhost:5173，
      // 这样无论用 localhost / 127.0.0.1 / 局域网 IP 打开页面都不会被 CORS 拦截。
      '/api': {
        target: 'http://localhost:8080',
        changeOrigin: true,
        configure: (proxy) => {
          proxy.on('proxyReq', (proxyReq) => {
            proxyReq.setHeader('Origin', 'http://localhost:5173')
          })
        }
      }
    }
  }
})
