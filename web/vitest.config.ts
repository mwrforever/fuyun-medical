// 根聚合 Vitest 配置（web 宪法 C.4）：projects 逐应用声明，新增 app 必须同步此清单
// 覆盖率 report-only 起步不设阈值（web C.5-4），经 --coverage 按需产出
import { defineConfig } from 'vitest/config';

export default defineConfig({
  test: {
    projects: ['apps/workstation', 'apps/portal', 'apps/bigscreen'],
  },
});
