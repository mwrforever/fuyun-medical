// 岗位菜单模型单测：selectMenuItemsForPost（岗位过滤）与 groupMenuItems（分组聚合）
// 三类场景全覆盖——正常（五岗位各取所得）、边界（全部/仅全部/恒显/纯函数无副作用）、
// 异常（未知岗位键防御语义）。过滤/聚合用例零挂载零 Pinia；末组「路由表同步守护」仅
// 消费路由表做 resolve 存在性断言（不挂载组件），防菜单 index 指向已删路由静默落 404。
import { describe, expect, it } from 'vitest';
import { router } from '@/router';
import {
  MENU_ITEMS,
  POST_OPTIONS,
  groupMenuItems,
  selectMenuItemsForPost,
  type PostSelection,
  type SidebarMenuItem,
} from './menu';

/** 提取菜单项标签集（断言辅助：面向业务结果而非实现细节） */
function labelsOf(items: SidebarMenuItem[]): string[] {
  return items.map((item) => item.label);
}

describe('岗位过滤 selectMenuItemsForPost', () => {
  it('护士岗位返回护士工作台口径入口与恒显项（首页/权限管理）', () => {
    const labels = labelsOf(selectMenuItemsForPost(MENU_ITEMS, 'nurse'));
    // 护士口径十入口（护理 4 + 住院 3 + 病区 3）+ 恒显两项，顺序沿菜单常量
    expect(labels).toEqual([
      '首页',
      '护士站',
      '护理执行工作台',
      '不良事件上报',
      'PDA 扫码',
      '入院登记台',
      '病区床位图',
      '出院管理',
      '输液看板',
      '呼叫工作台',
      '冷链台账',
      '权限管理',
    ]);
    // 其他岗位专属入口不得混入护士工作台
    expect(labels).not.toContain('发药工作台');
    expect(labels).not.toContain('挂号收费');
  });

  it('药师岗位仅返回药房五入口与恒显项', () => {
    const labels = labelsOf(selectMenuItemsForPost(MENU_ITEMS, 'pharmacist'));
    expect(labels).toEqual([
      '首页',
      '药品字典',
      '发药工作台',
      '退药受理',
      '住院审方台',
      '住院摆药台',
      '权限管理',
    ]);
  });

  it('全部岗位返回完整菜单不过滤', () => {
    expect(selectMenuItemsForPost(MENU_ITEMS, 'all')).toHaveLength(MENU_ITEMS.length);
  });

  it('未纳入岗位口径的菜单（posts 空数组）仅全部岗位可见', () => {
    // 分诊台 posts=[]：全部岗位下可见
    expect(labelsOf(selectMenuItemsForPost(MENU_ITEMS, 'all'))).toContain('分诊台');
    // 任一具体岗位均不可见
    for (const option of POST_OPTIONS) {
      if (option.key === 'all') {
        continue;
      }
      expect(labelsOf(selectMenuItemsForPost(MENU_ITEMS, option.key))).not.toContain('分诊台');
    }
  });

  it('恒显项（posts 缺省）在任何岗位下保留（权限过滤兜底语义）', () => {
    for (const option of POST_OPTIONS) {
      const labels = labelsOf(selectMenuItemsForPost(MENU_ITEMS, option.key));
      expect(labels).toContain('首页');
      expect(labels).toContain('权限管理');
    }
  });

  it('未知岗位键防御：仅剩恒显项不整栏白屏（与权限空集语义同构）', () => {
    const labels = labelsOf(selectMenuItemsForPost(MENU_ITEMS, 'unknown' as PostSelection));
    expect(labels).toEqual(['首页', '权限管理']);
  });

  it('过滤不改入参数组（纯函数无副作用）', () => {
    const snapshot = JSON.parse(JSON.stringify(MENU_ITEMS)) as SidebarMenuItem[];
    selectMenuItemsForPost(MENU_ITEMS, 'nurse');
    selectMenuItemsForPost(MENU_ITEMS, 'all');
    expect(MENU_ITEMS).toEqual(snapshot);
  });
});

describe('分组聚合 groupMenuItems', () => {
  it('按首现序聚合且组内非空（护士岗位三业务域分组 + 恒显系统组）', () => {
    const groups = groupMenuItems(selectMenuItemsForPost(MENU_ITEMS, 'nurse'));
    // 护士工作台分组首现序：护理管理 → 住院管理 → 病区视图 → 系统（权限管理恒显成组）
    expect(groups.map((group) => group.name)).toEqual(['护理管理', '住院管理', '病区视图', '系统']);
    for (const group of groups) {
      expect(group.items.length).toBeGreaterThan(0);
    }
    const nursing = groups.find((group) => group.name === '护理管理');
    expect(labelsOf(nursing?.items ?? [])).toEqual([
      '护士站',
      '护理执行工作台',
      '不良事件上报',
      'PDA 扫码',
    ]);
  });

  it('顶层项（group 空串）不参与聚合，系统组恒在', () => {
    const names = groupMenuItems(MENU_ITEMS).map((group) => group.name);
    expect(names).not.toContain('');
    expect(names).toContain('系统');
  });
});

describe('菜单常量与路由表同步守护', () => {
  it('MENU_ITEMS 每项 index 都命中真实路由（防菜单点击落 404：index 指向已删路由时 resolve 空 matched 会被当恒显项渲染）', () => {
    for (const item of MENU_ITEMS) {
      const resolved = router.resolve(item.index);
      expect(
        resolved.matched.length,
        `菜单项 ${item.label}(${item.index}) 未命中任何路由`,
      ).toBeGreaterThan(0);
    }
  });
});
