// 大屏令牌缓存单源单测（W-68 附调改造）：http.post 经 vi.mock 桩承载（禁真实网络），断言
// 三导出契约——ensureBigscreenToken 携 wardId 出网与按 wardId 区分缓存（换病区强制重签为
// 裁决固化语义：禁复用旧病区令牌订新病区，WS 单病区防线会拒）、getCachedBigscreenToken
// 同步读缓存（空串=未持有，http.ts 拦截器据此保持匿名）、签发失败清缓存返 false 不抛。
// 泛哨兵形态（wardId undefined）经 axios params 序列化自动省略 query 参数，与后端
// required=false 空白归一 null 口径对齐。每用例 vi.resetModules 后动态再导入（模块级
// 缓存三态随重置归零）。
import { beforeEach, describe, expect, it, vi } from 'vitest';

/** mock 捕获状态（vi.hoisted：vi.mock 工厂提升后仍可引用；逐用例手动复位） */
const h = vi.hoisted(() => {
  /** 签发响应载荷桩（形态镜像生成物 BigscreenTokenVO：字段可选、expiresIn 字符串线格式） */
  const responseBody: { accessToken?: string; tokenType?: string; expiresIn?: string } = {
    accessToken: 't1',
    tokenType: 'Bearer',
    expiresIn: '300',
  };
  return {
    /** http.post 桩捕获：每次调用的 url / data / config 三元组 */
    postCalls: [] as Array<{ url: string; data: unknown; config: unknown }>,
    responseBody,
    /** 非 null 时签发请求拒绝（模拟后端不可达/5xx） */
    postError: null as Error | null,
  };
});

vi.mock('./http', () => ({
  http: {
    // 签发端点桩：仅捕获出网形态并回放可编排响应，无任何真实网络行为
    post: (url: string, data: unknown, config: unknown) => {
      h.postCalls.push({ url, data, config });
      if (h.postError !== null) {
        return Promise.reject(h.postError);
      }
      return Promise.resolve({ data: h.responseBody });
    },
  },
}));

// 模块级缓存单例经 resetModules 重置：每用例取得全新模块实例（令牌缓存随重置归零）
let bigt: typeof import('./bigscreenToken');

/** 动态导入被测模块（签发桩状态经 h 预置） */
async function importModule(): Promise<void> {
  vi.resetModules();
  bigt = await import('./bigscreenToken');
}

beforeEach(async () => {
  vi.clearAllMocks();
  h.postCalls = [];
  h.responseBody = { accessToken: 't1', tokenType: 'Bearer', expiresIn: '300' };
  h.postError = null;
  await importModule();
});

describe('大屏令牌缓存单源（W-68：ensure/fetch/getCached 三导出）', () => {
  it('ensureBigscreenToken 携 wardId 出网且缓存按 wardId 区分重签', async () => {
    await bigt.ensureBigscreenToken('1001');
    // 出网形态：POST 签发端点 + query 参数携病区（后端按病区签发单病区哨兵令牌）
    expect(h.postCalls[0]).toEqual({
      url: '/v1/system/auth/bigscreen-token',
      data: undefined,
      config: { params: { wardId: '1001' } },
    });
    // 缓存命中：同 wardId 二次调用零出网（防匿名签发端点被重连风暴放大调用）
    await bigt.ensureBigscreenToken('1001');
    expect(h.postCalls).toHaveLength(1);
    // 换病区：强制重签（换病区重订阅链路依赖——禁复用旧病区令牌订新病区）
    h.responseBody = { accessToken: 't2', tokenType: 'Bearer', expiresIn: '300' };
    await bigt.ensureBigscreenToken('1002');
    expect(h.postCalls).toHaveLength(2);
    expect(bigt.getCachedBigscreenToken()).toBe('t2');
  });

  it('泛哨兵（无 wardId）query 参数值为 undefined 由 axios 省略；getCachedBigscreenToken 同步读', async () => {
    // 未持有时同步读为空串：http.ts 请求拦截器据此保持匿名（白名单面零 Authorization）
    expect(bigt.getCachedBigscreenToken()).toBe('');
    await bigt.ensureBigscreenToken();
    expect(h.postCalls[0]?.config).toEqual({ params: { wardId: undefined } });
    expect(h.postCalls[0]?.url).toBe('/v1/system/auth/bigscreen-token');
    expect(bigt.getCachedBigscreenToken()).toBe('t1');
  });

  it('失败语义：畸形载荷（缺令牌值）返 false 清缓存不抛；expiresIn 非数值不可缓存立即重签', async () => {
    // 畸形载荷防御：缺令牌值视同签发失败（空值拼 Bearer 头必被服务端拒），不抛出
    h.responseBody = { accessToken: undefined, tokenType: 'Bearer', expiresIn: '300' };
    await expect(bigt.ensureBigscreenToken('1001')).resolves.toBe(false);
    expect(bigt.getCachedBigscreenToken()).toBe('');
    // expiresIn 非数值（Number→NaN）兜底为「不可缓存」：表面签发成功但下次 ensure 立即重签
    // （三次出网：畸形载荷一次 + 不可缓存签发一次 + 立即重签一次）
    h.responseBody = { accessToken: 't1', tokenType: 'Bearer', expiresIn: 'not-a-number' };
    await expect(bigt.ensureBigscreenToken('1001')).resolves.toBe(true);
    await bigt.ensureBigscreenToken('1001');
    expect(h.postCalls).toHaveLength(3);
    // 网络/服务端失败同样清缓存返 false（connect 入口据此拒建连），不向上抛（第 4 次出网）
    h.postError = new Error('签发端点不可用');
    await expect(bigt.ensureBigscreenToken('1001')).resolves.toBe(false);
    expect(h.postCalls).toHaveLength(4);
  });
});
