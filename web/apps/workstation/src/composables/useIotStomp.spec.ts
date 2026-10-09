// workstation STOMP 多端点登记封装单测（bigscreen useIotStomp 移植后多端点改造面）：
// vi.mock('@stomp/stompjs') 捕获构造参数与回调挂接（禁真实建连），vi.mock('@/stores/auth')
// 桩化会话令牌源（纯单元隔离，不牵入 router/element-plus 模块链），覆盖端点注册表复用、
// beforeConnect 实时读会话令牌、构造参数合规、遥测/告警双主题路径精确等于契约值、连接落地前
// 登记 onConnect 转正、断线重连重订阅、毒帧防御、无令牌拒绝建连、非数字病区拒绝、显式断开
// 先退订后 deactivate，以及多端点改造新增面（册 2 审查修复波 2 补测）：fillTopicTemplate
// 三态（合法填充/占位符未填满拒绝/非法字符拒绝）、connectionStateOf 任意端点读态、端点参数化
// 订阅与带端点参数 disconnect 的单端点回收、无参 disconnect 全端点保守清理。
// 每用例 vi.resetModules 后动态再导入，重置模块级注册表（端点条目/Client 缓存/订阅在册/状态
// ref）保证隔离。
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
  /** 会话令牌桩值（getter/setter 实时读写，模拟 auth store 会话令牌轮换） */
  token: null as string | null,
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

// 会话令牌源桩：useIotStomp 仅读 token 单字段，getter/setter 保证每次读取取实时值
vi.mock('@/stores/auth', () => ({
  useAuthStore: () => ({
    get token(): string | null {
      return h.token;
    },
    set token(value: string | null) {
      h.token = value;
    },
  }),
}));

// 模块级单例经 resetModules 重置：每用例取得全新模块实例
let stomp: typeof import('./useIotStomp');

beforeEach(async () => {
  vi.clearAllMocks();
  h.constructorCalls = 0;
  h.configs = [];
  h.clients = [];
  h.activateCalls = 0;
  h.deactivateCalls = 0;
  h.subscriptions = [];
  h.unsubscribeCalls = 0;
  h.token = 'ws-access-token';
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

describe('workstation STOMP 多端点登记封装', () => {
  it('两次 connect 复用同一 Client 单例（首次 connect 惰性创建，不重复建连）', () => {
    stomp.connect();
    stomp.connect();
    expect(h.constructorCalls).toBe(1);
    expect(h.activateCalls).toBe(2);
    expect(stomp.connectionState.value).toBe('connecting');
  });

  it('beforeConnect 每次连接尝试实时读会话令牌拼 Bearer 头，令牌轮换后重连带新值', () => {
    stomp.connect();
    const beforeConnect = lastConfig()['beforeConnect'] as () => void;
    beforeConnect();
    expect(lastClient().connectHeaders['Authorization']).toBe('Bearer ws-access-token');
    // 模拟会话令牌轮换后断线重连：beforeConnect 再次执行应携带最新令牌（禁构造期固化一次性 token）
    h.token = 'ws-access-token-rotated';
    beforeConnect();
    expect(lastClient().connectHeaders['Authorization']).toBe('Bearer ws-access-token-rotated');
  });

  it('Client 构造参数合规：库内建固定重连 10s、双向心跳 10s、brokerURL 同源推导 /ws/iot', () => {
    stomp.connect();
    const config = lastConfig();
    expect(config['reconnectDelay']).toBe(10000);
    expect(config['heartbeatIncoming']).toBe(10000);
    expect(config['heartbeatOutgoing']).toBe(10000);
    // jsdom 页面协议 http → ws 推导（生产 https → wss），路径走 nginx /ws/ 升级路由
    expect(config['brokerURL']).toBe('ws://localhost:3000/ws/iot');
  });

  it('会话无令牌（未登录）拒绝建连：不创建 Client 不发起激活并提示', () => {
    const warnSpy = vi.spyOn(console, 'warn').mockImplementation(() => {});
    h.token = null;
    stomp.connect();
    expect(h.constructorCalls).toBe(0);
    expect(h.activateCalls).toBe(0);
    expect(warnSpy).toHaveBeenCalled();
    warnSpy.mockRestore();
  });

  it('onStompError/onWebSocketClose 已挂接：调用置断开态且日志不含令牌值', () => {
    const warnSpy = vi.spyOn(console, 'warn').mockImplementation(() => {});
    const errorSpy = vi.spyOn(console, 'error').mockImplementation(() => {});
    stomp.connect();
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
    expect(allLogs).not.toContain('ws-access-token');
    warnSpy.mockRestore();
    errorSpy.mockRestore();
  });

  it('遥测/告警双主题订阅：连接落地前登记待订阅，onConnect 转正且主题路径精确等于契约值', () => {
    const onStateSeen: string[] = [];
    stomp.connect({ onStateChange: (state) => void onStateSeen.push(state) });
    stomp.subscribeTopic(stomp.telemetryTopicPath('1001'), () => {});
    stomp.subscribeTopic(stomp.alarmTopicPath('1001'), () => {});
    expect(h.subscriptions).toHaveLength(0);
    (lastConfig()['onConnect'] as () => void)();
    expect(h.subscriptions).toHaveLength(2);
    expect(h.subscriptions[0]?.destination).toBe('/topic/iot/telemetry/1001');
    expect(h.subscriptions[1]?.destination).toBe('/topic/iot/alarm/1001');
    // 连接状态机（页面消费）：connecting → connected，onStateChange 随翻转触发
    expect(onStateSeen).toEqual(['connecting', 'connected']);
  });

  it('已连接态调用订阅立即落地真实订阅；同目的地重订阅退旧订新', () => {
    stomp.connect();
    // 模拟库已建立连接：桩 Client connected 置真后订阅应立即落地（不经待订阅登记）
    lastClient().connected = true;
    const onFrame = vi.fn();
    const handle = stomp.subscribeTopic(stomp.telemetryTopicPath('1001'), onFrame);
    expect(h.subscriptions).toHaveLength(1);
    // 同目的地重订阅：在册旧订阅先退订（替换语义），新订阅立即落地
    stomp.subscribeTopic(stomp.telemetryTopicPath('1001'), () => {});
    expect(h.unsubscribeCalls).toBe(1);
    expect(h.subscriptions).toHaveLength(2);
    expect(h.subscriptions.at(-1)?.destination).toBe('/topic/iot/telemetry/1001');
    // 代理句柄退订（单出口）：清在册并退订当前库句柄
    handle.unsubscribe();
    expect(h.unsubscribeCalls).toBe(2);
    expect(onFrame).not.toHaveBeenCalled();
  });

  it('非法 JSON 毒帧仅 warn 留痕不触达帧回调、不中断订阅', () => {
    const warnSpy = vi.spyOn(console, 'warn').mockImplementation(() => {});
    stomp.connect();
    const onFrame = vi.fn();
    stomp.subscribeTopic(stomp.telemetryTopicPath('1001'), onFrame);
    (lastConfig()['onConnect'] as () => void)();
    const callback = h.subscriptions[0]?.callback;
    expect(callback).toBeDefined();
    // 非法 JSON 毒帧：不抛异常、帧回调不触达（载荷语义收窄归消费方 utils/iotMessage 承载）
    expect(() => callback?.({ body: 'not-json{{' })).not.toThrow();
    expect(onFrame).not.toHaveBeenCalled();
    // 订阅保持：后续合法 JSON 帧仍可触达回调
    callback?.({ body: '{"count":1}' });
    expect(onFrame).toHaveBeenCalledTimes(1);
    expect(warnSpy).toHaveBeenCalled();
    warnSpy.mockRestore();
  });

  it('显式 disconnect 先退订全部在册订阅再 deactivate，状态回归断开态', async () => {
    stomp.connect();
    stomp.subscribeTopic(stomp.telemetryTopicPath('1001'), () => {});
    stomp.subscribeTopic(stomp.alarmTopicPath('1001'), () => {});
    (lastConfig()['onConnect'] as () => void)();
    await stomp.disconnect();
    // 卸载清理条款：显式断开先退订全部在册订阅（2 条）再 deactivate（1 次）
    expect(h.unsubscribeCalls).toBe(2);
    expect(h.deactivateCalls).toBe(1);
    expect(stomp.connectionState.value).toBe('disconnected');
  });

  it('断线 close 后重连 onConnect 重新落地全部在册订阅且新帧入流（stompjs 7.3.0 无自动重订阅）', () => {
    const onFrame = vi.fn();
    stomp.connect();
    stomp.subscribeTopic(stomp.telemetryTopicPath('1001'), onFrame);
    stomp.subscribeTopic(stomp.alarmTopicPath('1001'), () => {});
    const config = lastConfig();
    (config['onConnect'] as () => void)();
    expect(h.subscriptions).toHaveLength(2);
    // 模拟断线：stompjs 7.3.0 整体作废 _stompHandler（旧订阅句柄随连接失效）
    (config['onWebSocketClose'] as () => void)();
    expect(stomp.connectionState.value).toBe('disconnected');
    // 模拟库内建自动重连成功：onConnect 二次触发必须重新 subscribe（禁假连接——徽标已连接零帧）
    (config['onConnect'] as () => void)();
    expect(h.subscriptions).toHaveLength(4);
    expect(h.subscriptions.at(-2)?.destination).toBe('/topic/iot/telemetry/1001');
    expect(h.subscriptions.at(-1)?.destination).toBe('/topic/iot/alarm/1001');
    // 新句柄帧入流：新订阅回调正常触达页面帧回调
    h.subscriptions.at(-2)?.callback({ body: '{"count":1}' });
    expect(onFrame).toHaveBeenCalledTimes(1);
    expect(stomp.connectionState.value).toBe('connected');
  });

  it('非数字病区 ID 主题路径拒绝订阅（异常上抛且不产生库订阅）', () => {
    stomp.connect();
    expect(() => stomp.telemetryTopicPath('W01')).toThrow();
    expect(() => stomp.alarmTopicPath('abc')).toThrow();
    expect(h.subscriptions).toHaveLength(0);
  });

  it('fillTopicTemplate 合法填充：占位符全量供给真实业务编码后还原可订阅主题路径', () => {
    // 事件流指引主题模板（/v1/ops/workbench/events topics[].topic 契约形态）按候诊表 deptCode 填充
    expect(stomp.fillTopicTemplate('/topic/outpatient/queue/{deptCode}', { deptCode: 'D01' })).toBe(
      '/topic/outpatient/queue/D01',
    );
    expect(stomp.fillTopicTemplate('/topic/iot/device-status/{wardId}', { wardId: '1001' })).toBe(
      '/topic/iot/device-status/1001',
    );
  });

  it('fillTopicTemplate 占位符未填满拒绝：缺参残留 { } 抛错拒绝（不猜测语义填充）', () => {
    // 参数表为空：占位符原样残留 → 拒绝
    expect(() => stomp.fillTopicTemplate('/topic/outpatient/queue/{deptCode}', {})).toThrow(
      /占位符未填满/,
    );
    // 多占位符模板只供一键：残留另一占位符 → 拒绝
    expect(() =>
      stomp.fillTopicTemplate('/topic/iot/device-status/{wardId}/{deviceId}', { wardId: 'W01' }),
    ).toThrow(/占位符未填满/);
  });

  it('fillTopicTemplate 非法字符拒绝：参数值含空白或通配符抛错（防目的地注入与手拼漂移）', () => {
    // 空白字符（STOMP 目的地禁空格）
    expect(() =>
      stomp.fillTopicTemplate('/topic/outpatient/queue/{deptCode}', { deptCode: 'D 01' }),
    ).toThrow(/非法字符/);
    // 通配符（只属订阅语义，禁入目的地字面值）
    expect(() =>
      stomp.fillTopicTemplate('/topic/outpatient/queue/{deptCode}', { deptCode: 'D01/*' }),
    ).toThrow(/非法字符/);
  });

  it('connectionStateOf 读任意端点状态：惰性条目初值 disconnected，随该端点连接生命周期翻转，与缺省导出面同源', () => {
    const outpatientState = stomp.connectionStateOf('/ws/outpatient');
    // 读态即惰性建条目（零网络副作用）：初值断开态
    expect(outpatientState.value).toBe('disconnected');
    stomp.connect(undefined, '/ws/outpatient');
    expect(outpatientState.value).toBe('connecting');
    (lastConfig()['onConnect'] as () => void)();
    expect(outpatientState.value).toBe('connected');
    // 缺省端点导出面（connectionState）与 connectionStateOf(缺省端点) 同一条目同值
    expect(stomp.connectionStateOf(stomp.DEFAULT_STOMP_ENDPOINT).value).toBe(
      stomp.connectionState.value,
    );
  });

  it('多端点注册表：两端点各持一套 Client（brokerURL 各自端点），订阅按端点参数分流落地', () => {
    stomp.connect(undefined, stomp.DEFAULT_STOMP_ENDPOINT);
    stomp.connect(undefined, '/ws/outpatient');
    // 每端点惰性一套 Client：构造两次，brokerURL 分别指向两端点
    expect(h.constructorCalls).toBe(2);
    expect(h.configs[0]?.['brokerURL']).toBe('ws://localhost:3000/ws/iot');
    expect(h.configs[1]?.['brokerURL']).toBe('ws://localhost:3000/ws/outpatient');
    // 端点参数化订阅：主题登记进各自端点条目，onConnect 只落地本端点在册订阅
    stomp.subscribeTopic('/topic/outpatient/queue/D01', () => {}, '/ws/outpatient');
    stomp.subscribeTopic(stomp.telemetryTopicPath('1001'), () => {});
    (h.configs[1]?.['onConnect'] as () => void)();
    expect(h.subscriptions).toHaveLength(1);
    expect(h.subscriptions[0]?.destination).toBe('/topic/outpatient/queue/D01');
    (h.configs[0]?.['onConnect'] as () => void)();
    expect(h.subscriptions).toHaveLength(2);
    expect(h.subscriptions[1]?.destination).toBe('/topic/iot/telemetry/1001');
  });

  it('带端点参数 disconnect 只回收该端点：退订其在册订阅并 deactivate 单 Client，另一端点连接不受牵连', async () => {
    stomp.connect(undefined, stomp.DEFAULT_STOMP_ENDPOINT);
    stomp.connect(undefined, '/ws/outpatient');
    stomp.subscribeTopic(stomp.telemetryTopicPath('1001'), () => {}, stomp.DEFAULT_STOMP_ENDPOINT);
    stomp.subscribeTopic('/topic/outpatient/queue/D01', () => {}, '/ws/outpatient');
    (h.configs[0]?.['onConnect'] as () => void)();
    (h.configs[1]?.['onConnect'] as () => void)();
    expect(stomp.connectionStateOf('/ws/iot').value).toBe('connected');
    expect(stomp.connectionStateOf('/ws/outpatient').value).toBe('connected');
    await stomp.disconnect('/ws/outpatient');
    // 仅 outpatient 端点被回收：1 条在册订阅退订 + 1 个 Client deactivate
    expect(h.unsubscribeCalls).toBe(1);
    expect(h.deactivateCalls).toBe(1);
    expect(stomp.connectionStateOf('/ws/outpatient').value).toBe('disconnected');
    // /ws/iot 面零波及：连接态保持 connected，在册订阅未被退订
    expect(stomp.connectionStateOf('/ws/iot').value).toBe('connected');
    expect(h.subscriptions[0]?.destination).toBe('/topic/iot/telemetry/1001');
  });

  it('无端点参数 disconnect 保守清理全部端点：逐端点退订在册订阅并全部 deactivate（防跨视图连接泄漏）', async () => {
    stomp.connect(undefined, stomp.DEFAULT_STOMP_ENDPOINT);
    stomp.connect(undefined, '/ws/outpatient');
    stomp.subscribeTopic(stomp.telemetryTopicPath('1001'), () => {}, stomp.DEFAULT_STOMP_ENDPOINT);
    stomp.subscribeTopic('/topic/outpatient/queue/D01', () => {}, '/ws/outpatient');
    (h.configs[0]?.['onConnect'] as () => void)();
    (h.configs[1]?.['onConnect'] as () => void)();
    await stomp.disconnect();
    // 全端点回收：2 条在册订阅全部退订 + 2 个 Client 全部 deactivate，状态齐落断开态
    expect(h.unsubscribeCalls).toBe(2);
    expect(h.deactivateCalls).toBe(2);
    expect(stomp.connectionStateOf('/ws/iot').value).toBe('disconnected');
    expect(stomp.connectionStateOf('/ws/outpatient').value).toBe('disconnected');
  });
});
