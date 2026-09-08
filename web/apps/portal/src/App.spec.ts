// 冒烟单测（真实行为断言，web 简报 §5.3）：应用根组件可挂载、首页路由可达且渲染出站点中文名
import { flushPromises, mount } from '@vue/test-utils';
import { createPinia } from 'pinia';
import { describe, expect, it } from 'vitest';
import App from './App.vue';
import { router } from './router';

describe('portal 应用冒烟', () => {
  it('挂载后路由就绪且首页渲染站点中文名「患者门户」', async () => {
    // 以真实 Pinia + Router 装配根组件，走完整挂载链路
    const wrapper = mount(App, { global: { plugins: [createPinia(), router] } });
    // 等待首次导航完成与懒加载路由组件渲染
    await router.isReady();
    await flushPromises();
    // 断言业务结果：页面可达（站点中文名渲染）+ 路由就绪落在首页
    expect(wrapper.text()).toContain('患者门户');
    expect(router.currentRoute.value.path).toBe('/');
  });
});
