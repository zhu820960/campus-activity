import { mergeConfig } from 'vite'
import baseConfig from './vite.config.js'

export default mergeConfig(baseConfig, {
  server: {
    host: '127.0.0.1',
    port: 5174,
    strictPort: true,
    proxy: {
      '/api': 'http://127.0.0.1:18080',
      '/_AMapService': 'http://127.0.0.1:18080',
    },
  },
})
