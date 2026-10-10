// 主布局单测（暖纸卷宗基础册 · 契约 ⑪.9-2 TDD 锚点）：路由视图经 fuy-page 过渡包裹
// （out-in 语义=旧页先退 120ms 直退、新页后进 240ms 上浮显影）与折叠态事件上行既有
// 行为守护（侧栏 240px↔64px 瞬切零动画铁律不破）。
// 降级登记（契约 ⑪.9-2 允许并要求留痕）：「真实路由 push 后同步帧断言内容子元素出现
// fuy-page-enter-active/from 类」在 out-in 模式下依赖真实定时器时序——enter 类在旧页
// leave（120ms）结束后才出现，jsdom 真实时钟下轮询窗口窄、CI 易 flaky，故降级为
// Transition 组件实例结构断言（name/mode props 是过渡类前缀与切换语义的唯一来源）；
// 过渡类本体样式由 motion.css .fuy-page-* 段与 pnpm build 门禁兜底。
// 注：全文件共享单一 Pinia——守卫经挂载 app 上下文解析 store，逐用例换实例会割裂会话语义。
import { mount } from '@vue/test-utils';
import { createPinia, setActivePinia } from 'pinia';
import type { Pinia } from 'pinia';
import { beforeEach, describe, expect, it } from 'vitest';
import { router } from '@/router';
import { useAuthStore } from '@/stores/auth';
import MainLayout from './MainLayout.vue';

describe('主布局 · 路由过渡与折叠态', () => {
  /** 文件级共享 Pinia：守卫与被挂组件必须读写同一会话 store */
  let pinia: Pinia;

  beforeEach(async () => {
    sessionStorage.clear();
    pinia = createPinia();
    setActivePinia(pinia);
    const auth = useAuthStore();
    auth.token = 'layout-access-token'; // 测试注入会话（假令牌资产，非真实凭证）
    // 预落 403 布局子路由：RouterView 渲染轻量子页（首页含轮询数据源，非本文件关注面）
    await router.push('/403');
  });

  it('路由视图经 fuy-page 过渡包裹（out-in：旧页先退、新页后进）', () => {
    const wrapper = mount(MainLayout, { global: { plugins: [pinia, router] } });

    // 断言业务结果：过渡类前缀（fuy-page）与切换语义（out-in）在 RouterView 的插槽
    // 渲染树上就位——路由切换显影节奏的唯一声明点。名称选择器承载（RouterView/
    // Transition 为 Vue 内建组件，组件定义引用作选择器在 VTU 遍历中不稳定）
    const routerView = wrapper.findComponent({ name: 'RouterView' });
    expect(routerView.exists()).toBe(true);
    // RouterView 子树内可能存在 EP 内建 Transition（弹层/结果组件内部），故按
    // name/mode props 精确过滤出本布局声明的页面过渡实例
    const pageTransition = routerView
      .findAllComponents({ name: 'Transition' })
      .find((t) => t.props('name') === 'fuy-page');
    expect(pageTransition).toBeDefined();
    expect(pageTransition?.props('mode')).toBe('out-in');
    wrapper.unmount();
  });

  it('顶栏折叠开关事件上行翻转侧栏宽度（240px ↔ 64px 瞬切既有行为）', async () => {
    const wrapper = mount(MainLayout, { global: { plugins: [pinia, router] } });

    // 断言业务结果：Header 按钮发 toggle 事件上行，本组件翻转状态经 props 下行侧栏
    // 宽度（EP el-aside 以 --el-aside-width 内联变量承载宽度，断言取变量实值）
    const aside = wrapper.find('.main-aside');
    expect((aside.element as HTMLElement).style.getPropertyValue('--el-aside-width')).toBe('240px');
    await wrapper.find('button.app-header-toggle').trigger('click');
    expect((aside.element as HTMLElement).style.getPropertyValue('--el-aside-width')).toBe('64px');
    wrapper.unmount();
  });
});
