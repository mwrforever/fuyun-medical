// 403 无权限页单测（暖纸卷宗基础册 · 契约 ⑪.7 门牌构图重写 + ⑪.9-3 TDD 锚点）：
// 门牌页首（衬线标题「403 · 无访问权限」+「谁·何时」批注行空值以 — 占位零伪数据）、
// 书法「禁」静态水印装饰层（aria-hidden）、诚实错误脸（.fuy-empty 承载说问题+给恢复，
// role=alert）、返回首页经真实路由守卫导航（登录会话注入承载，假令牌资产，非真实凭证）、
// 零 el-result 结构（旧 el-result 脸断言随本次构图重写一并删除——重写属本次功能交付内
// 合法断言现代化，契约 ⑪.9-3 留痕）。
// 注：全文件共享单一 Pinia——与挂载组件必须读写同一会话 store。
import { mount } from '@vue/test-utils';
import { createPinia, setActivePinia } from 'pinia';
import type { Pinia } from 'pinia';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { router } from '@/router';
import { useAuthStore } from '@/stores/auth';
import ForbiddenView from './ForbiddenView.vue';

describe('403 无权限页（门牌构图）', () => {
  /** 文件级共享 Pinia：被挂组件与用例注入的会话必须同一 store 实例 */
  let pinia: Pinia;

  beforeEach(async () => {
    sessionStorage.clear();
    pinia = createPinia();
    setActivePinia(pinia);
    // 预落登录页：消除路由器安装期初始导航与用例交互的竞争
    await router.push('/login');
  });

  it('渲染门牌构图锚点：衬线标题/书法禁水印/错误脸主句说明/零 el-result', () => {
    const wrapper = mount(ForbiddenView, { global: { plugins: [pinia, router] } });

    // 断言业务结果：拦截落点页向用户明示无权限语义（门牌页首衬线标题）
    expect(wrapper.find('.fuy-page-title').text()).toBe('403 · 无访问权限');

    // 书法「禁」水印为装饰层：aria-hidden 不进读屏，静态零动画（业务页禁氛围动效）
    const mark = wrapper.find('.forbidden-mark');
    expect(mark.exists()).toBe(true);
    expect(mark.text()).toBe('禁');
    expect(mark.attributes('aria-hidden')).toBe('true');

    // 错误脸（.fuy-empty 承载）：说问题（主句）+ 给恢复（说明），role=alert 提示即时性
    expect(wrapper.find('.fuy-empty').exists()).toBe(true);
    expect(wrapper.find('.fuy-empty').attributes('role')).toBe('alert');
    expect(wrapper.find('.fuy-empty-title').text()).toBe('当前会话未获授权访问该功能');
    expect(wrapper.find('.fuy-empty-hint').text()).toContain('请联系管理员开通权限');

    // 旧 el-result 脸随构图重写退场：零残留
    expect(wrapper.find('.el-result').exists()).toBe(false);

    // 返回首页出口恒在
    const homeButton = wrapper.findAll('button').find((b) => b.text() === '返回首页');
    expect(homeButton).toBeDefined();
    wrapper.unmount();
  });

  it('批注行存在且空值以 — 占位（「谁·何时」语法零伪数据）', () => {
    const wrapper = mount(ForbiddenView, { global: { plugins: [pinia, router] } });

    // 断言业务结果：批注行渲染且空值档位以 — 占位（禁伪数据铁律）
    const note = wrapper.find('.fuy-page-note');
    expect(note.exists()).toBe(true);
    expect(note.text()).toContain('—');
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
