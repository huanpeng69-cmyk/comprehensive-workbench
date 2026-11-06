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
      // 同源代理：前端以 /api 调用，由 vite 转发到后端 8081。
      // 8080 已被同机其它项目（AI 视频平台 fusion-nginx）占用，后端改用 8081，
      // 与 server/src/main/resources/application.yml 的 server.port 保持一致。
      // 同时把 Origin 归一为后端 CORS 白名单内的 http://localhost:5173，
      // 这样无论用 localhost / 127.0.0.1 / 局域网 IP 打开页面都不会被 CORS 拦截。
      '/api': {
        target: 'http://localhost:8081',
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
