// 登录页单测（批次 2 册 1 契约 §2 v3「暖纸卷宗 + 朱砂印鉴」门面）：空表单提交被校验拦截、
// 有效提交调用认证链路 →「启封成功」钤印浮层显示 → 编排后跳转首页、登录失败不钤印不跳转、
// reduced-motion 编排缩短仍完整；门面视觉锚点在位（stage 双栏/封面品牌印/病案表头/双字段/
// 审计注记）。api 层 mock 承载（不打真实网络），错误弹窗口径归 http.spec 覆盖，本文件不重复断言。
// 视觉锚点只锚类名与文案（不绑 EP 内部结构，重构不破）；jsdom 不计算样式，装饰形态以类名锚定。
// 注：beforeEach 预导航登录页（消除路由器安装期初始导航与用例交互的竞争），
// 全文件共享单一 Pinia——守卫经挂载 app 上下文解析 store，逐用例换实例会割裂会话语义。
import { mount } from '@vue/test-utils';
import { createPinia, setActivePinia } from 'pinia';
import type { Pinia } from 'pinia';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { login as loginApiMock } from '@/api/auth';
import { router } from '@/router';
import LoginView from './LoginView.vue';
import type { LoginResponse } from '@/api/auth';

vi.mock('@/api/auth', () => ({
  login: vi.fn(),
  logout: vi.fn(),
  refresh: vi.fn(),
}));

/** 构造登录成功响应（契约字段：userId/expiresIn 为后端 Long 经全局 Long→String 的字符串输出） */
function loginResponse(): LoginResponse {
  return {
    accessToken: 'access-token-1',
    refreshToken: 'refresh-token-1',
    tokenType: 'Bearer',
    expiresIn: '7200',
    user: {
      userId: '1932000000000000001',
      loginName: 'admin',
      displayName: '系统管理员',
      orgId: undefined,
      roles: ['ADMIN'],
    },
  };
}

describe('登录页', () => {
  /** 文件级共享 Pinia：守卫与被挂组件必须读写同一会话 store */
  let pinia: Pinia;

  beforeEach(async () => {
    sessionStorage.clear();
    vi.mocked(loginApiMock).mockReset();
    pinia = createPinia();
    setActivePinia(pinia);
    // 预先落在登录页：安装期不再触发初始导航，用例内导航行为完全确定
    await router.push('/login');
  });

  afterEach(() => {
    vi.useRealTimers();
    vi.unstubAllGlobals();
  });

  it('渲染暖纸卷宗门面锚点与登录表单骨架（stage 双栏/品牌钤印/病案表头/双字段/审计/浮层常驻隐藏）', () => {
    const wrapper = mount(LoginView, { global: { plugins: [pinia, router] } });

    // stage 双栏卡片骨架：左封面 + 右病案内页（契约 §2 v3 构图，全站唯一全视口页）
    expect(wrapper.find('.login-stage').exists()).toBe(true);
    expect(wrapper.find('.login-cover').exists()).toBe(true);
    expect(wrapper.find('.login-sheet').exists()).toBe(true);

    // 品牌钤印「富」与病案表头「启 · 当日病案」锚点（契约 §2 v3，文案语义零漂移）
    expect(wrapper.find('.login-brand-mark').text()).toBe('富');
    expect(wrapper.find('.login-sheet-title h2').text()).toBe('启 · 当日病案');

    // 表单骨架：登录名/口令双字段 + 登录按钮（主提交按钮为原生 button 载体，类名精确定位）
    expect(wrapper.findAll('input')).toHaveLength(2);
    expect(wrapper.find('.login-submit').text()).toBe('登 录');

    // 审计注记（等保三级真实合规语义）与钤印浮层初始隐藏态（失败前不显示）
    expect(wrapper.text()).toContain('登录行为纳入审计日志 · 留存不少于六个月');
    expect(wrapper.find('.login-stamp-toast').classes()).not.toContain('is-show');
    wrapper.unmount();
  });

  it('空表单提交被校验拦截，不触发登录调用', async () => {
    const wrapper = mount(LoginView, { global: { plugins: [pinia, router] } });

    // Element Plus 校验状态有 100ms 防抖：用假时钟推进驱动校验链与错误文案渲染
    vi.useFakeTimers();
    await wrapper.find('.login-submit').trigger('click');
    await vi.advanceTimersByTimeAsync(200);

    // 断言业务结果：校验未通过时登录 api 不被触达，且校验文案渲染给用户
    expect(vi.mocked(loginApiMock)).not.toHaveBeenCalled();
    expect(wrapper.text()).toContain('请输入登录名');
    expect(wrapper.text()).toContain('请输入口令');
    wrapper.unmount();
  });

  it('有效表单提交调用登录并跳转首页', async () => {
    vi.mocked(loginApiMock).mockResolvedValue(loginResponse());
    const wrapper = mount(LoginView, { global: { plugins: [pinia, router] } });

    const inputs = wrapper.findAll('input');
    await inputs[0]?.setValue('admin');
    await inputs[1]?.setValue('Fuyun@2026');
    await wrapper.find('.login-submit').trigger('click');
    // 登录成功后的跳转含懒加载布局组件的动态导入（耗时跨宏任务）：轮询等待导航真正完成，
    // 显式 3s 超时（MainLayout 懒加载实测约 0.5s），防慢 CI flaky
    await vi.waitFor(
      () => {
        expect(vi.mocked(loginApiMock)).toHaveBeenCalledWith({
          loginName: 'admin',
          password: 'Fuyun@2026',
        });
        expect(router.currentRoute.value.path).toBe('/');
      },
      { timeout: 3000 },
    );
    wrapper.unmount();
  }, 10000);

  it('登录成功先钤「启封成功」印再跳转（浮层编排先于导航）', async () => {
    vi.mocked(loginApiMock).mockResolvedValue(loginResponse());
    const wrapper = mount(LoginView, { global: { plugins: [pinia, router] } });

    const inputs = wrapper.findAll('input');
    await inputs[0]?.setValue('admin');
    await inputs[1]?.setValue('Fuyun@2026');
    await wrapper.find('.login-submit').trigger('click');

    // 钤印浮层在跳转编排（≤1000ms）前显示：等待登录 resolve 后浮层置显示态
    await vi.waitFor(
      () => {
        expect(wrapper.find('.login-stamp-toast').classes()).toContain('is-show');
      },
      { timeout: 3000 },
    );
    // 编排完成后执行既有跳转（导航含懒加载布局动态导入，轮询等待真正完成）
    await vi.waitFor(
      () => {
        expect(router.currentRoute.value.path).toBe('/');
      },
      { timeout: 5000 },
    );
    wrapper.unmount();
  }, 12000);

  it('登录失败不钤印不跳转（浮层保持隐藏，会话留在登录页）', async () => {
    vi.mocked(loginApiMock).mockRejectedValue(new Error('凭据错误'));
    const wrapper = mount(LoginView, { global: { plugins: [pinia, router] } });

    const inputs = wrapper.findAll('input');
    await inputs[0]?.setValue('admin');
    await inputs[1]?.setValue('Fuyun@2026');
    await wrapper.find('.login-submit').trigger('click');

    // 登录调用确已发出且被拒绝（catch 终止流程，弹错归响应拦截器不在此断言）
    await vi.waitFor(
      () => {
        expect(vi.mocked(loginApiMock)).toHaveBeenCalledTimes(1);
      },
      { timeout: 3000 },
    );
    // 失败路径：浮层保持隐藏、路由不跳转
    expect(wrapper.find('.login-stamp-toast').classes()).not.toContain('is-show');
    expect(router.currentRoute.value.path).toBe('/login');
    wrapper.unmount();
  });

  it('reduced-motion 下钤印浮层直达终态、跳转编排缩短仍完整', async () => {
    // stub matchMedia：指针精确 + 偏好减少动效均命中（jsdom 无 matchMedia，注入后脚本侧
    // 走 reduce 分支——视差不启用、钤印延迟 1000ms→300ms）
    vi.stubGlobal('matchMedia', vi.fn().mockReturnValue({ matches: true }));
    vi.mocked(loginApiMock).mockResolvedValue(loginResponse());
    const wrapper = mount(LoginView, { global: { plugins: [pinia, router] } });

    const inputs = wrapper.findAll('input');
    await inputs[0]?.setValue('admin');
    await inputs[1]?.setValue('Fuyun@2026');
    await wrapper.find('.login-submit').trigger('click');

    // 编排缩短但链路完整：浮层显示 + 跳转达成（比常规编排提前完成）
    await vi.waitFor(
      () => {
        expect(wrapper.find('.login-stamp-toast').classes()).toContain('is-show');
        expect(router.currentRoute.value.path).toBe('/');
      },
      { timeout: 5000 },
    );
    wrapper.unmount();
  }, 12000);
});
