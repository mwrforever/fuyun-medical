// 侧栏树形菜单单测（批次 2 册 1 契约 §3）：权限过滤（BUG-14 守卫骨架 + PR-4D 空集语义，
// 路由 meta 单一事实源经真实路由表反查）、树形渲染（分组父节点/页项叶节点/组内全滤整组
// 剔除）、图标注册表对照契约 §1 全 43 枚、默认全展开与瞬切铁律锚点（collapseTransition
// 关闭 + popper 挂类）、路由高亮药丸选中延续、收起态图标条形态、键盘可达（W-105②
// roving tabindex：Tab 入口唯一/方向键走序列/Enter 导航与组开合/折叠组叶项退出序列）。
// 会话以直接注入 state 的方式承载（假令牌资产，非真实凭证）。
// 注：全文件共享单一 Pinia——与挂载组件必须读写同一会话 store，逐用例换实例会割裂会话语义。
import { readFileSync } from 'node:fs';
import { resolve } from 'node:path';
import { flushPromises, mount } from '@vue/test-utils';
import { createPinia, setActivePinia } from 'pinia';
import type { Pinia } from 'pinia';
import { beforeEach, describe, expect, it } from 'vitest';
import { router } from '@/router';
import { MENU_ITEMS } from '@/views/layout/menu';
import { useAuthStore } from '@/stores/auth';
import AppSidebar, { MENU_ICON_REGISTRY } from './AppSidebar.vue';

/** 契约 §1 图标全集（9 组 + 34 项 = 43 枚，断言锚点=契约原文；2026-10-12 D-29 九枚
 * 圆形笔画图标取代后值域——取代出处=CHANGELOG 同日 D-29 条目，断言现代化留痕） */
const CONTRACT_ALL_ICONS = [
  // §1.1 分组图标
  'FolderOpened',
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
  'Filter',
  'Tickets',
  'Guide',
  'EditPen',
  'PriceTag',
  'Stamp',
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
  'Management',
  'Paperclip',
  'Flag',
  'Promotion',
  'MagicStick',
  'TrendCharts',
  'Pouring',
  'Phone',
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

  it('收起态激活项白块回归守护：EP tooltip trigger 结构前提在位（图标随 trigger 渲染）且收起态居中收编规则落位于 scoped 段', async () => {
    injectSession(allMenuPermissions());
    // 预落首页：激活顶层项（首页）使收起态药丸选中语法生效
    await router.push('/');
    const wrapper = mount(AppSidebar, {
      global: { plugins: [pinia, router] },
      props: { collapsed: true },
    });

    // 断言①DOM 结构前提（EP 收起态结构守护）：顶层激活项内层 EP 内建 tooltip trigger
    // （div.el-menu-tooltip__trigger，绝对定位铺满项体承载图标）在位——居中修复即清零其
    // EP 自带 0 20px 内边距；trigger 缺位=EP 结构漂移致规则落空，激活药丸退化为白色
    // 无语义块（2026-10-11 白块形态实测前提：svg 48..64 vs 药丸 16..47，图标整体移出药丸）
    const activeTrigger = wrapper.find('.el-menu-item.is-active .el-menu-tooltip__trigger');
    expect(activeTrigger.exists()).toBe(true);
    // 断言②图标随 trigger 内容渲染：图标在 trigger 内=居中规则的作用对象在位
    expect(activeTrigger.find('.el-icon svg').exists()).toBe(true);

    // 断言③样式源守护：vitest 测试环境桩化组件样式不真实加载（vitest.config deps.inline
    // css:false 口径），CSS 效果无法在 jsdom 断言，收编规则只能在源码层锚定
    // （menuPermissionMatrix.spec readFileSync 跨层守护先例同款）——清零 trigger 内边距
    // 与收起态图标右距两组规则必须在 AppSidebar scoped 段在位，缺任一=图标移出药丸
    // 白块回归；通道必须是 .fuy-menu 挂类 + :deep() 组合（非裸改 .el-*）
    const source = readFileSync(resolve(import.meta.dirname, './AppSidebar.vue'), 'utf-8');
    expect(source).toContain('.fuy-menu.el-menu--collapse :deep(.el-menu-tooltip__trigger)');
    expect(source).toContain('.fuy-menu.el-menu--collapse :deep(.el-menu-item .el-icon)');
    expect(source).toContain('.fuy-menu.el-menu--collapse :deep(.el-sub-menu__title .el-icon)');
    wrapper.unmount();
  });

  it('树形进场级联挂类与级联序锚点（琢段动效）：el-menu 挂 fuy-tree-stagger，行级联序=品牌头 0/顶层项 1/组块自 2 递增（原始序内联，封顶由 motion.css min() 执行）', () => {
    injectSession(allMenuPermissions());
    const wrapper = mount(AppSidebar, { global: { plugins: [pinia, router] } });

    // 断言业务结果：进场级联挂类在位（motion.css .fuy-tree-stagger 唯一定义处的消费锚）；
    // 行元素内联 --fuy-stagger-index 承载级联序——顶层项第 1 档、首组第 2 档、末组第 10
    // 档（原始序直传，min() 封顶属样式层职责不进模板）；叶项内联序=组内序（0 起，供
    // fuy-leaf-reveal-in 展开显影 delay 取值）。样式断言用空白容忍正则（jsdom 序列化
    // 冒号后带空格；\b 防前缀误配，如 1 不匹配 10）
    expect(wrapper.find('.fuy-menu').classes()).toContain('fuy-tree-stagger');
    expect(wrapper.find('.fuy-tree-root').attributes('style')).toMatch(
      /--fuy-stagger-index:\s*1\b/,
    );
    const groupLis = wrapper.findAll('.el-sub-menu');
    expect(groupLis[0]?.attributes('style')).toMatch(/--fuy-stagger-index:\s*2\b/);
    expect(groupLis[8]?.attributes('style')).toMatch(/--fuy-stagger-index:\s*10\b/);
    const firstLeafOfFirstGroup = groupLis[0]?.find('.el-menu-item');
    expect(firstLeafOfFirstGroup?.attributes('style')).toMatch(/--fuy-stagger-index:\s*0\b/);
    wrapper.unmount();
  });

  // ===== 键盘可达（W-105②，2026-10-12）：roving tabindex 方案的业务行为断言 =====

  /**
   * 取 DOMWrapper 的真实 HTMLElement（VTU 按选择器推断 element 为 Element，focus 断言
   * 与按键派发需要 HTMLElement）。
   *
   * @param node 测试包装节点（菜单项/组头）
   * @return 同一 DOM 的 HTMLElement 视图
   */
  function htmlOf(node: { element: Element }): HTMLElement {
    return node.element as HTMLElement;
  }

  /**
   * 向 document 派发真实 keydown（键盘导航监听挂 document 级——收起态组弹层 teleported
   * 至 body 组件根触达不了，实现统一单监听）。
   *
   * @param key 键名（KeyboardEvent.key，如 ArrowDown/Enter/Home）
   * @param target 按键目标元素（菜单项或组头 DOM）
   */
  function pressKey(key: string, target: Element): void {
    target.dispatchEvent(new KeyboardEvent('keydown', { key, bubbles: true, cancelable: true }));
  }

  it('键盘可达：挂载即施加 roving tabindex——路由激活叶项为唯一 Tab 入口（组头与其余项 -1）', async () => {
    injectSession(['patient:archive:search']);
    await router.push('/patients');
    // attachTo 文档内：jsdom 仅对文档内元素设置 document.activeElement（焦点断言前提）
    const wrapper = mount(AppSidebar, {
      attachTo: document.body,
      global: { plugins: [pinia, router] },
    });
    await flushPromises();

    // 断言业务结果：Tab 恰能进入侧栏一次（序列内唯一 tabindex=0 项=当前业务位），
    // 组头与未激活项均 -1——修复前全部项 -1 且容器无 tabindex，Tab 无法进侧栏
    const tabEntries = wrapper
      .findAll('.el-menu-item, .el-sub-menu__title')
      .filter((node) => node.attributes('tabindex') === '0');
    expect(tabEntries).toHaveLength(1);
    expect(tabEntries[0]?.text()).toContain('患者检索');
    expect(
      wrapper.findAll('.el-sub-menu__title').every((node) => node.attributes('tabindex') === '-1'),
    ).toBe(true);
    wrapper.unmount();
  });

  it('键盘可达：ArrowDown/ArrowUp 在可见序列内逐项移动焦点并同步 roving（展开组叶项在序列内）', async () => {
    injectSession(['patient:archive:search']);
    await router.push('/patients');
    // attachTo 文档内：jsdom 仅对文档内元素设置 document.activeElement（焦点断言前提）
    const wrapper = mount(AppSidebar, {
      attachTo: document.body,
      global: { plugins: [pinia, router] },
    });
    await flushPromises();

    // 序列（DOM 序）：首页 → 患者管理组头 → 患者检索（默认全展开，组内叶项可见入序列）
    const homeItem = wrapper.findAll('.el-menu-item')[0];
    const groupTitle = wrapper.findAll('.el-sub-menu__title')[0];
    const leaf = wrapper.findAll('.el-menu--inline .el-menu-item')[0];

    // 自激活叶项向上走：组头 ← 叶项；焦点与 roving（tabindex=0）随动同一项
    htmlOf(leaf).focus();
    pressKey('ArrowUp', htmlOf(leaf));
    expect(document.activeElement).toBe(groupTitle.element);
    expect(groupTitle.attributes('tabindex')).toBe('0');
    expect(leaf.attributes('tabindex')).toBe('-1');

    // 组头继续向上回到首项；再向下穿组头入叶项（方向键可走全序列）
    pressKey('ArrowUp', htmlOf(groupTitle));
    expect(document.activeElement).toBe(homeItem.element);
    pressKey('ArrowDown', htmlOf(homeItem));
    expect(document.activeElement).toBe(groupTitle.element);
    pressKey('ArrowDown', htmlOf(groupTitle));
    expect(document.activeElement).toBe(leaf.element);
    wrapper.unmount();
  });

  it('键盘可达：Enter 激活叶项触发真实路由导航（URL 变更）', async () => {
    injectSession(['patient:archive:search']);
    await router.push('/');
    // attachTo 文档内：jsdom 仅对文档内元素设置 document.activeElement（焦点断言前提）
    const wrapper = mount(AppSidebar, {
      attachTo: document.body,
      global: { plugins: [pinia, router] },
    });
    await flushPromises();

    const leaf = wrapper.findAll('.el-menu-item').find((node) => node.text() === '患者检索')!;
    htmlOf(leaf).focus();
    pressKey('Enter', htmlOf(leaf));
    await flushPromises();

    // 断言业务结果：键盘激活与鼠标点击同效——EP onClick 链路承接路由跳转
    expect(router.currentRoute.value.path).toBe('/patients');
    wrapper.unmount();
  });

  it('键盘可达：Enter 在展开态组头切换组开合，折叠后其叶项退出焦点序列（ArrowDown 跳过）', async () => {
    injectSession(['patient:archive:search']);
    await router.push('/patients');
    // attachTo 文档内：jsdom 仅对文档内元素设置 document.activeElement（焦点断言前提）
    const wrapper = mount(AppSidebar, {
      attachTo: document.body,
      global: { plugins: [pinia, router] },
    });
    await flushPromises();

    const groupLi = wrapper.findAll('.el-sub-menu')[0];
    const groupTitle = wrapper.findAll('.el-sub-menu__title')[0];
    const homeItem = wrapper.findAll('.el-menu-item')[0];
    expect(groupLi.classes()).toContain('is-opened');

    // 组头 Enter=切换组开合（EP onClick 承载）；收起后叶项隐藏
    htmlOf(groupTitle).focus();
    pressKey('Enter', htmlOf(groupTitle));
    await flushPromises();
    expect(groupLi.classes()).not.toContain('is-opened');

    // 折叠组的叶项不可见不入序列：组头上 ArrowDown 越过叶项环绕回首项
    pressKey('ArrowDown', htmlOf(groupTitle));
    expect(document.activeElement).toBe(homeItem.element);
    wrapper.unmount();
  });

  it('键盘可达：Home/End 跳序列首尾；Ctrl 修饰组合不拦截（浏览器快捷键让路）', async () => {
    injectSession(['patient:archive:search']);
    await router.push('/patients');
    // attachTo 文档内：jsdom 仅对文档内元素设置 document.activeElement（焦点断言前提）
    const wrapper = mount(AppSidebar, {
      attachTo: document.body,
      global: { plugins: [pinia, router] },
    });
    await flushPromises();

    const nodes = wrapper.findAll('.el-menu-item, .el-sub-menu__title');
    const first = nodes[0];
    const last = nodes[nodes.length - 1];
    const leaf = wrapper.findAll('.el-menu--inline .el-menu-item')[0];

    htmlOf(leaf).focus();
    pressKey('End', htmlOf(leaf));
    expect(document.activeElement).toBe(last.element);
    pressKey('Home', htmlOf(last));
    expect(document.activeElement).toBe(first.element);

    // 带 Ctrl 的方向键不属侧栏职责：焦点原位不动（修饰组合让路浏览器快捷键）
    htmlOf(first).dispatchEvent(
      new KeyboardEvent('keydown', {
        key: 'ArrowDown',
        bubbles: true,
        cancelable: true,
        ctrlKey: true,
      }),
    );
    expect(document.activeElement).toBe(first.element);
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
