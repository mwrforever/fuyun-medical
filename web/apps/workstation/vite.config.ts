// 医护工作站 Vite 配置：按需引入 Element Plus + dev 反向代理（生产反代由 nginx 承担，web A.2-4）
import vue from '@vitejs/plugin-vue';
import { fileURLToPath, URL } from 'node:url';
import AutoImport from 'unplugin-auto-import/vite';
import { ElementPlusResolver } from 'unplugin-vue-components/resolvers';
import Components from 'unplugin-vue-components/vite';
import { defineConfig } from 'vite';

export default defineConfig({
  plugins: [
    vue(),
    // Element Plus 按需引入（web B.3-6）：模板组件自动按需注册 + 组合式 API 自动导入，样式由解析器联动引入
    Components({
      dirs: ['src/components'],
      dts: 'src/components.d.ts',
      resolvers: [ElementPlusResolver()],
    }),
    AutoImport({
      dts: 'src/auto-imports.d.ts',
      resolvers: [ElementPlusResolver()],
    }),
  ],
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
