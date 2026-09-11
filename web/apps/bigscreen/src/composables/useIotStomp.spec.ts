// STOMP 单例封装单测（BRIEF-PR5-01 §2.3）：vi.mock('@stomp/stompjs') 捕获构造参数与回调挂接
//（禁真实建连），覆盖单例复用、beforeConnect 动态令牌、构造参数合规、订阅路径精确等于契约值、
// 毒帧防御、无令牌拒绝建连、非数字病区拒绝订阅与显式断开先退订后 deactivate。
// 每用例 vi.resetModules 后动态再导入，重置模块级单例（Client 缓存/订阅在册/状态 ref）保证隔离。
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

// 模块级单例经 resetModules 重置：每用例取得全新模块实例
let stomp: typeof import('./useIotStomp');

beforeEach(async () => {
  vi.clearAllMocks();
  sessionStorage.clear();
  h.constructorCalls = 0;
  h.configs = [];
  h.clients = [];
  h.activateCalls = 0;
  h.deactivateCalls = 0;
  h.subscriptions = [];
  h.unsubscribeCalls = 0;
  vi.resetModules();
  stomp = await import('./useIotStomp');
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

describe('STOMP 单例封装（web B.3-3）', () => {
  it('两次 connect 复用同一 Client 单例（首次 connect 惰性创建，不重复建连）', () => {
    stomp.connect({ token: 'token-a', wardId: '1001' });
    stomp.connect({ token: 'token-b', wardId: '1002' });
    expect(h.constructorCalls).toBe(1);
    expect(h.activateCalls).toBe(2);
    // 状态随建连进入连接中（第二次 connect 保持连接中态，不回退）
    expect(stomp.connectionState.value).toBe('connecting');
  });

  it('beforeConnect 每次连接尝试实时读 sessionStorage 拼 Bearer 头，改值后重连带新值', () => {
    stomp.connect({ token: 'token-a', wardId: '1001' });
    const beforeConnect = lastConfig()['beforeConnect'] as () => void;
    beforeConnect();
    expect(lastClient().connectHeaders['Authorization']).toBe('Bearer token-a');
    // 模拟令牌轮换后断线重连：beforeConnect 再次执行应携带最新令牌（禁构造期固化一次性 token）
    sessionStorage.setItem('fy:bigscreen:iot-token', 'token-b');
    beforeConnect();
    expect(lastClient().connectHeaders['Authorization']).toBe('Bearer token-b');
  });

  it('Client 构造参数合规：库内建固定重连 10s、双向心跳 10s、brokerURL 同源推导 /ws/iot', () => {
    stomp.connect({ token: 'token-a', wardId: '1001' });
    const config = lastConfig();
    expect(config['reconnectDelay']).toBe(10000);
    expect(config['heartbeatIncoming']).toBe(10000);
    expect(config['heartbeatOutgoing']).toBe(10000);
    // jsdom 页面协议 http → ws 推导（生产 https → wss），路径走 nginx /ws/ 升级路由
    expect(config['brokerURL']).toBe('ws://localhost:3000/ws/iot');
  });

  it('onStompError/onWebSocketClose 已挂接：调用置断开态且日志不含令牌值', () => {
    const warnSpy = vi.spyOn(console, 'warn').mockImplementation(() => {});
    const errorSpy = vi.spyOn(console, 'error').mockImplementation(() => {});
    stomp.connect({ token: 'token-a', wardId: '1001' });
    const config = lastConfig();
    expect(typeof config['onStompError']).toBe('function');
    expect(typeof config['onWebSocketClose']).toBe('function');
    (config['onWebSocketClose'] as () => void)();
    (config['onStompError'] as (frame: { headers: Record<string, string> }) => void)({
      headers: { message: 'broker error' },
    });
    expect(stomp.connectionState.value).toBe('disconnected');
    // 令牌禁入日志（web A.6 红线）：全部 warn/error 输出拼接后不得出现令牌值
    const allLogs = [...warnSpy.mock.calls, ...errorSpy.mock.calls].flat().join('\n');
    expect(allLogs).not.toContain('token-a');
    warnSpy.mockRestore();
    errorSpy.mockRestore();
  });

  it('订阅路径精确等于 /topic/iot/telemetry/{wardId}（连接落地前登记待订阅，onConnect 转正）', () => {
    const onStateSeen: string[] = [];
    stomp.connect({
      token: 'token-a',
      wardId: '1001',
      onStateChange: (state) => void onStateSeen.push(state),
    });
    stomp.subscribeTelemetrySummary('1001', () => {});
    expect(h.subscriptions).toHaveLength(0);
    (lastConfig()['onConnect'] as () => void)();
    expect(h.subscriptions).toHaveLength(1);
    expect(h.subscriptions[0]?.destination).toBe('/topic/iot/telemetry/1001');
    // 连接状态机（页面消费）：connecting → connected，onStateChange 随翻转触发
    expect(onStateSeen).toEqual(['connecting', 'connected']);
  });

  it('已连接态调用订阅立即落地真实订阅并返回可退订句柄', () => {
    stomp.connect({ token: 'token-a', wardId: '1001' });
    // 模拟库已建立连接：桩 Client connected 置真后订阅应立即落地（不经待订阅登记）
    lastClient().connected = true;
    const handle = stomp.subscribeTelemetrySummary('1001', () => {});
    expect(h.subscriptions).toHaveLength(1);
    expect(h.subscriptions[0]?.destination).toBe('/topic/iot/telemetry/1001');
    // 换病区重订阅：在册旧订阅先退订（单槽位替换语义），新订阅立即落地
    stomp.subscribeTelemetrySummary('1002', () => {});
    expect(h.unsubscribeCalls).toBe(1);
    expect(h.subscriptions).toHaveLength(2);
    expect(h.subscriptions.at(-1)?.destination).toBe('/topic/iot/telemetry/1002');
    // 代理句柄退订（单出口）：清在册并退订当前库句柄
    handle.unsubscribe();
    expect(h.unsubscribeCalls).toBe(2);
  });

  it('毒帧（非法 JSON 与残缺载荷）仅 warn 留痕不中断订阅、不触达帧回调', () => {
    const warnSpy = vi.spyOn(console, 'warn').mockImplementation(() => {});
    stomp.connect({ token: 'token-a', wardId: '1001' });
    const onFrame = vi.fn();
    stomp.subscribeTelemetrySummary('1001', onFrame);
    (lastConfig()['onConnect'] as () => void)();
    const callback = h.subscriptions[0]?.callback;
    expect(callback).toBeDefined();
    // 非法 JSON 毒帧：不抛异常、帧回调不触达
    expect(() => callback?.({ body: 'not-json{{' })).not.toThrow();
    // 残缺载荷毒帧（缺 count）：同样防御
    expect(() =>
      callback?.({
        body: JSON.stringify({ occurredAtUpperBound: '2026-09-11T02:00:00Z', items: [] }),
      }),
    ).not.toThrow();
    expect(onFrame).not.toHaveBeenCalled();
    expect(warnSpy).toHaveBeenCalled();
    warnSpy.mockRestore();
  });

  it('无令牌调用 connect 拒绝建连并提示注入令牌（不创建 Client 不发起激活）', () => {
    const warnSpy = vi.spyOn(console, 'warn').mockImplementation(() => {});
    stomp.connect({ token: '', wardId: '1001' });
    expect(h.constructorCalls).toBe(0);
    expect(h.activateCalls).toBe(0);
    expect(warnSpy).toHaveBeenCalled();
    warnSpy.mockRestore();
  });

  it('非数字病区 ID 拒绝订阅（异常上抛且不产生库订阅）', () => {
    stomp.connect({ token: 'token-a', wardId: '1001' });
    expect(() => stomp.subscribeTelemetrySummary('abc', () => {})).toThrow();
    expect(h.subscriptions).toHaveLength(0);
  });

  it('显式 disconnect 先退订在册订阅再 deactivate，状态回归断开态', async () => {
    stomp.connect({ token: 'token-a', wardId: '1001' });
    stomp.subscribeTelemetrySummary('1001', () => {});
    (lastConfig()['onConnect'] as () => void)();
    await stomp.disconnect();
    // B.3-3 卸载清理条款：显式断开先退订（1 次）再 deactivate（1 次）
    expect(h.unsubscribeCalls).toBe(1);
    expect(h.deactivateCalls).toBe(1);
    expect(stomp.connectionState.value).toBe('disconnected');
  });

  it('断线 close 后重连 onConnect 重新落地订阅且新帧入流（stompjs 7.3.0 无自动重订阅）', () => {
    const onFrame = vi.fn();
    stomp.connect({ token: 'token-a', wardId: '1001' });
    stomp.subscribeTelemetrySummary('1001', onFrame);
    const config = lastConfig();
    (config['onConnect'] as () => void)();
    expect(h.subscriptions).toHaveLength(1);
    // 模拟断线：stompjs 7.3.0 整体作废 _stompHandler（旧订阅句柄随连接失效）
    (config['onWebSocketClose'] as () => void)();
    expect(stomp.connectionState.value).toBe('disconnected');
    // 模拟库内建自动重连成功：onConnect 二次触发必须重新 subscribe（禁假连接——徽标已连接零帧）
    (config['onConnect'] as () => void)();
    expect(h.subscriptions).toHaveLength(2);
    expect(h.subscriptions.at(-1)?.destination).toBe('/topic/iot/telemetry/1001');
    // 新句柄帧入流：新订阅回调正常触达页面帧回调
    h.subscriptions.at(-1)?.callback({
      body: JSON.stringify({
        count: 1,
        occurredAtUpperBound: '2026-09-11T02:00:00Z',
        items: [{ deviceId: 'dev-1', metricCode: 'vital.heart-rate' }],
      }),
    });
    expect(onFrame).toHaveBeenCalledTimes(1);
    expect(stomp.connectionState.value).toBe('connected');
  });

  it('已连接态再次 connect（换病区）：保持 connected 不进 connecting 且不重复 activate', () => {
    stomp.connect({ token: 'token-a', wardId: '1001' });
    (lastConfig()['onConnect'] as () => void)();
    // 模拟库已建立连接
    lastClient().connected = true;
    // 已连接态换病区再点连接：状态机不得回退 connecting（stompjs activate 对已激活 Client 为 no-op）
    stomp.connect({ token: 'token-b', wardId: '1002' });
    expect(stomp.connectionState.value).toBe('connected');
    expect(h.activateCalls).toBe(1);
    // 订阅切换由紧随的 subscribeTelemetrySummary 已连接分支立即落地新主题
    stomp.subscribeTelemetrySummary('1002', () => {});
    expect(h.subscriptions).toHaveLength(1);
    expect(h.subscriptions.at(-1)?.destination).toBe('/topic/iot/telemetry/1002');
  });

  it('非安全上下文（crypto.randomUUID 缺失）connect 不抛异常且仍生成降级 traceId', () => {
    const infoSpy = vi.spyOn(console, 'info').mockImplementation(() => {});
    // 模拟 HTTP 内网部署（非安全上下文）：crypto.randomUUID 因 [SecureContext] 限定为 undefined
    vi.stubGlobal('crypto', { randomUUID: undefined });
    try {
      expect(() => stomp.connect({ token: 'token-a', wardId: '1001' })).not.toThrow();
      // traceId 已生成：连接日志携带非占位 traceId 锚点（降级串，非 '-' 占位）
      const traceLogged = infoSpy.mock.calls
        .flat()
        .some(
          (arg) => typeof arg === 'string' && arg.startsWith('traceId=') && arg !== 'traceId=-',
        );
      expect(traceLogged).toBe(true);
    } finally {
      vi.unstubAllGlobals();
      infoSpy.mockRestore();
    }
  });
});
