// 树形图标菜单模型单测（批次 2 册 1 契约 §3/§1）：groupMenuItems（分组聚合/组图标登记）
// 与 MENU_ITEMS（项图标值域）三类场景全覆盖——正常（全量分组/图标映射）、边界（顶层项
// 不入组/组内非空/纯函数无副作用）、契约守护（34 项+9 组图标逐项对照 §1 表、组图标与
// 项图标不重名、菜单 index 命中真实路由防点击落 404）。聚合用例零挂载零 Pinia；末组
// 「路由表同步守护」仅消费路由表做 resolve 存在性断言（不挂载组件）。
import { describe, expect, it } from 'vitest';
import { router } from '@/router';
import { GROUP_ICONS, MENU_ITEMS, groupMenuItems, type SidebarMenuItem } from './menu';

/** 提取菜单项标签集（断言辅助：面向业务结果而非实现细节） */
function labelsOf(items: SidebarMenuItem[]): string[] {
  return items.map((item) => item.label);
}

/** 契约 §1.1 分组图标唯一权威表（9 组；断言锚点=契约原文，防实现漂移） */
const CONTRACT_GROUP_ICONS: Record<string, string> = {
  患者管理: 'User',
  门诊服务: 'FirstAidKit',
  收费管理: 'Wallet',
  药房管理: 'Box',
  护理管理: 'Notebook',
  住院管理: 'OfficeBuilding',
  'IoT 管理': 'Cpu',
  病区视图: 'Monitor',
  系统: 'Setting',
};

/** 契约 §1.2 页项图标唯一权威表（34 项，路由 → 图标名） */
const CONTRACT_ITEM_ICONS: Record<string, string> = {
  '/': 'HomeFilled',
  '/patient/create': 'DocumentAdd',
  '/patients': 'Search',
  '/outpatient/registration-charge': 'Tickets',
  '/outpatient/triage-board': 'Compass',
  '/outpatient/doctor-station': 'EditPen',
  '/billing/pricing-settle': 'PriceTag',
  '/billing/refunds': 'Discount',
  '/billing/daily-list': 'List',
  '/pharmacy/drug-dict': 'Reading',
  '/pharmacy/dispense-workbench': 'Sell',
  '/pharmacy/dispense-return': 'TakeawayBox',
  '/nursing/ward': 'Bell',
  '/nursing/execution': 'Finished',
  '/nursing/adverse-events': 'Warning',
  '/pda': 'Iphone',
  '/inpatient/admission': 'Memo',
  '/inpatient/beds': 'Grid',
  '/inpatient/station': 'Operation',
  '/inpatient/transfer': 'CopyDocument',
  '/inpatient/discharge': 'SuitcaseLine',
  '/pharmacy/review': 'DocumentChecked',
  '/pharmacy/inpatient-dispense': 'ShoppingTrolley',
  '/iot/products': 'Files',
  '/iot/devices': 'Odometer',
  '/iot/bindings': 'Connection',
  '/iot/alarm-rules': 'AlarmClock',
  '/iot/commands': 'Promotion',
  '/iot/linkage-rules': 'Link',
  '/iot/quality': 'TrendCharts',
  '/ward/infusion-board': 'Pouring',
  '/ward/call-workbench': 'Service',
  '/ward/cold-chain': 'Refrigerator',
  '/system/permissions': 'Key',
};

describe('菜单项图标值域（契约 §1.2 守护）', () => {
  it('34 项菜单逐项命中契约图标映射（值域外图标零容忍）', () => {
    expect(MENU_ITEMS).toHaveLength(34);
    for (const item of MENU_ITEMS) {
      expect(item.icon, `菜单项 ${item.label}(${item.index}) 图标偏离契约`).toBe(
        CONTRACT_ITEM_ICONS[item.index],
      );
    }
  });

  it('项图标名两两唯一（一枚图标一个业务动作，扫读不混淆）', () => {
    const icons = MENU_ITEMS.map((item) => item.icon);
    expect(new Set(icons).size).toBe(icons.length);
  });
});

describe('分组图标登记表（契约 §1.1 守护）', () => {
  it('9 组图标与契约逐项一致', () => {
    expect(GROUP_ICONS).toEqual(CONTRACT_GROUP_ICONS);
  });

  it('组图标与项图标不重名（扫读双通道不混淆）', () => {
    const itemIcons = new Set(MENU_ITEMS.map((item) => item.icon));
    for (const groupIcon of Object.values(GROUP_ICONS)) {
      expect(itemIcons.has(groupIcon), `组图标 ${groupIcon} 与项图标重名`).toBe(false);
    }
  });
});

describe('分组聚合 groupMenuItems', () => {
  it('按首现序聚合 9 组且组内非空，组图标随组下发', () => {
    const groups = groupMenuItems(MENU_ITEMS);
    // 首现序：患者管理 → 门诊服务 → 收费管理 → 药房管理 → 护理管理 → 住院管理 → IoT 管理 → 病区视图 → 系统
    expect(groups.map((group) => group.name)).toEqual([
      '患者管理',
      '门诊服务',
      '收费管理',
      '药房管理',
      '护理管理',
      '住院管理',
      'IoT 管理',
      '病区视图',
      '系统',
    ]);
    for (const group of groups) {
      expect(group.icon).toBe(CONTRACT_GROUP_ICONS[group.name]);
      expect(group.items.length).toBeGreaterThan(0);
    }
  });

  it('药房管理组归组跨段完整（药品字典…摆药台 5 项，住院审方/摆药虽后置仍归药房组）', () => {
    const pharmacy = groupMenuItems(MENU_ITEMS).find((group) => group.name === '药房管理');
    expect(labelsOf(pharmacy?.items ?? [])).toEqual([
      '药品字典',
      '发药工作台',
      '退药受理',
      '住院审方台',
      '住院摆药台',
    ]);
  });

  it('顶层项（group 空串）不参与聚合：仅首页一枚', () => {
    const names = groupMenuItems(MENU_ITEMS).map((group) => group.name);
    expect(names).not.toContain('');
    expect(MENU_ITEMS.filter((item) => item.group === '')).toHaveLength(1);
  });

  it('过滤后组内清空的分组整组剔除（防空标题组残留）', () => {
    // 仅保留患者管理组两项：其余组整组消失
    const onlyPatient = MENU_ITEMS.filter((item) => item.group === '患者管理');
    const groups = groupMenuItems(onlyPatient);
    expect(groups.map((group) => group.name)).toEqual(['患者管理']);
  });

  it('聚合不改入参数组（纯函数无副作用）', () => {
    const snapshot = JSON.parse(JSON.stringify(MENU_ITEMS)) as SidebarMenuItem[];
    groupMenuItems(MENU_ITEMS);
    groupMenuItems([]);
    expect(MENU_ITEMS).toEqual(snapshot);
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
