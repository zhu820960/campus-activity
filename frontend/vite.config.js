import vue from '@vitejs/plugin-vue'
import { defineConfig } from 'vite'

// https://vite.dev/config/
export default defineConfig({
  plugins: [vue()],
  server: {
    host: '0.0.0.0',
    proxy: {
      '/api': process.env.VITE_BACKEND_URL || 'http://localhost:8080',
      '^/images/[0-9a-f]{32}\\.(jpg|png)$': process.env.VITE_BACKEND_URL || 'http://localhost:8080',
      '/_AMapService': process.env.VITE_BACKEND_URL || 'http://localhost:8080',
    },
  },
})
