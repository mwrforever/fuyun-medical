/**
 * 树形图标菜单模型（「纸质病案」世界壳层树形化，批次 2 册 1 契约 §3，2026-10-08）：
 * 菜单常量与分组聚合的唯一来源。一份常量承载两个消费面：侧栏树形渲染（分组父节点 +
 * 页项叶节点，图标名→组件解析收敛在 AppSidebar 图标注册表）与首页常用入口链接条。
 * 岗位维度已随批次 2 裁决废除（RBAC 已承载权限过滤），本模块不含任何岗位筛选语义。
 * 抽为独立模块的原因：菜单数据是布局域数据而非组件私产——侧栏与首页消费同一份，
 * 且分组聚合逻辑必须可脱离组件挂载做纯函数单测（宪法 §四 测试纪律）。
 *
 * 权限口径不变：权限语义只登记在路由 meta（单一事实源），本模块不重复登记权限编码，
 * 消费方（侧栏/首页）经 auth.hasRoutePermission 自行过滤（BUG-14 / PR-4D 空集语义）。
 */

/** 侧栏菜单项元数据：index=路由路径（兼作 el-menu index）、label=全称、
 * icon=项图标名（值域=批次 2 册 1 契约 §1.2 表 34 枚 @element-plus/icons-vue 名，
 * 组件解析在消费方 AppSidebar 的注册表）、group=分组名（空串=顶层项不入组） */
export interface SidebarMenuItem {
  index: string;
  label: string;
  icon: string;
  group: string;
}

/** 菜单分组结构：分组名 + 组图标名（值域=契约 §1.1 表 9 枚）+ 组内菜单项（顺序沿菜单常量首次出现序） */
export interface MenuGroup {
  name: string;
  icon: string;
  items: SidebarMenuItem[];
}

/**
 * 分组图标登记表（契约 §1.1 唯一权威，组图标与项图标不重名——扫读双通道不混淆）：
 * 分组名 → 图标名。新增分组必须在此登记组图标，否则侧栏该组父节点无图标渲染
 * （值域完整性由 menu.spec 对照契约全量断言守护）。
 */
export const GROUP_ICONS: Record<string, string> = {
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

/**
 * 菜单常量（数组顺序即渲染序，首现序不变）：与 router 子路由清单一一对应，新增页面在此登记；
 * 图标名按批次 2 册 1 契约 §1.2 逐项列死（语义优先、线性形态、医疗具象物优先）。
 */
export const MENU_ITEMS: SidebarMenuItem[] = [
  // 顶层恒显项（group 空串不入组）：首页为门户起点，唯一允许的实底图标（与药丸选中同语法）
  { index: '/', label: '首页', icon: 'HomeFilled', group: '' },
  // 患者管理组：建档即新建病案文书 / 检索放大镜通用零歧义
  { index: '/patient/create', label: '患者建档', icon: 'DocumentAdd', group: '患者管理' },
  { index: '/patients', label: '患者检索', icon: 'Search', group: '患者管理' },
  // 门诊服务组：号票=挂号凭证 / 罗盘=分诊导向 / 处方笔=医生站核心动作
  {
    index: '/outpatient/registration-charge',
    label: '挂号收费',
    icon: 'Tickets',
    group: '门诊服务',
  },
  { index: '/outpatient/triage-board', label: '分诊台', icon: 'Compass', group: '门诊服务' },
  {
    index: '/outpatient/doctor-station',
    label: '门诊医生站',
    icon: 'EditPen',
    group: '门诊服务',
  },
  // 收费管理组：标价签=划价 / 折让=资金返还 / 清单列表=日结直白形态
  {
    index: '/billing/pricing-settle',
    label: '划价结算',
    icon: 'PriceTag',
    group: '收费管理',
  },
  { index: '/billing/refunds', label: '退费审批', icon: 'Discount', group: '收费管理' },
  { index: '/billing/daily-list', label: '一日清单', icon: 'List', group: '收费管理' },
  // 药房管理组：翻阅=典藏查阅 / 售出=发药交付 / 取回药盒=退药画面
  { index: '/pharmacy/drug-dict', label: '药品字典', icon: 'Reading', group: '药房管理' },
  {
    index: '/pharmacy/dispense-workbench',
    label: '发药工作台',
    icon: 'Sell',
    group: '药房管理',
  },
  {
    index: '/pharmacy/dispense-return',
    label: '退药受理',
    icon: 'TakeawayBox',
    group: '药房管理',
  },
  // 护理管理组：呼叫铃=病区响应中枢 / 完成勾=医嘱执行核对 / 线性警示三角=上报 / 手持终端=PDA
  { index: '/nursing/ward', label: '护士站', icon: 'Bell', group: '护理管理' },
  {
    index: '/nursing/execution',
    label: '护理执行工作台',
    icon: 'Finished',
    group: '护理管理',
  },
  {
    index: '/nursing/adverse-events',
    label: '不良事件上报',
    icon: 'Warning',
    group: '护理管理',
  },
  { index: '/pda', label: 'PDA 扫码', icon: 'Iphone', group: '护理管理' },
  // 住院管理组：登记簿便签 / 床位网格 / 控制杆=持续处置 / 誊抄文书 / 行李箱=离院归家
  {
    index: '/inpatient/admission',
    label: '入院登记台',
    icon: 'Memo',
    group: '住院管理',
  },
  { index: '/inpatient/beds', label: '病区床位图', icon: 'Grid', group: '住院管理' },
  { index: '/inpatient/station', label: '住院医生站', icon: 'Operation', group: '住院管理' },
  { index: '/inpatient/transfer', label: '转抄工作台', icon: 'CopyDocument', group: '住院管理' },
  { index: '/inpatient/discharge', label: '出院管理', icon: 'SuitcaseLine', group: '住院管理' },
  // 药房管理组续（首现序归组）：审核勾验文书=审方完成态 / 摆药推车=按单配药
  {
    index: '/pharmacy/review',
    label: '住院审方台',
    icon: 'DocumentChecked',
    group: '药房管理',
  },
  {
    index: '/pharmacy/inpatient-dispense',
    label: '住院摆药台',
    icon: 'ShoppingTrolley',
    group: '药房管理',
  },
  // IoT 管理组（M14 管理台四项 + M16 命令/联动/质量三页）：规格文档族 / 仪表盘盘点 / 连接绑定 / 闹钟告警 / 纸飞机下行 / 链环联动 / 趋势图统计
  { index: '/iot/products', label: '产品与物模型', icon: 'Files', group: 'IoT 管理' },
  { index: '/iot/devices', label: '设备管理', icon: 'Odometer', group: 'IoT 管理' },
  { index: '/iot/bindings', label: '设备绑定', icon: 'Connection', group: 'IoT 管理' },
  { index: '/iot/alarm-rules', label: '告警规则', icon: 'AlarmClock', group: 'IoT 管理' },
  { index: '/iot/commands', label: '命令中心', icon: 'Promotion', group: 'IoT 管理' },
  { index: '/iot/linkage-rules', label: '联动规则', icon: 'Link', group: 'IoT 管理' },
  { index: '/iot/quality', label: '质量看板', icon: 'TrendCharts', group: 'IoT 管理' },
  // 病区视图组（M16 三页）：倾注=输液滴注 / 服务耳机=呼叫受理 / 冷藏设备=温控直白具象
  {
    index: '/ward/infusion-board',
    label: '输液看板',
    icon: 'Pouring',
    group: '病区视图',
  },
  {
    index: '/ward/call-workbench',
    label: '呼叫工作台',
    icon: 'Service',
    group: '病区视图',
  },
  { index: '/ward/cold-chain', label: '冷链台账', icon: 'Refrigerator', group: '病区视图' },
  // 系统组（PR-4F 权限管理台：ADMIN 专属菜单码，无权限会话整组隐藏；恒显不限岗位语义已随岗位维度废除）
  { index: '/system/permissions', label: '权限管理', icon: 'Key', group: '系统' },
];

/**
 * 菜单项按分组名聚合（纯函数）：分组顺序 = 菜单常量中首次出现序（稳定渲染序）；
 * 顶层项（group 空串）不参与聚合，由消费方单独渲染在分组之前；聚合后组内必非空
 * （组名来自项自身），调用方无需防空组。组图标经 GROUP_ICONS 登记（契约 §1.1）。
 *
 * @param items 已过滤的菜单项（顺序保持）
 * @return 分组数组（首现序，icon=登记表取值；未登记组名将得到 undefined 图标名，值域由 menu.spec 守护）
 */
export function groupMenuItems(items: SidebarMenuItem[]): MenuGroup[] {
  return Array.from(new Set(items.map((item) => item.group).filter((name) => name !== ''))).map(
    (name) => ({
      name,
      icon: GROUP_ICONS[name],
      items: items.filter((item) => item.group === name),
    }),
  );
}
