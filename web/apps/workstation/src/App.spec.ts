// 冒烟单测（因认证守卫改造同步改写，BRIEF-PR3-01 §4）：未登录访问受保护首页重定向登录页；
// 注入测试会话后首页可达且渲染站点中文名（拆两条覆盖守卫前后行为，禁留失效断言）。
// 注：全文件共享单一 Pinia 实例——路由守卫经挂载 app 上下文解析 store，逐用例换实例会割裂会话语义
import { flushPromises, mount } from '@vue/test-utils';
import { createPinia, setActivePinia } from 'pinia';
import type { Pinia } from 'pinia';
import { beforeEach, describe, expect, it } from 'vitest';
import App from './App.vue';
import { useAuthStore } from './stores/auth';
import { router } from './router';

describe('workstation 应用冒烟', () => {
  /** 文件级共享 Pinia：守卫与被挂组件必须读写同一会话 store */
  let pinia: Pinia;

  beforeEach(async () => {
    sessionStorage.clear();
    pinia = createPinia();
    // 守卫在导航期调用 useAuthStore：必须先激活 Pinia 再落登录页，避免"无激活 Pinia"导航失败
    setActivePinia(pinia);
    await router.push('/login');
  });

  it('未登录访问受保护首页重定向登录页', async () => {
    const wrapper = mount(App, { global: { plugins: [pinia, router] } });
    await router.isReady();
    await flushPromises();

    // 断言业务结果：默认拒绝生效，未登录落在登录页且渲染登录表单
    expect(router.currentRoute.value.path).toBe('/login');
    expect(wrapper.text()).toContain('请登录');
    wrapper.unmount();
  });

  it('注入测试会话后首页可达且渲染站点中文名「医护工作站」', async () => {
    const auth = useAuthStore();
    auth.token = 'smoke-access-token'; // 测试注入会话（假令牌资产，非真实凭证）
    const wrapper = mount(App, { global: { plugins: [pinia, router] } });
    await router.push('/');
    await flushPromises();

    // 断言业务结果：携带会话经真实守卫落到首页，站点中文名渲染（HomeView 锚点文案）
    expect(router.currentRoute.value.path).toBe('/');
    expect(wrapper.text()).toContain('医护工作站');
    wrapper.unmount();
  });
});
