// 应用级 Vitest 配置（web 宪法 C.3/C.5-4）：jsdom 环境，仅收集 src 下单测
import vue from '@vitejs/plugin-vue';
import { fileURLToPath, URL } from 'node:url';
import AutoImport from 'unplugin-auto-import/vite';
import { ElementPlusResolver } from 'unplugin-vue-components/resolvers';
import Components from 'unplugin-vue-components/vite';
import { defineConfig } from 'vitest/config';

export default defineConfig({
  // 与 vite.config 同款按需引入插件：单测真实装配 Element Plus 组件（表单校验/交互行为断言的前提）
  plugins: [
    vue(),
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
    // 路径别名与 vite.config / tsconfig 同步（web A.2-5）
    alias: {
      '@': fileURLToPath(new URL('./src', import.meta.url)),
    },
  },
  test: {
    environment: 'jsdom',
    include: ['src/**/*.spec.ts'],
    server: {
      deps: {
        // element-plus 按需样式（resolver 注入 + ElMessage 手动引入）经 Node 原生加载会因
        // .css 扩展名报错：内联交给 Vite 管线按 css:false 桩化，测试环境不真实加载样式
        inline: ['element-plus'],
      },
    },
  },
});
