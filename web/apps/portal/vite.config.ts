// 患者门户 Vite 配置：dev 反向代理（生产反代由 nginx 承担，web A.2-4）
import vue from '@vitejs/plugin-vue';
import { fileURLToPath, URL } from 'node:url';
import { defineConfig } from 'vite';

export default defineConfig({
  // 部署 base 与 nginx fuyun.conf 子路径挂载一一对应（dev 模式应用即服务于该子路径，属预期）
  base: '/portal/',
  plugins: [vue()],
  resolve: {
    // 路径别名与 tsconfig.app.json paths 同步（web A.2-5）
    alias: {
      '@': fileURLToPath(new URL('./src', import.meta.url)),
    },
  },
  server: {
    proxy: {
      // REST 反向代理：保留 /api 前缀原样转发（接口挂 /api/v1，禁 rewrite，web A.2-4）
      '/api': { target: 'http://localhost:8080' },
      // WebSocket 联调（/ws/** 统一前缀）：升级转发，仅本地 dev 生效
      '/ws': { target: 'ws://localhost:8080', ws: true },
    },
  },
});
