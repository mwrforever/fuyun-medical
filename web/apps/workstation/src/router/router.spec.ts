// 路由守卫单测（BRIEF-PR3-01 §4 + BUG-14 守卫骨架）：认证三态（默认拒绝重定向登录页并
// 携带回跳地址、public 路由直通、已登录访问 /login 回首页防死循环）+ 权限两态（集内放行/
// 集外与空集重定向 403，未登记权限点路由放行——PR-4D 空集语义反转后口径）；
// 会话以直接注入 state 的方式承载
import { createPinia, setActivePinia } from 'pinia';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { router } from './index';
import { useAuthStore } from '@/stores/auth';
import type { UserVO } from '@/api/auth';

describe('路由认证守卫', () => {
  beforeEach(() => {
    sessionStorage.clear();
    setActivePinia(createPinia());
  });

  it('public 路由（登录页）未登录可直通', async () => {
    await router.push('/login');

    expect(router.currentRoute.value.path).toBe('/login');
  });

  it('未登录访问受保护首页重定向登录页并携带回跳地址', async () => {
    // 先落登录页（public），避免与上一用例停留位置构成重复导航
    await router.push('/login');
    await router.push('/');

    expect(router.currentRoute.value.path).toBe('/login');
    expect(router.currentRoute.value.query.redirect).toBe('/');
  });

  it('已登录访问登录页重定向首页且受保护路由可达（防死循环）', async () => {
    const auth = useAuthStore();
    auth.token = 'guard-access-token'; // 测试注入会话（假令牌资产，非真实凭证）
    await router.push('/'); // 未登录守卫会把 / 重定向到 /login，此处注入会话后应直通首页
    await router.push('/login');

    // 重定向后的首页依赖 MainLayout 懒加载（动态导入耗时跨宏任务）：轮询等待导航真正完成，
    // 显式 3s 超时（实测约 0.5s），防慢 CI flaky
    await vi.waitFor(
      () => {
        expect(router.currentRoute.value.path).toBe('/');
        expect(router.currentRoute.value.name).toBe('home');
      },
      { timeout: 3000 },
    );
  });
});

describe('路由权限守卫骨架（BUG-14）', () => {
  /**
   * 构造带权限点集的测试会话（假令牌资产，非真实凭证）。
   *
   * @param permissions 登录用户权限点编码集；undefined 模拟权限字段缺省的防御会话形态
   *        （后端 PR-4D 已填实登录契约，正常登录恒为数组），传数组模拟真实授权会话
   */
  function injectSession(permissions: string[] | undefined): void {
    const auth = useAuthStore();
    auth.token = 'guard-access-token';
    const user: UserVO = {
      userId: '1',
      loginName: 'nurse01',
      displayName: '测试护士',
      orgId: undefined,
      roles: [],
    };
    // 仅在显式传入时携带权限点集：与真实登录响应「字段缺省 = 数据源缺失」形态一致
    if (permissions !== undefined) {
      user.permissions = permissions;
    }
    auth.user = user;
  }

  beforeEach(() => {
    sessionStorage.clear();
    setActivePinia(createPinia());
  });

  it('权限点集非空且含目标权限点：放行直达业务页', async () => {
    injectSession(['patient:archive:search']);
    await router.push('/patients');

    // 懒加载目标组件耗时跨宏任务，轮询等待导航真正完成（显式 3s 超时防慢 CI flaky）
    await vi.waitFor(
      () => {
        expect(router.currentRoute.value.path).toBe('/patients');
        expect(router.currentRoute.value.name).toBe('patient-search');
      },
      { timeout: 3000 },
    );
  });

  it('权限点集非空且不含目标权限点：重定向 403 页', async () => {
    injectSession(['patient:archive:search']);
    await router.push('/billing/refunds');

    // 目标页（billing:refund:approve 不在集内）被拦，落点为 403 页（懒加载经 MainLayout）
    await vi.waitFor(
      () => {
        expect(router.currentRoute.value.path).toBe('/403');
        expect(router.currentRoute.value.name).toBe('forbidden');
      },
      { timeout: 3000 },
    );
  });

  it('权限点集为空（无任何权限）：已登记权限点的业务页重定向 403', async () => {
    injectSession([]);
    await router.push('/patients');

    // 空集=无任何业务权限（PR-4D 语义反转）：目标页权限点 patient:archive:search 不在
    // 集内被拦，落点为 403 页（懒加载经 MainLayout，轮询等导航完成防慢 CI flaky）
    await vi.waitFor(
      () => {
        expect(router.currentRoute.value.path).toBe('/403');
        expect(router.currentRoute.value.name).toBe('forbidden');
      },
      { timeout: 3000 },
    );
  });
});
