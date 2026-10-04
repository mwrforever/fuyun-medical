// 护士站大屏 STOMP 单例封装单测（Task 17，镜像 useQueueStomp.spec 母版——禁自造 mock 形态）：
// vi.mock('@stomp/stompjs') 捕获构造参数与回调挂接（禁真实建连）；令牌缓存单源经
// vi.mock('@/api/bigscreenToken') 承载（W-68 收敛：ensure 把关 + getCached 拼头，缓存/重签
// 行为本体归 bigscreenToken.spec 覆盖）。三组断言：连接单例（令牌失败拒建连零出网/
// brokerURL=/ws/nursing/库内建重连心跳/匿名令牌 Bearer 时序/beforeConnect 每次经 ensure
// 把关/单例复用）、重订阅（订阅路径精确/topic/nursing/board/{wardId} 登记转正/换病区透传
// 当前 wardId/断线重连重订阅禁假连接/显式断开先退订/在飞卸载代际作废/空病区拒绝）、
// 帧收窄挂接（毒帧 warn 不中断、合法帧触达回调、令牌值禁入日志）。每用例 vi.resetModules
// 后动态再导入。
import { flushPromises } from '@vue/test-utils';
import { beforeEach, describe, expect, it, vi } from 'vitest';

/** mock 捕获状态（vi.hoisted：vi.mock 工厂提升后仍可引用；逐用例手动复位） */
const h = vi.hoisted(() => ({
  constructorCalls: 0,
  configs: [] as Record<string, unknown>[],
  /** 捕获的桩 Client 实例（connectHeaders 经实例属性赋值，须从实例侧断言） */
  clients: [] as { connected: boolean; connectHeaders: Record<string, string> }[],
  activateCalls: 0,
  deactivateCalls: 0,
  /** 捕获的订阅：destination + 帧回调（用例内投递毒帧/合法帧） */
  subscriptions: [] as { destination: string; callback: (message: { body: string }) => void }[],
  unsubscribeCalls: 0,
  /** 令牌缓存单源桩状态：tokenReady=ensure 结论（false=签发失败）；tokenValue=getCached
   *  缓存值（拼 Bearer 头用）；ensureCalls/ensureWardIds=ensure 调用计数与透传病区序列 */
  tokenValue: 'tok-nurse',
  tokenReady: true,
  ensureCalls: 0,
  ensureWardIds: [] as Array<string | undefined>,
}));

vi.mock('@stomp/stompjs', () => {
  /** 捕获构造参数与订阅行为的桩 Client（无任何真实网络行为） */
  class MockClient {
    connected = false;
    connectHeaders: Record<string, string> = {};
    constructor(config: Record<string, unknown>) {
      h.constructorCalls += 1;
      h.configs.push(config);
      h.clients.push(this);
    }
    activate(): void {
      h.activateCalls += 1;
    }
    deactivate(): Promise<void> {
      h.deactivateCalls += 1;
      return Promise.resolve();
    }
    subscribe(
      destination: string,
      callback: (message: { body: string }) => void,
    ): { id: string; destination: string; unsubscribe: () => void } {
      h.subscriptions.push({ destination, callback });
      return { id: 'sub-0', destination, unsubscribe: () => void (h.unsubscribeCalls += 1) };
    }
  }
  return { Client: MockClient };
});

vi.mock('@/api/bigscreenToken', () => ({
  // 令牌缓存单源桩（W-68 收敛改造）：ensure 承载「是否持有有效令牌」把关结论并捕获透传
  // 病区序列，getCached 承载拼 Bearer 头的缓存值；缓存命中/到期/换病区重签本体归
  // bigscreenToken.spec 覆盖（本层只验消费契约）
  ensureBigscreenToken: (wardId?: string): Promise<boolean> => {
    h.ensureCalls += 1;
    h.ensureWardIds.push(wardId);
    return Promise.resolve(h.tokenReady);
  },
  getCachedBigscreenToken: (): string => (h.tokenReady ? h.tokenValue : ''),
}));

// 模块级单例经 resetModules 重置：每用例取得全新模块实例（令牌缓存与失败态随重置归零）
let nursingStomp: typeof import('./useNursingStomp');

/** 动态导入被测模块（令牌状态经 mock 桩 h 预置） */
async function importModule(): Promise<void> {
  vi.resetModules();
  nursingStomp = await import('./useNursingStomp');
}

beforeEach(async () => {
  vi.clearAllMocks();
  h.constructorCalls = 0;
  h.configs = [];
  h.clients = [];
  h.activateCalls = 0;
  h.deactivateCalls = 0;
  h.subscriptions = [];
  h.unsubscribeCalls = 0;
  h.tokenValue = 'tok-nurse';
  h.tokenReady = true;
  h.ensureCalls = 0;
  h.ensureWardIds = [];
  await importModule();
});

/** 取最近一次捕获的 Client 构造配置（用例内已保证至少一次建连） */
function lastConfig(): Record<string, unknown> {
  const config = h.configs.at(-1);
  expect(config).toBeDefined();
  return config as Record<string, unknown>;
}

/** 取最近一次捕获的桩 Client 实例（connectHeaders 经实例属性赋值，从实例侧断言） */
function lastClient(): { connected: boolean; connectHeaders: Record<string, string> } {
  const instance = h.clients.at(-1);
  expect(instance).toBeDefined();
  return instance as { connected: boolean; connectHeaders: Record<string, string> };
}

describe('护士站大屏 STOMP 单例封装（web B.3-3）', () => {
  describe('连接单例', () => {
    it('匿名令牌获取失败：connect 拒建连，零 Client 创建零激活，tokenFailed 置位供页面横幅', async () => {
      h.tokenReady = false;
      const warnSpy = vi.spyOn(console, 'warn').mockImplementation(() => {});
      nursingStomp.connect('1001');
      await flushPromises();
      // 拒建连语义（useQueueStomp 同款）：取不到令牌不建 Client、不激活——整条 WS 链路禁用
      expect(h.ensureCalls).toBe(1);
      expect(h.constructorCalls).toBe(0);
      expect(h.activateCalls).toBe(0);
      expect(nursingStomp.connectionState.value).toBe('disconnected');
      expect(nursingStomp.tokenFailed.value).toBe(true);
      warnSpy.mockRestore();
    });

    it('令牌获取成功建连：brokerURL 形态 ws://…/ws/nursing，库内建重连 10s、双向心跳 10s', async () => {
      nursingStomp.connect('1001');
      // 连接发起（含取令牌阶段）先行进入 connecting 态（同步断言）
      expect(nursingStomp.connectionState.value).toBe('connecting');
      await flushPromises();
      const config = lastConfig();
      expect(config['brokerURL']).toBe('ws://localhost:3000/ws/nursing');
      expect(config['reconnectDelay']).toBe(10000);
      expect(config['heartbeatIncoming']).toBe(10000);
      expect(config['heartbeatOutgoing']).toBe(10000);
      expect(h.activateCalls).toBe(1);
      expect(nursingStomp.tokenFailed.value).toBe(false);
    });

    it('beforeConnect 携带运行期签发匿名令牌拼 Bearer 头（先取令牌后建连时序）', async () => {
      nursingStomp.connect('1001');
      await flushPromises();
      const beforeConnect = lastConfig()['beforeConnect'] as () => Promise<void>;
      await beforeConnect();
      expect(lastClient().connectHeaders['Authorization']).toBe('Bearer tok-nurse');
    });

    it('beforeConnect 每次连接尝试经 ensure 把关（缓存/重签收敛 api 层单源）并携带最新令牌值', async () => {
      nursingStomp.connect('1001');
      await flushPromises();
      const beforeConnect = lastConfig()['beforeConnect'] as () => Promise<void>;
      await beforeConnect();
      // connect 建连把关一次 + beforeConnect 重连把关一次：每次连接尝试都经 ensure（到期/
      // 换病区重签裁决在 api 层命中条件承载，W-68 收敛）
      expect(h.ensureCalls).toBe(2);
      expect(lastClient().connectHeaders['Authorization']).toBe('Bearer tok-nurse');
      // api 层重签后缓存值更新：重连尝试拼头携带新值（getCached 实时读取不固化）
      h.tokenValue = 'tok-nurse-2';
      await beforeConnect();
      expect(h.ensureCalls).toBe(3);
      expect(lastClient().connectHeaders['Authorization']).toBe('Bearer tok-nurse-2');
    });

    it('两次 connect 复用同一 Client 单例（首次 connect 惰性创建，不重复建连）', async () => {
      nursingStomp.connect('1001');
      await flushPromises();
      nursingStomp.connect('1001');
      await flushPromises();
      expect(h.constructorCalls).toBe(1);
      expect(h.activateCalls).toBe(2);
    });
  });

  describe('重订阅', () => {
    it('订阅路径精确等于 /topic/nursing/board/{wardId}（连接落地前登记待订阅，onConnect 转正）', async () => {
      const onStateSeen: string[] = [];
      nursingStomp.connect('1001', (state) => void onStateSeen.push(state));
      await flushPromises();
      nursingStomp.subscribeBoard('1001', () => {});
      expect(h.subscriptions).toHaveLength(0);
      (lastConfig()['onConnect'] as () => void)();
      expect(h.subscriptions).toHaveLength(1);
      expect(h.subscriptions[0]?.destination).toBe('/topic/nursing/board/1001');
      // 连接状态机（页面消费）：connecting → connected，onStateChange 随翻转触发
      expect(onStateSeen).toEqual(['connecting', 'connected']);
    });

    it('换病区重订阅：已连接态同槽位先退订旧订阅再立即落地新主题（防旧主题帧流入页面）', async () => {
      nursingStomp.connect('1001');
      await flushPromises();
      nursingStomp.subscribeBoard('1001', () => {});
      const client = lastClient();
      client.connected = true;
      (lastConfig()['onConnect'] as () => void)();
      nursingStomp.subscribeBoard('1002', () => {});
      expect(h.unsubscribeCalls).toBe(1);
      expect(h.subscriptions.at(-1)?.destination).toBe('/topic/nursing/board/1002');
    });

    it('换病区重订阅透传当前 wardId 调 ensure（wardId 变化强制重签——WS 单病区防线衔接）', async () => {
      nursingStomp.connect('1001');
      await flushPromises();
      // 换病区重订阅后重连路径必须按新病区签发：禁复用旧病区令牌订新病区（WS 防线会拒）
      nursingStomp.subscribeBoard('1002', () => {});
      const beforeConnect = lastConfig()['beforeConnect'] as () => Promise<void>;
      await beforeConnect();
      expect(h.ensureWardIds[0]).toBe('1001');
      expect(h.ensureWardIds.at(-1)).toBe('1002');
    });

    it('断线 close 后重连 onConnect 重新落地订阅（stompjs 7.3.0 无自动重订阅，禁假连接）', async () => {
      nursingStomp.connect('1001');
      await flushPromises();
      nursingStomp.subscribeBoard('1001', () => {});
      const config = lastConfig();
      (config['onConnect'] as () => void)();
      expect(h.subscriptions).toHaveLength(1);
      // 模拟断线：stompjs 7.3.0 整体作废 _stompHandler（旧订阅句柄随连接失效）
      (config['onWebSocketClose'] as () => void)();
      expect(nursingStomp.connectionState.value).toBe('disconnected');
      // 模拟库内建自动重连成功：onConnect 二次触发必须重新 subscribe
      (config['onConnect'] as () => void)();
      expect(h.subscriptions).toHaveLength(2);
      expect(h.subscriptions.at(-1)?.destination).toBe('/topic/nursing/board/1001');
    });

    it('显式 disconnect 先退订在册订阅再 deactivate，状态回归断开态', async () => {
      nursingStomp.connect('1001');
      await flushPromises();
      nursingStomp.subscribeBoard('1001', () => {});
      (lastConfig()['onConnect'] as () => void)();
      await nursingStomp.disconnect();
      // B.3-3 卸载清理条款：显式断开先退订（1 次）再 deactivate（1 次）
      expect(h.unsubscribeCalls).toBe(1);
      expect(h.deactivateCalls).toBe(1);
      expect(nursingStomp.connectionState.value).toBe('disconnected');
    });

    it('建连在飞时卸载（disconnect 递增代际）：令牌链落定后不建 Client 不激活 WS', async () => {
      nursingStomp.connect('1001');
      // 竞态窗口模拟：令牌签发在飞（未 flush）即卸载断开——真实组件 onUnmounted 调用时序
      await nursingStomp.disconnect();
      await flushPromises();
      expect(h.ensureCalls).toBe(1);
      expect(h.constructorCalls).toBe(0);
      expect(h.activateCalls).toBe(0);
      expect(nursingStomp.connectionState.value).toBe('disconnected');
      expect(nursingStomp.tokenFailed.value).toBe(false);
    });

    it('空病区编码拒绝连接与订阅（异常上抛且不产生库订阅、不触发令牌签发）', () => {
      expect(() => nursingStomp.connect('')).not.toThrow();
      expect(h.ensureCalls).toBe(0);
      expect(h.constructorCalls).toBe(0);
      expect(() => nursingStomp.subscribeBoard('   ', () => {})).toThrow();
      expect(h.subscriptions).toHaveLength(0);
    });
  });

  describe('帧收窄挂接', () => {
    it('毒帧（非法 JSON 与词表外 type 信封）仅 warn 留痕不中断订阅、不触达帧回调', async () => {
      const warnSpy = vi.spyOn(console, 'warn').mockImplementation(() => {});
      const onFrame = vi.fn();
      nursingStomp.connect('1001');
      await flushPromises();
      nursingStomp.subscribeBoard('1001', onFrame);
      (lastConfig()['onConnect'] as () => void)();
      const callback = h.subscriptions[0]?.callback;
      expect(callback).toBeDefined();
      // 非法 JSON 毒帧：不抛异常、帧回调不触达
      expect(() => callback?.({ body: 'not-json{{' })).not.toThrow();
      // 词表外 type 信封毒帧（后端五值冻结词表，词表外即不合法）
      expect(() =>
        callback?.({ body: JSON.stringify({ type: 'HACK', payload: {}, occurredAt: 't' }) }),
      ).not.toThrow();
      expect(onFrame).not.toHaveBeenCalled();
      expect(warnSpy).toHaveBeenCalled();
      warnSpy.mockRestore();
    });

    it('合法 BED_PATIENT 信封逐字段收窄后触达帧回调（bedNo null 双形态容忍）', async () => {
      const onFrame = vi.fn();
      nursingStomp.connect('1001');
      await flushPromises();
      nursingStomp.subscribeBoard('1001', onFrame);
      (lastConfig()['onConnect'] as () => void)();
      h.subscriptions[0]?.callback({
        body: JSON.stringify({
          type: 'BED_PATIENT',
          payload: {
            visitId: 'I20260920001',
            patientId: '1002',
            bedNo: null,
            wardId: '1001',
          },
          occurredAt: '2026-10-03T06:00:00Z',
        }),
      });
      expect(onFrame).toHaveBeenCalledWith({
        type: 'BED_PATIENT',
        payload: {
          visitId: 'I20260920001',
          patientId: '1002',
          bedNo: null,
          wardId: '1001',
        },
        occurredAt: '2026-10-03T06:00:00Z',
      });
    });

    it('令牌值禁入任何日志（web A.6 红线）：全部 info/warn/error 输出拼接后不得出现令牌值', async () => {
      h.tokenValue = 'tok-nurse-secret';
      const warnSpy = vi.spyOn(console, 'warn').mockImplementation(() => {});
      const errorSpy = vi.spyOn(console, 'error').mockImplementation(() => {});
      const infoSpy = vi.spyOn(console, 'info').mockImplementation(() => {});
      // 覆盖拒建连分支（空病区）、连接成功留痕、重签失败与连接失败分支
      nursingStomp.connect('');
      nursingStomp.connect('1001');
      await flushPromises();
      h.tokenReady = false;
      const beforeConnect = lastConfig()['beforeConnect'] as () => Promise<void>;
      await beforeConnect();
      (lastConfig()['onWebSocketClose'] as () => void)();
      (lastConfig()['onStompError'] as (frame: { headers: Record<string, string> }) => void)({
        headers: { message: 'broker error' },
      });
      const allLogs = [...infoSpy.mock.calls, ...warnSpy.mock.calls, ...errorSpy.mock.calls]
        .flat()
        .join('\n');
      expect(allLogs).not.toContain('tok-nurse-secret');
      infoSpy.mockRestore();
      warnSpy.mockRestore();
      errorSpy.mockRestore();
    });
  });
});
