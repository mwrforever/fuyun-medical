// 应用级 Vitest 配置（web 宪法 C.3/C.5-4）：jsdom 环境，仅收集 src 下单测
import vue from '@vitejs/plugin-vue';
import { fileURLToPath, URL } from 'node:url';
import { defineConfig } from 'vitest/config';

export default defineConfig({
  plugins: [vue()],
  resolve: {
    // 路径别名与 vite.config / tsconfig 同步（web A.2-5）
    alias: {
      '@': fileURLToPath(new URL('./src', import.meta.url)),
    },
  },
  test: {
    environment: 'jsdom',
    include: ['src/**/*.spec.ts'],
  },
});
