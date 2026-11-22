// 临时预览配置：独立端口 5180，代理到临时后端 8090。不影响共享 vite.config.js。
import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue'
export default defineConfig({
  plugins: [vue()],
  server: { host: '0.0.0.0', port: 5180, strictPort: true, proxy: { '/api': 'http://127.0.0.1:8090' } }
})