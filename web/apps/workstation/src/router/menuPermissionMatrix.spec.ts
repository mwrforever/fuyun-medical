// 路由权限面与 MENU 种子跨层一致性守护（PR-4D 评审 D-1 修复环）：workstation 路由表
// meta.permission 消费的 MENU 权限码与后端 V1116+V1120 种子 MENU 段必须双向一致——
// 「新路由登记权限码而未种子化」（前端守卫放行、后端 403 矩阵无绑定可挂）与
// 「种子码无路由消费」（僵尸码）两类漂移此前无 CI 断言锚定，本 spec 将既有的
// 精确一致状态变为机器守护（评审 D-1：当前一致为事实非缺陷，漂移面才是缺口）；
// PR-4F 扩源 V1120（管理台 MENU 码 system:permission:manage 种子化于 ELEMENT 种子文件）
/// <reference types="node" />
import { readFileSync } from 'node:fs';
import { resolve } from 'node:path';
import { describe, expect, it } from 'vitest';
import { router } from './index';

// V1116 种子 MENU 段行形态：SELECT <id>, '<perm_code>', '<perm_name>', 'MENU'
const MENU_ROW_PATTERN = /SELECT \d+, '([^']+)', '[^']*', 'MENU'/g;

/** 从 V1116+V1120 种子 SQL 提取 MENU 权限码集合（V1120 新增管理台 MENU 码，PR-4F 扩源） */
function loadMenuSeedCodes(): Set<string> {
  // 路径锚定 import.meta：对 cwd 不敏感（历史 process.cwd 锚定在 --filter 口径下 ENOENT——
  // 应用目录跑测时拼 ../backend 必失手，根跑者才绿）；本文件位于 src/router，上溯 5 级即仓库根
  const systemMigrations = resolve(
    import.meta.dirname,
    '../../../../../backend/fuyun-system/src/main/resources/db/migration/system/',
  );
  const sql1116 = readFileSync(
    resolve(systemMigrations, 'V1116__seed_full_permissions.sql'),
    'utf-8',
  );
  const sql1120 = readFileSync(
    resolve(systemMigrations, 'V1120__seed_element_permissions.sql'),
    'utf-8',
  );
  const codes = new Set<string>();
  // V1116：MENU 段标记后提取（段标记失效时 slice 取末段、提取面收缩由断言①守护）
  const menuSection = sql1116.slice(sql1116.indexOf('3. MENU 权限点'));
  for (const match of menuSection.matchAll(MENU_ROW_PATTERN)) codes.add(match[1]);
  // V1120：全文提取（该文件 MENU 行唯一，ELEMENT/API 行 perm_type 列不匹配正则天然出界）
  for (const match of sql1120.matchAll(MENU_ROW_PATTERN)) codes.add(match[1]);
  return codes;
}

describe('路由权限面与 MENU 种子跨层一致性（PR-4D 评审 D-1 守护）', () => {
  it('路由 meta.permission 码与 V1116+V1120 MENU 种子码双向一致（新码须双侧同步登记）', () => {
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
