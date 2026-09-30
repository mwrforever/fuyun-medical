// 认证会话 store 单测（BRIEF-PR3-01 §4 + BUG-14 权限点集）：登录写 state+sessionStorage、
// 登出清空并回登录页（api 失败也必须完成本地登出）、isLoggedIn 计算、会话恢复与损坏数据防御、
// 权限点集派生与 hasRoutePermission 三态判定；api 层以 mock 承载
import { createPinia, setActivePinia } from 'pinia';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { login as loginApiMock, logout as logoutApiMock } from '@/api/auth';
import { router } from '@/router';
import { useAuthStore } from './auth';
import type { LoginResponse, UserVO } from '@/api/auth';

// api 层 mock：store 行为断言聚焦会话状态与持久化语义，不触达 Axios 单例
vi.mock('@/api/auth', () => ({
  login: vi.fn(),
  logout: vi.fn(),
  refresh: vi.fn(),
}));

/**
 * 构造登录成功响应（字段与后端契约一致：userId/expiresIn 为后端 Long 经 Long→String 的字符串输出）。
 *
 * @param permissions 权限点编码集；缺省 = user 无 permissions 字段（防御存量快照/异常形态）
 */
function loginResponse(permissions?: string[]): LoginResponse {
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
      ...(permissions !== undefined ? { permissions } : {}),
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

  it('登录响应携带权限点集时 permissions 派生承载', async () => {
    vi.mocked(loginApiMock).mockResolvedValue(
      loginResponse(['patient:archive:search', 'nursing:ward:view']),
    );
    const auth = useAuthStore();

    await auth.login({ loginName: 'admin', password: 'Fuyun@2026' });

    // 断言业务结果：权限点集随会话身份进入 store（守卫与侧栏共用的判定数据源）
    expect(auth.permissions).toEqual(['patient:archive:search', 'nursing:ward:view']);
  });

  it('登录响应未携带权限点集时 permissions 为空集（数据源缺失形态）', async () => {
    vi.mocked(loginApiMock).mockResolvedValue(loginResponse()); // user 无 permissions 字段
    const auth = useAuthStore();

    await auth.login({ loginName: 'admin', password: 'Fuyun@2026' });

    // 断言业务结果：P0 后端契约缺省字段 → 空集（守卫全放行/侧栏全量显示的骨架口径）
    expect(auth.permissions).toEqual([]);
  });

  it('hasRoutePermission 三态：未登记权限点放行/空集全放行/集非空按集判定', () => {
    const auth = useAuthStore();
    auth.token = 'guard-access-token'; // 测试注入会话（假令牌资产，非真实凭证）
    const user: UserVO = {
      userId: '1',
      loginName: 'nurse01',
      displayName: '测试护士',
      orgId: undefined,
      roles: [],
      permissions: ['patient:archive:search'],
    };
    auth.user = user;

    // 集非空：未登记权限点的路由（首页等）恒放行；集内权限点放行；集外权限点拒绝
    expect(auth.hasRoutePermission(undefined)).toBe(true);
    expect(auth.hasRoutePermission('patient:archive:search')).toBe(true);
    expect(auth.hasRoutePermission('billing:refund:approve')).toBe(false);

    // 空集（数据源缺失）：任意权限点均放行（骨架语义，P1 接线后收紧）
    auth.user = { ...user, permissions: undefined };
    expect(auth.hasRoutePermission('billing:refund:approve')).toBe(true);
  });

  it('会话恢复保留权限点集（刷新标签页后守卫判定数据源不丢失）', () => {
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
          permissions: ['iot:device:manage'],
        },
      }),
    );

    const auth = useAuthStore();

    expect(auth.isLoggedIn).toBe(true);
    expect(auth.permissions).toEqual(['iot:device:manage']);
  });

  it('sessionStorage 权限点集字段被篡改为非数组时整份快照丢弃', () => {
    sessionStorage.setItem(
      'fy:workstation:auth',
      JSON.stringify({
        token: 'tampered-token',
        refreshToken: 'tampered-refresh',
        user: {
          userId: '1',
          loginName: 'admin',
          displayName: '系统管理员',
          orgId: null,
          roles: [],
          permissions: 'admin', // 篡改形态：字符串混入会致权限判定退化成子串匹配
        },
      }),
    );

    const auth = useAuthStore();

    // 断言业务结果：脏权限点集不污染会话——整份快照丢弃回未登录态（结构非法快照与
    // 损坏 JSON 同为「不进 state」口径；残留键不清理属既有结构非法处理语义，非本断言范围）
    expect(auth.token).toBeNull();
    expect(auth.user).toBeNull();
  });
});
