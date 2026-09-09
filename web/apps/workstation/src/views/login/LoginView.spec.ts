// 登录页单测（BRIEF-PR3-01 §4）：空表单提交被校验拦截、有效提交调用认证链路并跳转首页；
// api 层 mock 承载（不打真实网络），错误弹窗口径归 http.spec 覆盖，本文件不重复断言。
// 注：beforeEach 预导航登录页（消除路由器安装期初始导航与用例交互的竞争），
// 全文件共享单一 Pinia——守卫经挂载 app 上下文解析 store，逐用例换实例会割裂会话语义。
import { mount } from '@vue/test-utils';
import { createPinia, setActivePinia } from 'pinia';
import type { Pinia } from 'pinia';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { login as loginApiMock } from '@/api/auth';
import { router } from '@/router';
import LoginView from './LoginView.vue';
import type { LoginResponse } from '@/types/auth';

vi.mock('@/api/auth', () => ({
  login: vi.fn(),
  logout: vi.fn(),
  refresh: vi.fn(),
}));

/** 构造登录成功响应（契约字段：userId 为字符串化 Long） */
function loginResponse(): LoginResponse {
  return {
    accessToken: 'access-token-1',
    refreshToken: 'refresh-token-1',
    tokenType: 'Bearer',
    expiresIn: 7200,
    user: {
      userId: '1932000000000000001',
      loginName: 'admin',
      displayName: '系统管理员',
      orgId: null,
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

  it('空表单提交被校验拦截，不触发登录调用', async () => {
    const wrapper = mount(LoginView, { global: { plugins: [pinia, router] } });

    // Element Plus 校验状态有 100ms 防抖：用假时钟推进驱动校验链与错误文案渲染
    vi.useFakeTimers();
    await wrapper.find('button').trigger('click');
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
    await wrapper.find('button').trigger('click');
    // 登录成功后的跳转含懒加载布局组件的动态导入（耗时跨宏任务）：轮询等待导航真正完成
    await vi.waitFor(() => {
      expect(vi.mocked(loginApiMock)).toHaveBeenCalledWith({
        loginName: 'admin',
        password: 'Fuyun@2026',
      });
      expect(router.currentRoute.value.path).toBe('/');
    });
    wrapper.unmount();
  });
});
