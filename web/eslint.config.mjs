// ESLint 单根 flat config（web 宪法 C.5-2）：覆盖三应用与共享包
// 组合顺序敏感：vue essential → typescript-eslint 类型感知规则 → eslint-config-prettier 必须置尾
// （关闭与 Prettier 冲突的格式规则，格式一律不进 eslint，禁 eslint-plugin-prettier）
import { defineConfigWithVueTs, vueTsConfigs } from '@vue/eslint-config-typescript';
import eslintConfigPrettier from 'eslint-config-prettier';
import pluginVue from 'eslint-plugin-vue';

export default defineConfigWithVueTs(
  // 模板层硬门禁：Vue 官方风格指南 Priority A（多词组件名、v-for key 等）
  pluginVue.configs['flat/essential'],
  // TS/脚本层从类型感知规则起步（unknown 收窄替代 any 由 no-explicit-any 把关）
  vueTsConfigs.recommendedTypeChecked,
  // 置尾：关闭全部格式类规则，格式统一交 Prettier（pnpm format:check 门禁）
  eslintConfigPrettier,
  {
    ignores: [
      '**/dist/**',
      '**/node_modules/**',
      '**/auto-imports.d.ts',
      '**/components.d.ts',
      '**/coverage/**',
    ],
  },
);
