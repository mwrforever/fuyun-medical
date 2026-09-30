// 403 无权限页单测（BUG-14 守卫骨架落点）：页面渲染 403 语义文案与返回首页出口；
// 返回首页经真实路由守卫导航（登录会话注入承载，假令牌资产，非真实凭证）。
// 注：全文件共享单一 Pinia——与挂载组件必须读写同一会话 store。
import { mount } from '@vue/test-utils';
import { createPinia, setActivePinia } from 'pinia';
import type { Pinia } from 'pinia';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { router } from '@/router';
import { useAuthStore } from '@/stores/auth';
import ForbiddenView from './ForbiddenView.vue';

describe('403 无权限页', () => {
  /** 文件级共享 Pinia：被挂组件与用例注入的会话必须同一 store 实例 */
  let pinia: Pinia;

  beforeEach(async () => {
    sessionStorage.clear();
    pinia = createPinia();
    setActivePinia(pinia);
    // 预落登录页：消除路由器安装期初始导航与用例交互的竞争
    await router.push('/login');
  });

  it('渲染 403 语义文案与返回首页出口', () => {
    const wrapper = mount(ForbiddenView, { global: { plugins: [pinia, router] } });

    // 断言业务结果：拦截落点页向用户明示无权限语义，并提供确定可达的返回首页出口
    expect(wrapper.text()).toContain('403');
    expect(wrapper.text()).toContain('无访问权限');
    const homeButton = wrapper.findAll('button').find((b) => b.text() === '返回首页');
    expect(homeButton).toBeDefined();
    wrapper.unmount();
  });

  it('点击返回首页按钮经守卫导航回首页', async () => {
    const auth = useAuthStore();
    auth.token = 'forbidden-access-token'; // 测试注入会话（假令牌资产，非真实凭证）
    const wrapper = mount(ForbiddenView, { global: { plugins: [pinia, router] } });

    const homeButton = wrapper.findAll('button').find((b) => b.text() === '返回首页');
    if (!homeButton) {
      throw new Error('未找到返回首页按钮');
    }
    await homeButton.trigger('click');

    // 首页懒加载经 MainLayout 耗时跨宏任务：轮询等待导航真正完成（显式 3s 超时防慢 CI flaky）
    await vi.waitFor(
      () => {
        expect(router.currentRoute.value.path).toBe('/');
      },
      { timeout: 3000 },
    );
    wrapper.unmount();
  });
});
