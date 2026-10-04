// bigscreen Axios 单例请求拦截器单测（W-68 附调 + D-5 traceId 降级）：getCachedBigscreenToken
// 经 vi.mock 桩承载缓存两态（空串=匿名/非空=附调），直调导出的 requestInterceptor 断言
// Authorization 注入与 X-Trace-Id 非空；randomUUID 缺失形态经 Object.defineProperty 实例
// 遮蔽模拟非安全上下文（HTTP 部署无 TLS），finally 删除遮蔽回落 Crypto.prototype 真实实现。
import type { AxiosHeaders, InternalAxiosRequestConfig } from 'axios';
import { beforeEach, describe, expect, it, vi } from 'vitest';

/** mock 捕获状态（vi.hoisted：vi.mock 工厂提升后仍可引用；逐用例手动复位） */
const h = vi.hoisted(() => ({
  /** 拦截器读到的缓存令牌两态：''=未持有（匿名）/非空=已持有（附调） */
  cachedToken: '',
}));

vi.mock('./bigscreenToken', () => ({
  // 令牌缓存单源桩：拦截器仅消费同步读接口，缓存行为归 bigscreenToken.spec 覆盖
  getCachedBigscreenToken: (): string => h.cachedToken,
}));

// 模块级单例经 resetModules 重置：每用例取得全新模块实例（拦截器随重置重新挂接）
let httpMod: typeof import('./http');

/** 动态导入被测模块 */
async function importModule(): Promise<void> {
  vi.resetModules();
  httpMod = await import('./http');
}

beforeEach(async () => {
  vi.clearAllMocks();
  h.cachedToken = '';
  await importModule();
});

describe('bigscreen 请求拦截器（Authorization 附调 + traceId 注入）', () => {
  it('请求拦截器为持有令牌的请求注入 Authorization，无令牌保持匿名', () => {
    // 大屏白名单面（候诊榜快照）无令牌照常匿名出网；board 三端点携令牌（W-68 主路径）
    h.cachedToken = '';
    const anon = httpMod.requestInterceptor({ headers: {} as AxiosHeaders });
    expect(anon.headers.Authorization).toBeUndefined();
    h.cachedToken = 't1';
    const authed = httpMod.requestInterceptor({ headers: {} as AxiosHeaders });
    expect(authed.headers.Authorization).toBe('Bearer t1');
    // traceId 全链路贯穿（根定位层 §7）：无论是否持令牌均注入非空 X-Trace-Id
    expect(String(anon.headers['X-Trace-Id'])).not.toBe('');
    expect(String(authed.headers['X-Trace-Id'])).not.toBe('');
  });

  it('generateTraceId 降级：非安全上下文（randomUUID 缺失）不抛且产出非空 X-Trace-Id', () => {
    // D-5 改造点：HTTP 内网部署无 TLS 时 crypto.randomUUID 为 undefined，旧实现直调必抛
    // TypeError 令全部请求失败；降级时间戳+随机串保持 traceId 链路可用
    Object.defineProperty(crypto, 'randomUUID', { value: undefined, configurable: true });
    try {
      const config = httpMod.requestInterceptor({ headers: {} as AxiosHeaders } as InternalAxiosRequestConfig);
      expect(String(config.headers['X-Trace-Id'])).not.toBe('');
    } finally {
      // 恢复原型链真实实现：删除实例遮蔽（configurable）即回落 Crypto.prototype
      Reflect.deleteProperty(crypto, 'randomUUID');
    }
    expect(typeof crypto.randomUUID).toBe('function');
  });
});
