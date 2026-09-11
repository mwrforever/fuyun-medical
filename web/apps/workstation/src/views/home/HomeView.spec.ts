// 首页骨架单测（BRIEF-PR5-01 §2.3）：已登录态（直接注入 auth store 会话态）渲染 displayName
// 问候与 loginName；空 store 兜底渲染「未登录用户」防御文案。纯展示视图零出网，直挂 Pinia 即可。
import { mount } from '@vue/test-utils';
import { createPinia, setActivePinia } from 'pinia';
import type { Pinia } from 'pinia';
import { beforeEach, describe, expect, it } from 'vitest';
import HomeView from './HomeView.vue';
import { useAuthStore } from '@/stores/auth';

describe('workstation 首页骨架', () => {
  /** 文件级 Pinia：每用例新实例保证会话态互不串扰 */
  let pinia: Pinia;

  beforeEach(() => {
    sessionStorage.clear();
    pinia = createPinia();
    setActivePinia(pinia);
  });

  it('已登录态渲染显示名问候与登录名', () => {
    // 直接注入会话态（路由守卫已保证真实入口必有会话，此处仅组件级直挂）
    const auth = useAuthStore();
    auth.token = 'test-access-token'; // 测试假令牌资产，非真实凭证
    auth.user = {
      userId: '1932000000000000001',
      loginName: 'admin',
      displayName: '系统管理员',
      orgId: null,
      roles: ['ADMIN'],
    };
    const wrapper = mount(HomeView, { global: { plugins: [pinia] } });
    // 断言业务结果：问候区展示 displayName + loginName，业务开通占位文案在位
    expect(wrapper.text()).toContain('系统管理员，欢迎回来');
    expect(wrapper.text()).toContain('admin');
    expect(wrapper.text()).toContain('业务功能随各模块逐步开通');
    wrapper.unmount();
  });

  it('空会话兜底渲染未登录防御文案', () => {
    // 直接挂载场景（未经理守卫）的防御兜底：displayName/loginName 空值不渲染裸 undefined
    const wrapper = mount(HomeView, { global: { plugins: [pinia] } });
    expect(wrapper.text()).toContain('未登录用户，欢迎回来');
    expect(wrapper.text()).not.toContain('undefined');
    wrapper.unmount();
  });
});
