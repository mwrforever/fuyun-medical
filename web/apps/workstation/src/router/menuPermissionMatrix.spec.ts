// 路由权限面与 MENU 种子跨层一致性守护（PR-4D 评审 D-1 修复环）：workstation 路由表
// meta.permission 消费的 MENU 权限码与后端 V1116 种子 MENU 段必须双向一致——
// 「新路由登记权限码而未种子化」（前端守卫放行、后端 403 矩阵无绑定可挂）与
// 「种子码无路由消费」（僵尸码）两类漂移此前无 CI 断言锚定，本 spec 将既有的
// 精确一致状态变为机器守护（评审 D-1：当前一致为事实非缺陷，漂移面才是缺口）
/// <reference types="node" />
import { readFileSync } from 'node:fs';
import { resolve } from 'node:path';
import { describe, expect, it } from 'vitest';
import { router } from './index';

// V1116 种子 MENU 段行形态：SELECT <id>, '<perm_code>', '<perm_name>', 'MENU'
const MENU_ROW_PATTERN = /SELECT \d+, '([^']+)', '[^']*', 'MENU'/g;

/** 从 V1116 种子 SQL 的 MENU 段提取权限码集合（提取面非空由用例内断言守护，正则失效即红） */
function loadMenuSeedCodes(): Set<string> {
  // 前提：与 pnpm test 同口径在 web/ 根执行（process.cwd()=web/，种子在仓库 backend/ 侧）；
  // ?raw 静态导入不可用——Vite server.fs 严格模式拒绝 workspace root 外文件（Denied ID）
  const seedPath = resolve(
    process.cwd(),
    '../backend/fuyun-system/src/main/resources/db/migration/system/V1116__seed_full_permissions.sql',
  );
  const sql = readFileSync(seedPath, 'utf-8');
  // 段标记找不到时 indexOf 返回 -1，slice 仅取末字符、提取出空集——失败方向安全（断言①红）
  const menuSection = sql.slice(sql.indexOf('3. MENU 权限点'));
  const codes = new Set<string>();
  for (const match of menuSection.matchAll(MENU_ROW_PATTERN)) {
    codes.add(match[1]);
  }
  return codes;
}

describe('路由权限面与 MENU 种子跨层一致性（PR-4D 评审 D-1 守护）', () => {
  it('路由 meta.permission 码与 V1116 MENU 种子码双向一致（新码须双侧同步登记）', () => {
    const seedCodes = loadMenuSeedCodes();
    // 断言①种子提取面自证：正则或段落标记失效（0 码假象）直接红，防空集假绿
    expect(seedCodes.size).toBeGreaterThan(0);

    const routeCodes = new Set(
      router
        .getRoutes()
        .map((route) => route.meta.permission)
        .filter((code): code is string => typeof code === 'string'),
    );
    // 断言②路由消费面自证：全部路由丢失 permission meta 时防假绿
    expect(routeCodes.size).toBeGreaterThan(0);

    // 断言③双向一致：路由码无种子=前端放行而后端矩阵无此码可绑；种子码无路由=僵尸码
    expect([...routeCodes].filter((code) => !seedCodes.has(code))).toEqual([]);
    expect([...seedCodes].filter((code) => !routeCodes.has(code))).toEqual([]);
  });
});
