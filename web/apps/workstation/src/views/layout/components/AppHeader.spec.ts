// 顶栏组件单测：全局患者检索入口的三态行为——有权限会话渲染入口、点击跳转既有
// /patients 路由（真实路由入口零新增出网）、无权限会话随权限过滤不渲染（与侧栏/守卫
// 同口径：路由 meta 单一事实源 + hasRoutePermission）。挂载需 router（入口经
// router.resolve 反查权限点并承担跳转），会话直接注入 state（假令牌资产，非真实凭证）。
// 注：每用例新 Pinia 实例保证会话态互不串扰。
import { mount } from '@vue/test-utils';
import { createPinia, setActivePinia } from 'pinia';
import type { Pinia } from 'pinia';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { router } from '@/router';
import { useAuthStore } from '@/stores/auth';
import AppHeader from './AppHeader.vue';

describe('顶栏全局患者检索入口', () => {
  /** 文件级 Pinia：每用例新实例保证会话态互不串扰 */
  let pinia: Pinia;

  /**
   * 构造带权限点集的测试会话。
   *
   * @param permissions 权限点编码集；空数组 = 无任何业务权限的授权会话（PR-4D 全拒口径）
   */
  function injectSession(permissions: string[]): void {
    const auth = useAuthStore();
    auth.token = 'header-access-token'; // 测试假令牌资产，非真实凭证
    auth.user = {
      userId: '1',
      loginName: 'admin',
      displayName: '系统管理员',
      orgId: undefined,
      roles: [],
      permissions,
    };
  }

  beforeEach(async () => {
    sessionStorage.clear();
    pinia = createPinia();
    setActivePinia(pinia);
    // 预落登录页：入口消费路由表反查权限点，与当前停留路由解耦，只求路由器就绪
    await router.push('/login');
  });

  it('有权限会话渲染患者检索入口，站点名冒烟锚点不受影响', () => {
    injectSession(['patient:archive:search']);
    const wrapper = mount(AppHeader, { global: { plugins: [pinia, router] } });

    // 断言业务结果：入口按钮在位且为纯文字形态；「富云医护工作站」锚点保持（App.spec 锚点）
    const search = wrapper.find('button.app-header-search');
    expect(search.exists()).toBe(true);
    expect(search.text()).toBe('患者检索');
    expect(search.attributes('aria-label')).toBe('全局患者检索');
    expect(wrapper.text()).toContain('富云医护工作站');
    wrapper.unmount();
  });

  it('点击患者检索入口跳转既有 /patients 路由', async () => {
    injectSession(['patient:archive:search']);
    const wrapper = mount(AppHeader, { global: { plugins: [pinia, router] } });

    await wrapper.find('button.app-header-search').trigger('click');
    // 路由组件懒加载为宏任务，flushPromises 不等待动态 import：轮询至导航真正落地
    await vi.waitFor(() => expect(router.currentRoute.value.path).toBe('/patients'));
    wrapper.unmount();
  });

  it('无权限会话不渲染患者检索入口，折叠开关与用户区恒在', () => {
    injectSession([]);
    const wrapper = mount(AppHeader, { global: { plugins: [pinia, router] } });

    // 断言业务结果：入口随权限过滤消失；顶栏基础功能集（折叠开关）不受权限过滤影响
    expect(wrapper.find('button.app-header-search').exists()).toBe(false);
    expect(wrapper.find('button.app-header-toggle').exists()).toBe(true);
    wrapper.unmount();
  });
});
