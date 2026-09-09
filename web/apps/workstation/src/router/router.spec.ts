// 路由认证守卫单测（BRIEF-PR3-01 §4）：默认拒绝重定向登录页并携带回跳地址、
// public 路由直通、已登录访问 /login 回首页防死循环；会话以直接注入 state 的方式承载
import { createPinia, setActivePinia } from 'pinia';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { router } from './index';
import { useAuthStore } from '@/stores/auth';

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
