// 工作站首页单测（批次 2 册 1 契约 §4 删岗后口径）：门牌页首（displayName/loginName 沿用
// auth store，空会话防御兜底；批注行=「登录名 X · YYYY-MM-DD 周Z」无岗位段）、常用入口
// 链接条仅权限单道过滤（与侧栏同口径，入口经真实路由反查权限点；原岗位注入通道已废除）、
// 空权限会话渲染诚实空态。挂载需 router（入口权限点经 router.resolve 反查），会话以直接
// 注入 state 方式承载（假令牌资产）。
// 注：每用例新 Pinia 实例保证会话态互不串扰。
import { mount } from '@vue/test-utils';
import { createPinia, setActivePinia } from 'pinia';
import type { Pinia } from 'pinia';
import { beforeEach, describe, expect, it } from 'vitest';
import { router } from '@/router';
import { useAuthStore } from '@/stores/auth';
import HomeView from './HomeView.vue';

/** 批注行日期格式锚点（YYYY-MM-DD 周X，中文星期单字） */
const DATE_PATTERN = /\d{4}-\d{2}-\d{2} 周[日一二三四五六]/;

describe('workstation 工作站首页', () => {
  /** 文件级 Pinia：每用例新实例保证会话态互不串扰 */
  let pinia: Pinia;

  /**
   * 构造带权限点集的测试会话。
   *
   * @param permissions 权限点编码集；空数组 = 无任何业务权限的授权会话（PR-4D 全拒口径）
   */
  function injectSession(permissions: string[]): void {
    const auth = useAuthStore();
    auth.token = 'home-access-token'; // 测试假令牌资产，非真实凭证
    auth.user = {
      userId: '1',
      loginName: 'admin',
      displayName: '系统管理员',
      orgId: undefined,
      roles: ['ADMIN'],
      permissions,
    };
  }

  beforeEach(async () => {
    sessionStorage.clear();
    pinia = createPinia();
    setActivePinia(pinia);
    // 预落登录页：首页消费路由表反查权限点，与当前停留路由解耦，只求路由器就绪
    await router.push('/login');
  });

  it('已登录渲染问候语与「登录名 · 日期」批注行（删岗后无岗位段与岗位描边印）', () => {
    injectSession([]);
    const wrapper = mount(HomeView, { global: { plugins: [pinia, router] } });
    // 断言业务结果：问候区展示 displayName（时段词随钟点变，断言不绑钟点）+ 批注行
    // 「登录名 admin · YYYY-MM-DD 周X」；岗位维度废除后不再渲染「当前岗位」段与描边印
    expect(wrapper.text()).toContain('，系统管理员');
    expect(wrapper.text()).toContain('登录名 admin ·');
    expect(wrapper.text()).toMatch(DATE_PATTERN);
    expect(wrapper.text()).not.toContain('当前岗位');
    expect(wrapper.find('.home-post-stamp').exists()).toBe(false);
    wrapper.unmount();
  });

  it('入口链接条仅按权限单道过滤：跨业务域入口共存（删岗后不再岗位互斥）', () => {
    injectSession(['nursing:ward:view', 'pharmacy:dispense:issue']);
    const wrapper = mount(HomeView, { global: { plugins: [pinia, router] } });
    // 断言取入口名称标签（.home-quick-link-label），避免整条文本（缩写+名称）拼接干扰
    const labels = wrapper
      .findAll('a.home-quick-link .home-quick-link-label')
      .map((node) => node.text());
    // 护理域与药房域入口同时可见（权限单道）；集外权限入口（挂号收费）隐藏；
    // 首页自身不入链接条（当前页自引用无意义）
    expect(labels).toContain('护士站');
    expect(labels).toContain('发药工作台');
    expect(labels).not.toContain('挂号收费');
    expect(labels).not.toContain('首页');
    expect(wrapper.findAll('a.home-quick-link')).toHaveLength(2);
    wrapper.unmount();
  });

  it('入口缩写为页名首字派生的单字纸块（缩写字段废除后的标签架语法延续）', () => {
    injectSession(['nursing:ward:view', 'pharmacy:dispense:issue']);
    const wrapper = mount(HomeView, { global: { plugins: [pinia, router] } });
    const initials = wrapper
      .findAll('a.home-quick-link .home-quick-mark')
      .map((node) => node.text());
    // 单字缩写与页名首字一一对应（装饰性重复字符，aria-hidden 承载）；顺序沿菜单常量
    // 首现序（发药工作台先于护士站）
    expect(initials).toEqual(['发', '护']);
    wrapper.unmount();
  });

  it('空权限会话渲染诚实空态（真实权限语义非占位文案），链接条不渲染', () => {
    injectSession([]);
    const wrapper = mount(HomeView, { global: { plugins: [pinia, router] } });
    // 断言业务结果：权限过滤后零入口 → 空态弱形态，文案为真实权限语义且无岗位措辞
    expect(wrapper.text()).toContain('暂无可视入口');
    expect(wrapper.text()).toContain('当前会话未被授予任何业务功能的访问权限');
    expect(wrapper.find('a.home-quick-link').exists()).toBe(false);
    wrapper.unmount();
  });

  it('空会话兜底渲染未登录防御文案（直挂场景不渲染裸 undefined，登录名以 — 占位）', () => {
    const wrapper = mount(HomeView, { global: { plugins: [pinia, router] } });
    expect(wrapper.text()).toContain('，未登录用户');
    expect(wrapper.text()).toContain('登录名 —');
    expect(wrapper.text()).not.toContain('undefined');
    wrapper.unmount();
  });
});
