// 侧栏树形菜单单测（批次 2 册 1 契约 §3）：权限过滤（BUG-14 守卫骨架 + PR-4D 空集语义，
// 路由 meta 单一事实源经真实路由表反查）、树形渲染（分组父节点/页项叶节点/组内全滤整组
// 剔除）、图标注册表对照契约 §1 全 43 枚、默认全展开与瞬切铁律锚点（collapseTransition
// 关闭 + popper 挂类）、路由高亮药丸选中延续、收起态图标条形态。会话以直接注入 state 的
// 方式承载（假令牌资产，非真实凭证）。
// 注：全文件共享单一 Pinia——与挂载组件必须读写同一会话 store，逐用例换实例会割裂会话语义。
import { mount } from '@vue/test-utils';
import { createPinia, setActivePinia } from 'pinia';
import type { Pinia } from 'pinia';
import { beforeEach, describe, expect, it } from 'vitest';
import { router } from '@/router';
import { MENU_ITEMS } from '@/views/layout/menu';
import { useAuthStore } from '@/stores/auth';
import AppSidebar, { MENU_ICON_REGISTRY } from './AppSidebar.vue';

/** 契约 §1 图标全集（9 组 + 34 项 = 43 枚，断言锚点=契约原文） */
const CONTRACT_ALL_ICONS = [
  // §1.1 分组图标
  'User',
  'FirstAidKit',
  'Wallet',
  'Box',
  'Notebook',
  'OfficeBuilding',
  'Cpu',
  'Monitor',
  'Setting',
  // §1.2 页项图标
  'HomeFilled',
  'DocumentAdd',
  'Search',
  'Tickets',
  'Compass',
  'EditPen',
  'PriceTag',
  'Discount',
  'List',
  'Reading',
  'Sell',
  'TakeawayBox',
  'Bell',
  'Finished',
  'Warning',
  'Iphone',
  'Memo',
  'Grid',
  'Operation',
  'CopyDocument',
  'SuitcaseLine',
  'DocumentChecked',
  'ShoppingTrolley',
  'Files',
  'Odometer',
  'Connection',
  'AlarmClock',
  'Promotion',
  'Link',
  'TrendCharts',
  'Pouring',
  'Service',
  'Refrigerator',
  'Key',
];

describe('侧栏树形菜单', () => {
  /** 文件级共享 Pinia：被挂组件与用例注入的会话必须同一 store 实例 */
  let pinia: Pinia;

  /**
   * 构造带权限点集的测试会话。
   *
   * @param permissions 权限点编码集；空数组 = 无任何业务权限的授权会话（后端 PR-4D 已
   *        填实登录契约，空集按无权限全拒消费），undefined 模拟权限字段缺省的防御会话形态
   */
  function injectSession(permissions: string[] | undefined): void {
    const auth = useAuthStore();
    auth.token = 'sidebar-access-token';
    auth.user = {
      userId: '1',
      loginName: 'nurse01',
      displayName: '测试护士',
      orgId: undefined,
      roles: [],
      ...(permissions !== undefined ? { permissions } : {}),
    };
  }

  /** 全量业务权限（路由表内全部菜单权限点一次收齐，供树形全量渲染用例） */
  function allMenuPermissions(): string[] {
    return MENU_ITEMS.map((item) => router.resolve(item.index).meta.permission).filter(
      (permission): permission is string => permission !== undefined,
    );
  }

  beforeEach(async () => {
    sessionStorage.clear();
    pinia = createPinia();
    setActivePinia(pinia);
    // 预落登录页：侧栏挂载仅消费路由表与会话，与当前停留路由解耦，此处只求路由器就绪
    await router.push('/login');
  });

  it('权限点集为空（无任何权限）：仅显示未登记权限点的首页，九个分组整组不渲染（与守卫同口径）', () => {
    injectSession([]);
    const wrapper = mount(AppSidebar, { global: { plugins: [pinia, router] } });

    // 断言业务结果：空集=无任何业务权限（PR-4D 语义反转）——全部业务菜单与分组隐藏，
    // 仅首页（路由未登记权限点）可见
    const texts = wrapper.findAll('.el-menu-item').map((item) => item.text());
    expect(texts).toEqual(['首页']);
    expect(wrapper.findAll('.el-sub-menu')).toHaveLength(0);
    wrapper.unmount();
  });

  it('权限点集非空：集内项在所属分组内渲染，集外项隐藏', () => {
    injectSession(['patient:archive:search']);
    const wrapper = mount(AppSidebar, { global: { plugins: [pinia, router] } });

    // 断言业务结果：首页恒可见；患者检索（集内）落在患者管理分组内；患者建档/退费审批
    // （集外）隐藏，患者管理组仅剩一项仍保留分组父节点
    const texts = wrapper.findAll('.el-menu-item').map((item) => item.text());
    expect(texts).toContain('首页');
    expect(texts).toContain('患者检索');
    expect(texts).not.toContain('患者建档');
    expect(texts).not.toContain('退费审批');
    expect(wrapper.findAll('.el-menu-item')).toHaveLength(2);
    const groupTitles = wrapper.findAll('.el-sub-menu__title').map((node) => node.text());
    expect(groupTitles).toEqual(['患者管理']);
    wrapper.unmount();
  });

  it('组内菜单项全部被滤时整组剔除，部分可见时保留分组', () => {
    injectSession(['patient:archive:search', 'pharmacy:dispense:issue']);
    const wrapper = mount(AppSidebar, { global: { plugins: [pinia, router] } });

    // 断言业务结果：药房管理组仅剩发药工作台（集内）而药品字典/住院审方台被滤，分组保留；
    // 护理管理组两项（护士站/PDA 扫码）全部被滤，整组剔除不渲染空标题组
    const texts = wrapper.findAll('.el-menu-item').map((item) => item.text());
    expect(texts).toContain('发药工作台');
    expect(texts).not.toContain('药品字典');
    expect(texts).not.toContain('住院审方台');
    const groupTitles = wrapper.findAll('.el-sub-menu__title').map((node) => node.text());
    expect(groupTitles).toContain('药房管理');
    expect(groupTitles).not.toContain('护理管理');
    // 可见项 = 首页 + 患者检索 + 发药工作台
    expect(wrapper.findAll('.el-menu-item')).toHaveLength(3);
    wrapper.unmount();
  });

  it('全量权限下渲染 9 组 + 34 项的完整树形，组头与叶项均携带图标', () => {
    injectSession(allMenuPermissions());
    const wrapper = mount(AppSidebar, { global: { plugins: [pinia, router] } });

    // 断言业务结果：树形全量——9 个分组父节点、34 个页项叶节点（含首页顶层项），
    // 每个组头与叶项都渲染图标 svg（图标名→组件注册表解析成功）；组头断言排除 EP
    // 内建展开箭头图标（.el-sub-menu__icon-arrow 非 biz 图标）
    expect(wrapper.findAll('.el-sub-menu')).toHaveLength(9);
    expect(wrapper.findAll('.el-menu-item')).toHaveLength(34);
    expect(
      wrapper.findAll('.el-sub-menu__title > .el-icon:not(.el-sub-menu__icon-arrow) svg'),
    ).toHaveLength(9);
    expect(wrapper.findAll('.el-menu-item .el-icon svg').length).toBe(34);
    wrapper.unmount();
  });

  it('默认全展开且瞬切铁律锚点齐备（default-openeds 全组 + 折叠动画关闭 + 弹层挂类）', () => {
    injectSession(allMenuPermissions());
    const wrapper = mount(AppSidebar, { global: { plugins: [pinia, router] } });

    // 断言业务结果：上班扫读第一优先——挂载即全组展开（9 组名全量下发 default-openeds）；
    // 侧栏宽度切换与组展开折叠零动画（collapse-transition 关闭）；弹层经 popper-class
    // 挂 .fuy-tree-popper 承载瞬切与纸面收编（契约 §3 铁律）
    const menu = wrapper.findComponent({ name: 'ElMenu' });
    expect(menu.props('defaultOpeneds')).toHaveLength(9);
    expect(menu.props('collapseTransition')).toBe(false);
    expect(menu.props('popperClass')).toBe('fuy-tree-popper');
    wrapper.unmount();
  });

  it('路由高亮药丸选中：精确命中页项挂 is-active（亮纸白药丸类承载选中通道）', async () => {
    injectSession(['patient:archive:search']);
    await router.push('/patients');
    const wrapper = mount(AppSidebar, { global: { plugins: [pinia, router] } });

    // 断言业务结果：当前路由对应叶节点精确高亮（.is-active 药丸选中语法延续），
    // 其他页项不高亮不误标
    const active = wrapper.findAll('.el-menu-item.is-active');
    expect(active).toHaveLength(1);
    expect(active[0]?.text()).toBe('患者检索');
    wrapper.unmount();
  });

  it('收起态为图标条：组头仅剩图标（组名隐藏给 EP 收起规则）、顶层项文字转 tooltip 不内联渲染', () => {
    injectSession(allMenuPermissions());
    const wrapper = mount(AppSidebar, {
      global: { plugins: [pinia, router] },
      props: { collapsed: true },
    });

    // 断言业务结果：el-menu 挂 collapse 类（64px 图标条形态）；组头图标在位（悬浮弹层
    // 触发器，排除 EP 展开箭头图标后恰 9 枚）；顶层项正文不内联渲染（全名经 EP 内建
    // tooltip 承载，li 内无文字文本）
    expect(wrapper.find('.el-menu.el-menu--collapse').exists()).toBe(true);
    expect(
      wrapper.findAll('.el-sub-menu__title > .el-icon:not(.el-sub-menu__icon-arrow) svg'),
    ).toHaveLength(9);
    const rootItem = wrapper.findAll('.el-menu-item')[0];
    expect(rootItem?.text()).toBe('');
    wrapper.unmount();
  });
});

describe('图标注册表 MENU_ICON_REGISTRY（契约 §1 守护）', () => {
  it('43 枚图标组件全量在位，与契约 9+34 名单一一对应（显式引入禁 other 来源）', () => {
    expect(Object.keys(MENU_ICON_REGISTRY)).toHaveLength(43);
    expect(Object.keys(MENU_ICON_REGISTRY).sort()).toEqual([...CONTRACT_ALL_ICONS].sort());
    // 注册表值必须是真实组件定义（EP 图标组件对象带 name/render），防名存实空
    for (const [name, component] of Object.entries(MENU_ICON_REGISTRY)) {
      expect(component, `图标 ${name} 未解析为组件`).toBeTruthy();
    }
  });
});
