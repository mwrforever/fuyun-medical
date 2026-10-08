/**
 * 岗位工作台菜单模型（「纸质病案」世界换血，2026-10-08）：菜单常量与岗位维度的唯一来源。
 * 一份常量承载四个消费面：侧栏分组渲染、折叠单字缩写、首页高频入口网格、岗位过滤纯函数。
 * 抽为独立模块的原因：菜单数据是布局域数据而非组件私产——侧栏与首页都要消费同一份，
 * 且岗位过滤/分组逻辑必须可脱离组件挂载做纯函数单测（宪法 §四 测试纪律）。
 *
 * 权限口径不变：权限语义只登记在路由 meta（单一事实源），本模块不重复登记权限编码，
 * 消费方（侧栏/首页）经 auth.hasRoutePermission 自行过滤（BUG-14 / PR-4D 空集语义）。
 */

import type { InjectionKey, Ref } from 'vue';

/** 岗位键：与总 Spec FU-M01-11 五岗位（医生/护士/药师/收费员/设备科）一一对应 */
export type PostKey = 'doctor' | 'nurse' | 'pharmacist' | 'cashier' | 'equipment';

/** 岗位选择值：'all' = 全部岗位（不按岗位过滤，仅权限过滤）；其余为五岗位键 */
export type PostSelection = 'all' | PostKey;

/** 侧栏菜单项元数据：index=路由路径（兼作 el-menu index）、label=展开态全称、
 * abbr=折叠态单字缩写、group=分组名（空串=顶层项不入组）、posts=归属岗位维度 */
export interface SidebarMenuItem {
  index: string;
  label: string;
  abbr: string;
  group: string;
  /**
   * 归属岗位清单（岗位切换过滤依据）：
   * - undefined = 恒显项（岗位过滤兜底，如首页/权限管理，任何岗位下都渲染）；
   * - 空数组 = 仅「全部」岗位展示（未纳入五岗位工作台口径的存量页面）；
   * - 非空数组 = 仅列出的岗位展示（一个菜单项可属多岗位）。
   */
  posts?: PostKey[];
}

/** 菜单分组结构：分组名 + 组内菜单项（顺序沿菜单常量首次出现序） */
export interface MenuGroup {
  name: string;
  items: SidebarMenuItem[];
}

/** 岗位切换器选项（数组顺序即展开态渲染序与折叠态循环切换序；label 适配展开态
 * 三列网格，abbr 单字适配折叠态单 chip 呈现） */
export const POST_OPTIONS: ReadonlyArray<{ key: PostSelection; label: string; abbr: string }> = [
  { key: 'all', label: '全部', abbr: '全' },
  { key: 'doctor', label: '医生', abbr: '医' },
  { key: 'nurse', label: '护士', abbr: '护' },
  { key: 'pharmacist', label: '药师', abbr: '药' },
  { key: 'cashier', label: '收费员', abbr: '费' },
  { key: 'equipment', label: '设备科', abbr: '设' },
];

/**
 * 菜单常量（数组顺序即渲染序）：与 router 子路由清单一一对应，新增页面在此登记；
 * 岗位归属按 FU-M01-11 五岗位工作台口径起草（业务侧确认后可微调，未列岗位的存量页
 * posts 置空数组仅「全部」可见）。
 */
export const MENU_ITEMS: SidebarMenuItem[] = [
  { index: '/', label: '首页', abbr: '首', group: '' },
  {
    index: '/patient/create',
    label: '患者建档',
    abbr: '档',
    group: '患者管理',
    posts: ['cashier'],
  },
  {
    index: '/patients',
    label: '患者检索',
    abbr: '查',
    group: '患者管理',
    posts: ['doctor', 'cashier'],
  },
  {
    index: '/outpatient/registration-charge',
    label: '挂号收费',
    abbr: '挂',
    group: '门诊服务',
    posts: ['cashier'],
  },
  { index: '/outpatient/triage-board', label: '分诊台', abbr: '分', group: '门诊服务', posts: [] },
  {
    index: '/outpatient/doctor-station',
    label: '门诊医生站',
    abbr: '医',
    group: '门诊服务',
    posts: ['doctor'],
  },
  {
    index: '/billing/pricing-settle',
    label: '划价结算',
    abbr: '价',
    group: '收费管理',
    posts: ['cashier'],
  },
  {
    index: '/billing/refunds',
    label: '退费审批',
    abbr: '退',
    group: '收费管理',
    posts: ['cashier'],
  },
  {
    index: '/billing/daily-list',
    label: '一日清单',
    abbr: '清',
    group: '收费管理',
    posts: ['doctor', 'cashier'],
  },
  {
    index: '/pharmacy/drug-dict',
    label: '药品字典',
    abbr: '药',
    group: '药房管理',
    posts: ['pharmacist'],
  },
  {
    index: '/pharmacy/dispense-workbench',
    label: '发药工作台',
    abbr: '发',
    group: '药房管理',
    posts: ['pharmacist'],
  },
  {
    index: '/pharmacy/dispense-return',
    label: '退药受理',
    abbr: '收',
    group: '药房管理',
    posts: ['pharmacist'],
  },
  { index: '/nursing/ward', label: '护士站', abbr: '护', group: '护理管理', posts: ['nurse'] },
  {
    index: '/nursing/execution',
    label: '护理执行工作台',
    abbr: '执',
    group: '护理管理',
    posts: ['nurse'],
  },
  {
    index: '/nursing/adverse-events',
    label: '不良事件上报',
    abbr: '报',
    group: '护理管理',
    posts: ['nurse'],
  },
  { index: '/pda', label: 'PDA 扫码', abbr: '扫', group: '护理管理', posts: ['nurse'] },
  {
    index: '/inpatient/admission',
    label: '入院登记台',
    abbr: '登',
    group: '住院管理',
    posts: ['nurse'],
  },
  {
    index: '/inpatient/beds',
    label: '病区床位图',
    abbr: '床',
    group: '住院管理',
    posts: ['nurse'],
  },
  {
    index: '/inpatient/station',
    label: '住院医生站',
    abbr: '住',
    group: '住院管理',
    posts: ['doctor'],
  },
  {
    index: '/inpatient/transfer',
    label: '转抄工作台',
    abbr: '抄',
    group: '住院管理',
    posts: ['doctor'],
  },
  {
    index: '/inpatient/discharge',
    label: '出院管理',
    abbr: '出',
    group: '住院管理',
    posts: ['nurse'],
  },
  {
    index: '/pharmacy/review',
    label: '住院审方台',
    abbr: '审',
    group: '药房管理',
    posts: ['pharmacist'],
  },
  {
    index: '/pharmacy/inpatient-dispense',
    label: '住院摆药台',
    abbr: '摆',
    group: '药房管理',
    posts: ['pharmacist'],
  },
  // IoT 管理分组（M14 管理台四项 + M16 命令/联动/质量三页）：设备科工作台整组
  {
    index: '/iot/products',
    label: '产品与物模型',
    abbr: '物',
    group: 'IoT 管理',
    posts: ['equipment'],
  },
  { index: '/iot/devices', label: '设备管理', abbr: '备', group: 'IoT 管理', posts: ['equipment'] },
  {
    index: '/iot/bindings',
    label: '设备绑定',
    abbr: '绑',
    group: 'IoT 管理',
    posts: ['equipment'],
  },
  {
    index: '/iot/alarm-rules',
    label: '告警规则',
    abbr: '警',
    group: 'IoT 管理',
    posts: ['equipment'],
  },
  {
    index: '/iot/commands',
    label: '命令中心',
    abbr: '令',
    group: 'IoT 管理',
    posts: ['equipment'],
  },
  {
    index: '/iot/linkage-rules',
    label: '联动规则',
    abbr: '联',
    group: 'IoT 管理',
    posts: ['equipment'],
  },
  { index: '/iot/quality', label: '质量看板', abbr: '质', group: 'IoT 管理', posts: ['equipment'] },
  // 病区视图分组（M16 病区视图三页：输液看板/呼叫工作台/冷链台账）
  {
    index: '/ward/infusion-board',
    label: '输液看板',
    abbr: '液',
    group: '病区视图',
    posts: ['nurse'],
  },
  {
    index: '/ward/call-workbench',
    label: '呼叫工作台',
    abbr: '呼',
    group: '病区视图',
    posts: ['nurse'],
  },
  { index: '/ward/cold-chain', label: '冷链台账', abbr: '冷', group: '病区视图', posts: ['nurse'] },
  // 系统管理分组（PR-4F 权限管理台：ADMIN 专属菜单码，无权限会话整组隐藏；恒显不限岗位）
  { index: '/system/permissions', label: '权限管理', abbr: '权', group: '系统' },
];

/**
 * 按岗位过滤菜单项（纯函数）：'all' 不过滤岗位维度（仍需消费方做权限过滤）；具体岗位
 * 只保留「恒显项（posts 缺省）」与「归属该岗位项」。防御语义：未知岗位键 = 集合不含
 * 恒 false，仅剩恒显项（与权限过滤空集语义同构，宁可多显兜底项不可整栏白屏）。
 *
 * @param items 菜单常量或已过权限过滤的子集（顺序保持）
 * @param post 岗位选择值（'all' 或五岗位键）
 * @return 过滤后的菜单项新数组（不改入参）
 */
export function selectMenuItemsForPost(
  items: SidebarMenuItem[],
  post: PostSelection,
): SidebarMenuItem[] {
  if (post === 'all') {
    return [...items];
  }
  return items.filter((item) => item.posts === undefined || item.posts.includes(post));
}

/**
 * 菜单项按分组名聚合（纯函数）：分组顺序 = 菜单常量中首次出现序（稳定渲染序）；
 * 顶层项（group 空串）不参与聚合，由消费方单独渲染在分组之前；聚合后组内必非空
 * （组名来自项自身），调用方无需防空组。
 *
 * @param items 已过滤的菜单项（顺序保持）
 * @return 分组数组（首现序）
 */
export function groupMenuItems(items: SidebarMenuItem[]): MenuGroup[] {
  return Array.from(new Set(items.map((item) => item.group).filter((name) => name !== ''))).map(
    (name) => ({
      name,
      items: items.filter((item) => item.group === name),
    }),
  );
}

/**
 * 岗位选择注入键（MainLayout → 路由级视图跨层级下行，web A.7-4 provide/inject 通道）：
 * 值为只读 Ref（computed 派生），写入口收敛在 MainLayout（侧栏切换器事件上行翻转）；
 * 只读防路由视图反向篡改岗位态。
 */
export const POST_SELECTION_KEY: InjectionKey<Readonly<Ref<PostSelection>>> = Symbol(
  'workstation:post-selection',
);
