// 登录页单测（批次 2 册 1 契约 §2 v2 两栏沉浸门面）：空表单提交被校验拦截、
// 有效提交调用认证链路并跳转首页、门面视觉锚点在位（两栏骨架/墨脊品牌字标/
// 请登录副题/墨规面板/双字段）；api 层 mock 承载（不打真实网络），错误弹窗口径
// 归 http.spec 覆盖，本文件不重复断言。
// 视觉锚点只锚类名与文案（不绑 EP 内部结构，重构不破）；jsdom 不计算样式，墨规以承载类名锚定。
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
  });

  it('渲染两栏沉浸门面锚点与登录表单骨架（墨脊字标/请登录副题/墨规面板/双字段）', () => {
    const wrapper = mount(LoginView, { global: { plugins: [pinia, router] } });

    // 两栏沉浸骨架：左墨脊叙事面 + 右纸面工作面（契约 §2 v2 构图，全站唯一全视口页）
    expect(wrapper.find('.login-spine').exists()).toBe(true);
    expect(wrapper.find('.login-face').exists()).toBe(true);

    // 品牌字标「富云」与「请登录」文案锚点（契约 §2，文案语义零漂移）
    expect(wrapper.find('.spine-brand-mark').text()).toBe('富云');
    expect(wrapper.find('.login-panel-title').text()).toBe('富云医院信息系统 · 请登录');

    // 表单区为 2px 墨规收底的封面面板（.login-panel 承载墨规下缘，样式存在性以类名锚定）
    expect(wrapper.find('.login-panel').exists()).toBe(true);

    // 表单骨架：登录名/口令双字段 + 登录按钮（show-password 切换为 EP 内建非 button
    // 元素，主提交按钮以类名精确定位，DOM 序不敏感）
    expect(wrapper.findAll('input')).toHaveLength(2);
    expect(wrapper.find('.login-submit').text()).toBe('登录');
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
  });
});
