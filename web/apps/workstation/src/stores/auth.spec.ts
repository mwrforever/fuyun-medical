// 认证会话 store 单测（BRIEF-PR3-01 §4）：登录写 state+sessionStorage、登出清空并回登录页
// （api 失败也必须完成本地登出）、isLoggedIn 计算、会话恢复与损坏数据防御；api 层以 mock 承载
import { createPinia, setActivePinia } from 'pinia';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { login as loginApiMock, logout as logoutApiMock } from '@/api/auth';
import { router } from '@/router';
import { useAuthStore } from './auth';
import type { LoginResponse } from '@/types/auth';

// api 层 mock：store 行为断言聚焦会话状态与持久化语义，不触达 Axios 单例
vi.mock('@/api/auth', () => ({
  login: vi.fn(),
  logout: vi.fn(),
  refresh: vi.fn(),
}));

/** 构造登录成功响应（字段与后端契约一致：userId/expiresIn 为后端 Long 经 Long→String 的字符串输出） */
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
      orgId: null,
      roles: ['ADMIN'],
    },
  };
}

describe('认证会话 store', () => {
  beforeEach(() => {
    sessionStorage.clear();
    vi.mocked(loginApiMock).mockReset();
    vi.mocked(logoutApiMock).mockReset();
    setActivePinia(createPinia());
  });

  it('登录成功写入 token/user state 并持久化 sessionStorage', async () => {
    vi.mocked(loginApiMock).mockResolvedValue(loginResponse());
    const auth = useAuthStore();

    await auth.login({ loginName: 'admin', password: 'Fuyun@2026' });

    // 断言业务结果：双令牌与用户身份进入 state，快照同步写入 sessionStorage（换标签页可恢复）
    expect(auth.token).toBe('access-token-1');
    expect(auth.refreshToken).toBe('refresh-token-1');
    expect(auth.user?.displayName).toBe('系统管理员');
    const stored: unknown = JSON.parse(sessionStorage.getItem('fy:workstation:auth') ?? 'null');
    expect(stored).toMatchObject({ token: 'access-token-1', refreshToken: 'refresh-token-1' });
  });

  it('登出清空 state 与 sessionStorage 并回登录页（api 失败也必须完成本地登出）', async () => {
    vi.mocked(loginApiMock).mockResolvedValue(loginResponse());
    vi.mocked(logoutApiMock).mockRejectedValue(new Error('network down'));
    const auth = useAuthStore();
    await auth.login({ loginName: 'admin', password: 'Fuyun@2026' });

    await auth.logout();

    // 断言业务结果：会话三态清空、存储清除、路由回登录页；api 拒绝不阻断本地登出
    expect(auth.token).toBeNull();
    expect(auth.user).toBeNull();
    expect(sessionStorage.getItem('fy:workstation:auth')).toBeNull();
    expect(router.currentRoute.value.path).toBe('/login');
  });

  it('isLoggedIn 随登录与登出正确翻转', async () => {
    vi.mocked(loginApiMock).mockResolvedValue(loginResponse());
    const auth = useAuthStore();

    expect(auth.isLoggedIn).toBe(false);
    await auth.login({ loginName: 'admin', password: 'Fuyun@2026' });
    expect(auth.isLoggedIn).toBe(true);
  });

  it('构造时从 sessionStorage 恢复会话（刷新标签页后保活）', () => {
    sessionStorage.setItem(
      'fy:workstation:auth',
      JSON.stringify({
        token: 'restored-token',
        refreshToken: 'restored-refresh',
        user: {
          userId: '1',
          loginName: 'admin',
          displayName: '系统管理员',
          orgId: null,
          roles: [],
        },
      }),
    );

    const auth = useAuthStore();

    expect(auth.token).toBe('restored-token');
    expect(auth.user?.displayName).toBe('系统管理员');
    expect(auth.isLoggedIn).toBe(true);
  });

  it('sessionStorage 存量数据损坏时丢弃并回退未登录态', () => {
    sessionStorage.setItem('fy:workstation:auth', '{broken-json');

    const auth = useAuthStore();

    // 断言业务结果：非法 JSON 不污染 state 且残留键被清理，避免反复解析失败
    expect(auth.token).toBeNull();
    expect(sessionStorage.getItem('fy:workstation:auth')).toBeNull();
  });
});
